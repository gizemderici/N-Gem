#!/usr/bin/env python3
"""Eğitilmiş Türkçe sınıflandırıcıyı çalıştırır.

    python classify_text.py --model models/nexi-text-v1.json \\
        "Yeni güncelleme gerçekten başarılı."

    Metin: Yeni güncelleme gerçekten başarılı.
    Duygu: Olumlu
    Konu: Teknoloji

Bir CSV'yi toplu değerlendirmek için `--evaluate`. Bu mod eğitimden ayrıdır:
kararların hiçbirine girmemiş bir test setini, model dosyasına dokunmadan
puanlayabilmek gerekiyor.
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from turkish_text import TfidfVectorizer  # noqa: E402

# Etiketten kullanıcıya gösterilecek ada. Konu adları TopicCatalog.LABELS ile
# birebir aynı; ayrı bir çeviri tablosu tutmak ikisinin ayrışmasına açık kapı
# bırakırdı, bu yüzden değişirse iki dosya birlikte değişmeli.
DUYGU_ADLARI = {"olumlu": "Olumlu", "olumsuz": "Olumsuz", "notr": "Nötr"}
KONU_ADLARI = {
    "teknoloji": "Teknoloji",
    "yapay-zeka": "Yapay Zekâ",
    "sanat": "Sanat ve Tasarım",
    "egitim": "Eğitim",
    "spor": "Spor",
    "gundem": "Gündem",
    "bilim": "Bilim",
    "oyun": "Oyun",
    "muzik": "Müzik",
    "saglik": "Sağlık ve Yaşam",
    "girisimcilik": "Girişimcilik",
    "seyahat": "Seyahat",
}


def stdout_utf8() -> None:
    """Windows terminali CP1252 olsa da Türkçe çıktıyı güvenle yazdırır.

    `PYTHONIOENCODING` veya eski Windows konsol ayarı stdout'u CP1252 olarak
    açabiliyor. Model sonucu doğru hesaplandıktan sonra yalnızca `ı` ya da `ş`
    yazdırmak bu durumda süreci UnicodeEncodeError ile kapatıyordu.
    StringIO gibi test akışlarında `reconfigure` bulunmayabileceği için bu
    yardımcı o durumda hiçbir şey yapmaz.
    """
    reconfigure = getattr(sys.stdout, "reconfigure", None)
    if reconfigure is None:
        return
    try:
        reconfigure(encoding="utf-8")
    except (OSError, ValueError):
        # Kapanmış veya yeniden yapılandırılamayan özel stdout akışları.
        return


class YuklenmisModel:
    def __init__(self, payload: dict):
        self.version = payload["model_version"]
        self.vectorizer = TfidfVectorizer.from_dict(payload["vectorizer"])
        self.duygu = payload["duygu"]
        self.konu = payload["konu"]

    def _tahmin(self, model: dict, vector: dict[int, float]) -> tuple[str, float]:
        scores = []
        for sinif_index, row in enumerate(model["weights"]):
            total = model["bias"][sinif_index]
            for index, value in vector.items():
                weight = row.get(str(index))
                if weight is not None:
                    total += weight * value
            scores.append(total)
        peak = max(scores)
        exponents = [math.exp(s - peak) for s in scores]
        toplam = sum(exponents)
        olasiliklar = [e / toplam for e in exponents]
        best = max(range(len(olasiliklar)), key=olasiliklar.__getitem__)
        return model["classes"][best], olasiliklar[best]

    def siniflandir(self, metin: str) -> tuple[tuple[str, float], tuple[str, float]]:
        vector = self.vectorizer.transform(metin)
        return self._tahmin(self.duygu, vector), self._tahmin(self.konu, vector)


def yazdir(model: YuklenmisModel, metin: str, olasilik_goster: bool) -> None:
    (duygu, duygu_p), (konu, konu_p) = model.siniflandir(metin)
    print(f"Metin: {metin}")
    if olasilik_goster:
        print(f"Duygu: {DUYGU_ADLARI.get(duygu, duygu)} ({duygu_p:.2f})")
        print(f"Konu: {KONU_ADLARI.get(konu, konu)} ({konu_p:.2f})")
    else:
        print(f"Duygu: {DUYGU_ADLARI.get(duygu, duygu)}")
        print(f"Konu: {KONU_ADLARI.get(konu, konu)}")


def siniflandirma_metrikleri(
    gercekler: list[str],
    tahminler: list[str],
    siniflar: list[str],
) -> dict:
    """Accuracy, macro-F1 ve sınıf bazlı precision/recall/F1 hesaplar."""
    if not gercekler or len(gercekler) != len(tahminler):
        raise ValueError("gerçek ve tahmin listeleri aynı ve sıfırdan büyük olmalı")

    sinif_metrikleri: dict[str, dict[str, float | int]] = {}
    for sinif in siniflar:
        tp = sum(g == sinif and t == sinif for g, t in zip(gercekler, tahminler))
        fp = sum(g != sinif and t == sinif for g, t in zip(gercekler, tahminler))
        fn = sum(g == sinif and t != sinif for g, t in zip(gercekler, tahminler))
        destek = sum(g == sinif for g in gercekler)
        precision = tp / (tp + fp) if tp + fp else 0.0
        recall = tp / (tp + fn) if tp + fn else 0.0
        f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
        sinif_metrikleri[sinif] = {
            "precision": precision,
            "recall": recall,
            "f1": f1,
            "support": destek,
        }

    dogru = sum(g == t for g, t in zip(gercekler, tahminler))
    return {
        "dogru": dogru,
        "toplam": len(gercekler),
        "accuracy": dogru / len(gercekler),
        "macro_f1": sum(m["f1"] for m in sinif_metrikleri.values()) / len(siniflar),
        "siniflar": sinif_metrikleri,
    }


def degerlendir(model: YuklenmisModel, path: Path, json_cikti: bool = False) -> dict:
    with path.open(encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle)
        gereken = {"metin", "duygu", "konu"}
        if not reader.fieldnames or not gereken.issubset(reader.fieldnames):
            raise SystemExit(f"{path}: gereken sütunlar: metin, duygu, konu")
        rows = list(reader)
    if not rows:
        raise SystemExit(f"{path}: değerlendirilecek satır yok")

    duygu_siniflari = model.duygu["classes"]
    konu_siniflari = model.konu["classes"]
    bilinmeyen_duygu = sorted({r["duygu"] for r in rows} - set(duygu_siniflari))
    bilinmeyen_konu = sorted({r["konu"] for r in rows} - set(konu_siniflari))
    if bilinmeyen_duygu or bilinmeyen_konu:
        raise SystemExit(
            f"{path}: modelde olmayan etiket; "
            f"duygu={bilinmeyen_duygu} konu={bilinmeyen_konu}"
        )

    duygu_gercek: list[str] = []
    duygu_tahmin: list[str] = []
    konu_gercek: list[str] = []
    konu_tahmin: list[str] = []
    hatalar: list[str] = []
    for row in rows:
        (duygu, _), (konu, _) = model.siniflandir(row["metin"])
        duygu_gercek.append(row["duygu"])
        duygu_tahmin.append(duygu)
        konu_gercek.append(row["konu"])
        konu_tahmin.append(konu)
        if duygu != row["duygu"] or konu != row["konu"]:
            hatalar.append(
                f"  {row['metin'][:58]:<58} "
                f"duygu {row['duygu']}->{duygu}  konu {row['konu']}->{konu}"
            )

    rapor = {
        "model_version": model.version,
        "dataset": path.name,
        "duygu": siniflandirma_metrikleri(
            duygu_gercek, duygu_tahmin, duygu_siniflari
        ),
        "konu": siniflandirma_metrikleri(konu_gercek, konu_tahmin, konu_siniflari),
        "hata_satiri": len(hatalar),
    }
    if json_cikti:
        print(json.dumps(rapor, ensure_ascii=False, indent=2))
    else:
        print(f"{path.name}: n={len(rows)} model={model.version}")
        for ad in ("duygu", "konu"):
            metrik = rapor[ad]
            print(
                f"  {ad:<5} {metrik['dogru']}/{metrik['toplam']} "
                f"accuracy={metrik['accuracy']:.3f} "
                f"macro_f1={metrik['macro_f1']:.3f}"
            )
        if hatalar:
            print("hatalar:")
            print("\n".join(hatalar))
    return rapor


def main() -> None:
    stdout_utf8()
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("metin", nargs="*", help="sınıflandırılacak metin")
    parser.add_argument("--model", type=Path, default=Path("models/nexi-text-v1.json"))
    parser.add_argument("--evaluate", type=Path, default=None, help="etiketli CSV")
    parser.add_argument("--probabilities", action="store_true")
    parser.add_argument(
        "--json",
        action="store_true",
        help="--evaluate sonucunu makinece okunabilir JSON yaz",
    )
    args = parser.parse_args()

    if not args.model.exists():
        raise SystemExit(
            f"model yok: {args.model}\n"
            "önce: python train_text_classifier.py corpus/train.csv"
        )
    model = YuklenmisModel(json.loads(args.model.read_text(encoding="utf-8")))

    if args.evaluate:
        degerlendir(model, args.evaluate, args.json)
        return
    if args.json:
        parser.error("--json yalnızca --evaluate ile kullanılabilir")
    if not args.metin:
        parser.error("bir metin verin ya da --evaluate kullanın")
    for index, metin in enumerate(args.metin):
        if index:
            print()
        yazdir(model, metin, args.probabilities)


if __name__ == "__main__":
    main()

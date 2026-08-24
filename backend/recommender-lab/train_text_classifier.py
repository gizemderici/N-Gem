#!/usr/bin/env python3
"""Türkçe duygu ve konu sınıflandırıcısı: TF-IDF + çok sınıflı lojistik regresyon.

Yalnızca standart kütüphane. BERTurk ya da XLM-R bu iş için gerekmiyor:
sınıflar az, veri kurgu ve az, ve doğrusal bir modelin hangi kelimeye ne
ağırlık verdiği tek tek okunabiliyor. Transformer, doğrusal taban gerçek
veriyle kıyaslandıktan sonra anlamlı olur.

İki ayrı model eğitiliyor (duygu ve konu), tek bir çok etiketli model değil:
duygu ile konu bağımsız eksenler, birleşik 36 sınıflı bir model her
kombinasyon için ayrı veri isterdi.

    python train_text_classifier.py corpus/train.csv \\
        --holdout corpus/holdout.csv --output models/nexi-text-v1.json
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
import random
import sys
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from turkish_text import TfidfVectorizer  # noqa: E402

MODEL_VERSION = "nexi-text-v1"


@dataclass(frozen=True)
class Settings:
    learning_rate: float = 0.5
    epochs: int = 300
    l2: float = 1e-4
    # Sabit tohum: aynı veri ve aynı ayar aynı ağırlıkları üretmeli.
    seed: int = 20260824


@dataclass(frozen=True)
class Ornek:
    metin: str
    duygu: str
    konu: str


def load(path: Path) -> list[Ornek]:
    with path.open(encoding="utf-8", newline="") as handle:
        rows = list(csv.DictReader(handle))
    eksik = [r for r in rows if not (r.get("metin") and r.get("duygu") and r.get("konu"))]
    if eksik:
        raise SystemExit(f"{path}: {len(eksik)} satırda eksik alan var")
    return [Ornek(r["metin"], r["duygu"], r["konu"]) for r in rows]


def softmax(scores: list[float]) -> list[float]:
    # En büyüğü çıkarmadan exp almak taşar; exp(1000) hata verir.
    peak = max(scores)
    exponents = [math.exp(s - peak) for s in scores]
    total = sum(exponents)
    return [e / total for e in exponents]


class SoftmaxSiniflandirici:
    """L2 düzenlileştirmeli çok sınıflı lojistik regresyon, seyrek girdiyle.

    Seyrek gösterim bir hız numarası değil: karakter n-gramlarıyla sözlük on
    binlerce boyuta çıkıyor ve yoğun vektörlerde her örnek için o boyutun
    tamamını dolaşmak eğitimi dakikalar sürdürürdü.
    """

    def __init__(self, classes: list[str], dimension: int, settings: Settings):
        self.classes = classes
        self.dimension = dimension
        self.settings = settings
        self.weights = [[0.0] * dimension for _ in classes]
        self.bias = [0.0] * len(classes)

    def _scores(self, vector: dict[int, float]) -> list[float]:
        return [
            self.bias[c] + sum(w[i] * v for i, v in vector.items())
            for c, w in enumerate(self.weights)
        ]

    def fit(self, vectors: list[dict[int, float]], labels: list[int]) -> None:
        rastgele = random.Random(self.settings.seed)
        order = list(range(len(vectors)))
        rate = self.settings.learning_rate
        for _ in range(self.settings.epochs):
            rastgele.shuffle(order)
            for index in order:
                vector = vectors[index]
                target = labels[index]
                probabilities = softmax(self._scores(vector))
                for c in range(len(self.classes)):
                    error = probabilities[c] - (1.0 if c == target else 0.0)
                    if error == 0.0 and not vector:
                        continue
                    row = self.weights[c]
                    step = rate * error
                    for i, v in vector.items():
                        # L2 yalnızca dokunulan boyutlara uygulanıyor;
                        # bütün sözlüğü her örnekte küçültmek hem yavaş hem de
                        # seyrek özellikleri haksızca cezalandırırdı.
                        row[i] -= step * v + rate * self.settings.l2 * row[i]
                    self.bias[c] -= step

    def predict(self, vector: dict[int, float]) -> tuple[str, float]:
        probabilities = softmax(self._scores(vector))
        best = max(range(len(probabilities)), key=probabilities.__getitem__)
        return self.classes[best], probabilities[best]

    def to_dict(self) -> dict:
        # Sıfır ağırlıkları atmak dosyayı onda birine indiriyor; seyrek
        # metin modellerinde ağırlıkların çoğu hiç güncellenmiyor.
        return {
            "classes": self.classes,
            "dimension": self.dimension,
            "bias": self.bias,
            "weights": [
                {str(i): w for i, w in enumerate(row) if w != 0.0}
                for row in self.weights
            ],
        }


def egit(
    ornekler: list[Ornek],
    vectorizer: TfidfVectorizer,
    alan: str,
    settings: Settings,
) -> SoftmaxSiniflandirici:
    classes = sorted({getattr(o, alan) for o in ornekler})
    index = {c: i for i, c in enumerate(classes)}
    vectors = [vectorizer.transform(o.metin) for o in ornekler]
    labels = [index[getattr(o, alan)] for o in ornekler]
    model = SoftmaxSiniflandirici(classes, len(vectorizer.vocabulary), settings)
    model.fit(vectors, labels)
    return model


def dogruluk(
    model: SoftmaxSiniflandirici,
    vectorizer: TfidfVectorizer,
    ornekler: list[Ornek],
    alan: str,
) -> tuple[float, dict[str, dict[str, int]]]:
    dogru = 0
    karisiklik: dict[str, dict[str, int]] = {}
    for ornek in ornekler:
        gercek = getattr(ornek, alan)
        tahmin, _ = model.predict(vectorizer.transform(ornek.metin))
        karisiklik.setdefault(gercek, {}).setdefault(tahmin, 0)
        karisiklik[gercek][tahmin] += 1
        if tahmin == gercek:
            dogru += 1
    return dogru / len(ornekler) if ornekler else 0.0, karisiklik


def checksum(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()[:16]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("training", type=Path)
    parser.add_argument("--holdout", type=Path, default=None)
    parser.add_argument("--output", type=Path, default=Path("models/nexi-text-v1.json"))
    parser.add_argument("--model-version", default=MODEL_VERSION)
    parser.add_argument("--min-df", type=int, default=2)
    parser.add_argument(
        "--no-char-ngrams",
        action="store_true",
        help="karakter n-gramlarını kapat; eklemeli dilde etkisini ölçmek için",
    )
    parser.add_argument("--epochs", type=int, default=Settings.epochs)
    args = parser.parse_args()

    settings = Settings(epochs=args.epochs)
    egitim = load(args.training)
    vectorizer = TfidfVectorizer(
        min_df=args.min_df,
        char_ngrams=None if args.no_char_ngrams else (4, 5),
    ).fit([o.metin for o in egitim])

    duygu_modeli = egit(egitim, vectorizer, "duygu", settings)
    konu_modeli = egit(egitim, vectorizer, "konu", settings)

    rapor: dict[str, dict] = {
        "egitim": {
            "duygu": dogruluk(duygu_modeli, vectorizer, egitim, "duygu")[0],
            "konu": dogruluk(konu_modeli, vectorizer, egitim, "konu")[0],
        }
    }
    # Eğitim doğruluğu bir başarı ölçüsü değil; kalıptan üretilmiş veride
    # model kalıbı ezberler. Holdout geliştirme sırasında görülmüş olabilir;
    # bağımsız değerlendirme ayrıca classify_text.py ile yapılmalıdır.
    if args.holdout:
        sinama = load(args.holdout)
        duygu_oran, duygu_karisiklik = dogruluk(duygu_modeli, vectorizer, sinama, "duygu")
        konu_oran, konu_karisiklik = dogruluk(konu_modeli, vectorizer, sinama, "konu")
        rapor["sinama"] = {
            "satir": len(sinama),
            "duygu": duygu_oran,
            "konu": konu_oran,
            "duygu_karisiklik": duygu_karisiklik,
            "konu_karisiklik": konu_karisiklik,
        }

    payload = {
        "model_version": args.model_version,
        "trained_at": datetime.now(timezone.utc).isoformat(),
        "training_file": args.training.name,
        "training_checksum": checksum(args.training),
        "training_rows": len(egitim),
        "settings": settings.__dict__,
        "vectorizer": vectorizer.to_dict(),
        "duygu": duygu_modeli.to_dict(),
        "konu": konu_modeli.to_dict(),
        "rapor": rapor,
        "veri_notu": (
            "Egitim verisi kurgu ve kalip uretimi. Egitim dogrulugu genelleme "
            "olcusu degildir. Holdout gelistirme olcumudur; bagimsiz test "
            "classify_text.py ile model dosyasina dokunmadan yapilmalidir."
        ),
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")

    print(f"sozluk: {len(vectorizer.vocabulary)} terim")
    print(f"egitim  duygu={rapor['egitim']['duygu']:.3f} konu={rapor['egitim']['konu']:.3f}")
    if "sinama" in rapor:
        s = rapor["sinama"]
        print(f"sinama  duygu={s['duygu']:.3f} konu={s['konu']:.3f}  (n={s['satir']})")
    print(f"model -> {args.output}")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Model kayıt defteri ve üretime terfi kapısı.

Bir modelin üretime çıkması bir dosyayı kopyalamak olmamalı. Defter her
sürümün özetini, eğitildiği veriyi, çevrimdışı metriklerini ve hangi
aşamada olduğunu tutar; terfi ancak kapılar geçildiğinde mümkün.

Aşamalar tek yönlü: `candidate → shadow → production`. Geri dönüş
`archived`'a; eski model dosyası silinmez, geri alma onunla yapılır.

Kullanım:

    python registry.py register models/nexi-lr-v1.json --metrics output/metrics.json
    python registry.py promote nexi-lr-v1 --to shadow
    python registry.py promote nexi-lr-v1 --to production
    python registry.py verify
    python registry.py list
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

LAB = Path(__file__).resolve().parent
sys.path.insert(0, str(LAB))

from features import FEATURE_VERSION  # noqa: E402

REGISTRY = LAB / "models" / "registry.json"

STAGES = ("candidate", "shadow", "production", "archived")

# Bir modelin üretime çıkabilmesi için çevrimdışı değerlendirmede geçmesi
# gereken eşikler. Sayılar mutlak kalite değil, "taban modelleri geçiyor mu"
# sorusunun cevabı; isabet artarken güvenlik bozulursa kapı kapalı.
PRODUCTION_GATES = {
    # Kişiselleştirmenin en azından kronolojiği geçmesi gerekir; geçmiyorsa
    # ortada model değil, gürültü var.
    "beats_chronological": True,
    # Olumsuz geri bildirim oranı kontrolün iki katını aşmamalı.
    "max_negative_feedback_ratio": 2.0,
    # Yeni üreticiye ayrılan pay kronolojiğin yarısının altına düşmemeli;
    # aksi hâlde sistem yalnızca zaten görünür olanı güçlendirir.
    "min_new_creator_ratio": 0.5,
}


class PromotionError(RuntimeError):
    """Kapı geçilemedi; sebebi mesajda."""


def checksum(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_registry(path: Path = REGISTRY) -> dict:
    if not path.exists():
        return {"models": {}}
    return json.loads(path.read_text(encoding="utf-8"))


def save_registry(registry: dict, path: Path = REGISTRY) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(registry, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def register(
    model_path: Path,
    metrics_path: Path | None,
    registry: dict,
    demo: bool = False,
) -> dict:
    model = json.loads(model_path.read_text(encoding="utf-8"))
    version = model.get("model_version")
    if not version:
        raise PromotionError("model dosyasında model_version yok")
    if model.get("feature_version") != FEATURE_VERSION:
        # Farklı özellik sürümüyle eğitilmiş bir model, servis tarafında
        # başka bir vektör görür; sessizce kaydetmek onu üretime taşırdı.
        raise PromotionError(
            f"özellik sürümü uyuşmuyor: {model.get('feature_version')} != {FEATURE_VERSION}"
        )

    entry = {
        "version": version,
        "stage": "candidate",
        # Kurgu veriyle egitilmis model. Uretim kapisi bunu mutlak olarak
        # reddediyor: metrikleri iyi cikabilir ama olculen sey gercek
        # kullanici davranisi degil, seed betiginin urettigi desen.
        "demo": demo,
        "feature_version": model["feature_version"],
        "file": model_path.name,
        "sha256": checksum(model_path),
        "data": model.get("data", {}),
        "settings": model.get("settings", {}),
        "training_metrics": model.get("metrics", {}),
        "registered_at": datetime.now(timezone.utc).replace(microsecond=0).isoformat(),
        "offline_metrics": None,
        "shadow_verified": False,
        "history": [],
    }
    if metrics_path is not None:
        entry["offline_metrics"] = json.loads(metrics_path.read_text(encoding="utf-8"))

    registry.setdefault("models", {})[version] = entry
    return entry


def check_production_gates(entry: dict) -> list[str]:
    """Üretime çıkışı engelleyen sebepler; boş liste "geçti" demek."""
    problems: list[str] = []

    if entry.get("demo"):
        # Tek basina yeterli sebep; metriklere bakmaya bile gerek yok.
        problems.append("demo modeli uretime cikamaz (kurgu veriyle egitildi)")

    if not entry.get("shadow_verified"):
        problems.append("gölge modda doğrulanmadı")

    metrics = entry.get("offline_metrics")
    if not metrics:
        problems.append("çevrimdışı değerlendirme sonucu yok")
        return problems

    policies = metrics.get("policies", {})
    learned = policies.get("learned") or policies.get("contextual")
    chronological = policies.get("chronological")
    if not learned or not chronological:
        problems.append("karşılaştırma için taban model sonucu eksik")
        return problems

    k = metrics.get("k", 10)
    hit = f"hit_rate@{k}"
    negative = f"negative_feedback_rate@{k}"
    new_creator = f"new_creator_share@{k}"

    if PRODUCTION_GATES["beats_chronological"] and learned.get(hit, 0.0) <= chronological.get(hit, 0.0):
        problems.append(
            f"kronolojik tabanı geçmiyor: {learned.get(hit)} <= {chronological.get(hit)}"
        )

    baseline_negative = chronological.get(negative, 0.0)
    limit = baseline_negative * PRODUCTION_GATES["max_negative_feedback_ratio"]
    if baseline_negative > 0 and learned.get(negative, 0.0) > limit:
        problems.append(f"olumsuz geri bildirim sınırı aşıldı: {learned.get(negative)} > {limit}")

    baseline_new = chronological.get(new_creator, 0.0)
    floor = baseline_new * PRODUCTION_GATES["min_new_creator_ratio"]
    if baseline_new > 0 and learned.get(new_creator, 0.0) < floor:
        problems.append(f"yeni üretici payı çok düşük: {learned.get(new_creator)} < {floor}")

    return problems


def promote(version: str, stage: str, registry: dict) -> dict:
    if stage not in STAGES:
        raise PromotionError(f"bilinmeyen aşama: {stage}")
    entry = registry.get("models", {}).get(version)
    if entry is None:
        raise PromotionError(f"kayıtlı model yok: {version}")

    if stage == "production":
        problems = check_production_gates(entry)
        if problems:
            raise PromotionError("üretime çıkış engellendi: " + "; ".join(problems))
        # Üretimde tek model olur; öncekini arşive alıyoruz ki geri alma
        # için dosyası dursun.
        for other in registry["models"].values():
            if other["stage"] == "production" and other["version"] != version:
                other["stage"] = "archived"
                other.setdefault("history", []).append(
                    {"stage": "archived", "at": _now(), "reason": f"{version} üretime alındı"}
                )

    if stage == "shadow":
        entry["shadow_verified"] = True

    entry["stage"] = stage
    entry.setdefault("history", []).append({"stage": stage, "at": _now()})
    return entry


def verify(registry: dict, directory: Path) -> list[str]:
    """Kayıtlı her modelin dosyası duruyor ve özeti tutuyor mu.

    Bozulmuş ya da elle değiştirilmiş bir model dosyası, üretimde sessizce
    başka bir sıralama üretir. Bu komut CI'da koşup sıfırdan farklı çıkış
    kodu verdiğinde alarm kaynağı olur.
    """
    problems: list[str] = []
    for version, entry in registry.get("models", {}).items():
        path = directory / entry["file"]
        if not path.exists():
            problems.append(f"{version}: dosya yok ({entry['file']})")
            continue
        actual = checksum(path)
        if actual != entry["sha256"]:
            problems.append(f"{version}: özet tutmuyor (beklenen {entry['sha256'][:12]}…, bulunan {actual[:12]}…)")
        if entry.get("feature_version") != FEATURE_VERSION:
            problems.append(f"{version}: özellik sürümü {entry.get('feature_version')}, beklenen {FEATURE_VERSION}")
    return problems


def _now() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat()


def main() -> None:
    parser = argparse.ArgumentParser(description="NSosyal model kayıt defteri")
    sub = parser.add_subparsers(dest="command", required=True)

    register_parser = sub.add_parser("register", help="Yeni model sürümü kaydet")
    register_parser.add_argument("model", type=Path)
    register_parser.add_argument("--metrics", type=Path)
    register_parser.add_argument(
        "--demo",
        action="store_true",
        help="Kurgu veriyle egitildi; uretime cikisi kalici olarak engellenir.",
    )

    promote_parser = sub.add_parser("promote", help="Aşama değiştir")
    promote_parser.add_argument("version")
    promote_parser.add_argument("--to", required=True, choices=STAGES)

    sub.add_parser("verify", help="Dosya ve özet doğrulaması")
    sub.add_parser("list", help="Kayıtlı modeller")

    args = parser.parse_args()
    registry = load_registry()

    if args.command == "register":
        entry = register(args.model, args.metrics, registry, demo=args.demo)
        save_registry(registry)
        print(json.dumps(entry, ensure_ascii=False, indent=2))
    elif args.command == "promote":
        try:
            entry = promote(args.version, args.to, registry)
        except PromotionError as error:
            print(f"HATA: {error}", file=sys.stderr)
            raise SystemExit(1) from error
        save_registry(registry)
        print(f"{entry['version']} -> {entry['stage']}")
    elif args.command == "verify":
        problems = verify(registry, REGISTRY.parent)
        for problem in problems:
            print(f"HATA: {problem}", file=sys.stderr)
        raise SystemExit(1 if problems else 0)
    elif args.command == "list":
        for version, entry in sorted(registry.get("models", {}).items()):
            stage = entry["stage"] + ("/demo" if entry.get("demo") else "")
            print(f"{stage:<17} {version:<26} {entry['sha256'][:12]}  {entry['registered_at']}")


if __name__ == "__main__":
    main()

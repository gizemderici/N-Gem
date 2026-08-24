#!/usr/bin/env python3
"""Özellik dağılımı kayması (drift) tespiti.

Model bozulmasının en sessiz biçimi: kod değişmez, veri değişir. Kullanıcı
davranışı ya da içerik karışımı kaydığında model eğitildiği dünyayı değil
başka bir dünyayı puanlamaya başlar ve bunu hiçbir birim testi yakalamaz.

Ölçüm **Population Stability Index**: iki dağılımı aynı kovalara bölüp
farkını tek sayıya indiriyor. Yaygın yorum eşikleri:

| PSI | Anlam |
|---|---|
| < 0,10 | Kayma yok |
| 0,10 – 0,25 | Dikkat; izlenmeli |
| > 0,25 | Belirgin kayma; model yeniden eğitilmeli |

Kullanım:

    python drift.py egitim_seti.csv yeni_veri.csv
"""

from __future__ import annotations

import argparse
import json
import math
import sys
from pathlib import Path

LAB = Path(__file__).resolve().parent
sys.path.insert(0, str(LAB))

from features import FEATURE_NAMES, feature_vector  # noqa: E402
from train_ranker import load_rows  # noqa: E402

BUCKET_COUNT = 10

# Sıfır orana bölmemek için taban. Kovalardan biri referansta boşsa PSI
# sonsuza giderdi; bu taban sonucu sonlu ve karşılaştırılabilir tutuyor.
EPSILON = 1e-6

WARNING_THRESHOLD = 0.10
CRITICAL_THRESHOLD = 0.25


def bucket_edges(values: list[float], count: int = BUCKET_COUNT) -> list[float]:
    """Referans dağılımın yüzdelik sınırları.

    Eşit genişlikli kova yerine yüzdelik: özelliklerin çoğu (beğeni sayısı,
    yakınlık) çarpık dağılıyor ve eşit genişlik neredeyse her şeyi tek kovaya
    doldururdu.
    """
    if not values:
        return []
    ordered = sorted(values)
    edges = []
    for index in range(1, count):
        position = int(len(ordered) * index / count)
        edges.append(ordered[min(position, len(ordered) - 1)])
    # Tekrarlı değerler aynı sınırı üretebilir; kovaların ayrı kalması gerek.
    return sorted(set(edges))


def distribution(values: list[float], edges: list[float]) -> list[float]:
    counts = [0] * (len(edges) + 1)
    for value in values:
        index = 0
        while index < len(edges) and value > edges[index]:
            index += 1
        counts[index] += 1
    total = len(values) or 1
    return [count / total for count in counts]


def psi(reference: list[float], current: list[float]) -> float:
    edges = bucket_edges(reference)
    expected = distribution(reference, edges)
    actual = distribution(current, edges)
    score = 0.0
    for exp, act in zip(expected, actual):
        exp = max(exp, EPSILON)
        act = max(act, EPSILON)
        score += (act - exp) * math.log(act / exp)
    return score


def compare(reference_rows, current_rows) -> dict[str, object]:
    reference = [feature_vector(row) for row in reference_rows]
    current = [feature_vector(row) for row in current_rows]
    if not reference or not current:
        raise ValueError("iki taraf da boş olamaz")

    features: dict[str, float] = {}
    for index, name in enumerate(FEATURE_NAMES):
        if name == "bias":
            continue
        features[name] = round(psi([row[index] for row in reference], [row[index] for row in current]), 6)

    critical = sorted(
        (name for name, value in features.items() if value > CRITICAL_THRESHOLD),
        key=lambda name: -features[name],
    )
    warning = sorted(
        (name for name, value in features.items() if WARNING_THRESHOLD < value <= CRITICAL_THRESHOLD),
        key=lambda name: -features[name],
    )

    return {
        "reference_rows": len(reference),
        "current_rows": len(current),
        "psi": features,
        "warning": warning,
        "critical": critical,
        # Alarm koşulu: en az bir özellikte belirgin kayma.
        "retrain_recommended": bool(critical),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Özellik dağılımı kayması ölçümü")
    parser.add_argument("reference", type=Path, help="Modelin eğitildiği veri")
    parser.add_argument("current", type=Path, help="Karşılaştırılacak yeni veri")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()

    report = compare(load_rows(args.reference), load_rows(args.current))
    payload = json.dumps(report, ensure_ascii=False, indent=2)
    if args.output:
        args.output.write_text(payload + "\n", encoding="utf-8")
    print(payload)

    # Sıfırdan farklı çıkış kodu: CI ya da zamanlanmış iş bunu alarm olarak
    # kullanabilsin.
    raise SystemExit(1 if report["retrain_recommended"] else 0)


if __name__ == "__main__":
    main()

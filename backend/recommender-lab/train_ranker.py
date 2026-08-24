#!/usr/bin/env python3
"""İlk eğitilmiş sıralayıcı: L2 düzenlileştirmeli lojistik regresyon.

Derin öğrenme ya da LightGBM ile başlamıyoruz. Sebep sadece bağımlılık değil:
bu aşamada elimizde birinci taraf veri yok, ve açıklanabilir bir doğrusal model
hem hangi özelliğin ne yönde etkilediğini gösteriyor hem de gölge modda geri
alınması kolay oluyor. Ağaç tabanlı modeller, doğrusal taban gerçek veriyle
kıyaslandıktan sonra anlamlı.

Model dosyası ağırlıkların yanında **eğitim ayarlarını, veri özetini ve özellik
sürümünü** de taşır; bunlar olmadan bir tahminin hangi modelden geldiği
sonradan söylenemez.

Kullanım:

    python train_ranker.py training.csv --output models/nexi-lr-v1.json
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
import sys
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from features import (  # noqa: E402
    FEATURE_NAMES,
    FEATURE_VERSION,
    TrainingRow,
    feature_vector,
)

MODEL_VERSION = "nexi-lr-v1"


@dataclass(frozen=True)
class TrainingSettings:
    learning_rate: float = 0.1
    epochs: int = 400
    l2: float = 1e-3
    # Sabit tohum: aynı veri ve aynı ayar aynı ağırlıkları üretmeli.
    seed: int = 20260823


def sigmoid(value: float) -> float:
    # Taşmayı önlemek için iki koldan hesap; exp(1000) hata verirdi.
    if value >= 0:
        return 1.0 / (1.0 + math.exp(-value))
    exponent = math.exp(value)
    return exponent / (1.0 + exponent)


def train(
    rows: list[TrainingRow],
    settings: TrainingSettings = TrainingSettings(),
) -> tuple[list[float], dict[str, float]]:
    """Toplu gradyan inişi.

    Rastgele karıştırma yok: veri sırası sabit olduğu sürece sonuç tekrar
    üretilebilir, ve tekrar üretilebilirlik bu aşamada hızdan önemli.
    """
    if not rows:
        raise ValueError("eğitim verisi boş")

    vectors = [feature_vector(row) for row in rows]
    labels = [float(row.label) for row in rows]
    weights = [0.0] * len(FEATURE_NAMES)
    count = len(rows)

    history: list[float] = []
    for _ in range(settings.epochs):
        gradient = [0.0] * len(weights)
        loss = 0.0
        for vector, label in zip(vectors, labels):
            prediction = sigmoid(sum(w * x for w, x in zip(weights, vector)))
            error = prediction - label
            for index, value in enumerate(vector):
                gradient[index] += error * value
            # Sayısal taban: log(0) sonsuz olurdu.
            probability = min(max(prediction, 1e-12), 1 - 1e-12)
            loss -= label * math.log(probability) + (1 - label) * math.log(1 - probability)

        for index in range(len(weights)):
            # Sapma terimi düzenlileştirilmiyor; taban oranı cezalandırmak
            # modeli sistematik olarak kaydırırdı.
            penalty = 0.0 if index == 0 else settings.l2 * weights[index]
            weights[index] -= settings.learning_rate * (gradient[index] / count + penalty)

        history.append(loss / count)

    metrics = {
        "final_loss": round(history[-1], 6),
        "initial_loss": round(history[0], 6),
        "positive_rate": round(sum(labels) / count, 6),
    }
    return weights, metrics


def predict(weights: list[float], row: TrainingRow) -> float:
    return sigmoid(sum(w * x for w, x in zip(weights, feature_vector(row))))


def load_rows(path: Path) -> list[TrainingRow]:
    """Soy kütüğünden dışa aktarılmış CSV; şema `export_training_data.sql`."""
    rows: list[TrainingRow] = []
    with path.open("r", encoding="utf-8-sig", newline="") as handle:
        for line, record in enumerate(csv.DictReader(handle), start=2):
            try:
                rows.append(
                    TrainingRow(
                        feed_request_id=record["feed_request_id"],
                        post_id=record["post_id"],
                        candidate_source=record["candidate_source"],
                        like_count=int(record["like_count"]),
                        comment_count=int(record["comment_count"]),
                        age_hours=float(record["age_hours"]),
                        media_type=record["media_type"],
                        topic_slugs=tuple(
                            slug for slug in record.get("topic_slugs", "").split(";") if slug
                        ),
                        creator_id=record.get("creator_id", ""),
                        local_hour=int(record["local_hour"]),
                        signal_count=int(record["signal_count"]),
                        affinities=json.loads(record.get("affinities") or "{}"),
                        label=int(record["label"]),
                        negative=int(record.get("negative", "0") or 0),
                    )
                )
            except (KeyError, TypeError, ValueError) as error:
                raise ValueError(f"{path}:{line}: {error}") from error
    return rows


def checksum(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def build_model(
    weights: list[float],
    metrics: dict[str, float],
    settings: TrainingSettings,
    row_count: int,
    data_checksum: str,
    data_name: str,
) -> dict[str, object]:
    return {
        "model_version": MODEL_VERSION,
        "feature_version": FEATURE_VERSION,
        "trained_at": datetime.now(timezone.utc).replace(microsecond=0).isoformat(),
        "data": {"name": data_name, "sha256": data_checksum, "rows": row_count},
        "settings": {
            "learning_rate": settings.learning_rate,
            "epochs": settings.epochs,
            "l2": settings.l2,
            "seed": settings.seed,
        },
        "metrics": metrics,
        "weights": {name: round(weight, 9) for name, weight in zip(FEATURE_NAMES, weights)},
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="NSosyal eğitilmiş sıralayıcı eğitimi")
    parser.add_argument("training", type=Path, help="Soy kütüğünden dışa aktarılmış CSV")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--learning-rate", type=float, default=TrainingSettings.learning_rate)
    parser.add_argument("--epochs", type=int, default=TrainingSettings.epochs)
    parser.add_argument("--l2", type=float, default=TrainingSettings.l2)
    args = parser.parse_args()

    settings = TrainingSettings(learning_rate=args.learning_rate, epochs=args.epochs, l2=args.l2)
    rows = load_rows(args.training)
    weights, metrics = train(rows, settings)
    model = build_model(
        weights, metrics, settings, len(rows), checksum(args.training), args.training.name
    )

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(model, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: model[key] for key in ("model_version", "data", "metrics")}, indent=2))


if __name__ == "__main__":
    main()

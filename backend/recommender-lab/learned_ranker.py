"""Eğitilmiş modeli çevrimdışı karşılaştırma hattına bağlar.

Eğitilmiş model ancak aynı hatta, aynı taban modellerle ve aynı metriklerle
ölçülürse anlamlı. Ayrı bir betikte "şu skoru aldı" demek, kıyas noktası
olmadığı için hiçbir şey söylemez.

Puanlama [`features`][features] modülündeki **tek** özellik fonksiyonunu
kullanır; eğitim ile servis arasında ikinci bir kopya olsaydı model eğitimde
gördüğü sayıyı burada başka bir sayı olarak alırdı.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from features import FEATURE_NAMES, FEATURE_VERSION, TrainingRow, feature_vector  # noqa: E402
from nexi_ranker import Candidate, Signal, build_affinities  # noqa: E402
from train_ranker import sigmoid  # noqa: E402


class LearnedRanker:
    """Model dosyasından yüklenen doğrusal sıralayıcı."""

    def __init__(self, model: dict[str, object]) -> None:
        weights = model.get("weights")
        if not isinstance(weights, dict):
            raise ValueError("model dosyasında ağırlık yok")

        missing = set(FEATURE_NAMES) - set(weights)
        extra = set(weights) - set(FEATURE_NAMES)
        if missing or extra:
            # Sessizce sıfır atamak, özellik sürümü değiştiğinde modeli
            # gizlice bozar ve bunu fark etmenin yolu kalmaz.
            raise ValueError(
                f"özellik listesi uyuşmuyor. eksik={sorted(missing)} fazla={sorted(extra)}"
            )
        if model.get("feature_version") != FEATURE_VERSION:
            raise ValueError(
                f"özellik sürümü uyuşmuyor: {model.get('feature_version')} != {FEATURE_VERSION}"
            )

        self.model_version = str(model.get("model_version", "unknown"))
        self.weights = [float(weights[name]) for name in FEATURE_NAMES]

    @classmethod
    def load(cls, path: Path) -> "LearnedRanker":
        return cls(json.loads(path.read_text(encoding="utf-8")))

    def probability(self, row: TrainingRow) -> float:
        return sigmoid(sum(w * x for w, x in zip(self.weights, feature_vector(row))))

    def rank(
        self,
        viewer_id: str,
        candidates: list[Candidate],
        signals: list[Signal],
        local_hour: int,
        now: int,
    ) -> list[Candidate]:
        affinities = build_affinities(signals, local_hour, now)
        scored = [
            (self.probability(self._row(candidate, affinities, signals, local_hour, now)), candidate)
            for candidate in candidates
        ]
        scored.sort(key=lambda pair: (pair[0], pair[1].post_id), reverse=True)
        return [candidate for _, candidate in scored]

    def _row(
        self,
        candidate: Candidate,
        affinities: dict[str, float],
        signals: list[Signal],
        local_hour: int,
        now: int,
    ) -> TrainingRow:
        media = next(
            (key.removeprefix("media:") for key in candidate.features if key.startswith("media:")),
            "text",
        )
        topics = tuple(
            key.removeprefix("topic:") for key in candidate.features if key.startswith("topic:")
        )
        return TrainingRow(
            feed_request_id="offline",
            post_id=candidate.post_id,
            # Çevrimdışı veri setlerinde aday kaynağı yok; hepsi keşif sayılıyor.
            # Kaynak tek değere sabitlendiği için o özelliklerin ayırt edici
            # gücü bu koşularda ölçülemez, raporda belirtilmeli.
            candidate_source="DISCOVERY",
            like_count=candidate.like_count,
            comment_count=0,
            age_hours=max(0, (now - candidate.created_at) // 60) / 60.0,
            media_type=media,
            topic_slugs=topics,
            creator_id=candidate.owner_id,
            local_hour=local_hour,
            signal_count=len(signals),
            affinities=affinities,
            label=0,
        )


def policy_from(path: Path):
    """`POLICIES` sözlüğüne eklenebilecek bir politika üretir."""
    ranker = LearnedRanker.load(path)

    def learned(
        viewer_id: str,
        candidates: list[Candidate],
        signals: list[Signal],
        local_hour: int,
        now: int,
    ) -> list[Candidate]:
        return ranker.rank(viewer_id, candidates, signals, local_hour, now)

    return learned

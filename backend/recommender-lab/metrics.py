"""Sıralama metrikleri.

İsabet metrikleri tek başına yanıltıcı: yalnızca popüler içeriği döndüren bir
politika HitRate'te iyi görünüp katalogun %4'ünü gösterir, yeni üreticiyi hiç
göstermez ve kullanıcının gizlediği içeriği tekrar önüne koyar. Bu yüzden
güvenlik ve çeşitlilik metrikleri isabetle birlikte raporlanıyor.

Kalibrasyon bilerek yok: `nexi-contextual-v1` olasılık üretmiyor, sıralama
puanı üretiyor. Puanı olasılık gibi raporlamak uydurma bir sayı olurdu; ilk
eğitilmiş model (AI Faz 5) olasılık verdiğinde buraya eklenecek.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field

from nexi_ranker import Candidate


@dataclass
class MetricAccumulator:
    """Bir politikanın bütün değerlendirme örnekleri boyunca biriktirdiği."""

    k: int
    hits: int = 0
    reciprocal_rank: float = 0.0
    discounted_gain: float = 0.0
    evaluated: int = 0
    recommended_items: set[str] = field(default_factory=set)
    creator_diversity_sum: float = 0.0
    topic_diversity_sum: float = 0.0
    new_creator_slots: int = 0
    total_slots: int = 0
    negative_slots: int = 0

    def observe(
        self,
        ranking: list[Candidate],
        target_id: str,
        new_creators: set[str],
        disliked: set[str],
    ) -> None:
        self.evaluated += 1
        top = ranking[: self.k]
        self.recommended_items.update(item.post_id for item in top)

        position = next(
            (index + 1 for index, item in enumerate(top) if item.post_id == target_id),
            None,
        )
        if position is not None:
            self.hits += 1
            self.reciprocal_rank += 1.0 / position
            self.discounted_gain += 1.0 / math.log2(position + 1)

        if top:
            self.creator_diversity_sum += len({item.owner_id for item in top}) / len(top)
            topics = [
                feature for item in top for feature in item.features if feature.startswith("topic:")
            ]
            self.topic_diversity_sum += (len(set(topics)) / len(topics)) if topics else 0.0
            self.total_slots += len(top)
            self.new_creator_slots += sum(1 for item in top if item.owner_id in new_creators)
            self.negative_slots += sum(1 for item in top if item.post_id in disliked)

    def summary(self) -> dict[str, float]:
        if self.evaluated == 0:
            return {}
        slots = self.total_slots or 1
        return {
            f"hit_rate@{self.k}": _round(self.hits / self.evaluated),
            f"mrr@{self.k}": _round(self.reciprocal_rank / self.evaluated),
            f"ndcg@{self.k}": _round(self.discounted_gain / self.evaluated),
            f"creator_diversity@{self.k}": _round(self.creator_diversity_sum / self.evaluated),
            f"topic_diversity@{self.k}": _round(self.topic_diversity_sum / self.evaluated),
            # Yeni üreticinin gösterim payı: bu sıfıra yaklaşıyorsa sistem
            # yalnızca zaten görünür olanı güçlendiriyor demektir.
            f"new_creator_share@{self.k}": _round(self.new_creator_slots / slots),
            # Kullanıcının daha önce gizlediği ya da şikâyet ettiği içeriğin
            # tekrar önüne konma oranı; güvenlik sınırı.
            f"negative_feedback_rate@{self.k}": _round(self.negative_slots / slots),
        }

    def coverage(self, catalog_size: int) -> float:
        return _round(len(self.recommended_items) / catalog_size) if catalog_size else 0.0


def _round(value: float) -> float:
    return round(value, 6)

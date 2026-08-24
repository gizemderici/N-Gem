"""Eğitilmiş sıralayıcının özellik vektörü.

Eğitim ile puanlama **aynı** fonksiyonu kullanmak zorunda. İki ayrı yerde
kurulan özellik vektörü, eğitim/servis sapmasının en yaygın sebebi: model
eğitimde gördüğü sayıyı üretimde başka bir sayı olarak alır ve kimse fark
etmez.

Bütün alanlar `feed_candidates` satırından ve o isteğin
`user_feature_snapshots` kaydından hesaplanabilir. İstek anından sonraki hiçbir
bilgi kullanılmıyor — kullanılsaydı model kendi sonucunu girdi olarak görürdü.
"""

from __future__ import annotations

import math
from dataclasses import dataclass

# Aday kaynakları; sırası `CandidateSource` ile aynı olmalı.
SOURCES = (
    "FOLLOWING",
    "PRIORITY_TOPIC",
    "OTHER_TOPIC",
    "RELATED_TOPIC",
    "POPULAR",
    "NEW_CREATOR",
    "DISCOVERY",
)

PERIODS = ("MORNING", "WORK", "EVENING", "NIGHT")

FEATURE_NAMES: tuple[str, ...] = (
    "bias",
    "topic_affinity",
    "creator_affinity",
    "media_affinity",
    "token_affinity_mean",
    "log_like_count",
    "log_comment_count",
    "freshness",
    "is_video",
    "is_image",
    "cold_start",
    *(f"source_{name.lower()}" for name in SOURCES),
    *(f"period_{name.lower()}" for name in PERIODS),
)

FEATURE_VERSION = "nexi-learned-features-v1"


@dataclass(frozen=True)
class TrainingRow:
    """Soy kütüğünden çıkarılmış tek bir aday satırı."""

    feed_request_id: str
    post_id: str
    candidate_source: str
    like_count: int
    comment_count: int
    age_hours: float
    media_type: str
    topic_slugs: tuple[str, ...]
    creator_id: str
    local_hour: int
    signal_count: int
    affinities: dict[str, float]
    # Sunumdan sonra kullanıcı olumlu etkileşim kurdu mu.
    label: int
    # Gizleme ya da şikâyet; güvenlik sınırı bunun üzerinden ölçülüyor.
    negative: int = 0


def period_of(hour: int) -> str:
    hour = min(max(hour, 0), 23)
    if 6 <= hour <= 8:
        return "MORNING"
    if 9 <= hour <= 17:
        return "WORK"
    if 18 <= hour <= 21:
        return "EVENING"
    return "NIGHT"


def feature_vector(row: TrainingRow) -> list[float]:
    """Ham satırdan model girdisi.

    Yakınlıklar istek anındaki anlık görüntüden okunuyor; güncel profilden
    okunsalardı geçmiş bir isteğe geleceğin davranışı sızardı.
    """
    topic_keys = [f"topic:{slug}" for slug in row.topic_slugs]
    topic_affinity = max((row.affinities.get(key, 0.0) for key in topic_keys), default=0.0)
    creator_affinity = row.affinities.get(f"creator:{row.creator_id}", 0.0)
    media_affinity = row.affinities.get(f"media:{row.media_type}", 0.0)

    token_values = [value for key, value in row.affinities.items() if key.startswith("token:")]
    token_mean = sum(token_values) / len(token_values) if token_values else 0.0

    period = period_of(row.local_hour)
    return [
        1.0,
        topic_affinity,
        creator_affinity,
        media_affinity,
        token_mean,
        math.log1p(row.like_count),
        math.log1p(row.comment_count),
        math.exp(-max(row.age_hours, 0.0) / 96.0),
        1.0 if row.media_type == "video" else 0.0,
        1.0 if row.media_type == "image" else 0.0,
        # Soğuk başlangıç: hiç sinyali olmayan kullanıcı için yakınlık
        # özelliklerinin tamamı sıfır; model bunu "ilgisiz" ile karıştırmamalı.
        1.0 if row.signal_count == 0 else 0.0,
        *(1.0 if row.candidate_source == name else 0.0 for name in SOURCES),
        *(1.0 if period == name else 0.0 for name in PERIODS),
    ]


def assert_vector_matches_names(vector: list[float]) -> None:
    if len(vector) != len(FEATURE_NAMES):
        raise ValueError(
            f"özellik sayısı uyuşmuyor: {len(vector)} != {len(FEATURE_NAMES)}"
        )

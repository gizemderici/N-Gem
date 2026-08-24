"""`nexi-contextual-v1` sıralayıcısının Python karşılığı.

Bu dosya backend'deki `ContextualRanker` ile **birebir aynı** aritmetiği
yürütmek zorundadır. Daha önce çevrimdışı değerlendirici kendi basitleştirilmiş
puanlamasını kullanıyordu: güncellik bileşeni hiç yoktu, kalite başka bir
bölenle hesaplanıyordu, keşif gürültüsü SHA-256 ile üretiliyordu ve
çeşitlendirme uygulanmıyordu. Bu hâliyle "çevrimdışı değerlendirme" üretimdeki
modeli değil, ona benzeyen başka bir modeli ölçüyordu.

Türkçe kelime ayrıştırması bilerek burada yok: özellik çıkarımı Kotlin'de
kalıyor ve `fixtures/ranking_parity.json` dosyasına yazılıyor. İkinci bir
tokenizer kopyası bakımı imkânsız hâle getirirdi.

Eşitliği `tests/test_ranking_parity.py` doğruluyor.
"""

from __future__ import annotations

import math
import uuid
from dataclasses import dataclass, field

MASK64 = (1 << 64) - 1

# Puanlama ağırlıkları; ContextualRanker.rank ile aynı.
PERSONALIZED_WEIGHT = 2.4
FRESHNESS_WEIGHT = 0.9
QUALITY_WEIGHT = 0.35
EXPLORATION_WEIGHT = 0.10
FRESHNESS_HALF_LIFE_HOURS = 96.0
QUALITY_DIVISOR = 6.0

# Çeşitlendirme cezaları.
SAME_AUTHOR_PENALTY = 0.55
OVERLAP_PENALTY = 0.25

# Yakınlık birikimi.
RECENCY_HALF_LIFE_DAYS = 30.0
OFF_PERIOD_WEIGHT = 0.30
EVIDENCE_PRIOR = 2.0

REWARDS = {
    "SESSION_STARTED": 0.0,
    "FEED_SERVED": 0.0,
    "CONTENT_IMPRESSION": 0.0,
    "INTEREST_SELECTED": 2.8,
    "CONTENT_COMPLETE": 2.0,
    "CONTENT_LIKED": 2.2,
    "CONTENT_SAVED": 3.0,
    "CONTENT_SHARED": 3.2,
    "RECOMMENDATION_REASON_OPENED": 0.35,
    "CONTENT_HIDDEN": -4.0,
    "CONTENT_REPORTED": -6.0,
}


@dataclass(frozen=True)
class Signal:
    event_type: str
    features: tuple[str, ...]
    local_hour: int
    occurred_at: int
    dwell_millis: int | None = None
    completion_ratio: float | None = None


@dataclass(frozen=True)
class Candidate:
    post_id: str
    owner_id: str
    created_at: int
    like_count: int
    save_count: int
    features: tuple[str, ...]


@dataclass(frozen=True)
class Scored:
    candidate: Candidate
    raw_score: float
    final_score: float
    matched: dict[str, float] = field(default_factory=dict)


def reward(signal: Signal) -> float:
    """Sinyalin tercih kanıtı olarak ağırlığı.

    Sunum ve gösterim sıfır: ikisi de kullanıcının bir şey seçtiğini değil,
    içeriğin önüne geldiğini söyler.
    """
    if signal.event_type == "CONTENT_VIEW":
        dwell = min(signal.dwell_millis or 0, 60_000) / 60_000.0 * 1.1
        completion = min(max(signal.completion_ratio or 0.0, 0.0), 1.0) * 1.2
        return 0.15 + dwell + completion
    return REWARDS.get(signal.event_type, 0.0)


def time_period(hour: int) -> str:
    hour = min(max(hour, 0), 23)
    if 6 <= hour <= 8:
        return "MORNING"
    if 9 <= hour <= 17:
        return "WORK"
    if 18 <= hour <= 21:
        return "EVENING"
    return "NIGHT"


def build_affinities(signals: list[Signal], current_hour: int, now: int) -> dict[str, float]:
    """Zaman ağırlıklı özellik yakınlıkları.

    Kanıt payandası (`EVIDENCE_PRIOR`) tek bir olayın yakınlığı uç değere
    itmesini engelliyor: bir kez beğenmek ile on kez beğenmek aynı güveni
    vermemeli.
    """
    current_period = time_period(current_hour)
    weighted: dict[str, float] = {}
    evidence: dict[str, float] = {}

    for signal in signals:
        value = reward(signal)
        if value == 0.0:
            continue
        # Kotlin `Duration.toHours()` tam saate kırpar; bölme de öyle olmalı.
        age_hours = max(0, (now - signal.occurred_at)) // 3_600
        age_days = age_hours / 24.0
        recency = math.exp(-age_days / RECENCY_HALF_LIFE_DAYS)
        context = 1.0 if time_period(signal.local_hour) == current_period else OFF_PERIOD_WEIGHT
        weight = recency * context
        for key in signal.features:
            weighted[key] = weighted.get(key, 0.0) + value * weight
            evidence[key] = evidence.get(key, 0.0) + abs(value) * weight

    return {key: value / (EVIDENCE_PRIOR + evidence[key]) for key, value in weighted.items()}


def deterministic_exploration(viewer_id: str, post_id: str, now: int) -> float:
    """Günlük değişen ama tekrar üretilebilir keşif gürültüsü.

    Kotlin tarafı UUID'nin işaretli 64 bitlik yarımlarını XOR'luyor; SHA-256
    gibi başka bir kaynak kullanmak iki uygulamayı sessizce ayrıştırırdı.

    Kalan tam sayı aritmetiğiyle alınmalı. `math.fmod` işleneni önce float'a
    çevirir ve 2^53'ün üstündeki değerlerde hassasiyeti yitirir; karışım
    değeri 64 bitlik olduğu için bu, puanı binde üç mertebesinde kaydırıyordu.
    Kotlin'in `%` işlemi bölünenin işaretini koruduğundan `abs(x) % n`
    doğrudan `abs(x % n)` ile aynı sonucu veriyor.
    """
    day = now // 86_400 if now >= 0 else -((-now) // 86_400)
    viewer = uuid.UUID(viewer_id).int
    post = uuid.UUID(post_id).int
    msb = (viewer >> 64) & MASK64
    lsb = post & MASK64
    mixed_unsigned = (msb ^ lsb ^ (day & MASK64)) & MASK64
    mixed = mixed_unsigned - (1 << 64) if mixed_unsigned >= (1 << 63) else mixed_unsigned
    return (abs(mixed) % 10_000) / 10_000.0


def score_candidate(
    viewer_id: str,
    candidate: Candidate,
    affinities: dict[str, float],
    now: int,
) -> Scored:
    matched = {key: affinities[key] for key in candidate.features if key in affinities}
    personalized = sum(matched.values()) / math.sqrt(max(1, len(candidate.features)))

    # Kotlin `Duration.between(...).toMinutes()` tam dakikaya kırpıyor.
    age_minutes = max(0, (now - candidate.created_at) // 60)
    age_hours = age_minutes / 60.0
    freshness = math.exp(-age_hours / FRESHNESS_HALF_LIFE_HOURS)
    quality = math.log1p(candidate.like_count + candidate.save_count * 2.0) / QUALITY_DIVISOR
    exploration = deterministic_exploration(viewer_id, candidate.post_id, now) * EXPLORATION_WEIGHT

    final = (
        personalized * PERSONALIZED_WEIGHT
        + freshness * FRESHNESS_WEIGHT
        + quality * QUALITY_WEIGHT
        + exploration
    )
    return Scored(candidate=candidate, raw_score=personalized, final_score=final, matched=matched)


def diversify(scored: list[Scored]) -> list[Scored]:
    """Aynı yazarın ve benzer konunun art arda gelmesini cezalandırır.

    Açgözlü seçim; Kotlin `maxBy` beraberlikte ilk öğeyi tutuyor, Python'un
    `max` fonksiyonu da öyle. Başlangıç sırası da aynı olmak zorunda: iki
    tarafta da puana göre kararlı azalan sıralama.
    """
    remaining = sorted(scored, key=lambda item: item.final_score, reverse=True)
    selected: list[Scored] = []

    while remaining:
        best_index = 0
        best_value = None
        for index, candidate in enumerate(remaining):
            same_author = sum(
                1 for item in selected if item.candidate.owner_id == candidate.candidate.owner_id
            )
            candidate_tokens = {
                key for key in candidate.candidate.features
                if key.startswith("topic:") or key.startswith("token:")
            }
            overlaps = [
                0.0 if not candidate_tokens
                else len(candidate_tokens & set(previous.candidate.features)) / len(candidate_tokens)
                for previous in selected
            ]
            max_overlap = max(overlaps) if overlaps else 0.0
            value = (
                candidate.final_score
                - same_author * SAME_AUTHOR_PENALTY
                - max_overlap * OVERLAP_PENALTY
            )
            if best_value is None or value > best_value:
                best_value = value
                best_index = index
        selected.append(remaining.pop(best_index))

    return selected


def rank(
    viewer_id: str,
    candidates: list[Candidate],
    signals: list[Signal],
    local_hour: int,
    now: int,
) -> list[Scored]:
    """Sıralamanın tamamı; backend'deki `ContextualRanker.rankAll` karşılığı."""
    affinities = build_affinities(signals, local_hour, now)
    scored = [score_candidate(viewer_id, candidate, affinities, now) for candidate in candidates]
    return diversify(scored)

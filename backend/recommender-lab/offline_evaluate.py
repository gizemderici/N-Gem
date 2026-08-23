#!/usr/bin/env python3
"""Time-aware offline evaluation for the NSosyal contextual ranker.

The script intentionally uses only Python's standard library. It expects the
normalized event contract documented in recommender-lab/README.md and keeps the
last positive interaction of every eligible user as the test target.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
from collections import Counter, defaultdict
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable


@dataclass(frozen=True)
class Event:
    user_id: str
    item_id: str
    timestamp: float
    local_hour: int
    event_type: str
    topics: tuple[str, ...]
    target_feature: str
    creator_id: str
    media_type: str
    dwell_ms: int
    completion_ratio: float

    @property
    def features(self) -> tuple[str, ...]:
        explicit_topic = self.target_feature.lower().removeprefix("topic:").strip()
        values = [f"topic:{explicit_topic}"] if explicit_topic else [f"topic:{topic}" for topic in self.topics]
        if self.creator_id:
            values.append(f"creator:{self.creator_id}")
        if self.media_type:
            values.append(f"media:{self.media_type}")
        return tuple(values)


def parse_timestamp(value: str) -> float:
    text = value.strip()
    if not text:
        raise ValueError("timestamp boş olamaz")
    try:
        numeric = float(text)
        return numeric / 1000.0 if numeric > 10_000_000_000 else numeric
    except ValueError:
        normalized = text.replace("Z", "+00:00")
        parsed = datetime.fromisoformat(normalized)
        if parsed.tzinfo is None:
            parsed = parsed.replace(tzinfo=timezone.utc)
        return parsed.timestamp()


def parse_topics(value: str) -> tuple[str, ...]:
    normalized = value.replace("|", ";").replace(",", ";")
    return tuple(sorted({topic.strip().lower() for topic in normalized.split(";") if topic.strip()}))


def load_events(path: Path) -> list[Event]:
    events: list[Event] = []
    with path.open("r", encoding="utf-8-sig", newline="") as handle:
        reader = csv.DictReader(handle)
        required = {"user_id", "item_id", "timestamp", "local_hour", "event_type"}
        missing = required.difference(reader.fieldnames or ())
        if missing:
            raise ValueError(f"Eksik kolonlar: {', '.join(sorted(missing))}")
        for line, row in enumerate(reader, start=2):
            try:
                hour = int(row["local_hour"])
                if hour not in range(24):
                    raise ValueError("local_hour 0-23 olmalı")
                events.append(
                    Event(
                        user_id=row["user_id"].strip(),
                        item_id=row["item_id"].strip(),
                        timestamp=parse_timestamp(row["timestamp"]),
                        local_hour=hour,
                        event_type=row["event_type"].strip().lower(),
                        topics=parse_topics(row.get("topics", "")),
                        target_feature=row.get("target_feature", "").strip(),
                        creator_id=row.get("creator_id", "").strip(),
                        media_type=row.get("media_type", "").strip().lower(),
                        dwell_ms=max(0, int(float(row.get("dwell_ms", "0") or 0))),
                        completion_ratio=min(1.0, max(0.0, float(row.get("completion_ratio", "0") or 0))),
                    )
                )
            except (TypeError, ValueError) as error:
                raise ValueError(f"{path}:{line}: {error}") from error
    return sorted(events, key=lambda event: (event.timestamp, event.user_id, event.item_id))


def reward(event: Event) -> float:
    if event.event_type in {"session_started", "content_impression"}:
        return 0.0
    if event.event_type == "interest_selected":
        return 2.8
    if event.event_type == "content_view":
        dwell = min(event.dwell_ms, 60_000) / 60_000.0 * 1.1
        return 0.15 + dwell + event.completion_ratio * 1.2
    return {
        "content_complete": 2.0,
        "content_liked": 2.2,
        "content_saved": 3.0,
        "content_shared": 3.2,
        "recommendation_reason_opened": 0.35,
        "content_hidden": -4.0,
        "content_reported": -6.0,
    }.get(event.event_type, 0.0)


def time_period(hour: int) -> str:
    if 6 <= hour <= 8:
        return "morning"
    if 9 <= hour <= 17:
        return "work_hours"
    if 18 <= hour <= 21:
        return "evening"
    return "night"


def build_affinities(history: Iterable[Event], target: Event) -> dict[str, float]:
    weighted_reward: Counter[str] = Counter()
    evidence: Counter[str] = Counter()
    target_period = time_period(target.local_hour)
    for event in history:
        value = reward(event)
        if value == 0.0:
            continue
        age_days = max(0.0, target.timestamp - event.timestamp) / 86_400.0
        recency = math.exp(-age_days / 30.0)
        context = 1.0 if time_period(event.local_hour) == target_period else 0.30
        weight = recency * context
        for feature in event.features:
            weighted_reward[feature] += value * weight
            evidence[feature] += abs(value) * weight
    return {key: value / (2.0 + evidence[key]) for key, value in weighted_reward.items()}


def stable_noise(user_id: str, item_id: str, salt: str = "") -> float:
    digest = hashlib.sha256(f"{salt}:{user_id}:{item_id}".encode()).digest()
    return int.from_bytes(digest[:4], "big") / 2**32


def evaluate(
    events: list[Event],
    k: int = 10,
    max_candidates: int = 500,
    max_users: int | None = None,
) -> dict[str, float | int]:
    by_user: dict[str, list[Event]] = defaultdict(list)
    item_features: dict[str, tuple[str, ...]] = {}
    item_first_seen: dict[str, float] = {}
    for event in events:
        by_user[event.user_id].append(event)
        if event.item_id and event.features:
            item_features[event.item_id] = event.features
        if event.item_id:
            item_first_seen[event.item_id] = min(item_first_seen.get(event.item_id, event.timestamp), event.timestamp)

    targets: list[Event] = []
    histories: dict[str, list[Event]] = {}
    for user_id, history in by_user.items():
        positives = [event for event in history if event.item_id and reward(event) >= 0.30]
        if len(positives) < 2:
            continue
        target = positives[-1]
        before = [event for event in history if event.timestamp < target.timestamp]
        if not before:
            continue
        targets.append(target)
        histories[user_id] = before

    if max_users is not None and len(targets) > max_users:
        targets = sorted(targets, key=lambda event: stable_noise(event.user_id, "evaluation-user", "user-sample"))[:max_users]
    targets.sort(key=lambda event: event.timestamp)

    ranks: list[int] = []
    popularity_ranks: list[int] = []
    random_ranks: list[int] = []
    recommended_items: set[str] = set()
    popularity_recommended: set[str] = set()
    random_recommended: set[str] = set()
    popularity: Counter[str] = Counter()
    popularity_cursor = 0
    for target in targets:
        while popularity_cursor < len(events) and events[popularity_cursor].timestamp < target.timestamp:
            event = events[popularity_cursor]
            if reward(event) > 0 and event.item_id:
                popularity[event.item_id] += 1
            popularity_cursor += 1
        history = histories[target.user_id]
        seen = {event.item_id for event in history if event.item_id}
        catalog = [
            item_id for item_id, first_seen in item_first_seen.items()
            if first_seen <= target.timestamp and (item_id not in seen or item_id == target.item_id)
        ]
        if target.item_id not in catalog:
            continue
        negatives = sorted(
            (item for item in catalog if item != target.item_id),
            key=lambda item: stable_noise(target.user_id, item, "candidate-sample"),
        )[: max(0, max_candidates - 1)]
        candidates = negatives + [target.item_id]
        affinities = build_affinities(history, target)

        def item_score(item_id: str) -> float:
            features = item_features.get(item_id, ())
            personalized = sum(affinities.get(feature, 0.0) for feature in features)
            personalized /= math.sqrt(max(1, len(features)))
            quality = math.log1p(popularity[item_id]) / 10.0
            return personalized * 2.4 + quality * 0.35 + stable_noise(target.user_id, item_id, "model-tie") * 0.01

        ranking = sorted(candidates, key=item_score, reverse=True)
        popularity_ranking = sorted(
            candidates,
            key=lambda item: (popularity[item], stable_noise(target.user_id, item, "popularity-tie")),
            reverse=True,
        )
        random_ranking = sorted(
            candidates,
            key=lambda item: stable_noise(target.user_id, item, "random-baseline"),
            reverse=True,
        )
        rank = ranking.index(target.item_id) + 1
        ranks.append(rank)
        popularity_ranks.append(popularity_ranking.index(target.item_id) + 1)
        random_ranks.append(random_ranking.index(target.item_id) + 1)
        recommended_items.update(ranking[:k])
        popularity_recommended.update(popularity_ranking[:k])
        random_recommended.update(random_ranking[:k])

    evaluated = len(ranks)
    catalog_size = len(item_first_seen)
    def metric_values(values: list[int], recommended: set[str], prefix: str = "") -> dict[str, float]:
        matching = [rank for rank in values if rank <= k]
        return {
            f"{prefix}hit_rate@{k}": round(len(matching) / evaluated, 6) if evaluated else 0.0,
            f"{prefix}mrr@{k}": round(sum(1.0 / rank for rank in matching) / evaluated, 6) if evaluated else 0.0,
            f"{prefix}ndcg@{k}": round(sum(1.0 / math.log2(rank + 1) for rank in matching) / evaluated, 6) if evaluated else 0.0,
            f"{prefix}coverage@{k}": round(len(recommended) / catalog_size, 6) if catalog_size else 0.0,
        }

    return {
        "evaluated_users": evaluated,
        **metric_values(ranks, recommended_items),
        **metric_values(popularity_ranks, popularity_recommended, "popularity_baseline_"),
        **metric_values(random_ranks, random_recommended, "random_baseline_"),
        "catalog_size": catalog_size,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="NSosyal bağlamsal önerici çevrimdışı değerlendirmesi")
    parser.add_argument("events", type=Path, help="Normalize edilmiş CSV olay dosyası")
    parser.add_argument("--k", type=int, default=10)
    parser.add_argument("--max-candidates", type=int, default=500)
    parser.add_argument("--max-users", type=int, default=1_000, help="Deterministik değerlendirme kullanıcı örneklemi; 0 tüm kullanıcılar")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if args.k < 1 or args.max_candidates < args.k:
        parser.error("k en az 1 olmalı ve max-candidates k'dan küçük olmamalı")
    metrics = evaluate(
        load_events(args.events),
        args.k,
        args.max_candidates,
        None if args.max_users == 0 else max(1, args.max_users),
    )
    payload = json.dumps(metrics, ensure_ascii=False, indent=2)
    if args.output:
        args.output.write_text(payload + "\n", encoding="utf-8")
    print(payload)


if __name__ == "__main__":
    main()

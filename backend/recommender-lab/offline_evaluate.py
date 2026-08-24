#!/usr/bin/env python3
"""NSosyal sıralayıcısı için zaman bazlı çevrimdışı değerlendirme.

Yalnızca standart kütüphane kullanır. Puanlama [`nexi_ranker`][nexi_ranker]
üzerinden yapılır; o modülün backend'deki `ContextualRanker` ile eşitliğini
`tests/test_ranking_parity.py` doğrular. Değerlendirici kendi basitleştirilmiş
kopyasını kullandığı sürece ölçtüğü şey üretimdeki model değildi.

Zaman bazlı ayrım: her uygun kullanıcının **son** olumlu etkileşimi test
hedefi, ondan önceki her şey geçmiş. Aday havuzu ve etkileşim sayaçları da
yalnızca hedef anına kadar olan olaylardan üretilir.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import sys
from collections import Counter, defaultdict
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from metrics import MetricAccumulator  # noqa: E402
from nexi_ranker import Candidate, Signal, deterministic_exploration, reward  # noqa: E402
from learned_ranker import policy_from  # noqa: E402
from policies import POLICIES, as_uuid  # noqa: E402

MODEL_VERSION = "nexi-contextual-v1"
FEATURE_VERSION = "nexi-features-v2"


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
            values.append(f"creator:{as_uuid(self.creator_id)}")
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


def to_signal(event: Event) -> Signal:
    return Signal(
        event_type=event.event_type.upper(),
        features=event.features,
        local_hour=event.local_hour,
        occurred_at=int(event.timestamp),
        dwell_millis=event.dwell_ms,
        completion_ratio=event.completion_ratio,
    )


def reward_of(event: Event) -> float:
    return reward(to_signal(event))


def dataset_checksum(path: Path) -> str:
    """Girdi dosyasının SHA-256 özeti.

    "Aynı veri ve aynı model sürümü aynı sonucu üretmeli" ancak sonucun hangi
    dosyadan geldiği kayıtlıysa doğrulanabilir.
    """
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


@dataclass(frozen=True)
class Example:
    """Tek değerlendirme örneği: bir kullanıcının son olumlu etkileşimi."""

    viewer_id: str
    target_item: str
    local_hour: int
    now: int
    signals: tuple[Signal, ...]
    candidates: tuple[Candidate, ...]
    disliked: frozenset[str]


def build_examples(
    events: list[Event],
    max_candidates: int,
    max_users: int | None,
) -> tuple[list[Example], set[str], int]:
    by_user: dict[str, list[Event]] = defaultdict(list)
    item_features: dict[str, tuple[str, ...]] = {}
    item_creator: dict[str, str] = {}
    item_first_seen: dict[str, float] = {}
    appearance: Counter[str] = Counter()

    for event in events:
        by_user[event.user_id].append(event)
        if event.item_id and event.features:
            item_features[event.item_id] = event.features
        if event.item_id and event.creator_id:
            item_creator[event.item_id] = event.creator_id
        if event.item_id:
            item_first_seen[event.item_id] = min(
                item_first_seen.get(event.item_id, event.timestamp), event.timestamp
            )
        if event.creator_id:
            appearance[event.creator_id] += 1

    targets: list[Event] = []
    histories: dict[str, list[Event]] = {}
    for user_id, history in by_user.items():
        positives = [event for event in history if event.item_id and reward_of(event) >= 0.30]
        if len(positives) < 2:
            continue
        target = positives[-1]
        before = [event for event in history if event.timestamp < target.timestamp]
        if not before:
            continue
        targets.append(target)
        histories[user_id] = before

    if max_users is not None and len(targets) > max_users:
        targets = sorted(
            targets,
            key=lambda event: deterministic_exploration(
                as_uuid(event.user_id), as_uuid("user-sample"), 0
            ),
        )[:max_users]
    targets.sort(key=lambda event: event.timestamp)

    examples: list[Example] = []
    engagement: Counter[str] = Counter()
    cursor = 0
    for target in targets:
        while cursor < len(events) and events[cursor].timestamp < target.timestamp:
            event = events[cursor]
            if reward_of(event) > 0 and event.item_id:
                engagement[event.item_id] += 1
            cursor += 1

        history = histories[target.user_id]
        seen = {event.item_id for event in history if event.item_id}
        disliked = {
            event.item_id for event in history
            if event.item_id and event.event_type in {"content_hidden", "content_reported"}
        }
        catalog = [
            item_id for item_id, first_seen in item_first_seen.items()
            if first_seen <= target.timestamp and (item_id not in seen or item_id == target.item_id)
        ]
        if target.item_id not in catalog:
            continue

        viewer_uuid = as_uuid(target.user_id)
        negatives = sorted(
            (item for item in catalog if item != target.item_id),
            key=lambda item: deterministic_exploration(viewer_uuid, as_uuid(item), 1),
        )[: max(0, max_candidates - 1)]

        candidates = tuple(
            Candidate(
                post_id=as_uuid(item_id),
                owner_id=as_uuid(item_creator.get(item_id, item_id)),
                created_at=int(item_first_seen[item_id]),
                like_count=engagement[item_id],
                save_count=0,
                features=item_features.get(item_id, ()),
            )
            for item_id in negatives + [target.item_id]
        )

        examples.append(
            Example(
                viewer_id=viewer_uuid,
                target_item=as_uuid(target.item_id),
                local_hour=target.local_hour,
                now=int(target.timestamp),
                signals=tuple(to_signal(event) for event in history),
                candidates=candidates,
                disliked=frozenset(as_uuid(item) for item in disliked),
            )
        )

    # "Yeni üretici": gösterim sayısı medyanın altında kalanlar. Mutlak bir
    # eşik veri setinden veri setine anlamını yitirirdi.
    median = sorted(appearance.values())[len(appearance) // 2] if appearance else 0
    new_creators = {as_uuid(creator) for creator, count in appearance.items() if count <= median}

    return examples, new_creators, len(item_first_seen)


def evaluate(
    events: list[Event],
    k: int = 10,
    max_candidates: int = 500,
    max_users: int | None = None,
    model_path: Path | None = None,
) -> dict[str, object]:
    examples, new_creators, catalog_size = build_examples(events, max_candidates, max_users)

    # Egitilmis model ancak ayni hatta, ayni taban modellerle ve ayni
    # metriklerle olculurse anlamli. Ayri bir betikte "su skoru aldi"
    # demek kiyas noktasi olmadigi icin bir sey soylemez.
    policies = dict(POLICIES)
    if model_path is not None:
        policies["learned"] = policy_from(model_path)

    accumulators = {name: MetricAccumulator(k=k) for name in policies}
    for example in examples:
        for name, policy in policies.items():
            ranking = policy(
                example.viewer_id,
                list(example.candidates),
                list(example.signals),
                example.local_hour,
                example.now,
            )
            accumulators[name].observe(ranking, example.target_item, new_creators, example.disliked)

    summaries: dict[str, dict[str, float]] = {}
    for name, accumulator in accumulators.items():
        summary = accumulator.summary()
        summary[f"coverage@{k}"] = accumulator.coverage(catalog_size)
        summaries[name] = summary

    return {
        "model_version": MODEL_VERSION,
        "feature_version": FEATURE_VERSION,
        "evaluated_examples": len(examples),
        "catalog_size": catalog_size,
        "k": k,
        "policies": summaries,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="NSosyal sıralayıcısı çevrimdışı değerlendirmesi")
    parser.add_argument("events", type=Path, help="Normalize edilmiş CSV olay dosyası")
    parser.add_argument("--k", type=int, default=10)
    parser.add_argument("--max-candidates", type=int, default=500)
    parser.add_argument(
        "--max-users",
        type=int,
        default=1_000,
        help="Deterministik değerlendirme kullanıcı örneklemi; 0 tüm kullanıcılar",
    )
    parser.add_argument("--output", type=Path)
    parser.add_argument(
        "--model",
        type=Path,
        help="Egitilmis model dosyasi; verilirse `learned` politikasi da karsilastirmaya girer.",
    )
    args = parser.parse_args()
    if args.k < 1 or args.max_candidates < args.k:
        parser.error("k en az 1 olmalı ve max-candidates k'dan küçük olmamalı")

    metrics = evaluate(
        load_events(args.events),
        args.k,
        args.max_candidates,
        None if args.max_users == 0 else max(1, args.max_users),
        model_path=args.model,
    )
    metrics["dataset"] = {"path": args.events.name, "sha256": dataset_checksum(args.events)}
    payload = json.dumps(metrics, ensure_ascii=False, indent=2)
    if args.output:
        args.output.write_text(payload + "\n", encoding="utf-8")
    print(payload)


if __name__ == "__main__":
    main()

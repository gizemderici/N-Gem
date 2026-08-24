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



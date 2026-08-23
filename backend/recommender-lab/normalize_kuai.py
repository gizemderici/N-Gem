#!/usr/bin/env python3
"""Stream KuaiRand or KuaiRec interactions into the NSosyal event contract."""

from __future__ import annotations

import argparse
import ast
import csv
import re
from datetime import datetime, timezone
from pathlib import Path


OUTPUT_FIELDS = [
    "user_id", "item_id", "timestamp", "local_hour", "event_type", "topics",
    "target_feature", "creator_id", "media_type", "dwell_ms", "completion_ratio",
]


def safe_float(value: str | None, default: float = 0.0) -> float:
    try:
        return float(value or default)
    except ValueError:
        return default


def truthy(row: dict[str, str], key: str) -> bool:
    return safe_float(row.get(key)) > 0


def load_side_info(path: Path | None, id_column: str, topic_column: str, author_column: str | None = None) -> dict[str, tuple[str, str]]:
    if path is None:
        return {}
    result: dict[str, tuple[str, str]] = {}
    with path.open("r", encoding="utf-8-sig", newline="") as handle:
        for row in csv.DictReader(handle):
            raw = row.get(topic_column, "")
            try:
                parsed = ast.literal_eval(raw)
                values = parsed if isinstance(parsed, (list, tuple, set)) else [parsed]
            except (SyntaxError, ValueError):
                values = re.findall(r"[\w-]+", raw)
            topics = ";".join(f"category_{value}" for value in values if str(value).strip())
            result[row[id_column]] = (topics, row.get(author_column, "") if author_column else "")
    return result


def kuairec(args: argparse.Namespace) -> None:
    side = load_side_info(args.categories, "video_id", "feat")
    with args.interactions.open("r", encoding="utf-8-sig", newline="") as source, args.output.open("w", encoding="utf-8", newline="") as target:
        writer = csv.DictWriter(target, fieldnames=OUTPUT_FIELDS)
        writer.writeheader()
        for row in csv.DictReader(source):
            timestamp = safe_float(row.get("timestamp"))
            human_time = row.get("time", "")
            try:
                hour = datetime.fromisoformat(human_time).hour
            except ValueError:
                hour = datetime.fromtimestamp(timestamp, timezone.utc).hour
            watch_ratio = max(0.0, safe_float(row.get("watch_ratio")))
            dwell = max(0, int(safe_float(row.get("play_duration"))))
            topics, creator = side.get(row["video_id"], ("", ""))
            writer.writerow({
                "user_id": row["user_id"], "item_id": row["video_id"], "timestamp": timestamp,
                "local_hour": hour, "event_type": "content_complete" if watch_ratio >= 0.95 else "content_view",
                "topics": topics, "target_feature": "", "creator_id": creator, "media_type": "video", "dwell_ms": dwell,
                "completion_ratio": min(1.0, watch_ratio),
            })


def kuairand(args: argparse.Namespace) -> None:
    side = load_side_info(args.items, "video_id", "tag", "author_id")
    with args.interactions.open("r", encoding="utf-8-sig", newline="") as source, args.output.open("w", encoding="utf-8", newline="") as target:
        writer = csv.DictWriter(target, fieldnames=OUTPUT_FIELDS)
        writer.writeheader()
        for row in csv.DictReader(source):
            dwell = max(0, int(safe_float(row.get("play_time_ms"))))
            duration = max(0.0, safe_float(row.get("duration_ms")))
            ratio = min(1.0, dwell / duration) if duration else 0.0
            if truthy(row, "is_hate"):
                event_type = "content_hidden"
            elif truthy(row, "is_forward"):
                event_type = "content_shared"
            elif truthy(row, "is_like"):
                event_type = "content_liked"
            elif ratio >= 0.95:
                event_type = "content_complete"
            elif truthy(row, "is_click") or dwell > 0:
                event_type = "content_view"
            else:
                event_type = "content_impression"
            hourmin = str(row.get("hourmin", "0")).zfill(4)
            hour = min(23, max(0, int(hourmin[:-2] or 0)))
            topics, creator = side.get(row["video_id"], ("", ""))
            writer.writerow({
                "user_id": row["user_id"], "item_id": row["video_id"],
                "timestamp": safe_float(row.get("time_ms")) / 1000.0, "local_hour": hour,
                "event_type": event_type, "topics": topics, "target_feature": "", "creator_id": creator,
                "media_type": "video", "dwell_ms": dwell, "completion_ratio": ratio,
            })


def main() -> None:
    parser = argparse.ArgumentParser(description="Kuai veri setlerini NSosyal şemasına dönüştür")
    subparsers = parser.add_subparsers(dest="dataset", required=True)
    rec = subparsers.add_parser("kuairec")
    rec.add_argument("--interactions", required=True, type=Path)
    rec.add_argument("--categories", type=Path)
    rec.add_argument("--output", required=True, type=Path)
    rec.set_defaults(handler=kuairec)
    rand = subparsers.add_parser("kuairand")
    rand.add_argument("--interactions", required=True, type=Path)
    rand.add_argument("--items", type=Path)
    rand.add_argument("--output", required=True, type=Path)
    rand.set_defaults(handler=kuairand)
    args = parser.parse_args()
    args.handler(args)


if __name__ == "__main__":
    main()

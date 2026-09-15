#!/usr/bin/env python3
"""Union per-market feeds from a second producer (the residential-IP scrape
pushed to the `feed-data` branch) into the CI-built feeds/ tree.

    python merge_feeds.py <incoming_feeds_dir> <target_feeds_dir>

Per market: every incoming event whose id is not already in the target feed
is appended (ids are sha1(title|date), so cross-producer duplicates collapse).
An incoming feed older than 36 hours is ignored so a stale residential run
never resurrects events a fresh CI scrape dropped. The merged feed carries the
newer generated_at."""
from __future__ import annotations

import json
import os
import sys
from datetime import datetime, timedelta, timezone

MAX_AGE = timedelta(hours=36)


def load(path: str) -> dict | None:
    if not os.path.exists(path):
        return None
    try:
        with open(path, encoding="utf-8") as fh:
            return json.load(fh)
    except (OSError, ValueError):
        return None


def generated_at(feed: dict | None) -> datetime | None:
    if not feed:
        return None
    try:
        return datetime.strptime(feed["generated_at"], "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)
    except (KeyError, ValueError):
        return None


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    src, dst = sys.argv[1], sys.argv[2]
    if not os.path.isdir(src):
        print(f"no incoming feeds at {src}; nothing merged")
        return 0
    now = datetime.now(timezone.utc)
    touched = 0
    for market in sorted(os.listdir(src)):
        inc = load(os.path.join(src, market, "events.json"))
        if not inc:
            continue
        inc_at = generated_at(inc)
        if inc_at is None or now - inc_at > MAX_AGE:
            print(f"{market}: incoming feed too old ({inc.get('generated_at')}); skipped")
            continue
        inc_events = inc.get("events") or []
        tgt_path = os.path.join(dst, market, "events.json")
        tgt = load(tgt_path)
        if not tgt:
            tgt = dict(inc, events=[])
        tgt_events = list(tgt.get("events") or [])
        seen = {e.get("id") for e in tgt_events}
        added = [e for e in inc_events if e.get("id") not in seen]
        if not added and tgt.get("events"):
            print(f"{market}: CI feed already covers the residential feed ({len(tgt_events)} events)")
            continue
        merged = tgt_events + added
        merged.sort(key=lambda e: (e.get("date") or "", e.get("time_start") or "", e.get("name") or ""))
        tgt["events"] = merged
        tgt_at = generated_at(tgt)
        if tgt_at is None or inc_at > tgt_at:
            tgt["generated_at"] = inc["generated_at"]
        os.makedirs(os.path.dirname(tgt_path), exist_ok=True)
        with open(tgt_path, "w", encoding="utf-8") as fh:
            json.dump(tgt, fh, ensure_ascii=False, indent=1)
        print(f"{market}: {len(tgt_events)} CI + {len(added)} residential -> {len(merged)} events")
        touched += 1
    print(f"merged {touched} market feed(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())

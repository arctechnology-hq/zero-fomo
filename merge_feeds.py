#!/usr/bin/env python3
"""Merge per-market feeds from a second producer (the residential-IP scrape
pushed to the `feed-data` branch) into the CI-built feeds/ tree.

    python merge_feeds.py <incoming_feeds_dir> <target_feeds_dir>

Per market: the incoming feed replaces the target when the target is missing
or empty, or when the incoming one has more events and is not older than
36 hours (a stale residential run must not shadow a live CI scrape)."""
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
    merged = 0
    for market in sorted(os.listdir(src)):
        inc_path = os.path.join(src, market, "events.json")
        inc = load(inc_path)
        if not inc:
            continue
        inc_n = len(inc.get("events") or [])
        inc_at = generated_at(inc)
        if inc_at is None or now - inc_at > MAX_AGE:
            print(f"{market}: incoming feed too old ({inc.get('generated_at')}); skipped")
            continue
        tgt_path = os.path.join(dst, market, "events.json")
        tgt = load(tgt_path)
        tgt_n = len((tgt or {}).get("events") or [])
        if tgt_n == 0 or inc_n > tgt_n:
            os.makedirs(os.path.dirname(tgt_path), exist_ok=True)
            with open(tgt_path, "w", encoding="utf-8") as fh:
                json.dump(inc, fh, ensure_ascii=False, indent=1)
            print(f"{market}: took residential feed ({inc_n} events, CI had {tgt_n})")
            merged += 1
        else:
            print(f"{market}: kept CI feed ({tgt_n} events, residential {inc_n})")
    print(f"merged {merged} market feed(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""Emit feeds/markets.json — the manifest the app uses to pick which
per-market feeds to sync (docs/GLOBAL_DESIGN.md §4). Zero dependencies."""
from __future__ import annotations

import json
import os
import sys
from datetime import datetime, timezone

HERE = os.path.dirname(os.path.abspath(__file__))
MARKETS_DIR = os.path.join(HERE, "markets")
OUT = os.path.join(HERE, "feeds", "markets.json")


def main() -> int:
    markets = []
    for fn in sorted(os.listdir(MARKETS_DIR)):
        if not fn.endswith(".json"):
            continue
        with open(os.path.join(MARKETS_DIR, fn), encoding="utf-8") as fh:
            d = json.load(fh)
        feed = os.path.join(HERE, "feeds", d["id"], "events.json")
        entry = {
            "id": d["id"], "name": d["name"], "country": d["country"].upper(),
            "tz": d["tz"], "lat": d["lat"], "lng": d["lng"],
            "radius_km": d.get("radius_km", 50), "currency": d.get("currency", "USD"),
            "path": f"feeds/{d['id']}/events.json",
            "available": os.path.exists(feed),
        }
        if entry["available"]:
            with open(feed, encoding="utf-8") as fh:
                f = json.load(fh)
            entry["events"] = len(f.get("events") or [])
            entry["generated_at"] = f.get("generated_at")
        markets.append(entry)
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as fh:
        json.dump({
            "schema_version": 1,
            "generated_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
            "markets": markets,
        }, fh, ensure_ascii=False, indent=1)
    print(f"{len(markets)} market(s) -> {OUT}; "
          f"{sum(m['available'] for m in markets)} with a feed")
    return 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""Emit feeds/markets.json — the manifest the app uses to pick which
per-market feeds to sync (docs/GLOBAL_DESIGN.md §4). Zero dependencies.

schema_version 2 (G5, 2026-10-01) adds per market: `region` (markets/regions.json),
`size` (empty / small / medium / large by event count — the app picks its
default scope from it), `sources` (count of non-infra sources), `auto`
(market created by discover_sources.py) and the manifest-level `regions`
list. Everything v1 clients read is unchanged."""
from __future__ import annotations

import json
import os
import sys
from datetime import datetime, timezone

HERE = os.path.dirname(os.path.abspath(__file__))
MARKETS_DIR = os.path.join(HERE, "markets")
REGIONS = os.path.join(MARKETS_DIR, "regions.json")
OUT = os.path.join(HERE, "feeds", "markets.json")
INFRA = {"community", "manual"}


def size_of(events: int) -> str:
    if events <= 0:
        return "empty"
    if events < 30:
        return "small"
    if events < 200:
        return "medium"
    return "large"


def main() -> int:
    try:
        with open(REGIONS, encoding="utf-8") as fh:
            regions = json.load(fh)
    except (OSError, ValueError):
        regions = {"regions": [], "countries": {}}
    cc_region = regions.get("countries") or {}
    markets = []
    for fn in sorted(os.listdir(MARKETS_DIR)):
        if not fn.endswith(".json") or fn == "regions.json":
            continue
        with open(os.path.join(MARKETS_DIR, fn), encoding="utf-8") as fh:
            d = json.load(fh)
        feed = os.path.join(HERE, "feeds", d["id"], "events.json")
        sources = [k for k in (d.get("sources") or {}) if k.split("#", 1)[0] not in INFRA]
        entry = {
            "id": d["id"], "name": d["name"], "country": d["country"].upper(),
            "tz": d["tz"], "lat": d["lat"], "lng": d["lng"],
            "radius_km": d.get("radius_km", 50), "currency": d.get("currency", "USD"),
            "path": f"feeds/{d['id']}/events.json",
            "available": os.path.exists(feed),
            "region": cc_region.get(d["country"].upper(), ""),
            "sources": len(sources),
            "auto": bool(d.get("auto", False)),
        }
        events = 0
        if entry["available"]:
            with open(feed, encoding="utf-8") as fh:
                f = json.load(fh)
            events = len(f.get("events") or [])
            entry["events"] = events
            entry["generated_at"] = f.get("generated_at")
        entry["size"] = size_of(events)
        markets.append(entry)
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as fh:
        json.dump({
            "schema_version": 2,
            "generated_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
            "regions": [{"id": r["id"], "name": r["name"], "order": r.get("order", i)}
                        for i, r in enumerate(regions.get("regions") or [])],
            "markets": markets,
        }, fh, ensure_ascii=False, indent=1)
    print(f"{len(markets)} market(s) -> {OUT}; "
          f"{sum(m['available'] for m in markets)} with a feed")
    return 0


if __name__ == "__main__":
    sys.exit(main())

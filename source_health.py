#!/usr/bin/env python3
"""source_health.py — the active watch over every scraper (2026-09-26).

The pipeline writes feeds/<market>/status.json after each run. This script
rolls those into a history, spots sources that went quiet or broke, watches
market totals for collapses, re-probes parked candidate sources so a site
that comes alive is noticed, and pushes a phone alert (status text only).

    python source_health.py --record            # append today's statuses to history
    python source_health.py --report            # health report -> feeds/health/report.json + stdout
    python source_health.py --probe             # re-test sources_watchlist.json candidates
    python source_health.py --notify            # ntfy push when the report has alerts (FIE_NTFY_TOPIC)
    python source_health.py --record --report --notify   # what scrape_and_publish.ps1 runs daily

Zero dependencies beyond requests (already a pipeline requirement).
"""
from __future__ import annotations

import argparse
import json
import os
import re
import statistics
import sys
from datetime import datetime, timezone

HERE = os.path.dirname(os.path.abspath(__file__))
FEEDS = os.path.join(HERE, "feeds")
HEALTH_DIR = os.path.join(FEEDS, "health")
HISTORY = os.path.join(HEALTH_DIR, "history.jsonl")
REPORT = os.path.join(HEALTH_DIR, "report.json")
WATCHLIST = os.path.join(HERE, "sources_watchlist.json")

DEAD_STREAK = 3          # consecutive zero runs after a productive baseline
FAIL_STREAK = 2          # consecutive scraper exceptions
DROP_RATIO = 0.5         # market exported < 50 % of its 7-run median
BASELINE_RUNS = 14       # look-back for "this source used to yield events"
INFRA_SOURCES = {"community", "manual"}   # quiet by design, never "dead"


def _utcnow() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _read_jsonl(path: str) -> list[dict]:
    if not os.path.exists(path):
        return []
    out = []
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if line:
                try:
                    out.append(json.loads(line))
                except ValueError:
                    continue
    return out


# --------------------------------------------------------------------------- record

def record() -> int:
    """Append one line per (market, source) from every feeds/<market>/status.json
    that is newer than the last recorded run for that market."""
    os.makedirs(HEALTH_DIR, exist_ok=True)
    seen = {(r["market"], r["generated_at"]) for r in _read_jsonl(HISTORY)}
    added = 0
    with open(HISTORY, "a", encoding="utf-8") as fh:
        for market in sorted(os.listdir(FEEDS)):
            path = os.path.join(FEEDS, market, "status.json")
            if not os.path.isfile(path):
                continue
            try:
                st = json.load(open(path, encoding="utf-8"))
            except ValueError:
                continue
            if (st.get("market"), st.get("generated_at")) in seen:
                continue
            for src in st.get("sources", []):
                fh.write(json.dumps({
                    "market": st["market"], "generated_at": st["generated_at"],
                    "source": src["name"], "ok": bool(src.get("ok")),
                    "events": int(src.get("events") or 0), "note": src.get("note") or "",
                    "exported": int(st.get("exported") or 0),
                }, ensure_ascii=False) + "\n")
                added += 1
    print(f"recorded {added} source rows -> {HISTORY}")
    return 0


# --------------------------------------------------------------------------- report

def report() -> dict:
    rows = _read_jsonl(HISTORY)
    by_pair: dict[tuple[str, str], list[dict]] = {}
    by_market: dict[str, dict[str, int]] = {}
    for r in rows:
        by_pair.setdefault((r["market"], r["source"]), []).append(r)
        by_market.setdefault(r["market"], {})[r["generated_at"]] = r.get("exported", 0)
    alerts: list[dict] = []
    sources: list[dict] = []
    for (market, source), hist in sorted(by_pair.items()):
        hist.sort(key=lambda r: r["generated_at"])
        recent = hist[-BASELINE_RUNS:]
        last = recent[-1]
        counts = [r["events"] for r in recent]
        baseline = statistics.median(counts[:-1]) if len(counts) > 1 else counts[0]
        zero_streak = 0
        for r in reversed(recent):
            if r["events"] == 0 and r["ok"]:
                zero_streak += 1
            else:
                break
        fail_streak = 0
        for r in reversed(recent):
            if not r["ok"]:
                fail_streak += 1
            else:
                break
        state = "ok"
        if fail_streak >= FAIL_STREAK:
            state = "failed"
        elif source not in INFRA_SOURCES and baseline > 0 and zero_streak >= DEAD_STREAK:
            state = "dead"
        elif source not in INFRA_SOURCES and baseline == 0 and last["events"] > 0 and len(recent) > 1:
            state = "revived"
        entry = {"market": market, "source": source, "state": state,
                 "last_events": last["events"], "baseline_median": baseline,
                 "zero_streak": zero_streak, "fail_streak": fail_streak,
                 "last_run": last["generated_at"], "note": last.get("note", "")}
        sources.append(entry)
        if state in ("dead", "failed", "revived"):
            alerts.append(entry)
    markets: list[dict] = []
    for market, runs in sorted(by_market.items()):
        series = [runs[k] for k in sorted(runs)][-8:]
        last = series[-1]
        median = statistics.median(series[:-1]) if len(series) > 1 else last
        state = "ok"
        if median > 0 and last < median * DROP_RATIO:
            state = "drop"
        markets.append({"market": market, "exported": last, "median_7": median, "state": state})
        if state == "drop":
            alerts.append({"market": market, "source": "*", "state": "drop",
                           "last_events": last, "baseline_median": median})
    rep = {"generated_at": _utcnow(), "alerts": alerts, "markets": markets, "sources": sources}
    os.makedirs(HEALTH_DIR, exist_ok=True)
    with open(REPORT, "w", encoding="utf-8") as fh:
        json.dump(rep, fh, ensure_ascii=False, indent=1)
    print(f"health report: {len(markets)} market(s), {len(sources)} source pairs, "
          f"{len(alerts)} alert(s) -> {REPORT}")
    for a in alerts:
        print(f"  [{a['state'].upper():7}] {a['market']}/{a['source']}: "
              f"last={a['last_events']} baseline={a['baseline_median']}")
    return rep


# --------------------------------------------------------------------------- probe

def _probe_one(item: dict) -> tuple[bool, str]:
    """Return (alive, detail) for a watchlist entry. Kinds:
    tribe  - WP Events Calendar REST, alive when total > 0
    json   - JSON endpoint, alive when `path` (dotted) is a non-empty list
    html   - alive when status 200 and `marker` regex matches the body
    status - alive when HTTP status is 200 (site exists at all)"""
    import requests
    ua = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/128 zerofomo-watch"}
    kind, url = item["kind"], item["url"]
    try:
        r = requests.get(url, headers=ua, timeout=25, allow_redirects=True)
    except requests.RequestException as exc:
        return False, f"error {type(exc).__name__}"
    if kind == "status":
        return r.status_code == 200, f"http {r.status_code}"
    if r.status_code != 200:
        return False, f"http {r.status_code}"
    if kind == "tribe":
        try:
            d = r.json()
            n = int(d.get("total") or len(d.get("events") or []))
        except ValueError:
            return False, "not json"
        return n > 0, f"{n} events"
    if kind == "json":
        try:
            d = r.json()
        except ValueError:
            body = r.text
            i = body.find("[{")
            try:
                d = json.loads(body[i:]) if i >= 0 else {}
            except ValueError:
                return False, "not json"
        for key in (item.get("path") or "").split("."):
            if key and isinstance(d, dict):
                d = d.get(key)
        n = len(d) if isinstance(d, list) else 0
        return n > 0, f"{n} items"
    if kind == "html":
        m = re.findall(item.get("marker", ""), r.text, re.I)
        threshold = int(item.get("min", 1))
        return len(m) >= threshold, f"{len(m)} marker hit(s)"
    return False, "unknown kind"


def probe() -> list[dict]:
    if not os.path.exists(WATCHLIST):
        print("no sources_watchlist.json")
        return []
    items = json.load(open(WATCHLIST, encoding="utf-8")).get("watch", [])
    results = []
    for it in items:
        alive, detail = _probe_one(it)
        results.append({**it, "alive": alive, "detail": detail, "checked_at": _utcnow()})
        flag = "ALIVE " if alive else "quiet "
        print(f"  [{flag}] {it['name']:38} {detail:18} {it['url'][:70]}")
    os.makedirs(HEALTH_DIR, exist_ok=True)
    with open(os.path.join(HEALTH_DIR, "watchlist.json"), "w", encoding="utf-8") as fh:
        json.dump({"generated_at": _utcnow(), "results": results}, fh, ensure_ascii=False, indent=1)
    alive = [r for r in results if r["alive"]]
    print(f"watchlist: {len(alive)}/{len(results)} alive")
    return results


# --------------------------------------------------------------------------- notify

def notify(rep: dict, probe_results: list[dict] | None) -> int:
    topic = os.environ.get("FIE_NTFY_TOPIC", "").strip()
    lines = []
    for a in rep.get("alerts", []):
        lines.append(f"{a['state'].upper()} {a['market']}/{a['source']} "
                     f"last={a['last_events']} base={a['baseline_median']}")
    for r in probe_results or []:
        if r.get("alive") and r.get("alert_when_alive", True):
            lines.append(f"ALIVE {r['name']} ({r['detail']})")
    if not lines:
        print("notify: nothing to report")
        return 0
    body = "\n".join(lines[:20])
    if len(lines) > 20:
        body += f"\n(+{len(lines) - 20} more)"
    print("notify:\n" + body)
    if not topic:
        print("FIE_NTFY_TOPIC not set - alert printed only")
        return 0
    import requests
    try:
        requests.post(f"https://ntfy.sh/{topic}", data=body.encode("utf-8"), timeout=10,
                      headers={"Title": "0 FOMO source health", "Priority": "default",
                               "Tags": "satellite"})
        print("notify: sent")
    except requests.RequestException as exc:
        print(f"notify failed: {exc}")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--record", action="store_true")
    ap.add_argument("--report", action="store_true")
    ap.add_argument("--probe", action="store_true")
    ap.add_argument("--notify", action="store_true")
    args = ap.parse_args()
    if not any((args.record, args.report, args.probe, args.notify)):
        ap.print_help()
        return 1
    rep: dict = {}
    probe_results: list[dict] | None = None
    if args.record:
        record()
    if args.report or args.notify:
        rep = report()
    if args.probe:
        probe_results = probe()
    if args.notify:
        notify(rep, probe_results)
    return 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""Review queue for community submissions.

    python inbox/review.py list [--market ID] [--all]
    python inbox/review.py show <submission_id>
    python inbox/review.py approve <submission_id> [--event N] [--set field=value ...]
    python inbox/review.py reject  <submission_id> [--reason "..."]
    python inbox/review.py auto [--min-confidence 0.85]     # approve confident, dated, venued events
    python inbox/review.py purge [--days 90]                # delete reviewed submissions older than N days

Approved events are appended to inbox/approved/<market>.json, which the
pipeline's `community` source ingests per market (comprehensive_bahamas_scraper
CommunityEventsScraper). Decisions are recorded in each submission folder as
review.json so nothing is approved twice."""
from __future__ import annotations

import argparse
import json
import os
import shutil
import sys
import time
from datetime import datetime, timezone

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.environ.get("INBOX_DATA_DIR", os.path.join(HERE, "data"))
APPROVED = os.path.join(HERE, "approved")


def submissions(market_filter: str = ""):
    if not os.path.isdir(DATA):
        return
    for market in sorted(os.listdir(DATA)):
        if market_filter and market != market_filter:
            continue
        mdir = os.path.join(DATA, market)
        if not os.path.isdir(mdir):
            continue
        for sid in sorted(os.listdir(mdir)):
            sdir = os.path.join(mdir, sid)
            if os.path.exists(os.path.join(sdir, "submission.json")):
                yield market, sid, sdir


def load(path):
    if not os.path.exists(path):
        return None
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


def find(sid: str):
    for market, s, sdir in submissions():
        if s == sid:
            return market, sdir
    raise SystemExit(f"submission {sid} not found under {DATA}")


def append_approved(market: str, event: dict, sid: str):
    os.makedirs(APPROVED, exist_ok=True)
    path = os.path.join(APPROVED, f"{market}.json")
    rows = load(path) or []
    event = dict(event)
    event["submission"] = sid
    event["approved_at"] = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    event.pop("confidence", None)
    rows.append(event)
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(rows, fh, ensure_ascii=False, indent=1)


def write_review(sdir: str, decision: dict):
    decision["decided_at"] = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    with open(os.path.join(sdir, "review.json"), "w", encoding="utf-8") as fh:
        json.dump(decision, fh, ensure_ascii=False, indent=1)


def cmd_list(args):
    n = 0
    for market, sid, sdir in submissions(args.market):
        rev = load(os.path.join(sdir, "review.json"))
        if rev and not args.all:
            continue
        ext = load(os.path.join(sdir, "extracted.json"))
        sub = load(os.path.join(sdir, "submission.json")) or {}
        state = rev["decision"] if rev else ("extracted" if ext else "pending")
        evs = (ext or {}).get("events") or []
        head = evs[0]["name"] if evs else (sub.get("text") or sub.get("url") or sub.get("image") or "")[:60]
        conf = f" conf={evs[0]['confidence']:.2f}" if evs else ""
        print(f"{market:18} {sid}  {state:9} {len(evs)} ev{conf}  {head}")
        n += 1
    print(f"{n} submission(s)")


def cmd_show(args):
    market, sdir = find(args.id)
    for fn in ("submission.json", "extracted.json", "review.json"):
        d = load(os.path.join(sdir, fn))
        if d is not None:
            if fn == "submission.json":
                d = {k: v for k, v in d.items()}
            print(f"--- {fn}")
            print(json.dumps(d, ensure_ascii=False, indent=1))


def apply_overrides(event: dict, sets: list[str]) -> dict:
    out = dict(event)
    for s in sets or []:
        k, _, v = s.partition("=")
        if k in out or k in ("date", "time_start", "time_end", "venue", "price", "name",
                             "category", "source_url", "description"):
            out[k] = v or None if k in ("date", "time_start", "time_end") else v
    return out


def cmd_approve(args):
    market, sdir = find(args.id)
    if load(os.path.join(sdir, "review.json")):
        raise SystemExit("already reviewed; edit inbox/approved/<market>.json by hand to change it")
    ext = load(os.path.join(sdir, "extracted.json"))
    if not ext or not ext.get("events"):
        raise SystemExit("nothing extracted yet — run inbox/extract.py first")
    events = ext["events"]
    chosen = [events[args.event]] if args.event is not None else events
    approved = []
    for ev in chosen:
        ev = apply_overrides(ev, args.set)
        if not ev.get("date") or not ev.get("name"):
            raise SystemExit(f"event needs at least a name and a date: {ev}")
        append_approved(market, ev, args.id)
        approved.append(ev["name"])
    write_review(sdir, {"decision": "approved", "events": approved})
    print(f"approved {len(approved)} event(s) -> inbox/approved/{market}.json")


def cmd_reject(args):
    _, sdir = find(args.id)
    write_review(sdir, {"decision": "rejected", "reason": args.reason})
    print("rejected")


def cmd_auto(args):
    n = 0
    for market, sid, sdir in submissions(args.market):
        if load(os.path.join(sdir, "review.json")):
            continue
        ext = load(os.path.join(sdir, "extracted.json"))
        if not ext:
            continue
        good = [e for e in ext.get("events") or []
                if e.get("confidence", 0) >= args.min_confidence and e.get("date") and e.get("venue")]
        if not good:
            continue
        for e in good:
            append_approved(market, e, sid)
        write_review(sdir, {"decision": "approved", "auto": True, "events": [e["name"] for e in good]})
        n += len(good)
        print(f"{market}/{sid}: auto-approved {len(good)}")
    print(f"auto-approved {n} event(s)")


def cmd_purge(args):
    """Retention promise in site/privacy.html: forwarded posts live at most
    --days days. Only reviewed folders go (an unreviewed one still needs eyes);
    approved events already sit in inbox/approved/ and survive."""
    cutoff = time.time() - args.days * 86400
    n = 0
    for market, sid, sdir in submissions(args.market):
        if not load(os.path.join(sdir, "review.json")):
            continue
        if os.path.getmtime(os.path.join(sdir, "submission.json")) < cutoff:
            shutil.rmtree(sdir, ignore_errors=True)
            n += 1
    print(f"purged {n} reviewed submission(s) older than {args.days} days")


def main() -> int:
    ap = argparse.ArgumentParser()
    sp = ap.add_subparsers(dest="cmd", required=True)
    p = sp.add_parser("list"); p.add_argument("--market", default=""); p.add_argument("--all", action="store_true"); p.set_defaults(fn=cmd_list)
    p = sp.add_parser("show"); p.add_argument("id"); p.set_defaults(fn=cmd_show)
    p = sp.add_parser("approve"); p.add_argument("id"); p.add_argument("--event", type=int); p.add_argument("--set", action="append"); p.set_defaults(fn=cmd_approve)
    p = sp.add_parser("reject"); p.add_argument("id"); p.add_argument("--reason", default=""); p.set_defaults(fn=cmd_reject)
    p = sp.add_parser("auto"); p.add_argument("--market", default=""); p.add_argument("--min-confidence", type=float, default=0.85); p.set_defaults(fn=cmd_auto)
    p = sp.add_parser("purge"); p.add_argument("--market", default=""); p.add_argument("--days", type=int, default=90); p.set_defaults(fn=cmd_purge)
    for stream in (sys.stdout, sys.stderr):   # cp1252 console/pipe cannot print event names
        stream.reconfigure(encoding="utf-8", errors="replace")
    args = ap.parse_args()
    args.fn(args)
    return 0


if __name__ == "__main__":
    sys.exit(main())

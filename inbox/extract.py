#!/usr/bin/env python3
"""Turn inbox submissions (flyer images, forwarded text, links) into event
records with Gemini (vision + text), zero Claude tokens.

    python inbox/extract.py [--data DIR] [--market ID] [--force]

For every <market>/<id>/submission.json without an extracted.json, calls
Gemini once with the image and/or text and writes extracted.json:

    {"events": [{name, date, time_start, time_end, venue, price, category,
                 source_url, description, confidence}], "model": ..., "notes": ...}

Env: GEMINI_API_KEY (required), GEMINI_MODEL (default gemini-3.5-flash).
Requires only `requests`. Category slugs mirror the app's EventCategory."""
from __future__ import annotations

import argparse
import base64
import json
import os
import sys
import time
from datetime import date

import requests

HERE = os.path.dirname(os.path.abspath(__file__))
MODEL = os.environ.get("GEMINI_MODEL", "gemini-3.5-flash")
ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"

CATEGORIES = [
    "JUNKANOO_CULTURAL", "REGATTA_MARITIME", "FARMERS_CRAFT_MARKET", "FAIR_POPUP",
    "FESTIVAL", "CONCERT_LIVE_MUSIC", "CLUB_PROMOTION", "NIGHTLIFE_PARTY", "BEACH_PARTY",
    "COMEDY", "PAGEANT", "FOOD_DRINK", "SPORTS_FITNESS", "ARTS_THEATRE", "CONFERENCE_EXPO",
    "BUSINESS_NETWORKING", "FAITH_COMMUNITY", "GENERAL",
]

PROMPT = """You extract event listings from promotional material shared by people in {market_name} ({market_tz}).
Today is {today}. Return ONLY a JSON object: {{"events": [...], "notes": "..."}}.
Each event: {{"name": str, "date": "YYYY-MM-DD" or null, "time_start": "HH:MM" 24h or null,
"time_end": "HH:MM" or null, "venue": str, "price": str (e.g. "$20", "$20 - $40", "Free", ""),
"category": one of {categories}, "source_url": str or "", "description": str (<= 300 chars, plain),
"confidence": 0.0-1.0}}.
Rules: dates without a year are the next occurrence on or after today; a date range or
multi-day event becomes one event per day only if explicitly listed, otherwise the first day;
never invent a venue or price; leave unknown fields null/empty and lower the confidence;
ignore anything that is not an event (ads, memes, chat). If nothing is an event, return
{{"events": [], "notes": "why"}}."""

MARKET_NAMES = {}


def market_meta(market_id: str) -> tuple[str, str]:
    if not MARKET_NAMES:
        mdir = os.path.join(os.path.dirname(HERE), "markets")
        if os.path.isdir(mdir):
            for fn in os.listdir(mdir):
                if fn.endswith(".json"):
                    with open(os.path.join(mdir, fn), encoding="utf-8") as fh:
                        d = json.load(fh)
                    MARKET_NAMES[d["id"]] = (d["name"], d["tz"])
    return MARKET_NAMES.get(market_id, (market_id, "UTC"))


def gemini(parts: list[dict], api_key: str) -> dict:
    body = {
        "contents": [{"role": "user", "parts": parts}],
        "generationConfig": {"temperature": 0.1, "responseMimeType": "application/json"},
    }
    url = ENDPOINT.format(model=MODEL)
    last = "no attempt"
    for attempt in range(3):
        try:
            r = requests.post(url, params={"key": api_key}, json=body, timeout=(15, 150))
        except requests.RequestException as exc:      # timeouts / resets are retryable
            last = f"{type(exc).__name__}: {exc}"
            time.sleep(3 * (attempt + 1))
            continue
        if r.status_code == 429 or r.status_code >= 500:
            last = f"{r.status_code} {r.text[:200]}"
            time.sleep(3 * (attempt + 1))
            continue
        r.raise_for_status()
        text = r.json()["candidates"][0]["content"]["parts"][0]["text"]
        return json.loads(text)
    raise RuntimeError(f"Gemini failed after retries: {last}")


def deepseek_text(prompt_text: str, api_key: str) -> dict:
    """Text-only fallback when Gemini is overloaded (503) or times out.
    FIE flash tier: deepseek-v4-flash in JSON mode."""
    r = requests.post(
        "https://api.deepseek.com/chat/completions",
        headers={"Authorization": f"Bearer {api_key}"},
        json={
            "model": os.environ.get("DEEPSEEK_MODEL", "deepseek-v4-flash"),
            "temperature": 0.1,
            "response_format": {"type": "json_object"},
            "messages": [{"role": "user", "content": prompt_text}],
        },
        timeout=(15, 150),
    )
    r.raise_for_status()
    return json.loads(r.json()["choices"][0]["message"]["content"])


def normalise(ev: dict) -> dict:
    cat = str(ev.get("category") or "GENERAL").upper()
    if cat not in CATEGORIES:
        cat = "GENERAL"
    conf = ev.get("confidence")
    try:
        conf = max(0.0, min(1.0, float(conf)))
    except (TypeError, ValueError):
        conf = 0.3
    return {
        "name": str(ev.get("name") or "").strip()[:200],
        "date": ev.get("date") or None,
        "time_start": ev.get("time_start") or None,
        "time_end": ev.get("time_end") or None,
        "venue": str(ev.get("venue") or "").strip()[:200],
        "price": str(ev.get("price") or "").strip()[:60],
        "category": cat,
        "source_url": str(ev.get("source_url") or "").strip()[:500],
        "description": str(ev.get("description") or "").strip()[:600],
        "confidence": round(conf, 2),
    }


def extract_one(sdir: str, api_key: str) -> dict:
    with open(os.path.join(sdir, "submission.json"), encoding="utf-8") as fh:
        sub = json.load(fh)
    name, tz = market_meta(sub["market"])
    parts: list[dict] = [{"text": PROMPT.format(
        market_name=name, market_tz=tz, today=date.today().isoformat(),
        categories=", ".join(CATEGORIES))}]
    context = []
    if sub.get("text"):
        context.append("Shared text:\n" + sub["text"])
    if sub.get("url"):
        context.append("Shared link: " + sub["url"])
    if sub.get("source_hint"):
        context.append("Shared from: " + sub["source_hint"])
    if context:
        parts.append({"text": "\n\n".join(context)})
    if sub.get("image"):
        path = os.path.join(sdir, sub["image"])
        mime = {"jpg": "image/jpeg", "png": "image/png", "webp": "image/webp"}[path.rsplit(".", 1)[-1]]
        with open(path, "rb") as fh:
            parts.append({"inlineData": {"mimeType": mime, "data": base64.b64encode(fh.read()).decode()}})
    model = MODEL
    try:
        out = gemini(parts, api_key)
    except Exception as exc:  # noqa: BLE001
        ds_key = os.environ.get("DEEPSEEK_API_KEY", "").strip()
        if sub.get("image") or not ds_key:
            raise
        # Text-only submissions have a second engine; images wait for Gemini.
        out = deepseek_text("\n\n".join(p["text"] for p in parts if "text" in p), ds_key)
        model = f"deepseek (gemini: {type(exc).__name__})"
    events = [normalise(e) for e in (out.get("events") or []) if isinstance(e, dict)]
    events = [e for e in events if e["name"]]
    return {"submission": sub["id"], "market": sub["market"], "model": model,
            "events": events, "notes": str(out.get("notes") or "")[:300]}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", default=os.path.join(HERE, "data"))
    ap.add_argument("--market", default="")
    ap.add_argument("--force", action="store_true", help="re-extract even if extracted.json exists")
    args = ap.parse_args()
    api_key = os.environ.get("GEMINI_API_KEY", "").strip()
    if not api_key:
        print("GEMINI_API_KEY not set", file=sys.stderr)
        return 2
    done = failed = 0
    for market in sorted(os.listdir(args.data)) if os.path.isdir(args.data) else []:
        if args.market and market != args.market:
            continue
        mdir = os.path.join(args.data, market)
        for sid in sorted(os.listdir(mdir)):
            sdir = os.path.join(mdir, sid)
            if not os.path.exists(os.path.join(sdir, "submission.json")):
                continue
            if os.path.exists(os.path.join(sdir, "extracted.json")) and not args.force:
                continue
            try:
                result = extract_one(sdir, api_key)
                with open(os.path.join(sdir, "extracted.json"), "w", encoding="utf-8") as fh:
                    json.dump(result, fh, ensure_ascii=False, indent=1)
                done += 1
                print(f"{market}/{sid}: {len(result['events'])} event(s)"
                      + (f" ({result['notes']})" if not result['events'] else ""))
            except Exception as exc:  # noqa: BLE001 — one bad submission never stops the batch
                failed += 1
                print(f"{market}/{sid}: FAILED {type(exc).__name__}: {exc}", file=sys.stderr)
    print(f"extracted {done}, failed {failed}")
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())

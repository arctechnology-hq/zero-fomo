#!/usr/bin/env python3
"""Public Telegram channels -> 0 FOMO inbox (G5, 2026-10-01).

Reads the web preview Telegram serves for any PUBLIC channel
(https://t.me/s/<channel>) — no account, no bot, nothing the channel owner has
to add. Posts that announce an event (strict prefilter: an event word plus a
time signal, or a flyer image with an event word) become inbox submissions,
exactly like the bot bridges. Private groups and channels without a preview
are unreachable by design; forwarding covers them.

Env:
  TELEGRAM_PUBLIC_CHANNELS  "channel=market,channel=market"  (optional when the file exists)
  TELEGRAM_PUBLIC_FILE      JSON {channel: market} written by discover_sources.py
                            (default /var/lib/zerofomo-inbox/bridges/telegram_public.json,
                             re-read every poll so new channels need no restart)
  TELEGRAM_PUBLIC_POLL_SECONDS   default 900
  TELEGRAM_PUBLIC_STATE     newest post id per channel (default ./telegram_public.state.json)
  TELEGRAM_PUBLIC_DRY_RUN   "1" prints instead of POSTing
  INBOX_URL / INBOX_TOKEN   as for the other bridges

CLI: `--once` runs a single pass.
"""
from __future__ import annotations

import base64
import html
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request

INBOX_URL = os.environ.get("INBOX_URL", "http://127.0.0.1:8787").rstrip("/")
INBOX_TOKEN = os.environ.get("INBOX_TOKEN", "").strip()
HERE = os.path.dirname(os.path.abspath(__file__))
MAP_FILE = os.environ.get("TELEGRAM_PUBLIC_FILE", "/var/lib/zerofomo-inbox/bridges/telegram_public.json")
STATE = os.environ.get("TELEGRAM_PUBLIC_STATE", os.path.join(HERE, "telegram_public.state.json"))
POLL = int(os.environ.get("TELEGRAM_PUBLIC_POLL_SECONDS", "900"))
DRY_RUN = os.environ.get("TELEGRAM_PUBLIC_DRY_RUN", "") == "1"
UA = "zerofomo-bridge/1.0 (events inbox; https://0fomo.app; contact info@arctechnologyhq.com)"
MIN_TEXT = 25
MAX_BODY = 3000
GAP = 4.0

STRONG_RX = re.compile(
    r"\b(events?|concerts?|festivals?|fest|tickets?|rsvp|doors (open|at)|line-?up|dj|live music|open mic|"
    r"comedy|stand-?up|meet-?up|pop-?up|market|fair|expo|parade|party|brunch|happy hour|karaoke|trivia|"
    r"gala|fundraiser|workshop|screening|premiere|tournament|5k|10k|marathon|regatta|junkanoo|carnival|"
    r"fete|soca|exhibition|opening night|launch|tour|showcase|conference|summit|hackathon|"
    r"free (admission|entry)|performing|performs?|headlin(er|ing)|hosted by|featuring|feat\.?|show|"
    r"competition|giveaway|grand opening|open house|game ?night|movie night|evento|fiesta|concierto|"
    r"soirée|événement|evenement|feest)\b", re.I)
TIME_RX = re.compile(
    r"\b(tonight|tomorrow|this (weekend|week|friday|saturday|sunday)|mon(day)?|tue(s|sday)?|wed(nesday)?|"
    r"thu(rs|rsday)?|fri(day)?|sat(urday)?|sun(day)?|jan(uary)?|feb(ruary)?|mar(ch)?|apr(il)?|may|june?|"
    r"july?|aug(ust)?|sep(t|tember)?|oct(ober)?|nov(ember)?|dec(ember)?|\d{1,2}(:\d{2})?\s?(a\.?m|p\.?m)|"
    r"\d{1,2}/\d{1,2}(/\d{2,4})?|\d{1,2}(st|nd|rd|th)|\d{1,2}h\d{0,2})\b", re.I)
MSG_RX = re.compile(r'<div class="tgme_widget_message_wrap.*?(?=<div class="tgme_widget_message_wrap|<div class="tme_messages_more|</section>)', re.S)
POST_RX = re.compile(r'data-post="([^"/]+)/(\d+)"')
TEXT_RX = re.compile(r'<div class="tgme_widget_message_text[^"]*"[^>]*>(.*?)</div>', re.S)
PHOTO_RX = re.compile(r'tgme_widget_message_photo_wrap[^>]*style="[^"]*background-image:url\(\'([^\']+)\'\)')
TIME_ATTR_RX = re.compile(r'<time datetime="([^"]+)"')
TAG_RX = re.compile(r"<[^>]+>")
URL_RX = re.compile(r"https?://[^\s<\"']+")


def channel_markets() -> dict[str, str]:
    out: dict[str, str] = {}
    try:
        with open(MAP_FILE, encoding="utf-8") as fh:
            out.update({str(k).lstrip("@"): str(v).lower() for k, v in json.load(fh).items()})
    except (OSError, ValueError):
        pass
    for pair in os.environ.get("TELEGRAM_PUBLIC_CHANNELS", "").split(","):
        if "=" in pair:
            k, v = pair.split("=", 1)
            out[k.strip().lstrip("@")] = v.strip().lower()
    return out


def load_state() -> dict:
    try:
        with open(STATE, encoding="utf-8") as fh:
            return json.load(fh)
    except (OSError, ValueError):
        return {}


def save_state(st: dict) -> None:
    with open(STATE, "w", encoding="utf-8") as fh:
        json.dump(st, fh)


def _http(url: str, data: bytes | None = None, headers: dict | None = None, timeout: int = 40) -> bytes:
    req = urllib.request.Request(url, data=data, headers={"User-Agent": UA, **(headers or {})})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read()


def parse_channel(page: str, channel: str) -> list[dict]:
    """Newest-last list of {id, text, photo, url, when} from the preview HTML."""
    posts = []
    for block in MSG_RX.findall(page):
        m = POST_RX.search(block)
        if not m:
            continue
        pid = int(m.group(2))
        tm = TEXT_RX.search(block)
        text = ""
        if tm:
            raw = re.sub(r"<br\s*/?>", "\n", tm.group(1))
            text = html.unescape(TAG_RX.sub("", raw)).strip()
        pm = PHOTO_RX.search(block)
        when = TIME_ATTR_RX.findall(block)
        posts.append({"id": pid, "text": re.sub(r"[ \t]+", " ", text), "photo": pm.group(1) if pm else "",
                      "url": f"https://t.me/{channel}/{pid}", "when": when[-1] if when else ""})
    posts.sort(key=lambda p: p["id"])
    return posts


def looks_like_event(text: str, has_photo: bool) -> bool:
    strong = {m.group(0).lower() for m in STRONG_RX.finditer(text)}
    if not strong:
        return False
    if has_photo or len(strong) >= 2:
        return True
    return bool(TIME_RX.search(text))


def submit(market: str, kind: str, device: str, text: str, url: str, image: bytes | None, hint: str) -> dict:
    body = {"market": market, "kind": kind, "device": device, "text": text, "url": url,
            "source_hint": hint, "app_version": "telegram-public-bridge/1"}
    if image is not None:
        body["image_base64"] = base64.b64encode(image).decode()
        body["image_type"] = "image/jpeg"
    if DRY_RUN:
        print(f"[dry-run] {market} {kind} {hint}: {text[:90]!r} url={url} image={'yes' if image else 'no'}")
        return {"id": "dry-run"}
    req = urllib.request.Request(f"{INBOX_URL}/submit", data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json",
                                          **({"X-Inbox-Token": INBOX_TOKEN} if INBOX_TOKEN else {})})
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.load(r)


def post_to_submission(post: dict, channel: str, market: str) -> dict | None:
    text = post["text"][:MAX_BODY]
    has_photo = bool(post["photo"])
    if not looks_like_event(text, has_photo):
        return None
    device = f"tgp:{channel.lower()}"
    hint = f"Telegram @{channel}"
    m = URL_RX.search(text)
    link = m.group(0) if m else ""
    if has_photo:
        return {"market": market, "kind": "image", "device": device, "text": text,
                "url": link or post["url"], "hint": hint, "image_url": post["photo"]}
    if len(text) >= MIN_TEXT:
        return {"market": market, "kind": "url" if link and len(text) < 60 else "text",
                "device": device, "text": f"{text}\n\n{post['url']}"[:MAX_BODY],
                "url": link or post["url"], "hint": hint}
    return None


def poll_once(markets: dict[str, str], state: dict) -> int:
    n = 0
    for i, (channel, market) in enumerate(markets.items()):
        if i:
            time.sleep(GAP)
        try:
            page = _http(f"https://t.me/s/{channel}").decode("utf-8", "replace")
            posts = parse_channel(page, channel)
            if not posts:
                print(f"@{channel}: no preview (private or renamed?)", file=sys.stderr)
                continue
            last = state.get(channel)
            if last is None:
                state[channel] = posts[-1]["id"]
                save_state(state)
                print(f"@{channel}: watermark set at {posts[-1]['id']} ({len(posts)} existing posts skipped)")
                continue
            for post in posts:
                if post["id"] <= int(last):
                    continue
                try:
                    s = post_to_submission(post, channel, market)
                    if s:
                        image = _http(s.pop("image_url")) if "image_url" in s else None
                        res = submit(s["market"], s["kind"], s["device"], s["text"], s["url"], image, s["hint"])
                        print(f"{market} <- {s['kind']} from {s['hint']}: {res.get('id')}")
                        n += 1
                except Exception as exc:  # noqa: BLE001 — one bad post never stops the poll
                    print(f"@{channel}/{post['id']}: {type(exc).__name__}: {exc}", file=sys.stderr)
                state[channel] = max(int(state.get(channel) or 0), post["id"])
            save_state(state)
        except urllib.error.HTTPError as exc:
            print(f"@{channel}: HTTP {exc.code} {exc.reason}", file=sys.stderr)
        except Exception as exc:  # noqa: BLE001
            print(f"@{channel}: {type(exc).__name__}: {exc}", file=sys.stderr)
    return n


def main(argv: list[str]) -> int:
    markets = channel_markets()
    if not markets:
        print("no channels: set TELEGRAM_PUBLIC_CHANNELS or provide TELEGRAM_PUBLIC_FILE", file=sys.stderr)
        return 2
    print(f"bridge up ({'dry-run, ' if DRY_RUN else ''}public previews) watching {len(markets)} channel(s) -> {INBOX_URL}")
    state = load_state()
    if "--once" in argv:
        poll_once(markets, state)
        return 0
    while True:
        poll_once(markets, state)
        time.sleep(POLL)
        markets = channel_markets() or markets   # pick up channels discovery added


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

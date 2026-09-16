#!/usr/bin/env python3
"""Discord -> 0 FOMO inbox bridge (docs/GLOBAL_DESIGN.md §3, source "Bot").

A server admin invites the bot and lists the channels to watch; every new
message with enough text, or with an image attachment, becomes an inbox
submission for the channel's market. Plain REST polling (no Gateway
websocket), so it is standard library only and needs no inbound port.

Discord developer portal prerequisites (one-time, by the account owner):
  1. Applications -> New Application -> Bot: copy the token.
  2. Bot -> Privileged Gateway Intents -> enable MESSAGE CONTENT INTENT
     (without it the API returns empty `content` for messages).
  3. OAuth2 -> URL Generator: scope `bot`, permissions View Channels +
     Read Message History; open the URL to invite the bot to the server.

Env:
  DISCORD_BOT_TOKEN        required
  DISCORD_CHANNEL_MARKETS  "channel_id=market,channel_id=market" (required: only
                           listed channels are read)
  INBOX_URL                default http://127.0.0.1:8787
  INBOX_TOKEN              optional, sent as X-Inbox-Token
  DISCORD_STATE            last-seen message id per channel (default ./discord.state.json)
  DISCORD_POLL_SECONDS     default 60
"""
from __future__ import annotations

import base64
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

TOKEN = os.environ.get("DISCORD_BOT_TOKEN", "").strip()
INBOX_URL = os.environ.get("INBOX_URL", "http://127.0.0.1:8787").rstrip("/")
INBOX_TOKEN = os.environ.get("INBOX_TOKEN", "").strip()
STATE = os.environ.get("DISCORD_STATE", os.path.join(os.path.dirname(os.path.abspath(__file__)), "discord.state.json"))
POLL = int(os.environ.get("DISCORD_POLL_SECONDS", "60"))
API = "https://discord.com/api/v10"
UA = "DiscordBot (https://0fomo.app, 1.0)"
MIN_TEXT = 25
URL_RX = re.compile(r"https?://\S+")
IMAGE_TYPES = {"image/jpeg": "image/jpeg", "image/png": "image/png", "image/webp": "image/webp"}


def channel_markets() -> dict[str, str]:
    out = {}
    for pair in os.environ.get("DISCORD_CHANNEL_MARKETS", "").split(","):
        if "=" in pair:
            k, v = pair.split("=", 1)
            out[k.strip()] = v.strip().lower()
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


def discord_get(path: str, params: dict | None = None):
    url = f"{API}{path}" + (("?" + urllib.parse.urlencode(params)) if params else "")
    req = urllib.request.Request(url, headers={"Authorization": f"Bot {TOKEN}", "User-Agent": UA})
    for attempt in range(4):
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                return json.load(r)
        except urllib.error.HTTPError as e:
            if e.code == 429:
                try:
                    wait = float(json.load(e).get("retry_after", 5))
                except Exception:  # noqa: BLE001
                    wait = 5.0
                time.sleep(min(wait, 60) + 0.5)
                continue
            raise
    raise RuntimeError(f"rate limited too long on {path}")


def fetch(url: str) -> bytes:
    with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": UA}), timeout=60) as r:
        return r.read()


def submit(market: str, kind: str, device: str, text: str = "", url: str = "",
           image: tuple[bytes, str] | None = None, hint: str = "Discord") -> dict:
    body = {"market": market, "kind": kind, "device": device, "text": text, "url": url,
            "source_hint": hint, "app_version": "discord-bridge/1"}
    if image is not None:
        body["image_base64"] = base64.b64encode(image[0]).decode()
        body["image_type"] = image[1]
    req = urllib.request.Request(f"{INBOX_URL}/submit", data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json",
                                          **({"X-Inbox-Token": INBOX_TOKEN} if INBOX_TOKEN else {})})
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.load(r)


def message_to_submission(msg: dict, market: str, channel_name: str) -> dict | None:
    """Pure mapping (testable). Returns the submit pieces or None to skip."""
    if msg.get("author", {}).get("bot"):
        return None
    text = (msg.get("content") or "").strip()
    url = ""
    m = URL_RX.search(text)
    if m:
        url = m.group(0)
    image = next((a for a in msg.get("attachments") or []
                  if str(a.get("content_type", "")).split(";")[0] in IMAGE_TYPES), None)
    device = f"dc:{msg.get('channel_id')}"
    hint = f"Discord #{channel_name}".strip()
    if image:
        return {"market": market, "kind": "image", "device": device, "text": text, "url": url,
                "hint": hint, "image_url": image["url"],
                "image_type": IMAGE_TYPES[str(image.get("content_type", "")).split(";")[0]]}
    if len(text) >= MIN_TEXT:
        return {"market": market, "kind": "url" if url and len(text) < 60 else "text",
                "device": device, "text": text, "url": url, "hint": hint}
    return None


def main() -> int:
    if not TOKEN:
        print("DISCORD_BOT_TOKEN not set", file=sys.stderr)
        return 2
    markets = channel_markets()
    if not markets:
        print("DISCORD_CHANNEL_MARKETS not set; nothing to watch", file=sys.stderr)
        return 2
    me = discord_get("/users/@me")
    print(f"bridge up as {me.get('username')} watching {len(markets)} channel(s) -> {INBOX_URL}")
    names: dict[str, str] = {}
    state = load_state()
    while True:
        for channel_id, market in markets.items():
            try:
                if channel_id not in names:
                    names[channel_id] = discord_get(f"/channels/{channel_id}").get("name", channel_id)
                params = {"limit": 100}
                last = state.get(channel_id)
                if last:
                    params["after"] = last
                msgs = discord_get(f"/channels/{channel_id}/messages", params)
                if not last:
                    # First run: start from now rather than replaying history.
                    if msgs:
                        state[channel_id] = max(m["id"] for m in msgs)
                    save_state(state)
                    continue
                for msg in sorted(msgs, key=lambda m: int(m["id"])):
                    try:
                        sub = message_to_submission(msg, market, names[channel_id])
                        if sub:
                            image = (fetch(sub.pop("image_url")), sub.pop("image_type")) if "image_url" in sub else None
                            res = submit(sub["market"], sub["kind"], sub["device"], sub["text"], sub["url"], image, sub["hint"])
                            print(f"{market} <- {sub['kind']} from {sub['hint']}: {res.get('id')}")
                    except Exception as exc:  # noqa: BLE001 — one bad message never stops the poll
                        print(f"message {msg.get('id')}: {type(exc).__name__}: {exc}", file=sys.stderr)
                    state[channel_id] = msg["id"]
                save_state(state)
            except Exception as exc:  # noqa: BLE001
                print(f"channel {channel_id}: {type(exc).__name__}: {exc}", file=sys.stderr)
        time.sleep(POLL)
    return 0


if __name__ == "__main__":
    sys.exit(main())

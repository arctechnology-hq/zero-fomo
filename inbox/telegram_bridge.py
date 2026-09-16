#!/usr/bin/env python3
"""Telegram -> 0 FOMO inbox bridge (docs/GLOBAL_DESIGN.md §3, source "Bot").

A community adds @ZeroFomoBot to its group (privacy mode off, via BotFather's
/setprivacy) or the bot is pointed at public channels; every text post and
photo it sees becomes an inbox submission for the market the chat is mapped
to. Nothing is read from chats the bot was not invited to. Standard library
only, long-polling, no webhook, no inbound port.

Env:
  TELEGRAM_BOT_TOKEN     required (BotFather)
  INBOX_URL              default http://127.0.0.1:8787  (same host as server.py)
  INBOX_TOKEN            optional, sent as X-Inbox-Token
  TELEGRAM_CHAT_MARKETS  "chat_id=market,chat_id=market,..." — chats not listed
                         fall back to TELEGRAM_DEFAULT_MARKET (default bs-nassau)
  TELEGRAM_STATE         path for the last update offset (default ./telegram.offset)

Commands understood in a group:  /market <id>   (admins only) sets the chat's
market and is persisted next to the offset file.
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

TOKEN = os.environ.get("TELEGRAM_BOT_TOKEN", "").strip()
INBOX_URL = os.environ.get("INBOX_URL", "http://127.0.0.1:8787").rstrip("/")
INBOX_TOKEN = os.environ.get("INBOX_TOKEN", "").strip()
DEFAULT_MARKET = os.environ.get("TELEGRAM_DEFAULT_MARKET", "bs-nassau")
STATE = os.environ.get("TELEGRAM_STATE", os.path.join(os.path.dirname(os.path.abspath(__file__)), "telegram.offset"))
MAP_FILE = STATE + ".markets.json"
API = f"https://api.telegram.org/bot{TOKEN}"
FILE_API = f"https://api.telegram.org/file/bot{TOKEN}"
MIN_TEXT = 25   # shorter posts are chatter, not flyers
URL_RX = re.compile(r"https?://\S+")


def tg(method: str, **params):
    data = urllib.parse.urlencode(params).encode() if params else None
    req = urllib.request.Request(f"{API}/{method}", data=data)
    with urllib.request.urlopen(req, timeout=70) as r:
        out = json.load(r)
    if not out.get("ok"):
        raise RuntimeError(f"{method}: {out}")
    return out["result"]


def load_json(path, default):
    try:
        with open(path, encoding="utf-8") as fh:
            return json.load(fh)
    except (OSError, ValueError):
        return default


def save_json(path, value):
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(value, fh)


def chat_markets() -> dict[str, str]:
    m = {}
    for pair in os.environ.get("TELEGRAM_CHAT_MARKETS", "").split(","):
        if "=" in pair:
            k, v = pair.split("=", 1)
            m[k.strip()] = v.strip()
    m.update(load_json(MAP_FILE, {}))
    return m


def submit(market: str, kind: str, device: str, text: str = "", url: str = "",
           image_bytes: bytes | None = None, hint: str = "Telegram") -> dict:
    body = {"market": market, "kind": kind, "device": device, "text": text, "url": url,
            "source_hint": hint, "app_version": "telegram-bridge/1"}
    if image_bytes is not None:
        body["image_base64"] = base64.b64encode(image_bytes).decode()
        body["image_type"] = "image/jpeg"
    req = urllib.request.Request(f"{INBOX_URL}/submit", data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json",
                                          **({"X-Inbox-Token": INBOX_TOKEN} if INBOX_TOKEN else {})})
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.load(r)


def message_to_submission(msg: dict, markets: dict[str, str]) -> dict | None:
    """Pure mapping so it is testable: returns the /submit payload pieces or
    None when the message is not worth forwarding."""
    chat = msg.get("chat") or {}
    chat_id = str(chat.get("id"))
    market = markets.get(chat_id, DEFAULT_MARKET)
    text = (msg.get("text") or msg.get("caption") or "").strip()
    device = f"tg:{chat_id}"
    title = chat.get("title") or chat.get("username") or ""
    hint = f"Telegram {title}".strip()
    entities = msg.get("entities") or msg.get("caption_entities") or []
    url = ""
    for e in entities:
        if e.get("type") == "text_link":
            url = e.get("url", "")
            break
    if not url:
        # Entity offsets are UTF-16 code units; a plain regex is safer than slicing.
        m = URL_RX.search(text)
        url = m.group(0) if m else ""
    if msg.get("photo"):
        return {"market": market, "kind": "image", "device": device, "text": text, "url": url,
                "hint": hint, "file_id": msg["photo"][-1]["file_id"]}
    if len(text) >= MIN_TEXT:
        return {"market": market, "kind": "url" if url and len(text) < 60 else "text",
                "device": device, "text": text, "url": url, "hint": hint}
    return None


def download(file_id: str) -> bytes:
    info = tg("getFile", file_id=file_id)
    with urllib.request.urlopen(f"{FILE_API}/{info['file_path']}", timeout=60) as r:
        return r.read()


def handle_command(msg: dict, markets: dict[str, str]) -> bool:
    text = (msg.get("text") or "").strip()
    if not text.startswith("/market"):
        return False
    chat_id = str(msg["chat"]["id"])
    parts = text.split()
    if len(parts) == 2:
        try:
            member = tg("getChatMember", chat_id=chat_id, user_id=msg["from"]["id"])
            if member.get("status") in ("creator", "administrator") or msg["chat"].get("type") == "private":
                saved = load_json(MAP_FILE, {})
                saved[chat_id] = parts[1].lower()
                save_json(MAP_FILE, saved)
                markets[chat_id] = parts[1].lower()
                tg("sendMessage", chat_id=chat_id, text=f"0 FOMO: this chat now posts to market {parts[1].lower()}")
            else:
                tg("sendMessage", chat_id=chat_id, text="0 FOMO: only group admins can set the market")
        except Exception as exc:  # noqa: BLE001
            print(f"/market failed: {exc}", file=sys.stderr)
    else:
        tg("sendMessage", chat_id=chat_id, text=f"0 FOMO: this chat posts to market {markets.get(chat_id, DEFAULT_MARKET)}. Use /market <id> to change.")
    return True


def main() -> int:
    if not TOKEN:
        print("TELEGRAM_BOT_TOKEN not set", file=sys.stderr)
        return 2
    me = tg("getMe")
    print(f"bridge up as @{me.get('username')} -> {INBOX_URL}")
    markets = chat_markets()
    offset = int(load_json(STATE, {"offset": 0}).get("offset", 0))
    while True:
        try:
            updates = tg("getUpdates", offset=offset, timeout=50, allowed_updates=json.dumps(["message", "channel_post"]))
        except (urllib.error.URLError, TimeoutError, RuntimeError) as exc:
            print(f"getUpdates: {exc}", file=sys.stderr)
            time.sleep(5)
            continue
        for u in updates:
            offset = u["update_id"] + 1
            msg = u.get("message") or u.get("channel_post")
            if not msg:
                continue
            try:
                if handle_command(msg, markets):
                    continue
                sub = message_to_submission(msg, markets)
                if not sub:
                    continue
                image = download(sub.pop("file_id")) if "file_id" in sub else None
                res = submit(sub["market"], sub["kind"], sub["device"], sub["text"], sub["url"], image, sub["hint"])
                print(f"{sub['market']} <- {sub['kind']} from {sub['hint']}: {res.get('id')}")
            except Exception as exc:  # noqa: BLE001 — one bad message never stops the poll loop
                print(f"update {u['update_id']}: {type(exc).__name__}: {exc}", file=sys.stderr)
        save_json(STATE, {"offset": offset})
    return 0


if __name__ == "__main__":
    sys.exit(main())

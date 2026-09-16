#!/usr/bin/env python3
"""Instagram hashtag -> 0 FOMO inbox bridge (docs/GLOBAL_DESIGN.md §3, the one
compliant Instagram door: the Graph API Hashtag Search, which returns PUBLIC
posts for a hashtag to a Business/Creator account).

Meta prerequisites (one-time, by the account owner; review can take days):
  1. An Instagram Business or Creator account linked to a Facebook Page.
  2. A Meta app (developers.facebook.com) with Instagram Graph API; permissions
     `instagram_basic` and the hashtag-search capability approved in App Review.
  3. A long-lived user access token for that account and the IG user id.
Limits: 30 unique hashtags per 7 days per account; only public media.

Env:
  IG_ACCESS_TOKEN     required (long-lived)
  IG_USER_ID          required (Instagram Business account id)
  IG_HASHTAGS         "nassauevents=bs-nassau,kingstonparty=jm-kingston" (required)
  IG_GRAPH_VERSION    default v21.0
  INBOX_URL / INBOX_TOKEN   as for the other bridges
  IG_STATE            last-seen media id per hashtag (default ./instagram.state.json)
  IG_POLL_SECONDS     default 1800 (30 min; rate limits are per hour)
  IG_APP_ID / IG_APP_SECRET   optional; when set the bridge refreshes the
                      long-lived user token itself every ~45 days
                      (fb_exchange_token) and keeps the newest one in IG_STATE,
                      so the 60-day expiry never silently kills the poller.

Meta app "0 FOMO": app id 1601642318227085, created 2026-09-16 (Facebook-login
path of the Instagram API use case; hashtag search needs instagram_basic +
pages_show_list/pages_read_engagement). IG professional account
@arctechnologyhq (renamed from the typo arctechonologyhq 2026-09-16) (IG user id 17841439606653137) is linked to the Facebook
Page "A.R.C Technology" (652922774570717). ig_hashtag_search additionally
needs the "Instagram Public Content Access" feature with Advanced Access —
App Review, which Meta gates behind Tech Provider / business verification —
even for the app owner's own account (verified 2026-09-16: error #10).
"""
from __future__ import annotations

import base64
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

ENV_TOKEN = os.environ.get("IG_ACCESS_TOKEN", "").strip()
APP_ID = os.environ.get("IG_APP_ID", "").strip()
APP_SECRET = os.environ.get("IG_APP_SECRET", "").strip()
REFRESH_AFTER_DAYS = 45
USER_ID = os.environ.get("IG_USER_ID", "").strip()
VERSION = os.environ.get("IG_GRAPH_VERSION", "v21.0")
INBOX_URL = os.environ.get("INBOX_URL", "http://127.0.0.1:8787").rstrip("/")
INBOX_TOKEN = os.environ.get("INBOX_TOKEN", "").strip()
STATE = os.environ.get("IG_STATE", os.path.join(os.path.dirname(os.path.abspath(__file__)), "instagram.state.json"))
POLL = int(os.environ.get("IG_POLL_SECONDS", "1800"))
GRAPH = f"https://graph.facebook.com/{VERSION}"
MIN_TEXT = 25


def hashtag_markets() -> dict[str, str]:
    out = {}
    for pair in os.environ.get("IG_HASHTAGS", "").split(","):
        if "=" in pair:
            k, v = pair.split("=", 1)
            out[k.strip().lstrip("#").lower()] = v.strip().lower()
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


_tok: dict = {"token": "", "issued": 0.0}


def current_token(state: dict) -> str:
    """Newest token wins: one the bridge refreshed (kept in state) over the
    env one, unless the env token changed since (operator rotated it)."""
    saved = state.get("token") or {}
    if saved.get("value") and saved.get("env_seed") == ENV_TOKEN:
        return saved["value"]
    return ENV_TOKEN


def maybe_refresh_token(state: dict) -> None:
    """Exchange the long-lived token for a fresh 60-day one when it is older
    than REFRESH_AFTER_DAYS. Needs IG_APP_ID + IG_APP_SECRET; silently skips
    otherwise (the operator then rotates by hand before day 60)."""
    if not (APP_ID and APP_SECRET):
        return
    saved = state.get("token") or {}
    issued = float(saved.get("issued") or 0)
    if saved.get("env_seed") == ENV_TOKEN and time.time() - issued < REFRESH_AFTER_DAYS * 86400:
        return
    params = {"grant_type": "fb_exchange_token", "client_id": APP_ID, "client_secret": APP_SECRET,
              "fb_exchange_token": current_token(state)}
    try:
        with urllib.request.urlopen(f"{GRAPH}/oauth/access_token?" + urllib.parse.urlencode(params), timeout=60) as r:
            d = json.load(r)
        state["token"] = {"value": d["access_token"], "issued": time.time(), "env_seed": ENV_TOKEN,
                          "expires_in": d.get("expires_in")}
        save_state(state)
        print(f"token refreshed (expires_in={d.get('expires_in')})")
    except Exception as exc:  # noqa: BLE001
        print(f"token refresh failed: {type(exc).__name__}: {exc}", file=sys.stderr)


def graph_get(path: str, **params):
    params["access_token"] = _tok["token"]
    url = f"{GRAPH}/{path}?" + urllib.parse.urlencode(params)
    with urllib.request.urlopen(url, timeout=60) as r:
        return json.load(r)


def fetch(url: str) -> bytes:
    with urllib.request.urlopen(url, timeout=60) as r:
        return r.read()


def submit(market: str, kind: str, device: str, text: str = "", url: str = "",
           image: bytes | None = None, hint: str = "Instagram") -> dict:
    body = {"market": market, "kind": kind, "device": device, "text": text, "url": url,
            "source_hint": hint, "app_version": "instagram-bridge/1"}
    if image is not None:
        body["image_base64"] = base64.b64encode(image).decode()
        body["image_type"] = "image/jpeg"
    req = urllib.request.Request(f"{INBOX_URL}/submit", data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json",
                                          **({"X-Inbox-Token": INBOX_TOKEN} if INBOX_TOKEN else {})})
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.load(r)


def media_to_submission(media: dict, market: str, tag: str) -> dict | None:
    """Pure mapping (testable). Images go as flyers; videos/albums go as
    caption + permalink so the reviewer can still open them."""
    caption = (media.get("caption") or "").strip()
    permalink = media.get("permalink") or ""
    device = f"ig:{tag}"
    hint = f"Instagram #{tag}"
    if media.get("media_type") == "IMAGE" and media.get("media_url"):
        return {"market": market, "kind": "image", "device": device, "text": caption,
                "url": permalink, "hint": hint, "image_url": media["media_url"]}
    if len(caption) >= MIN_TEXT:
        return {"market": market, "kind": "text", "device": device, "text": caption,
                "url": permalink, "hint": hint}
    return None


def main() -> int:
    if not (ENV_TOKEN and USER_ID):
        print("IG_ACCESS_TOKEN / IG_USER_ID not set", file=sys.stderr)
        return 2
    tags = hashtag_markets()
    if not tags:
        print("IG_HASHTAGS not set; nothing to watch", file=sys.stderr)
        return 2
    state = load_state()
    if not (state.get("token") or {}).get("env_seed") == ENV_TOKEN:
        # New env token: start its age clock now so the refresh cadence is right.
        state["token"] = {"value": "", "issued": time.time(), "env_seed": ENV_TOKEN}
        save_state(state)
    maybe_refresh_token(state)
    _tok["token"] = current_token(state)
    try:
        me = graph_get("me", fields="id,name")
        print(f"bridge up for {len(tags)} hashtag(s) as {me.get('name')} / IG user {USER_ID} -> {INBOX_URL}")
    except urllib.error.HTTPError as e:
        print(f"token check failed: HTTP {e.code} {e.read()[:200]!r} — regenerate IG_ACCESS_TOKEN", file=sys.stderr)
        return 3
    ids: dict[str, str] = {}
    last_refresh_check = time.time()
    while True:
        if time.time() - last_refresh_check > 86400:
            maybe_refresh_token(state)
            _tok["token"] = current_token(state)
            last_refresh_check = time.time()
        for tag, market in tags.items():
            try:
                if tag not in ids:
                    found = graph_get("ig_hashtag_search", user_id=USER_ID, q=tag).get("data") or []
                    if not found:
                        print(f"#{tag}: no hashtag id", file=sys.stderr)
                        continue
                    ids[tag] = found[0]["id"]
                media = graph_get(f"{ids[tag]}/recent_media", user_id=USER_ID,
                                  fields="id,caption,permalink,media_type,media_url,timestamp",
                                  limit=50).get("data") or []
                seen = set(state.get(tag) or [])
                if not seen:
                    # First run: remember what exists, forward only what comes after.
                    state[tag] = [m["id"] for m in media][:200]
                    save_state(state)
                    continue
                fresh = [m for m in media if m["id"] not in seen]
                for m in reversed(fresh):
                    try:
                        sub = media_to_submission(m, market, tag)
                        if sub:
                            image = fetch(sub.pop("image_url")) if "image_url" in sub else None
                            res = submit(sub["market"], sub["kind"], sub["device"], sub["text"], sub["url"], image, sub["hint"])
                            print(f"{market} <- {sub['kind']} from {sub['hint']}: {res.get('id')}")
                    except Exception as exc:  # noqa: BLE001
                        print(f"media {m.get('id')}: {type(exc).__name__}: {exc}", file=sys.stderr)
                state[tag] = ([m["id"] for m in media] + list(seen))[:400]
                save_state(state)
            except urllib.error.HTTPError as e:
                print(f"#{tag}: HTTP {e.code} {e.read()[:200]!r}", file=sys.stderr)
            except Exception as exc:  # noqa: BLE001
                print(f"#{tag}: {type(exc).__name__}: {exc}", file=sys.stderr)
        time.sleep(POLL)
    return 0


if __name__ == "__main__":
    sys.exit(main())

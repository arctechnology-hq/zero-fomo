#!/usr/bin/env python3
"""Reddit -> 0 FOMO inbox bridge (docs/GLOBAL_DESIGN.md §3, source "Bot").

Watches the /new listing of one subreddit per market and forwards posts that
look like event announcements (keyword or flair prefilter) as inbox
submissions, exactly like the Discord and Telegram bridges. Standard library
only; plain polling; no inbound port.

Reddit access: an OAuth "script" app (client_credentials grant) is REQUIRED.
Unauthenticated `www.reddit.com/r/<sub>/new.json` answered HTTP 403 from both
RR-002 (residential) and fie-worker-1 (OCI) on 2026-09-16, so that path is
kept only as a fallback when no creds are set (it logs the 403 and keeps
polling; it never crashes). Create the app at https://www.reddit.com/prefs/apps
(type "script", any redirect URI) under any Reddit account; the bridge uses
`oauth.reddit.com` (100 req/10 min) and never logs in as a user.

Env:
  REDDIT_SUBREDDIT_MARKETS  "bahamas=bs-nassau,Miami=us-miami" (required: only
                            listed subreddits are read)
  REDDIT_CLIENT_ID / REDDIT_CLIENT_SECRET   required in practice (see above)
  REDDIT_KEYWORDS           comma list; a post must contain one (title+body,
                            case-insensitive) or carry an event-ish flair.
                            Empty string = forward everything.
  REDDIT_POLL_SECONDS       default 600
  REDDIT_STATE              newest created_utc per subreddit (default ./reddit.state.json)
  REDDIT_DRY_RUN            "1" prints submissions instead of POSTing them
  INBOX_URL / INBOX_TOKEN   as for the other bridges

CLI: `--once` runs a single pass (with REDDIT_DRY_RUN=1 this is the smoke test).
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
import urllib.parse
import urllib.request

INBOX_URL = os.environ.get("INBOX_URL", "http://127.0.0.1:8787").rstrip("/")
INBOX_TOKEN = os.environ.get("INBOX_TOKEN", "").strip()
CLIENT_ID = os.environ.get("REDDIT_CLIENT_ID", "").strip()
CLIENT_SECRET = os.environ.get("REDDIT_CLIENT_SECRET", "").strip()
STATE = os.environ.get("REDDIT_STATE", os.path.join(os.path.dirname(os.path.abspath(__file__)), "reddit.state.json"))
POLL = int(os.environ.get("REDDIT_POLL_SECONDS", "600"))
DRY_RUN = os.environ.get("REDDIT_DRY_RUN", "") == "1"
UA = "zerofomo-bridge/1.0 (events inbox; https://0fomo.app; contact info@arctechnologyhq.com)"
DEFAULT_KEYWORDS = ("event,party,concert,festival,fest,tickets,show,tonight,this weekend,live music,flyer,night,live,"
                    "fete,regatta,junkanoo,carnival,brunch,pop-up,popup,market,fair,expo,conference,"
                    "meetup,meet-up,open mic,comedy,dj,gig,performance,exhibition,screening,race,5k,"
                    "tournament,happening,lineup,line-up,doors open,rsvp")
KEYWORDS = [k.strip().lower() for k in os.environ.get("REDDIT_KEYWORDS", DEFAULT_KEYWORDS).split(",") if k.strip()]
FLAIR_RX = re.compile(r"event|happening|things to do|what's on|whats on|announcement", re.I)
MIN_TEXT = 25
MAX_BODY = 3000
IMAGE_EXT = {".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".png": "image/png", ".webp": "image/webp"}

_oauth: dict = {"token": "", "expires": 0.0}


def subreddit_markets() -> dict[str, str]:
    out = {}
    for pair in os.environ.get("REDDIT_SUBREDDIT_MARKETS", "").split(","):
        if "=" in pair:
            k, v = pair.split("=", 1)
            out[k.strip().lstrip("/").removeprefix("r/")] = v.strip().lower()
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


def _oauth_token() -> str:
    if not (CLIENT_ID and CLIENT_SECRET):
        return ""
    if _oauth["token"] and time.time() < _oauth["expires"] - 60:
        return _oauth["token"]
    cred = base64.b64encode(f"{CLIENT_ID}:{CLIENT_SECRET}".encode()).decode()
    req = urllib.request.Request(
        "https://www.reddit.com/api/v1/access_token",
        data=b"grant_type=client_credentials",
        headers={"Authorization": f"Basic {cred}", "User-Agent": UA,
                 "Content-Type": "application/x-www-form-urlencoded"})
    with urllib.request.urlopen(req, timeout=30) as r:
        d = json.load(r)
    _oauth["token"] = d["access_token"]
    _oauth["expires"] = time.time() + float(d.get("expires_in", 3600))
    return _oauth["token"]


def reddit_get(path: str, params: dict | None = None):
    """GET a listing; OAuth host when creds exist, else the public JSON host.
    Honors 429 Retry-After and refreshes an expired OAuth token once."""
    params = dict(params or {})
    params.setdefault("raw_json", "1")
    for attempt in range(3):
        token = _oauth_token()
        base = "https://oauth.reddit.com" if token else "https://www.reddit.com"
        suffix = "" if token else ".json"
        url = f"{base}{path}{suffix}?{urllib.parse.urlencode(params)}"
        headers = {"User-Agent": UA}
        if token:
            headers["Authorization"] = f"Bearer {token}"
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=30) as r:
                return json.load(r)
        except urllib.error.HTTPError as e:
            if e.code == 429:
                time.sleep(min(float(e.headers.get("Retry-After") or 10), 120))
                continue
            if e.code == 401 and token:
                _oauth["token"] = ""
                continue
            raise
    raise RuntimeError(f"gave up on {path}")


def fetch(url: str) -> bytes:
    with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": UA}), timeout=60) as r:
        return r.read()


def submit(market: str, kind: str, device: str, text: str = "", url: str = "",
           image: tuple[bytes, str] | None = None, hint: str = "Reddit") -> dict:
    body = {"market": market, "kind": kind, "device": device, "text": text, "url": url,
            "source_hint": hint, "app_version": "reddit-bridge/1"}
    if image is not None:
        body["image_base64"] = base64.b64encode(image[0]).decode()
        body["image_type"] = image[1]
    if DRY_RUN:
        print(f"[dry-run] {market} {kind} {hint}: {text[:90]!r} url={url!r} image={'yes' if image else 'no'}")
        return {"id": "dry-run"}
    req = urllib.request.Request(f"{INBOX_URL}/submit", data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json",
                                          **({"X-Inbox-Token": INBOX_TOKEN} if INBOX_TOKEN else {})})
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.load(r)


def looks_like_event(post: dict) -> bool:
    if not KEYWORDS:
        return True
    if FLAIR_RX.search(post.get("link_flair_text") or ""):
        return True
    hay = f"{post.get('title', '')}\n{post.get('selftext', '')}".lower()
    return any(k in hay for k in KEYWORDS)


def image_of(post: dict) -> tuple[str, str] | None:
    """(url, mime) for an image post, else None. Galleries take the first item."""
    url = post.get("url_overridden_by_dest") or post.get("url") or ""
    ext = os.path.splitext(urllib.parse.urlparse(url).path)[1].lower()
    if post.get("post_hint") == "image" and ext in IMAGE_EXT:
        return url, IMAGE_EXT[ext]
    if ext in IMAGE_EXT and "i.redd.it" in url:
        return url, IMAGE_EXT[ext]
    media = post.get("media_metadata") or {}
    for item in media.values():
        src = (item.get("s") or {}).get("u") or ""
        mime = item.get("m") or ""
        if src and mime in IMAGE_EXT.values():
            return html.unescape(src), mime
    return None


def post_to_submission(post: dict, market: str) -> dict | None:
    """Pure mapping (testable). Returns the submit pieces or None to skip."""
    if post.get("stickied") or post.get("removed_by_category") or post.get("author") == "AutoModerator":
        return None
    if not looks_like_event(post):
        return None
    sub = post.get("subreddit") or "?"
    title = html.unescape(post.get("title") or "").strip()
    body = html.unescape(post.get("selftext") or "").strip()
    if body.lower() == "[removed]" or body.lower() == "[deleted]":
        body = ""
    text = (title + ("\n\n" + body if body else "")).strip()[:MAX_BODY]
    permalink = "https://www.reddit.com" + (post.get("permalink") or "")
    link = post.get("url_overridden_by_dest") or post.get("url") or ""
    device = f"rd:{sub.lower()}"
    hint = f"Reddit r/{sub}"
    img = image_of(post)
    if img:
        return {"market": market, "kind": "image", "device": device, "text": text,
                "url": permalink, "hint": hint, "image_url": img[0], "image_type": img[1]}
    if link and not post.get("is_self") and "reddit.com" not in link:
        # Link post to an external page (ticket site, venue, Facebook event): the
        # extractor can fetch it; the reddit permalink goes in the text.
        return {"market": market, "kind": "url", "device": device,
                "text": f"{text}\n\n{permalink}"[:MAX_BODY], "url": link, "hint": hint}
    if len(text) >= MIN_TEXT:
        return {"market": market, "kind": "text", "device": device, "text": text, "url": permalink, "hint": hint}
    return None


def poll_once(markets: dict[str, str], state: dict) -> int:
    n = 0
    for sub, market in markets.items():
        try:
            listing = reddit_get(f"/r/{sub}/new", {"limit": 50})
            posts = [c["data"] for c in listing.get("data", {}).get("children", []) if c.get("kind") == "t3"]
            newest = max((float(p.get("created_utc") or 0) for p in posts), default=0.0)
            last = state.get(sub)
            if last is None:
                # First run: start from now rather than replaying history.
                state[sub] = newest
                save_state(state)
                print(f"r/{sub}: watermark set ({len(posts)} existing posts skipped)")
                continue
            for post in sorted(posts, key=lambda p: float(p.get("created_utc") or 0)):
                created = float(post.get("created_utc") or 0)
                if created <= float(last):
                    continue
                try:
                    s = post_to_submission(post, market)
                    if s:
                        image = (fetch(s.pop("image_url")), s.pop("image_type")) if "image_url" in s else None
                        res = submit(s["market"], s["kind"], s["device"], s["text"], s["url"], image, s["hint"])
                        print(f"{market} <- {s['kind']} from {s['hint']}: {res.get('id')}")
                        n += 1
                except Exception as exc:  # noqa: BLE001 — one bad post never stops the poll
                    print(f"r/{sub} {post.get('id')}: {type(exc).__name__}: {exc}", file=sys.stderr)
                state[sub] = max(float(state.get(sub) or 0), created)
            save_state(state)
        except urllib.error.HTTPError as exc:
            # 403 = Reddit refusing this network unauthenticated; keep polling,
            # the operator sees it in the journal and can add OAuth creds.
            print(f"r/{sub}: HTTP {exc.code} {exc.reason}", file=sys.stderr)
        except Exception as exc:  # noqa: BLE001
            print(f"r/{sub}: {type(exc).__name__}: {exc}", file=sys.stderr)
    return n


def main(argv: list[str]) -> int:
    markets = subreddit_markets()
    if not markets:
        print("REDDIT_SUBREDDIT_MARKETS not set; nothing to watch", file=sys.stderr)
        return 2
    mode = "oauth" if (CLIENT_ID and CLIENT_SECRET) else "public-json"
    print(f"bridge up ({mode}{', dry-run' if DRY_RUN else ''}) watching {len(markets)} subreddit(s) -> {INBOX_URL}")
    state = load_state()
    if "--once" in argv:
        poll_once(markets, state)
        return 0
    while True:
        poll_once(markets, state)
        time.sleep(POLL)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

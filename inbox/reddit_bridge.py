#!/usr/bin/env python3
"""Reddit -> 0 FOMO inbox bridge (docs/GLOBAL_DESIGN.md §3, source "Bot").

Watches one subreddit per market and forwards posts that look like event
announcements (keyword or flair prefilter) as inbox submissions, exactly like
the Discord and Telegram bridges. Standard library only; plain polling; no
inbound port.

How it reads Reddit (2026-09-16 reality check):
  * Creating an API app at reddit.com/prefs/apps is gated behind Reddit's
    Responsible Builder Policy (manual approval, weeks, not guaranteed) and the
    public `.json` listings answer 403 from both RR-002 and fie-worker-1.
  * The Atom feeds (`www.reddit.com/r/<sub>/new.rss`) still answer 200 from
    both networks with a descriptive User-Agent, so **RSS is the default
    mode and needs no credentials**. Reddit rate-limits bursts (429), so
    subreddits are polled one at a time with a pause between requests.
  * If REDDIT_CLIENT_ID + REDDIT_CLIENT_SECRET ever exist (approved app), the
    bridge switches to OAuth JSON automatically (richer: flair, galleries).

Env:
  REDDIT_SUBREDDIT_MARKETS  "bahamas=bs-nassau,Miami=us-miami" (required: only
                            listed subreddits are read)
  REDDIT_CLIENT_ID / REDDIT_CLIENT_SECRET   optional, switches to OAuth JSON
  REDDIT_KEYWORDS           comma list; a post must contain one (title+body,
                            case-insensitive) or carry an event-ish flair.
                            Empty string = forward everything.
  REDDIT_POLL_SECONDS       default 600
  REDDIT_REQUEST_GAP        seconds between subreddit requests, default 20
                            (8 s still drew 429s on 2026-09-16)
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
import xml.etree.ElementTree as ET
from datetime import datetime

INBOX_URL = os.environ.get("INBOX_URL", "http://127.0.0.1:8787").rstrip("/")
INBOX_TOKEN = os.environ.get("INBOX_TOKEN", "").strip()
CLIENT_ID = os.environ.get("REDDIT_CLIENT_ID", "").strip()
CLIENT_SECRET = os.environ.get("REDDIT_CLIENT_SECRET", "").strip()
STATE = os.environ.get("REDDIT_STATE", os.path.join(os.path.dirname(os.path.abspath(__file__)), "reddit.state.json"))
POLL = int(os.environ.get("REDDIT_POLL_SECONDS", "600"))
GAP = float(os.environ.get("REDDIT_REQUEST_GAP", "20"))
DRY_RUN = os.environ.get("REDDIT_DRY_RUN", "") == "1"
UA = "zerofomo-bridge/1.0 (events inbox; https://0fomo.app; contact info@arctechnologyhq.com)"
DEFAULT_KEYWORDS = ("event,party,concert,festival,fest,tickets,show,tonight,this weekend,live music,flyer,night,live,"
                    "fete,regatta,junkanoo,carnival,brunch,pop-up,popup,market,fair,expo,conference,"
                    "meetup,meet-up,open mic,comedy,dj,gig,performance,exhibition,screening,race,5k,"
                    "tournament,happening,lineup,line-up,doors open,rsvp")
KEYWORDS = [k.strip().lower() for k in os.environ.get("REDDIT_KEYWORDS", DEFAULT_KEYWORDS).split(",") if k.strip()]
FLAIR_RX = re.compile(r"event|happening|things to do|what's on|whats on|announcement", re.I)
# Strict prefilter (default since 2026-09-24). The loose any-keyword filter above
# passed 223 of 226 real posts and only 11 carried an event: every miss cost an LLM
# call and drained the Gemini daily quota. Scored on those 226 posts, this one
# passes 32 and keeps 10 of the 11 events. REDDIT_PREFILTER=loose restores the old
# behaviour; REDDIT_KEYWORDS still adds extra event words.
STRICT = os.environ.get("REDDIT_PREFILTER", "strict").lower() != "loose"
STRONG_RX = re.compile(
    r"\b(events?|concerts?|festivals?|fest|tickets?|rsvp|doors (open|at)|line-?up|dj|dj set|"
    r"live music|open mic|comedy (show|night)|stand-?up|meet-?up|pop-?up|farmers'? market|"
    r"night market|flea market|street fair|fair|expo|parade|party|block party|watch party|"
    r"brunch|happy hour|karaoke|trivia|gala|fundraiser|workshop|screening|premiere|tournament|"
    r"5k|10k|marathon|race day|regatta|junkanoo|carnival|fete|soca|exhibition|opening night|"
    r"launch party|album release|tour|matinee|showcase|conference|summit|hackathon|"
    r"free admission|free entry|all ages|21\+|18\+|cover charge|early bird|presale|"
    r"performing|performs?|headlin(er|ing)|hosted by|featuring|feat\.?|show|contest|competition|"
    r"call-in|giveaway|grand opening|open house|game ?night|movie night|paint (and|&) sip)\b", re.I)
TIME_RX = re.compile(
    r"\b(tonight|tomorrow|this (weekend|week|friday|saturday|sunday|thursday)|"
    r"mon(day)?|tue(s|sday)?|wed(nesday)?|thu(rs|rsday)?|fri(day)?|sat(urday)?|sun(day)?|"
    r"jan(uary)?|feb(ruary)?|mar(ch)?|apr(il)?|may|june?|july?|aug(ust)?|sep(t|tember)?|"
    r"oct(ober)?|nov(ember)?|dec(ember)?|"
    r"\d{1,2}(:\d{2})?\s?(a\.?m|p\.?m)|\d{1,2}/\d{1,2}(/\d{2,4})?|\d{1,2}(st|nd|rd|th))\b", re.I)
QUESTION_RX = re.compile(r"\?\s*$")
ASK_RX = re.compile(r"\b(recommend(ations?)?|anyone know|where (can|do|should) i|any (good|suggestions?)|"
                    r"looking for|does anyone|is there a|what are some|best place)\b", re.I)
SKIP_RX = re.compile(r"daily discussion|weekly (thread|discussion)|megathread|roommate|for sale|hiring|"
                     r"lost (dog|cat)|missing (dog|cat)", re.I)
MIN_TEXT = 25
MAX_BODY = 3000
IMAGE_EXT = {".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".png": "image/png", ".webp": "image/webp"}
ATOM = "{http://www.w3.org/2005/Atom}"
TAG_RX = re.compile(r"<[^>]+>")
MD_RX = re.compile(r'<div class="md">(.*?)</div>', re.S)
LINK_RX = re.compile(r'<a href="([^"]+)">\s*\[link\]\s*</a>')

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


# ---------------------------------------------------------------- transports
def _http(url: str, headers: dict | None = None, data: bytes | None = None, timeout: int = 30) -> bytes:
    req = urllib.request.Request(url, data=data, headers={"User-Agent": UA, **(headers or {})})
    for attempt in range(3):
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return r.read()
        except urllib.error.HTTPError as e:
            if e.code == 429 and attempt < 2:
                time.sleep(min(max(float(e.headers.get("Retry-After") or 0), 60.0), 180))
                continue
            raise
    raise RuntimeError(f"gave up on {url}")


def _oauth_token() -> str:
    if not (CLIENT_ID and CLIENT_SECRET):
        return ""
    if _oauth["token"] and time.time() < _oauth["expires"] - 60:
        return _oauth["token"]
    cred = base64.b64encode(f"{CLIENT_ID}:{CLIENT_SECRET}".encode()).decode()
    d = json.loads(_http("https://www.reddit.com/api/v1/access_token", data=b"grant_type=client_credentials",
                         headers={"Authorization": f"Basic {cred}",
                                  "Content-Type": "application/x-www-form-urlencoded"}))
    _oauth["token"] = d["access_token"]
    _oauth["expires"] = time.time() + float(d.get("expires_in", 3600))
    return _oauth["token"]


def posts_via_oauth(sub: str) -> list[dict]:
    token = _oauth_token()
    url = f"https://oauth.reddit.com/r/{sub}/new?" + urllib.parse.urlencode({"limit": 50, "raw_json": 1})
    try:
        raw = _http(url, headers={"Authorization": f"Bearer {token}"})
    except urllib.error.HTTPError as e:
        if e.code != 401:
            raise
        _oauth["token"] = ""
        raw = _http(url, headers={"Authorization": f"Bearer {_oauth_token()}"})
    listing = json.loads(raw)
    return [c["data"] for c in listing.get("data", {}).get("children", []) if c.get("kind") == "t3"]


def atom_to_post(entry: ET.Element, sub: str) -> dict:
    """Shape an Atom <entry> like a JSON listing post so the mapping below is
    shared. RSS carries no flair and no gallery metadata; image posts are
    recognised by the [link] target (i.redd.it) instead of post_hint."""
    def text(tag: str) -> str:
        el = entry.find(ATOM + tag)
        return (el.text or "") if el is not None else ""
    link_el = entry.find(ATOM + "link")
    permalink = link_el.get("href", "") if link_el is not None else ""
    content = html.unescape(text("content"))
    m = MD_RX.search(content)
    selftext = html.unescape(TAG_RX.sub(" ", m.group(1))).strip() if m else ""
    selftext = re.sub(r"[ \t]+", " ", selftext)
    lm = LINK_RX.search(content)
    link = html.unescape(lm.group(1)) if lm else permalink
    ext = os.path.splitext(urllib.parse.urlparse(link).path)[1].lower()
    author_el = entry.find(ATOM + "author/" + ATOM + "name")
    published = text("published")
    try:
        created = datetime.fromisoformat(published.replace("Z", "+00:00")).timestamp()
    except ValueError:
        created = 0.0
    return {
        "id": text("id").removeprefix("t3_"),
        "subreddit": sub,
        "title": html.unescape(text("title")).strip(),
        "selftext": selftext,
        "permalink": permalink.replace("https://www.reddit.com", ""),
        "url": link,
        "url_overridden_by_dest": link if link != permalink else "",
        "is_self": link == permalink,
        "post_hint": "image" if ext in IMAGE_EXT else "",
        "created_utc": created,
        "author": (author_el.text or "").strip().removeprefix("/u/") if author_el is not None else "",
        "link_flair_text": "",
    }


def posts_via_rss(sub: str) -> list[dict]:
    raw = _http(f"https://www.reddit.com/r/{sub}/new.rss")
    root = ET.fromstring(raw)
    return [atom_to_post(e, sub) for e in root.findall(ATOM + "entry")]


def fetch(url: str) -> bytes:
    return _http(url, timeout=60)


# ------------------------------------------------------------------ mapping
def submit(market: str, kind: str, device: str, text: str = "", url: str = "",
           image: tuple[bytes, str] | None = None, hint: str = "Reddit") -> dict:
    body = {"market": market, "kind": kind, "device": device, "text": text, "url": url,
            "source_hint": hint, "app_version": "reddit-bridge/2"}
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


def looks_like_event(post: dict, rich: bool = False) -> bool:
    """`rich` = the post carries a flyer image or an external link, where the
    details live outside the title; one event word is then enough."""
    if FLAIR_RX.search(post.get("link_flair_text") or ""):
        return True
    title = post.get("title", "") or ""
    hay = f"{title}\n{post.get('selftext', '') or ''}"
    if not STRICT:
        return not KEYWORDS or any(k in hay.lower() for k in KEYWORDS)
    if SKIP_RX.search(title):
        return False
    if QUESTION_RX.search(title.strip()) or ASK_RX.search(title):
        return False          # asking about events is not listing one
    strong = {m.group(0).lower() for m in STRONG_RX.finditer(hay)}
    extra = [k for k in KEYWORDS if k not in DEFAULT_KEYWORDS.lower() and k in hay.lower()]
    strong.update(extra)
    if not strong:
        return False
    if rich or len(strong) >= 2:
        return True
    return bool(TIME_RX.search(hay))


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
    img = image_of(post)
    link = post.get("url_overridden_by_dest") or post.get("url") or ""
    external = bool(link) and not post.get("is_self") and "reddit.com" not in link
    if not looks_like_event(post, rich=bool(img) or external):
        return None
    sub = post.get("subreddit") or "?"
    title = html.unescape(post.get("title") or "").strip()
    body = html.unescape(post.get("selftext") or "").strip()
    if body.lower() in ("[removed]", "[deleted]"):
        body = ""
    text = (title + ("\n\n" + body if body else "")).strip()[:MAX_BODY]
    permalink = "https://www.reddit.com" + (post.get("permalink") or "")
    device = f"rd:{sub.lower()}"
    hint = f"Reddit r/{sub}"
    if img:
        return {"market": market, "kind": "image", "device": device, "text": text,
                "url": permalink, "hint": hint, "image_url": img[0], "image_type": img[1]}
    if external:
        # Link post to an external page (ticket site, venue, Facebook event): the
        # extractor can fetch it; the reddit permalink goes in the text.
        return {"market": market, "kind": "url", "device": device,
                "text": f"{text}\n\n{permalink}"[:MAX_BODY], "url": link, "hint": hint}
    if len(text) >= MIN_TEXT:
        return {"market": market, "kind": "text", "device": device, "text": text, "url": permalink, "hint": hint}
    return None


# --------------------------------------------------------------------- loop
def poll_once(markets: dict[str, str], state: dict) -> int:
    n = 0
    use_oauth = bool(CLIENT_ID and CLIENT_SECRET)
    for i, (sub, market) in enumerate(markets.items()):
        if i:
            time.sleep(GAP)  # Reddit 429s bursts even on RSS (per-IP, ~1 req / 10-20 s)
        try:
            posts = posts_via_oauth(sub) if use_oauth else posts_via_rss(sub)
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
            print(f"r/{sub}: HTTP {exc.code} {exc.reason}", file=sys.stderr)
        except Exception as exc:  # noqa: BLE001
            print(f"r/{sub}: {type(exc).__name__}: {exc}", file=sys.stderr)
    return n


def main(argv: list[str]) -> int:
    markets = subreddit_markets()
    if not markets:
        print("REDDIT_SUBREDDIT_MARKETS not set; nothing to watch", file=sys.stderr)
        return 2
    mode = "oauth-json" if (CLIENT_ID and CLIENT_SECRET) else "rss"
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

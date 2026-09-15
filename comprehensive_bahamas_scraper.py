#!/usr/bin/env python3
"""
===============================================================================
 comprehensive_bahamas_scraper.py
 New Providence / Nassau, Bahamas — 2026 Events Aggregation Pipeline
===============================================================================

 Purpose
 -------
 Extract -> Clean -> Deduplicate -> Export 2026 event data for an Android
 events application covering New Providence, The Bahamas.

 Sources implemented (each fully isolated — one failing source never kills
 the run):

   1. Ticket Flare      https://ticket-flare.com/events
                        Custom PHP ticketing platform. Server-rendered card
                        listing + per-event detail pages (JSON-LD when present).
   2. ETickets Live     https://eticketslive.com/event_loc/bahamas/
                        WordPress events/tickets plugin. Server-rendered,
                        /page/N/ pagination, card text carries date/time/price.
   3. BahaEvents        https://bahaevents.com
                        JavaScript SPA — direct HTML is an empty shell.
                        Strategy: probe common JSON/XHR endpoints + sitemap
                        harvesting; optional Playwright rendering (--js).
   4. Bid Bahamas       https://bid-bahamas.com/events
                        Marketplace platform, events vertical. Same layered
                        strategy as BahaEvents (endpoint probe -> sitemap ->
                        optional Playwright).
   5. Eventbrite        https://www.eventbrite.com/d/bahamas/events/
                        Parses the embedded  window.__SERVER_DATA__  JSON blob
                        (the same payload their internal search XHR returns),
                        with JSON-LD and HTML-card fallbacks. Optional official
                        API via env var EVENTBRITE_API_TOKEN.
   6. AllEvents.in      https://allevents.in/nassau  (bonus source)
                        Aggregator with rich JSON-LD ItemList markup.
   7. BahamasLocal      https://www.bahamaslocal.com  (bonus source)
                        Long-standing Bahamian directory with an events section.

 Architecture
 ------------
   RequestEngine          rotating user-agents, retry/backoff, polite jittered
                          delays per domain, 429 Retry-After handling
   BaseScraper            error-isolated scrape() contract + run-status record
   Parsing helpers        JSON-LD extraction, fuzzy date/time/price regexes,
                          sitemap harvesting, recursive JSON event mining
   TransformEngine        canonical pandas DataFrame:
                          [Event Name, Date, Time, Venue/Location,
                           Ticket Price (USD), Category, Source URL, Description]
   Deduplicator           date-blocked fuzzy title matching (difflib, with
                          rapidfuzz auto-upgrade if installed); cross-listed
                          events melt into one master record, richest fields win
   Exporter               Excel workbook (Events / Needs Review / By Source /
                          Run Summary sheets) + CSV backup

 JavaScript-heavy pages — fallback notes
 ---------------------------------------
 BahaEvents and Bid Bahamas ship almost no server-side HTML. This script
 first probes their likely JSON endpoints and sitemaps (cheap, ban-safe).
 If those yield nothing and you pass --js, it will render the pages with
 Playwright (headless Chromium) and re-parse the hydrated DOM:

     pip install playwright
     playwright install chromium
     python comprehensive_bahamas_scraper.py --js

 Install
 -------
     pip install requests beautifulsoup4 lxml pandas python-dateutil openpyxl

 Optional extras:
     pip install rapidfuzz            # faster/better dedup similarity
     pip install playwright && playwright install chromium   # --js fallback

 Run
 ---
     python comprehensive_bahamas_scraper.py
     python comprehensive_bahamas_scraper.py --sources eventbrite,eticketslive
     python comprehensive_bahamas_scraper.py --year 2026 --max-pages 10 --verbose

 Output: New_Providence_Events_2026.xlsx (+ .csv backup) in this directory.
===============================================================================
"""

from __future__ import annotations

import argparse
import hashlib
import html as htmllib
import json
import logging
import os
import random
import re
import string
import sys
import time
from dataclasses import dataclass, field
from datetime import datetime, timezone
from difflib import SequenceMatcher
from functools import lru_cache
from typing import Callable, Iterable, Optional
from urllib.parse import urljoin, urlparse

import pandas as pd
import requests
from bs4 import BeautifulSoup
from dateutil import parser as dateparser
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry

# --- optional accelerators (never required) ---------------------------------
try:
    from rapidfuzz import fuzz as _rapidfuzz  # type: ignore

    HAVE_RAPIDFUZZ = True
except ImportError:
    HAVE_RAPIDFUZZ = False

try:
    import playwright.sync_api  # type: ignore  # noqa: F401

    HAVE_PLAYWRIGHT = True
except ImportError:
    HAVE_PLAYWRIGHT = False

log = logging.getLogger("bahamas_scraper")

# =============================================================================
# CONFIGURATION
# =============================================================================

HORIZON_DAYS = 730           # rolling window: today -> +2 years
OUTPUT_BASENAME = "New_Providence_Events"


# =============================================================================
# MARKETS (docs/GLOBAL_DESIGN.md §4) — one pipeline run = one market
# =============================================================================

@dataclass
class Market:
    """A city/island cluster with its own sources, timezone and feed."""
    id: str                       # "bs-nassau", "us-miami"
    name: str
    country: str                  # ISO 3166-1 alpha-2
    tz: str                       # IANA zone
    lat: float
    lng: float
    radius_km: int = 50
    currency: str = "USD"
    island_filter: bool = False   # Bahamas-only New Providence verdicting
    min_events: int = 0           # dead-man floor for this market
    sources: dict = field(default_factory=dict)   # {source_key: params}

    @property
    def feed_dir(self) -> str:
        return os.path.join("feeds", self.id)


DEFAULT_MARKET = Market(
    id="bs-nassau", name="Nassau, New Providence", country="BS",
    tz="America/Nassau", lat=25.06, lng=-77.345, radius_km=40, currency="BSD",
    island_filter=True, min_events=15,
    sources={k: {} for k in ("ticket-flare", "eticketslive", "bahaevents",
                             "bid-bahamas", "eventbrite", "allevents.in",
                             "bahamaslocal", "bandsintown", "songkick",
                             "reggaeville", "manual")},
)

MARKETS_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "markets")


def load_market(market_id: str) -> Market:
    path = os.path.join(MARKETS_DIR, f"{market_id}.json")
    with open(path, encoding="utf-8") as fh:
        d = json.load(fh)
    return Market(
        id=d["id"], name=d["name"], country=d["country"].upper(), tz=d["tz"],
        lat=float(d["lat"]), lng=float(d["lng"]),
        radius_km=int(d.get("radius_km", 50)), currency=d.get("currency", "USD"),
        island_filter=bool(d.get("island_filter", False)),
        min_events=int(d.get("min_events", 0)),
        sources=dict(d.get("sources") or {}),
    )


def list_markets() -> list[str]:
    if not os.path.isdir(MARKETS_DIR):
        return []
    return sorted(f[:-5] for f in os.listdir(MARKETS_DIR) if f.endswith(".json"))

# Keywords that positively identify New Providence
NEW_PROVIDENCE_KEYWORDS = [
    "nassau", "new providence", "paradise island", "cable beach", "baha mar",
    "atlantis", "arawak cay", "fish fry", "downtown nassau", "west bay",
    "east bay", "carmichael", "clifton", "goombay park", "junkanoo beach",
    "montagu", "fort charlotte", "national stadium", "queen elizabeth sports",
    "british colonial", "margaritaville", "bahamar", "sandyport", "love beach",
    "saunders beach", "compass point", "old fort bay", "lyford cay",
]

# Keywords that clearly place an event on ANOTHER island (exclusion signal)
OTHER_ISLAND_KEYWORDS = [
    "freeport", "grand bahama", "exuma", "eleuthera", "abaco", "bimini",
    "andros", "cat island", "long island", "harbour island", "spanish wells",
    "san salvador", "inagua", "ragged island", "acklins", "crooked island",
    "mayaguana", "berry islands", "marsh harbour", "george town", "rock sound",
    "governor's harbour", "port lucaya",
]

# Category inference: first matching keyword group wins (order matters).
# Plain entries match as substrings; "re:" entries are regexes — use those for
# short words ("fair") that would otherwise fire inside longer ones ("affair").
CATEGORY_RULES: list[tuple[str, list[str]]] = [
    ("Junkanoo / Cultural",  ["junkanoo", "heritage", "cultural", "independence",
                              "emancipation", "goombay", "rake and scrape",
                              "rake n scrape", "rake 'n' scrape", "majority rule"]),
    ("Regatta / Maritime",   ["regatta", "sailing", "boat ride", "boat cruise",
                              "yacht", "catamaran", "sloop"]),
    ("Farmers / Craft Market", ["farmers market", "farmer's market", "farmers' market",
                              "market day", "craft market", "flea market",
                              "vendor market", "vendors market", "pop-up market",
                              "popup market", "artisan market", "green market",
                              "fresh market", "island market", "makers market"]),
    ("Conference / Expo",    ["conference", "summit", "convention", "trade show",
                              "tradeshow", "symposium", "congress", "job fair",
                              "career fair", "recruitment fair", "college fair",
                              r"re:\bexpo(?:sition)?s?\b"]),
    ("Fair / Popup",         ["funfair", "fun fair", "bazaar", "open house",
                              "pop-up", "popup", "pop up", "sip and shop",
                              "sip & shop", "vendor showcase", "shopping village",
                              r"re:\bfairs?\b"]),
    ("Festival",             ["festival", "fest", "carnival", "fete", "j'ouvert",
                              "jouvert", "crop over", "soca"]),
    ("Concert / Live Music", ["concert", "live music", "album", "in concert",
                              "performing live", "band", "orchestra", "gospel",
                              "jazz", "reggae", "artist", "tour"]),
    ("Club Promotion",       ["ladies night", "ladies' night", "guest dj",
                              "bottle service", "happy hour", "drink special",
                              "drinks special", "club night", "vip section",
                              "free entry before", "2 for 1", "all inclusive drinks",
                              "open bar"]),
    ("Nightlife / Party",    ["party", "nightlife", "night club", "nightclub",
                              "all white", "day party", "brunch party", "vibes",
                              "link up", "lituation", "after dark", "glow",
                              "rooftop", "dj "]),
    ("Beach Party",          ["beach party", "beach bash", "beach jam",
                              "pool party", "wet fete"]),
    ("Comedy",               ["comedy", "comedian", "stand-up", "stand up"]),
    ("Pageant",              ["pageant", "miss ", "queen show", "crowning"]),
    ("Food & Drink",         ["food", "wine", "beer", "brunch", "tasting",
                              "culinary", "cook", "chef", "seafood", "rum"]),
    ("Sports & Fitness",     ["marathon", "5k", "10k", "run", "race", "fitness",
                              "yoga", "basketball", "softball", "soccer",
                              "football", "boxing", "golf", "tennis", "swim"]),
    ("Arts & Theatre",       ["theatre", "theater", "art ", "exhibit", "gallery",
                              "play", "musical", "dance", "ballet", "poetry",
                              "spoken word"]),
    ("Business / Networking", ["networking", "seminar", "workshop", "awards",
                               "mixer", "masterclass", "master class"]),
    ("Faith & Community",    ["church", "worship", "prayer", "revival",
                              "community", "charity", "fundraiser", "gala"]),
]

DEFAULT_CATEGORY = "General"

USER_AGENTS = [
    # A modest, realistic desktop/mobile UA pool — rotated per request.
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36 Edg/125.0.0.0",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 "
    "(KHTML, like Gecko) Version/17.4 Safari/605.1.15",
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:127.0) Gecko/20100101 "
    "Firefox/127.0",
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
]

MONTHS_RX = (
    "January|February|March|April|May|June|July|August|September|October|"
    "November|December|Jan|Feb|Mar|Apr|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec"
)

# "Fri January 15, 2027", "January 15th 2026", "15 January 2026", "Jan. 5, 2026"
DATE_RX = re.compile(
    rf"(?:(?:Mon|Tues?|Wed(?:nes)?|Thur?s?|Fri|Sat(?:ur)?|Sun)\.?(?:day)?,?\s+)?"
    rf"(?:(?P<month1>{MONTHS_RX})\.?\s+(?P<day1>\d{{1,2}})(?:st|nd|rd|th)?,?\s+(?P<year1>20\d{{2}})"
    rf"|(?P<day2>\d{{1,2}})(?:st|nd|rd|th)?\s+(?P<month2>{MONTHS_RX})\.?,?\s+(?P<year2>20\d{{2}}))",
    re.IGNORECASE,
)
TIME_RX = re.compile(r"\b(\d{1,2}(?::\d{2})?\s*(?:a\.?m\.?|p\.?m\.?))\b", re.IGNORECASE)
PRICE_RX = re.compile(r"(?:USD?\s*)?\$\s*(\d{1,4}(?:,\d{3})*(?:\.\d{2})?)")
FREE_RX = re.compile(r"\b(free(?:\s+(?:entry|admission|event))?|no\s+cover)\b", re.IGNORECASE)

# Aggregators geo-tag online events to Nassau; these are noise for a
# location-based app and get parked in Needs Review instead of the master.
ONLINE_EVENT_RX = re.compile(
    r"\b(webinar|online|virtual|zoom|livestream|live\s*stream|remote\s+session)\b",
    re.IGNORECASE)

TITLE_NOISE_WORDS = {
    "the", "a", "an", "at", "in", "of", "and", "&", "with", "presents",
    "present", "tickets", "ticket", "event", "official", "live", "edition",
    "annual", "1st", "2nd", "3rd", "4th", "5th", "6th", "7th", "8th", "9th",
    "10th", "nassau", "bahamas", "2026",
}

SIMILARITY_THRESHOLD = 0.83  # fuzzy title match cutoff within a date block


# =============================================================================
# DATA MODEL
# =============================================================================

@dataclass
class Event:
    """One raw event record as captured from a single source."""

    name: str = ""
    date: str = ""            # ISO yyyy-mm-dd once parsed; "" if unknown
    time: str = ""            # e.g. "8:00 PM" or "8:00 PM - 11:45 PM"
    venue: str = ""
    price: str = ""           # "$50.00", "$20.00 - $34.99", "Free", ""
    category: str = ""
    source_url: str = ""
    description: str = ""
    source_name: str = ""
    raw_date: str = ""        # original date text, kept for auditability
    lat: Optional[float] = None   # venue coordinates when the source has them
    lng: Optional[float] = None

    def completeness(self) -> int:
        return sum(
            1
            for v in (self.name, self.date, self.time, self.venue,
                      self.price, self.category, self.source_url, self.description)
            if v
        )

    def is_plausible(self) -> bool:
        return bool(self.name and len(self.name.strip()) >= 3 and self.source_url)


@dataclass
class SourceStatus:
    name: str
    ok: bool = False
    events_found: int = 0
    pages_fetched: int = 0
    note: str = ""


# =============================================================================
# REQUEST ENGINEERING
# =============================================================================

class RequestEngine:
    """
    Polite, ban-resistant HTTP layer.

    - Rotating User-Agent per request (realistic desktop pool)
    - urllib3 Retry with exponential backoff on 429/5xx
    - Per-domain jittered delay so no host is hammered
    - Honors Retry-After on 429s
    """

    def __init__(self, base_delay: float = 2.0, timeout: int = 25):
        self.base_delay = base_delay
        self.timeout = timeout
        self._last_hit: dict[str, float] = {}

        self.session = requests.Session()
        retry = Retry(
            total=3,
            connect=3,
            read=2,
            backoff_factor=1.5,
            status_forcelist=(429, 500, 502, 503, 504),
            allowed_methods=("GET", "HEAD"),
            respect_retry_after_header=True,
        )
        adapter = HTTPAdapter(max_retries=retry, pool_connections=10, pool_maxsize=10)
        self.session.mount("https://", adapter)
        self.session.mount("http://", adapter)

    def _headers(self, referer: Optional[str] = None) -> dict:
        headers = {
            "User-Agent": random.choice(USER_AGENTS),
            "Accept": ("text/html,application/xhtml+xml,application/xml;q=0.9,"
                       "image/avif,image/webp,*/*;q=0.8"),
            "Accept-Language": "en-US,en;q=0.9",
            # NOTE: never hard-code Accept-Encoding here. urllib3 advertises
            # exactly the codecs it can decode (br/zstd only when installed);
            # forcing "br" without a brotli decoder yields undecodable bytes.
            "Connection": "keep-alive",
            "Upgrade-Insecure-Requests": "1",
            "Sec-Fetch-Dest": "document",
            "Sec-Fetch-Mode": "navigate",
            "Sec-Fetch-Site": "none",
            "Cache-Control": "max-age=0",
        }
        if referer:
            headers["Referer"] = referer
            headers["Sec-Fetch-Site"] = "same-origin"
        return headers

    def _throttle(self, url: str) -> None:
        domain = urlparse(url).netloc
        elapsed = time.time() - self._last_hit.get(domain, 0.0)
        wait = self.base_delay + random.uniform(0.3, 1.4) - elapsed
        if wait > 0:
            time.sleep(wait)
        self._last_hit[domain] = time.time()

    def get(self, url: str, referer: Optional[str] = None,
            as_json: bool = False, quiet: bool = False,
            params: Optional[dict] = None):
        """GET a URL. Returns Response (or parsed JSON if as_json) or None."""
        self._throttle(url)
        try:
            resp = self.session.get(url, headers=self._headers(referer),
                                    params=params,
                                    timeout=self.timeout, allow_redirects=True)
            if resp.status_code == 404:
                if not quiet:
                    log.debug("404: %s", url)
                return None
            resp.raise_for_status()
            if as_json:
                ctype = resp.headers.get("Content-Type", "")
                if "json" not in ctype and not resp.text.lstrip().startswith(("{", "[")):
                    return None
                return resp.json()
            return resp
        except requests.RequestException as exc:
            if not quiet:
                log.warning("Request failed [%s]: %s", url, exc)
            return None
        except ValueError:
            return None

    def soup(self, url: str, referer: Optional[str] = None) -> Optional[BeautifulSoup]:
        resp = self.get(url, referer=referer)
        if resp is None:
            return None
        return make_soup(resp.text)


def make_soup(html: str) -> BeautifulSoup:
    # lxml silently truncates some malformed real-world markup (observed on
    # eticketslive.com, where it drops the entire event grid but keeps the
    # nav). Parse with both parsers and keep whichever tree recovered more
    # anchors — network latency dwarfs the double-parse cost at this scale.
    soup = BeautifulSoup(html, "html.parser")
    try:
        alt = BeautifulSoup(html, "lxml")
        if len(alt.find_all("a")) > len(soup.find_all("a")):
            return alt
    except Exception:
        pass
    return soup


# =============================================================================
# PARSING HELPERS
# =============================================================================

def clean_text(value) -> str:
    if not value:
        return ""
    # Embedded-JSON fields are not always plain strings: Eventbrite ships
    # {'text': ...} rich-text wrappers, JSON-LD allows single-element lists.
    if isinstance(value, list):
        value = value[0] if value else ""
    if isinstance(value, dict):
        value = value.get("text") or value.get("name") or value.get("@value") or ""
    return re.sub(r"\s+", " ", htmllib.unescape(str(value))).strip()


def strip_html(value: Optional[str]) -> str:
    """Drop markup + entities from rich-text fields (event descriptions
    scraped from embedded JSON frequently arrive as raw HTML)."""
    if not value:
        return ""
    text = htmllib.unescape(str(value))
    text = re.sub(r"<br\s*/?>|</p>|</div>|</li>", "\n", text, flags=re.I)
    text = re.sub(r"<[^>]+>", " ", text)
    text = text.replace("\xa0", " ")
    text = re.sub(r"[ \t]+", " ", text)
    return re.sub(r"\s*\n\s*", "\n", text).strip()


def parse_date_iso(text: str) -> tuple[str, str]:
    """Extract the FIRST date in free text. Returns (iso_date, raw_match)."""
    if not text:
        return "", ""
    m = DATE_RX.search(text)
    if m:
        raw = m.group(0)
        try:
            dt = dateparser.parse(raw, fuzzy=True)
            if dt:
                return dt.strftime("%Y-%m-%d"), raw
        except (ValueError, OverflowError):
            pass
    # Numeric fallbacks: 2026-07-04, 2026-08-15T12:00:00, 07/04/2026 (US order).
    # NOTE: no trailing \b — ISO datetimes continue with 'T', a word char.
    m2 = re.search(r"\b(20\d{2})-(\d{1,2})-(\d{1,2})", text)
    if m2:
        try:
            dt = datetime(int(m2.group(1)), int(m2.group(2)), int(m2.group(3)))
            return dt.strftime("%Y-%m-%d"), m2.group(0)
        except ValueError:
            pass
    m3 = re.search(r"\b(\d{1,2})/(\d{1,2})/(20\d{2})\b", text)
    if m3:
        try:
            dt = datetime(int(m3.group(3)), int(m3.group(1)), int(m3.group(2)))
            return dt.strftime("%Y-%m-%d"), m3.group(0)
        except ValueError:
            pass
    return "", ""


def parse_time(text: str) -> str:
    """Extract start (and optionally end) time from free text."""
    if not text:
        return ""
    matches = TIME_RX.findall(text)
    matches = [clean_text(m).upper().replace(".", "") for m in matches]
    if not matches:
        return ""
    if len(matches) >= 2:
        return f"{matches[0]} - {matches[1]}"
    return matches[0]


def parse_price(text: str) -> str:
    """Extract a price or price range in USD from free text."""
    if not text:
        return ""
    amounts = PRICE_RX.findall(text)
    values: list[float] = []
    for a in amounts:
        try:
            values.append(float(a.replace(",", "")))
        except ValueError:
            continue
    if values:
        lo, hi = min(values), max(values)
        if lo == hi:
            return f"${lo:,.2f}"
        return f"${lo:,.2f} - ${hi:,.2f}"
    if FREE_RX.search(text):
        return "Free"
    return ""


@lru_cache(maxsize=None)
def _category_matcher(kw: str):
    if kw.startswith("re:"):
        return re.compile(kw[3:], re.IGNORECASE).search
    return lambda blob, _kw=kw: _kw in blob


def infer_category(*texts: str) -> str:
    """Earlier texts are more authoritative: callers pass the event NAME first,
    so 'X Festival' stays a festival even when its description name-drops an
    expo, and descriptions/tags only decide when the name says nothing."""
    for text in texts:
        if not text:
            continue
        blob = str(text).lower()
        for category, keywords in CATEGORY_RULES:
            if any(_category_matcher(kw)(blob) for kw in keywords):
                return category
    return DEFAULT_CATEGORY


def location_verdict(*texts: str) -> str:
    """'np' = New Providence, 'other' = another island, 'unknown' otherwise."""
    blob = " ".join(t.lower() for t in texts if t)
    if any(kw in blob for kw in NEW_PROVIDENCE_KEYWORDS):
        return "np"
    if any(kw in blob for kw in OTHER_ISLAND_KEYWORDS):
        return "other"
    return "unknown"


# ---- JSON-LD ----------------------------------------------------------------

def extract_json_ld_events(soup: BeautifulSoup) -> list[dict]:
    """Pull every schema.org Event object out of ld+json blocks (handles
    @graph wrappers, ItemLists, and top-level arrays)."""
    found: list[dict] = []

    def walk(node) -> None:
        if isinstance(node, list):
            for item in node:
                walk(item)
        elif isinstance(node, dict):
            node_type = node.get("@type", "")
            types = node_type if isinstance(node_type, list) else [node_type]
            if any(isinstance(t, str) and t.endswith("Event") for t in types):
                found.append(node)
            for key in ("@graph", "itemListElement", "item", "subEvent", "events"):
                if key in node:
                    walk(node[key])

    for script in soup.find_all("script", type=re.compile(r"application/ld\+json", re.I)):
        raw = script.string or script.get_text() or ""
        raw = raw.strip()
        if not raw:
            continue
        try:
            walk(json.loads(raw))
        except json.JSONDecodeError:
            # Some sites emit multiple concatenated JSON objects or trailing
            # commas; try a lenient line-by-line rescue.
            try:
                walk(json.loads(re.sub(r",\s*([}\]])", r"\1", raw)))
            except json.JSONDecodeError:
                continue
    return found


def event_from_json_ld(node: dict, source_name: str, fallback_url: str) -> Event:
    ev = Event(source_name=source_name)
    ev.name = clean_text(node.get("name"))
    start = node.get("startDate") or node.get("startTime") or ""
    if start:
        ev.date, ev.raw_date = parse_date_iso(str(start))
        try:
            dt = dateparser.parse(str(start))
            if dt and (dt.hour or dt.minute):
                ev.time = dt.strftime("%I:%M %p").lstrip("0")
        except (ValueError, OverflowError):
            pass

    loc = node.get("location")
    if isinstance(loc, list) and loc:
        loc = loc[0]
    if isinstance(loc, dict):
        parts = [clean_text(loc.get("name"))]
        addr = loc.get("address")
        if isinstance(addr, dict):
            parts.append(clean_text(addr.get("streetAddress")))
            parts.append(clean_text(addr.get("addressLocality")))
        elif isinstance(addr, str):
            parts.append(clean_text(addr))
        ev.venue = ", ".join(p for p in parts if p)
    elif isinstance(loc, str):
        ev.venue = clean_text(loc)

    offers = node.get("offers")
    if isinstance(offers, dict):
        offers = [offers]
    if isinstance(offers, list):
        prices = []
        for off in offers:
            if isinstance(off, dict):
                p = off.get("price") or off.get("lowPrice") or off.get("highPrice")
                if p not in (None, ""):
                    prices.append(str(p))
        if prices:
            ev.price = parse_price(" ".join(f"${p}" for p in prices)) or ""

    ev.source_url = clean_text(node.get("url")) or fallback_url
    ev.description = clean_text(node.get("description"))[:600]
    ev.category = infer_category(ev.name, ev.description, str(node.get("@type", "")))
    return ev


# ---- Sitemap harvesting -----------------------------------------------------

def harvest_sitemap_urls(engine: RequestEngine, base_url: str,
                         keyword: str = "event", limit: int = 150) -> list[str]:
    """Fetch /sitemap.xml (and one level of sitemap indexes); return URLs
    whose path contains `keyword`."""
    candidates = ["/sitemap.xml", "/sitemap_index.xml", "/wp-sitemap.xml",
                  "/sitemap-index.xml"]
    urls: list[str] = []
    for path in candidates:
        resp = engine.get(urljoin(base_url, path), quiet=True)
        if resp is None or "<" not in resp.text[:200]:
            continue
        soup = BeautifulSoup(resp.text, "xml")
        locs = [clean_text(loc.get_text()) for loc in soup.find_all("loc")]
        # One level of recursion into child sitemaps
        child_maps = [u for u in locs if u.endswith(".xml")][:8]
        page_urls = [u for u in locs if not u.endswith(".xml")]
        for child in child_maps:
            child_resp = engine.get(child, quiet=True)
            if child_resp is not None:
                child_soup = BeautifulSoup(child_resp.text, "xml")
                page_urls.extend(clean_text(l.get_text())
                                 for l in child_soup.find_all("loc"))
        urls = [u for u in page_urls if keyword in urlparse(u).path.lower()]
        if urls:
            break
    return urls[:limit]


# ---- Recursive JSON event mining (for XHR/API payloads) ---------------------

def mine_event_dicts(obj, depth: int = 0) -> list[dict]:
    """Recursively find dicts that look like event records inside an arbitrary
    JSON payload (used on __SERVER_DATA__ blobs and probed API responses)."""
    results: list[dict] = []
    if depth > 8:
        return results
    if isinstance(obj, dict):
        keys = set(obj.keys())
        if ("name" in keys or "title" in keys or "event_name" in keys) and (
            keys & {"start_date", "startDate", "start_time", "startTime",
                    "date", "start", "event_date", "when"}
        ):
            results.append(obj)
        for v in obj.values():
            results.extend(mine_event_dicts(v, depth + 1))
    elif isinstance(obj, list):
        for item in obj:
            results.extend(mine_event_dicts(item, depth + 1))
    return results


def _fmt_24h(value) -> str:
    """'18:00:00' / '18:00' -> '6:00 PM'; returns '' if not a bare 24h time."""
    m = re.match(r"^(\d{1,2}):(\d{2})(?::\d{2})?$", str(value or "").strip())
    if not m:
        return ""
    hour, minute = int(m.group(1)), int(m.group(2))
    if hour > 23 or minute > 59:
        return ""
    return datetime(2000, 1, 1, hour, minute).strftime("%I:%M %p").lstrip("0")


def event_from_mined_dict(d: dict, source_name: str, base_url: str) -> Event:
    ev = Event(source_name=source_name)
    ev.name = clean_text(d.get("name") or d.get("title") or d.get("event_name"))
    raw_start = (d.get("start_date") or d.get("startDate") or d.get("date")
                 or d.get("event_date") or d.get("start") or d.get("when") or "")
    if isinstance(raw_start, dict):
        raw_start = raw_start.get("date") or raw_start.get("utc") or ""
    ev.date, ev.raw_date = parse_date_iso(str(raw_start))
    raw_time = str(d.get("start_time") or d.get("startTime") or raw_start)
    ev.time = parse_time(raw_time) or _fmt_24h(d.get("start_time"))
    if ev.time and _fmt_24h(d.get("end_time")):
        ev.time = f"{ev.time} - {_fmt_24h(d.get('end_time'))}"
    if not ev.time:
        try:
            dt = dateparser.parse(str(raw_start))
            if dt and (dt.hour or dt.minute):
                ev.time = dt.strftime("%I:%M %p").lstrip("0")
        except (ValueError, OverflowError):
            pass

    venue = d.get("venue") or d.get("location") or d.get("primary_venue") or ""
    if isinstance(venue, dict):
        bits = [clean_text(venue.get("name")),
                clean_text(venue.get("address", {}).get("localized_address_display")
                           if isinstance(venue.get("address"), dict) else venue.get("address"))]
        ev.venue = ", ".join(b for b in bits if b)
    else:
        ev.venue = clean_text(venue)
    # Flat locality fields (Eventmie/Laravel-style records)
    locality = [clean_text(str(d[k])) for k in ("address", "city", "state")
                if d.get(k) and clean_text(str(d[k])).lower() not in
                ev.venue.lower()]
    if locality:
        ev.venue = ", ".join(b for b in ([ev.venue] + locality) if b)

    price_bits = []
    for key in ("price", "ticket_price", "min_price", "max_price", "price_range",
                "ticket_availability"):
        val = d.get(key)
        if isinstance(val, dict):
            for sub in ("minimum_ticket_price", "maximum_ticket_price"):
                sv = val.get(sub)
                if isinstance(sv, dict) and sv.get("major_value"):
                    price_bits.append(f"${sv['major_value']}")
        elif val not in (None, ""):
            price_bits.append(f"${val}" if not str(val).startswith("$") else str(val))
    ev.price = parse_price(" ".join(price_bits))
    if not ev.price and (d.get("is_free") or d.get("isFree")):
        ev.price = "Free"

    url = clean_text(d.get("url") or d.get("link") or d.get("event_url") or "")
    if not url and d.get("slug"):
        url = f"/events/{clean_text(str(d['slug']))}"
    ev.source_url = urljoin(base_url, url) if url else base_url
    ev.description = strip_html(d.get("summary") or d.get("description") or "")[:600]
    ev.category = infer_category(ev.name, ev.description,
                                 str(d.get("category") or d.get("tags") or ""))
    return ev


def events_from_embedded_json(html_text: str, source_name: str,
                              base_url: str) -> list[Event]:
    """
    Extract event records that a server-rendered SPA embeds as JSON inside
    the page (Vue/React component props, often HTML-entity-encoded — e.g.
    Eventmie Pro on ticket-flare.com emits &quot;start_date&quot;:...).

    Approach: unescape entities, then attempt a strict JSON raw_decode at
    every '{"id":' anchor and keep any decoded object that mines as an event.
    """
    text = htmllib.unescape(html_text)
    decoder = json.JSONDecoder()
    events: list[Event] = []
    consumed_until = -1
    for m in re.finditer(r'\{"id"\s*:', text):
        start = m.start()
        if start < consumed_until:
            continue
        try:
            obj, span = decoder.raw_decode(text[start:start + 60000])
        except json.JSONDecodeError:
            continue
        consumed_until = start + span
        for d in mine_event_dicts(obj):
            ev = event_from_mined_dict(d, source_name, base_url)
            if ev.is_plausible():
                events.append(ev)
    return events


# ---- Optional Playwright rendering ------------------------------------------

def render_page_html(url: str, wait_ms: int = 6000) -> Optional[str]:
    """Render a JS-heavy page with Playwright if available; else None."""
    try:
        from playwright.sync_api import sync_playwright  # type: ignore
    except ImportError:
        log.info("Playwright not installed — skipping JS render for %s "
                 "(pip install playwright && playwright install chromium)", url)
        return None
    try:
        with sync_playwright() as p:
            browser = p.chromium.launch(headless=True)
            page = browser.new_page(user_agent=random.choice(USER_AGENTS))
            page.goto(url, timeout=45000, wait_until="domcontentloaded")
            page.wait_for_timeout(wait_ms)
            # Nudge lazy/infinite-scroll content
            for _ in range(4):
                page.mouse.wheel(0, 2500)
                page.wait_for_timeout(1200)
            html = page.content()
            browser.close()
            return html
    except Exception as exc:  # noqa: BLE001 — isolate render failures
        log.warning("Playwright render failed for %s: %s", url, exc)
        return None


# =============================================================================
# SCRAPERS
# =============================================================================

class BaseScraper:
    """Contract: subclasses implement scrape() -> list[Event]. run() provides
    total error isolation and a status record for the summary sheet."""

    name = "base"
    enabled_by_default = True

    def __init__(self, engine: RequestEngine, max_pages: int = 6,
                 fetch_details: bool = True, detail_cap: int = 40,
                 allow_js: bool = False, market: "Optional[Market]" = None,
                 params: Optional[dict] = None):
        self.engine = engine
        self.max_pages = max_pages
        self.fetch_details = fetch_details
        self.detail_cap = detail_cap
        self.allow_js = allow_js
        self.market = market or DEFAULT_MARKET
        self.params = params or {}
        self.status = SourceStatus(name=self.name)

    def scrape(self) -> list[Event]:  # pragma: no cover - abstract
        raise NotImplementedError

    def run(self) -> list[Event]:
        log.info("=== [%s] starting ===", self.name)
        try:
            events = [e for e in self.scrape() if e.is_plausible()]
            self.status.ok = True
            self.status.events_found = len(events)
            log.info("=== [%s] finished: %d event(s) ===", self.name, len(events))
            return events
        except Exception as exc:  # noqa: BLE001 — hard isolation by design
            self.status.ok = False
            self.status.note = f"{type(exc).__name__}: {exc}"
            log.error("=== [%s] FAILED (isolated): %s ===", self.name, exc,
                      exc_info=log.isEnabledFor(logging.DEBUG))
            return []

    # -- shared helpers -------------------------------------------------------

    def enrich_from_detail_pages(self, events: list[Event]) -> None:
        """Visit up to detail_cap event pages, upgrading records with JSON-LD
        or page text (dates, venues, prices, descriptions)."""
        if not self.fetch_details:
            return
        visited = 0
        for ev in events:
            if visited >= self.detail_cap:
                break
            if not ev.source_url or (ev.date and ev.venue and ev.price and ev.description):
                continue
            soup = self.engine.soup(ev.source_url)
            visited += 1
            if soup is None:
                continue
            ld_events = extract_json_ld_events(soup)
            if ld_events:
                rich = event_from_json_ld(ld_events[0], self.name, ev.source_url)
                ev.date = ev.date or rich.date
                ev.raw_date = ev.raw_date or rich.raw_date
                ev.time = ev.time or rich.time
                ev.venue = ev.venue or rich.venue
                ev.price = ev.price or rich.price
                ev.description = ev.description or rich.description
                continue
            page_text = clean_text(soup.get_text(" "))[:4000]
            if not ev.date:
                ev.date, ev.raw_date = parse_date_iso(page_text)
            ev.time = ev.time or parse_time(page_text)
            ev.price = ev.price or parse_price(page_text)
            if not ev.description:
                meta = soup.find("meta", attrs={"name": "description"})
                if meta and meta.get("content"):
                    ev.description = clean_text(meta["content"])[:600]

    def parse_listing_cards(self, soup: BeautifulSoup, base_url: str,
                            href_marker: str) -> list[Event]:
        """Generic card parser: find anchors whose href contains href_marker,
        climb to the card container, and regex the card text for fields.
        Works across most server-rendered listing layouts."""
        events: list[Event] = []
        seen_urls: set[str] = set()
        for a in soup.find_all("a", href=True):
            href = a["href"]
            if href_marker not in href:
                continue
            full_url = urljoin(base_url, href.strip().split("#")[0])
            if full_url in seen_urls or full_url.rstrip("/") == base_url.rstrip("/"):
                continue
            # Climb to a reasonable card container for context text
            card = a
            for _ in range(4):
                if card.parent is None:
                    break
                card = card.parent
                text = clean_text(card.get_text(" "))
                if len(text) > 40:
                    break
            card_text = clean_text(card.get_text(" "))[:800]
            title = clean_text(a.get_text(" "))
            if not title or len(title) < 4:
                heading = card.find(re.compile(r"^h[1-6]$"))
                title = clean_text(heading.get_text(" ")) if heading else ""
            if not title or len(title) < 4:
                continue
            seen_urls.add(full_url)
            date_iso, raw = parse_date_iso(card_text)
            ev = Event(
                name=title[:200],
                date=date_iso,
                raw_date=raw,
                time=parse_time(card_text),
                price=parse_price(card_text),
                source_url=full_url,
                source_name=self.name,
            )
            ev.category = infer_category(ev.name, card_text)
            events.append(ev)
        return events


# -----------------------------------------------------------------------------
# 1. TICKET FLARE  (custom PHP ticketing platform, server-rendered)
# -----------------------------------------------------------------------------

class TicketFlareScraper(BaseScraper):
    """Eventmie Pro (Laravel + Vue). The listing HTML embeds the full event
    records as entity-encoded JSON in the Vue component props — richest
    strategy first, then JSON-LD, then generic cards."""

    name = "ticket-flare"
    BASE = "https://ticket-flare.com"

    def scrape(self) -> list[Event]:
        events: list[Event] = []
        for page in range(1, self.max_pages + 1):
            url = f"{self.BASE}/events" if page == 1 else f"{self.BASE}/events?page={page}"
            resp = self.engine.get(url, referer=self.BASE)
            if resp is None:
                break
            self.status.pages_fetched += 1
            # Strategy A: embedded Vue-prop JSON (full Eventmie records)
            page_events = events_from_embedded_json(resp.text, self.name, self.BASE)
            if not page_events:
                soup = make_soup(resp.text)
                page_events = [event_from_json_ld(n, self.name, url)
                               for n in extract_json_ld_events(soup)]
                if not page_events:
                    page_events = self.parse_listing_cards(soup, self.BASE, "/events/")
            if not page_events:
                break
            before = len({e.source_url for e in events})
            events.extend(page_events)
            if len({e.source_url for e in events}) == before:
                break   # pagination exhausted / repeating page
        events = dedupe_by_url(events)
        self.enrich_from_detail_pages(events)
        for ev in events:
            if not ev.venue:
                ev.venue = "Nassau, Bahamas"   # platform is Nassau-centric
        return events


# -----------------------------------------------------------------------------
# 2. ETICKETS LIVE  (WordPress events plugin, server-rendered)
# -----------------------------------------------------------------------------

class ETicketsLiveScraper(BaseScraper):
    name = "eticketslive"
    BASE = "https://eticketslive.com"
    LISTING = "https://eticketslive.com/event_loc/bahamas/"

    def scrape(self) -> list[Event]:
        events: list[Event] = []
        for page in range(1, self.max_pages + 1):
            url = self.LISTING if page == 1 else f"{self.LISTING}page/{page}/"
            soup = self.engine.soup(url, referer=self.BASE)
            if soup is None:
                break
            self.status.pages_fetched += 1
            page_events = self.parse_listing_cards(soup, self.BASE, "/event/")
            ld = [event_from_json_ld(n, self.name, url)
                  for n in extract_json_ld_events(soup)]
            page_events.extend(ld)
            if not page_events:
                break
            before = len({e.source_url for e in events})
            events.extend(page_events)
            if len({e.source_url for e in events}) == before:
                break
        events = dedupe_by_url(events)
        self.enrich_from_detail_pages(events)
        for ev in events:
            if not ev.venue:
                ev.venue = "Bahamas"
        return events


# -----------------------------------------------------------------------------
# 3. BAHAEVENTS  (JavaScript SPA — layered fallback strategy)
# -----------------------------------------------------------------------------

class BahaEventsScraper(BaseScraper):
    name = "bahaevents"
    BASE = "https://bahaevents.com"
    API_PROBES = [
        "/api/events", "/api/v1/events", "/api/events?limit=100",
        "/events.json", "/api/event/list", "/_next/data/events.json",
        "/wp-json/tribe/events/v1/events?per_page=50",
        "/wp-json/wp/v2/tribe_events?per_page=50",
    ]

    def scrape(self) -> list[Event]:
        events: list[Event] = []

        # Layer 1: probe likely JSON/XHR endpoints (cheap, no JS needed)
        for probe in self.API_PROBES:
            payload = self.engine.get(urljoin(self.BASE, probe),
                                      as_json=True, quiet=True)
            if payload:
                mined = mine_event_dicts(payload)
                if mined:
                    log.info("[%s] JSON endpoint hit: %s (%d records)",
                             self.name, probe, len(mined))
                    events = [event_from_mined_dict(d, self.name, self.BASE)
                              for d in mined]
                    break

        # Layer 2: sitemap harvesting -> JSON-LD on detail pages
        if not events:
            urls = harvest_sitemap_urls(self.engine, self.BASE, "event",
                                        limit=self.detail_cap)
            log.info("[%s] sitemap harvest found %d event URL(s)", self.name, len(urls))
            for u in urls:
                soup = self.engine.soup(u)
                if soup is None:
                    continue
                for node in extract_json_ld_events(soup):
                    events.append(event_from_json_ld(node, self.name, u))

        # Layer 3: optional Playwright render of the hydrated SPA
        if not events and self.allow_js:
            html = render_page_html(self.BASE + "/events") or render_page_html(self.BASE)
            if html:
                soup = make_soup(html)
                events = [event_from_json_ld(n, self.name, self.BASE)
                          for n in extract_json_ld_events(soup)]
                if not events:
                    events = self.parse_listing_cards(soup, self.BASE, "event")

        if not events:
            self.status.note = ("No events found (JSON probes, sitemap, and "
                                "JS render all empty)." if self.allow_js else
                                "JS-rendered SPA: install Playwright or pass "
                                "--js to enable browser rendering.")
        return dedupe_by_url(events)


# -----------------------------------------------------------------------------
# 4. BID BAHAMAS  (marketplace events vertical — layered fallback strategy)
# -----------------------------------------------------------------------------

class BidBahamasScraper(BaseScraper):
    name = "bid-bahamas"
    BASE = "https://bid-bahamas.com"
    API_PROBES = [
        "/api/events", "/api/v1/events", "/events.json",
        "/api/listings?category=events", "/api/listings?type=event",
        "/wp-json/tribe/events/v1/events?per_page=50",
    ]

    def scrape(self) -> list[Event]:
        events: list[Event] = []

        # Layer 0: the /events page may still be server-rendered in part
        soup = self.engine.soup(f"{self.BASE}/events")
        if soup is not None:
            self.status.pages_fetched += 1
            events = [event_from_json_ld(n, self.name, f"{self.BASE}/events")
                      for n in extract_json_ld_events(soup)]
            if not events:
                events = self.parse_listing_cards(soup, self.BASE, "/event")

        # Layer 1: JSON endpoint probes
        if not events:
            for probe in self.API_PROBES:
                payload = self.engine.get(urljoin(self.BASE, probe),
                                          as_json=True, quiet=True)
                if payload:
                    mined = mine_event_dicts(payload)
                    if mined:
                        log.info("[%s] JSON endpoint hit: %s (%d records)",
                                 self.name, probe, len(mined))
                        events = [event_from_mined_dict(d, self.name, self.BASE)
                                  for d in mined]
                        break

        # Layer 2: sitemap harvesting
        if not events:
            urls = harvest_sitemap_urls(self.engine, self.BASE, "event",
                                        limit=self.detail_cap)
            for u in urls:
                s = self.engine.soup(u)
                if s is None:
                    continue
                for node in extract_json_ld_events(s):
                    events.append(event_from_json_ld(node, self.name, u))

        # Layer 3: optional Playwright render
        if not events and self.allow_js:
            html = render_page_html(f"{self.BASE}/events")
            if html:
                s = make_soup(html)
                events = [event_from_json_ld(n, self.name, f"{self.BASE}/events")
                          for n in extract_json_ld_events(s)]
                if not events:
                    events = self.parse_listing_cards(s, self.BASE, "/event")

        if not events:
            self.status.note = ("JS-rendered marketplace: no JSON endpoint or "
                                "sitemap events found. Re-run with --js after "
                                "installing Playwright.")
        return dedupe_by_url(events)


# -----------------------------------------------------------------------------
# 5. EVENTBRITE  (embedded __SERVER_DATA__ JSON == their internal search XHR)
# -----------------------------------------------------------------------------

class EventbriteScraper(BaseScraper):
    name = "eventbrite"
    BASE = "https://www.eventbrite.com"
    # Both regional slugs resolve; the first is what their nav links to.
    LISTINGS = [
        "https://www.eventbrite.com/d/bahamas/events/",
        "https://www.eventbrite.com/d/the-bahamas/all-events/",
    ]
    # Category listings surface conferences/expos/fairs that sit too deep in
    # the general feed to reach within max_pages. Single page each, optional.
    EXTRA_LISTINGS = [
        "https://www.eventbrite.com/d/bahamas/business--events/",
        "https://www.eventbrite.com/d/bahamas/conferences/",
    ]

    def scrape(self) -> list[Event]:
        events: list[Event] = []

        # Per-market listing: markets/<id>.json gives the Eventbrite location
        # slug ("fl--miami", "jamaica--kingston"); the Bahamas defaults stay.
        slug = str(self.params.get("slug", "")).strip("/")
        if slug:
            self.LISTINGS = [f"{self.BASE}/d/{slug}/events/",
                             f"{self.BASE}/d/{slug}/all-events/"]
            self.EXTRA_LISTINGS = [f"{self.BASE}/d/{slug}/business--events/"]

        # Optional: official API when a private token is provided.
        token = os.environ.get("EVENTBRITE_API_TOKEN", "").strip()
        if token:
            events.extend(self._scrape_via_api(token))

        listing = None
        for candidate in self.LISTINGS:
            html = self._fetch_listing_html(candidate)
            if html is not None:
                listing = candidate
                events.extend(self._parse_listing_response(html, candidate))
                self.status.pages_fetched += 1
                break

        if listing:
            for page in range(2, self.max_pages + 1):
                html = self._fetch_listing_html(f"{listing}?page={page}", referer=listing)
                if html is None:
                    break
                page_events = self._parse_listing_response(html, f"{listing}?page={page}")
                if not page_events:
                    break
                before = len({e.source_url for e in events})
                events.extend(page_events)
                self.status.pages_fetched += 1
                if len({e.source_url for e in events}) == before:
                    break

        for extra in self.EXTRA_LISTINGS:
            html = self._fetch_listing_html(extra, referer=self.BASE)
            if html is None:
                continue
            events.extend(self._parse_listing_response(html, extra))
            self.status.pages_fetched += 1

        return dedupe_by_url(events)

    def _fetch_listing_html(self, url: str, referer: Optional[str] = None) -> Optional[str]:
        """Plain GET first; Eventbrite answers datacenter IPs (GitHub runners)
        with 405, so fall back to a rendered browser session, which is what
        already gets Bandsintown through Cloudflare from CI. The rendered DOM
        still carries window.__SERVER_DATA__, so parsing is unchanged."""
        resp = self.engine.get(url, referer=referer)
        if resp is not None:
            return resp.text
        if not self.allow_js:
            return None
        html = render_page_html(url, wait_ms=4000)
        if html and "__SERVER_DATA__" in html:
            self.status.note = (self.status.note or "rendered via Playwright")
            return html
        return None

    def _parse_listing_response(self, html: str, url: str) -> list[Event]:
        events: list[Event] = []

        # Strategy A: window.__SERVER_DATA__ = {...};  (internal search payload)
        marker = "window.__SERVER_DATA__"
        idx = html.find(marker)
        if idx != -1:
            brace = html.find("{", idx)
            if brace != -1:
                try:
                    payload, _ = json.JSONDecoder().raw_decode(html[brace:])
                    mined = mine_event_dicts(payload)
                    for d in mined:
                        ev = event_from_mined_dict(d, self.name, self.BASE)
                        if ev.is_plausible():
                            events.append(ev)
                    if events:
                        log.info("[%s] __SERVER_DATA__ yielded %d event(s) on %s",
                                 self.name, len(events), url)
                        return events
                except (json.JSONDecodeError, ValueError):
                    log.debug("[%s] __SERVER_DATA__ decode failed on %s",
                              self.name, url)

        # Strategy B: JSON-LD blocks
        soup = make_soup(html)
        for node in extract_json_ld_events(soup):
            events.append(event_from_json_ld(node, self.name, url))
        if events:
            return events

        # Strategy C: plain HTML cards
        return self.parse_listing_cards(soup, self.BASE, "/e/")

    def _scrape_via_api(self, token: str) -> list[Event]:
        """Official API. Note: the public /events/search/ endpoint was retired;
        with a personal token you can still pull your organizations' events.
        Kept as an opt-in enrichment path."""
        out: list[Event] = []
        payload = self.engine.get(
            f"{self.BASE.replace('www', 'www')}/api/v3/users/me/events/"
            f"?token={token}&expand=venue,ticket_availability",
            as_json=True, quiet=True)
        if isinstance(payload, dict):
            for d in mine_event_dicts(payload):
                ev = event_from_mined_dict(d, self.name, self.BASE)
                if ev.is_plausible():
                    out.append(ev)
        return out


# -----------------------------------------------------------------------------
# 6. ALLEVENTS.IN  (aggregator, strong JSON-LD coverage)  [bonus source]
# -----------------------------------------------------------------------------

class AllEventsInScraper(BaseScraper):
    name = "allevents.in"
    BASE = "https://allevents.in"
    LISTINGS = [
        "https://allevents.in/nassau",
        "https://allevents.in/nassau/all",
        "https://allevents.in/nassau/parties",
        "https://allevents.in/nassau/concerts",
        "https://allevents.in/nassau/festivals",
        "https://allevents.in/nassau/food-drinks",
        "https://allevents.in/nassau/workshops",
        "https://allevents.in/nassau/sports",
        "https://allevents.in/nassau/art",
        "https://allevents.in/nassau/business",
        # Conference / fair / popup coverage — unknown slugs 404 harmlessly
        # (soup=None -> skipped) under the per-source error isolation.
        "https://allevents.in/nassau/conferences",
        "https://allevents.in/nassau/exhibitions",
        "https://allevents.in/nassau/trade-shows",
    ]

    def scrape(self) -> list[Event]:
        events: list[Event] = []
        for url in self.LISTINGS:
            soup = self.engine.soup(url, referer=self.BASE)
            if soup is None:
                continue
            self.status.pages_fetched += 1
            for node in extract_json_ld_events(soup):
                events.append(event_from_json_ld(node, self.name, url))
            if not events:
                events.extend(self.parse_listing_cards(soup, self.BASE, "allevents.in/"))
        for ev in events:
            if not ev.venue:
                ev.venue = "Nassau, Bahamas"
        return dedupe_by_url(events)


# -----------------------------------------------------------------------------
# 7. BAHAMASLOCAL  (directory events section)  [bonus source]
# -----------------------------------------------------------------------------

class BahamasLocalScraper(BaseScraper):
    name = "bahamaslocal"
    BASE = "https://www.bahamaslocal.com"
    LISTINGS = [
        "https://www.bahamaslocal.com/events.html",
        "https://www.bahamaslocal.com/eventsbrowse/all/upcoming/1.html",
    ]

    def scrape(self) -> list[Event]:
        events: list[Event] = []
        for url in self.LISTINGS:
            soup = self.engine.soup(url, referer=self.BASE)
            if soup is None:
                continue
            self.status.pages_fetched += 1
            found = [event_from_json_ld(n, self.name, url)
                     for n in extract_json_ld_events(soup)]
            if not found:
                found = self.parse_listing_cards(soup, self.BASE, "/event")
            events.extend(found)
            if found:
                break
        events = dedupe_by_url(events)
        self.enrich_from_detail_pages(events)
        return events


# -----------------------------------------------------------------------------
# 8. BANDSINTOWN  (Cloudflare-guarded; Playwright renders the Nassau city page
#    + a Caribbean-artist watchlist. JSON-LD MusicEvent markup throughout.)
# -----------------------------------------------------------------------------

class BandsintownScraper(BaseScraper):
    name = "bandsintown"
    CITY_URL = "https://www.bandsintown.com/c/nassau-bahamas"
    # Artists who tour the Caribbean circuit; their pages surface Bahamas
    # dates (often before local ticketing platforms list them).
    ARTIST_WATCHLIST = [
        "https://www.bandsintown.com/a/20663-sizzla",
        "https://www.bandsintown.com/a/8788-capleton",
        "https://www.bandsintown.com/a/19495",          # Anthony B
        "https://www.bandsintown.com/a/1035-buju-banton",
        "https://www.bandsintown.com/a/6404-beres-hammond",
        "https://www.bandsintown.com/a/64937-machel-montano",
        "https://www.bandsintown.com/a/512150-popcaan",
        "https://www.bandsintown.com/a/12554337-shenseea",
    ]

    def scrape(self) -> list[Event]:
        if not HAVE_PLAYWRIGHT:
            self.status.note = ("Playwright not installed — Bandsintown is "
                                "Cloudflare-guarded and needs a real browser.")
            return []
        events: list[Event] = []

        # City page: everything geo-tagged to Nassau
        html = render_page_html(self.CITY_URL)
        if html:
            self.status.pages_fetched += 1
            for node in extract_json_ld_events(make_soup(html)):
                events.append(event_from_json_ld(node, self.name, self.CITY_URL))

        # Artist watchlist: keep only Bahamas dates
        for artist_url in self.ARTIST_WATCHLIST:
            html = render_page_html(artist_url, wait_ms=4000)
            if not html:
                continue
            self.status.pages_fetched += 1
            for node in extract_json_ld_events(make_soup(html)):
                ev = event_from_json_ld(node, self.name, artist_url)
                blob = f"{ev.venue} {ev.name}".lower()
                if "bahamas" in blob or "nassau" in blob:
                    events.append(ev)
        return dedupe_by_url(events)


# -----------------------------------------------------------------------------
# 9. SONGKICK  (Nassau metro area; server-delivered event markup)
# -----------------------------------------------------------------------------

class SongkickScraper(BaseScraper):
    name = "songkick"
    BASE = "https://www.songkick.com"
    METRO = "https://www.songkick.com/metro-areas/27337-bahamas-nassau"

    def scrape(self) -> list[Event]:
        events: list[Event] = []
        for page in range(1, self.max_pages + 1):
            url = self.METRO if page == 1 else f"{self.METRO}?page={page}"
            soup = self.engine.soup(url, referer=self.BASE)
            if soup is None:
                break
            self.status.pages_fetched += 1
            found = [event_from_json_ld(n, self.name, url)
                     for n in extract_json_ld_events(soup)]
            if not found:
                found = self.parse_listing_cards(soup, self.BASE, "/concerts/")
            if not found:
                break
            before = len({e.source_url for e in events})
            events.extend(found)
            if len({e.source_url for e in events}) == before:
                break
        for ev in events:
            if not ev.venue:
                ev.venue = "Nassau, Bahamas"
        return dedupe_by_url(events)


# -----------------------------------------------------------------------------
# 10. REGGAEVILLE  (reggae-scene tour tracker; plain requests work)
# -----------------------------------------------------------------------------

class ReggaevilleScraper(BaseScraper):
    name = "reggaeville"
    BASE = "https://www.reggaeville.com"
    LISTINGS = [
        "https://www.reggaeville.com/dates/",
        "https://www.reggaeville.com/dates/festivals/",
    ]

    def scrape(self) -> list[Event]:
        events: list[Event] = []
        for url in self.LISTINGS:
            soup = self.engine.soup(url, referer=self.BASE)
            if soup is None:
                continue
            self.status.pages_fetched += 1
            found = [event_from_json_ld(n, self.name, url)
                     for n in extract_json_ld_events(soup)]
            if not found:
                found = self.parse_listing_cards(soup, self.BASE, "/dates/")
            # Reggaeville is worldwide — keep only Bahamas dates
            for ev in found:
                blob = f"{ev.venue} {ev.name} {ev.description}".lower()
                if "bahamas" in blob or "nassau" in blob:
                    events.append(ev)
        return dedupe_by_url(events)


# -----------------------------------------------------------------------------
# 11. MANUAL EVENTS  (curated hand-entries: word-of-mouth / social-media-only
#     promotions that no indexable site lists yet. Edit manual_events.json.)
# -----------------------------------------------------------------------------

# -----------------------------------------------------------------------------
# GLOBAL API SOURCES (official, keyed; skip cleanly when the key is absent)
# -----------------------------------------------------------------------------

def _api_category(segment: str, genre: str, fallback_text: str) -> str:
    """Map Ticketmaster/SeatGeek taxonomy onto the 0 FOMO categories; keyword
    inference wins when it finds something specific (comedy, festival)."""
    inferred = infer_category(fallback_text)
    if inferred != DEFAULT_CATEGORY:
        return inferred
    seg, gen = segment.lower(), genre.lower()
    if "comedy" in gen or "comedy" in seg:
        return "Comedy"
    if "music" in seg or "concert" in seg:
        return "Festival" if "festival" in gen else "Concert / Live Music"
    if "sport" in seg:
        return "Sports & Fitness"
    if "theat" in seg or "arts" in seg or "dance" in gen or "opera" in gen:
        return "Arts & Theatre"
    if "family" in seg or "fair" in gen:
        return "Fair / Popup"
    return DEFAULT_CATEGORY


class TicketmasterScraper(BaseScraper):
    """Ticketmaster Discovery API v2 — free key, geo search, US/CA/UK/IE/EU/
    AU/NZ/MX coverage. Env TICKETMASTER_API_KEY; 5,000 calls/day default."""
    name = "ticketmaster"
    URL = "https://app.ticketmaster.com/discovery/v2/events.json"

    def scrape(self) -> list[Event]:
        key = os.environ.get("TICKETMASTER_API_KEY", "").strip()
        if not key:
            self.status.note = "skipped: TICKETMASTER_API_KEY not set"
            return []
        events: list[Event] = []
        radius = int(self.params.get("radius_km", self.market.radius_km))
        page, size = 0, 200
        while page < max(self.max_pages, 5):
            resp = self.engine.get(self.URL, params={
                "apikey": key,
                "latlong": f"{self.market.lat},{self.market.lng}",
                "radius": radius, "unit": "km",
                "size": size, "page": page, "sort": "date,asc",
                "startDateTime": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
            })
            if resp is None:
                break
            self.status.pages_fetched += 1
            data = resp.json()
            items = (data.get("_embedded") or {}).get("events") or []
            for it in items:
                ev = self._to_event(it)
                if ev is not None:
                    events.append(ev)
            total_pages = int((data.get("page") or {}).get("totalPages") or 0)
            page += 1
            if page >= total_pages or not items:
                break
        return events

    def _to_event(self, it: dict) -> Optional[Event]:
        start = (it.get("dates") or {}).get("start") or {}
        date = str(start.get("localDate") or "")
        t = str(start.get("localTime") or "")
        venue = ((it.get("_embedded") or {}).get("venues") or [{}])[0]
        venue_name = clean_text(venue.get("name"))
        city = clean_text((venue.get("city") or {}).get("name"))
        loc = venue.get("location") or {}
        price = ""
        pr = (it.get("priceRanges") or [{}])[0]
        if pr.get("min") is not None:
            lo, hi = float(pr["min"]), float(pr.get("max") or pr["min"])
            price = f"${lo:.2f}" if hi <= lo else f"${lo:.2f} - ${hi:.2f}"
        cls = (it.get("classifications") or [{}])[0]
        seg = str((cls.get("segment") or {}).get("name") or "")
        gen = str((cls.get("genre") or {}).get("name") or "")
        name = clean_text(it.get("name"))
        desc = clean_text(it.get("info") or it.get("pleaseNote") or "")
        ev = Event(
            name=name[:200], date=date, raw_date=date,
            time=_fmt_24h(t) if t else "",
            venue=", ".join(x for x in (venue_name, city) if x),
            price=price,
            category=_api_category(seg, gen, f"{name} {gen} {desc}"),
            source_url=str(it.get("url") or ""),
            description=desc[:600],
            source_name=self.name,
            lat=float(loc["latitude"]) if loc.get("latitude") else None,
            lng=float(loc["longitude"]) if loc.get("longitude") else None,
        )
        return ev if ev.is_plausible() else None


class SeatGeekScraper(BaseScraper):
    """SeatGeek Platform API — free client id, geo search, US/CA/UK.
    Env SEATGEEK_CLIENT_ID (+ optional SEATGEEK_CLIENT_SECRET)."""
    name = "seatgeek"
    URL = "https://api.seatgeek.com/2/events"
    TYPE_CATEGORY = {
        "concert": "Concert / Live Music", "music_festival": "Festival",
        "comedy": "Comedy", "theater": "Arts & Theatre", "dance_performance_tour":
        "Arts & Theatre", "classical": "Arts & Theatre", "family": "Fair / Popup",
    }

    def scrape(self) -> list[Event]:
        cid = os.environ.get("SEATGEEK_CLIENT_ID", "").strip()
        if not cid:
            self.status.note = "skipped: SEATGEEK_CLIENT_ID not set"
            return []
        events: list[Event] = []
        radius = int(self.params.get("radius_km", self.market.radius_km))
        page, per_page = 1, 100
        while page <= max(self.max_pages, 5):
            params = {
                "client_id": cid, "lat": self.market.lat, "lon": self.market.lng,
                "range": f"{radius}km", "per_page": per_page, "page": page,
                "sort": "datetime_local.asc",
            }
            secret = os.environ.get("SEATGEEK_CLIENT_SECRET", "").strip()
            if secret:
                params["client_secret"] = secret
            resp = self.engine.get(self.URL, params=params)
            if resp is None:
                break
            self.status.pages_fetched += 1
            data = resp.json()
            items = data.get("events") or []
            for it in items:
                ev = self._to_event(it)
                if ev is not None:
                    events.append(ev)
            total = int((data.get("meta") or {}).get("total") or 0)
            if page * per_page >= total or not items:
                break
            page += 1
        return events

    def _to_event(self, it: dict) -> Optional[Event]:
        dt = str(it.get("datetime_local") or "")
        date, t = (dt.split("T") + [""])[:2] if dt else ("", "")
        if it.get("time_tbd"):
            t = ""
        venue = it.get("venue") or {}
        loc = venue.get("location") or {}
        stats = it.get("stats") or {}
        price = ""
        if stats.get("lowest_price"):
            lo = float(stats["lowest_price"]); hi = float(stats.get("highest_price") or lo)
            price = f"${lo:.2f}" if hi <= lo else f"${lo:.2f} - ${hi:.2f}"
        name = clean_text(it.get("title"))
        typ = str(it.get("type") or "")
        seg = "sports" if typ not in self.TYPE_CATEGORY and typ else ""
        cat = self.TYPE_CATEGORY.get(typ) or _api_category(seg, typ, name)
        ev = Event(
            name=name[:200], date=date, raw_date=dt,
            time=_fmt_24h(t[:5]) if t else "",
            venue=", ".join(x for x in (clean_text(venue.get("name")),
                                        clean_text(venue.get("city"))) if x),
            price=price, category=cat,
            source_url=str(it.get("url") or ""),
            description=clean_text(it.get("description") or "")[:600],
            source_name=self.name,
            lat=float(loc["lat"]) if loc.get("lat") is not None else None,
            lng=float(loc["lon"]) if loc.get("lon") is not None else None,
        )
        return ev if ev.is_plausible() else None


class ManualEventsScraper(BaseScraper):
    name = "manual"
    FILENAME = "manual_events.json"

    def scrape(self) -> list[Event]:
        path = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                            self.FILENAME)
        if not os.path.exists(path):
            self.status.note = f"{self.FILENAME} not found — nothing curated."
            return []
        with open(path, encoding="utf-8") as fh:
            entries = json.load(fh)
        events: list[Event] = []
        for d in entries:
            date_iso, raw = parse_date_iso(str(d.get("date", "")))
            ev = Event(
                name=clean_text(d.get("name")),
                date=date_iso or str(d.get("date", "")),
                raw_date=raw or str(d.get("date", "")),
                time=clean_text(d.get("time")),
                venue=clean_text(d.get("venue")),
                price=clean_text(d.get("price")),
                category=clean_text(d.get("category"))
                    or infer_category(str(d.get("name")), str(d.get("description"))),
                source_url=clean_text(d.get("source_url"))
                    or f"manual://{normalize_title(str(d.get('name', ''))).replace(' ', '-')}",
                description=clean_text(d.get("description")),
                source_name=self.name,
            )
            if ev.is_plausible():
                events.append(ev)
        return events


# =============================================================================
# DEDUPLICATION
# =============================================================================

def dedupe_by_url(events: list[Event]) -> list[Event]:
    """Cheap intra-source dedup on canonical URL, keeping the richest record."""
    best: dict[str, Event] = {}
    for ev in events:
        key = ev.source_url.rstrip("/").lower() or ev.name.lower()
        if key not in best or ev.completeness() > best[key].completeness():
            best[key] = ev
    return list(best.values())


def normalize_title(title: str) -> str:
    text = title.lower()
    text = text.translate(str.maketrans("", "", string.punctuation))
    tokens = [t for t in text.split() if t not in TITLE_NOISE_WORDS]
    return " ".join(sorted(tokens))  # token-sort => word order irrelevant


def title_similarity(a: str, b: str) -> float:
    if not a or not b:
        return 0.0
    if HAVE_RAPIDFUZZ:
        return _rapidfuzz.token_set_ratio(a, b) / 100.0
    return SequenceMatcher(None, a, b).ratio()


class Deduplicator:
    """
    Cross-source dedup: promoters cross-list the same party on Ticket Flare,
    ETickets Live AND Eventbrite. Two records melt into one master record when:

        same date (block key)  AND  normalized-title similarity >= threshold
        (records with no date compare globally at a stricter threshold)

    Merge policy: field-by-field, richest wins (longest description, any
    non-empty date/time/venue/price); all contributing sources are retained.
    """

    def __init__(self, threshold: float = SIMILARITY_THRESHOLD):
        self.threshold = threshold
        self.merged_away = 0

    def dedupe(self, events: list[Event]) -> list[tuple[Event, list[str], int]]:
        # Sort richest-first so cluster representatives start strong
        ordered = sorted(events, key=lambda e: e.completeness(), reverse=True)
        clusters: list[dict] = []   # {event, norm, sources, count}

        for ev in ordered:
            norm = normalize_title(ev.name)
            target = None
            for cl in clusters:
                rep: Event = cl["event"]
                if ev.date and rep.date:
                    if ev.date != rep.date:
                        continue
                    threshold = self.threshold
                else:
                    threshold = 0.92  # stricter when either date is unknown
                if title_similarity(norm, cl["norm"]) >= threshold:
                    target = cl
                    break
            if target is None:
                clusters.append({
                    "event": ev,
                    "norm": norm,
                    "sources": [f"{ev.source_name}: {ev.source_url}"],
                    "count": 1,
                })
            else:
                self._merge(target["event"], ev)
                target["sources"].append(f"{ev.source_name}: {ev.source_url}")
                target["count"] += 1
                self.merged_away += 1

        return [(c["event"], c["sources"], c["count"]) for c in clusters]

    @staticmethod
    def _merge(master: Event, other: Event) -> None:
        master.date = master.date or other.date
        master.raw_date = master.raw_date or other.raw_date
        master.time = master.time or other.time
        master.venue = master.venue or other.venue
        master.price = master.price or other.price
        if len(other.description) > len(master.description):
            master.description = other.description
        if master.category == DEFAULT_CATEGORY and other.category != DEFAULT_CATEGORY:
            master.category = other.category
        if len(other.name) > len(master.name):
            master.name = other.name
        if master.lat is None and other.lat is not None:
            master.lat, master.lng = other.lat, other.lng


# =============================================================================
# TRANSFORMATION + EXPORT
# =============================================================================

MASTER_COLUMNS = ["Event Name", "Date", "Time", "Venue/Location",
                  "Ticket Price (USD)", "Category", "Source URL", "Description"]


class TransformEngine:
    def __init__(self, horizon_days: int, strict_island_filter: bool,
                 market: Optional[Market] = None):
        self.window_start = datetime.now().strftime("%Y-%m-%d")
        self.window_end = (datetime.now()
                           + pd.Timedelta(days=horizon_days)).strftime("%Y-%m-%d")
        self.strict_island_filter = strict_island_filter
        self.market = market or DEFAULT_MARKET

    def build_frames(
        self, clusters: list[tuple[Event, list[str], int]]
    ) -> tuple[pd.DataFrame, pd.DataFrame]:
        """Returns (master_2026_df, needs_review_df)."""
        master_rows, review_rows = [], []
        for ev, sources, count in clusters:
            # The New Providence verdict only makes sense for the Bahamas
            # market; API-sourced markets are geo-bounded at the source.
            verdict = (location_verdict(ev.venue, ev.description, ev.name)
                       if self.market.island_filter else "np")
            row = {
                "Event Name": ev.name,
                "Date": ev.date,
                "Time": ev.time,
                "Venue/Location": ev.venue,
                "Ticket Price (USD)": ev.price,
                "Category": ev.category or DEFAULT_CATEGORY,
                "Source URL": ev.source_url,
                "Description": ev.description,
                "All Sources": " | ".join(sources),
                "Listings Merged": count,
                "Island Match": ({"np": "New Providence", "other": "Other Island",
                                  "unknown": "Unverified"}[verdict]
                                 if self.market.island_filter else self.market.name),
                # Scraped rows rarely carry coordinates; outside the Bahamas
                # (where islands stand in for geography) the market centroid
                # keeps them inside the app's "Near <city>" radius filter.
                "Lat": ev.lat if ev.lat is not None or self.market.island_filter
                       else self.market.lat,
                "Lng": ev.lng if ev.lng is not None or self.market.island_filter
                       else self.market.lng,
            }
            # ISO date strings compare lexicographically == chronologically
            in_window = bool(ev.date) and self.window_start <= ev.date <= self.window_end
            wrong_island = verdict == "other"
            is_online = bool(ONLINE_EVENT_RX.search(f"{ev.name} {ev.description}"))
            if wrong_island and self.strict_island_filter:
                continue  # confidently another island — drop
            if in_window and not wrong_island and not is_online:
                master_rows.append(row)
            else:
                reason = []
                if not ev.date:
                    reason.append("no parseable date")
                elif ev.date < self.window_start:
                    reason.append("event already past")
                elif not in_window:
                    reason.append(f"beyond {self.window_end} horizon")
                if wrong_island:
                    reason.append("appears to be another island")
                if is_online:
                    reason.append("online/virtual event")
                row["Review Reason"] = "; ".join(reason) or "unclassified"
                review_rows.append(row)

        master = pd.DataFrame(master_rows)
        review = pd.DataFrame(review_rows)
        if not master.empty:
            master = master.sort_values(["Date", "Event Name"]).reset_index(drop=True)
        if not review.empty:
            review = review.sort_values(["Date", "Event Name"]).reset_index(drop=True)
        return master, review


def _slugify_category(label: str) -> str:
    return re.sub(r"[^A-Za-z0-9]+", "_", str(label or "General")).strip("_").upper()


def _split_time_label(label) -> tuple[Optional[str], Optional[str]]:
    """'8:00 PM - 1:00 AM' -> ('20:00', '01:00'); '' / NaN -> (None, None)."""
    if label is None or (isinstance(label, float) and pd.isna(label)):
        return None, None
    parts = [p.strip() for p in str(label).split(" - ") if p.strip()]
    out: list[Optional[str]] = []
    for p in parts[:2]:
        try:
            out.append(datetime.strptime(p.upper().replace(".", ""), "%I:%M %p")
                       .strftime("%H:%M"))
        except ValueError:
            out.append(None)
    while len(out) < 2:
        out.append(None)
    return out[0], out[1]


def _split_price_label(label) -> tuple[Optional[float], Optional[float], bool]:
    """'$30.00 - $50.00' -> (30.0, 50.0, False); 'Free' -> (None, None, True)."""
    if label is None or (isinstance(label, float) and pd.isna(label)):
        return None, None, False
    text = str(label)
    if text.strip().lower() == "free":
        return None, None, True
    amounts = [float(a.replace(",", "")) for a in PRICE_RX.findall(text)]
    if not amounts:
        return None, None, False
    return min(amounts), max(amounts), False


# Market identity stamped on every feed record (schema_version 2). One
# pipeline run = one market; the global layout (docs/GLOBAL_DESIGN.md) runs
# this per market with these three overridden.
FEED_COUNTRY = "BS"
FEED_MARKET = "bs-nassau"
FEED_TZ = "America/Nassau"
FEED_SCHEMA_VERSION = 2


def _feed_record(row: dict) -> dict:
    """One master-sheet row -> one app-feed event (the 0 FOMO contract)."""
    name = str(row.get("Event Name") or "")
    date = str(row.get("Date") or "")
    t_start, t_end = _split_time_label(row.get("Time"))
    p_min, p_max, is_free = _split_price_label(row.get("Ticket Price (USD)"))
    island = ("NEW_PROVIDENCE"
              if FEED_COUNTRY == "BS" and
              str(row.get("Island Match") or "") == "New Providence" else None)

    def _num(v) -> Optional[float]:
        try:
            return None if v is None or pd.isna(v) else round(float(v), 5)
        except (TypeError, ValueError):
            return None
    return {
        "id": hashlib.sha1(f"{normalize_title(name)}|{date}".encode()).hexdigest()[:12],
        "name": name,
        "date": date,
        "time_start": t_start,
        "time_end": t_end,
        "venue": str(row.get("Venue/Location") or "") if not pd.isna(
            row.get("Venue/Location", "")) else "",
        "island": island,
        "lat": _num(row.get("Lat")),
        "lng": _num(row.get("Lng")),
        "price_min": p_min,
        "price_max": p_max,
        "is_free": is_free,
        "category": _slugify_category(row.get("Category")),
        # Only real links reach the app (manual:// placeholders would make
        # the Get Tickets button crash on an unresolvable scheme)
        "source_url": (str(row.get("Source URL") or "")
                       if str(row.get("Source URL") or "").startswith("http") else ""),
        "description": "" if pd.isna(row.get("Description", "")) else strip_html(
            row.get("Description") or ""),
        # schema_version 2: market identity (docs/GLOBAL_DESIGN.md)
        "country": FEED_COUNTRY,
        "market": FEED_MARKET,
        "tz": FEED_TZ,
    }


class Exporter:
    def __init__(self, out_dir: str, basename: str, workbook: bool = True):
        os.makedirs(out_dir, exist_ok=True)
        self.workbook = workbook
        self.xlsx_path = os.path.join(out_dir, f"{basename}.xlsx")
        self.csv_path = os.path.join(out_dir, f"{basename}.csv")
        self.json_path = os.path.join(out_dir, f"{basename}.json")

    def export(self, master: pd.DataFrame, review: pd.DataFrame,
               per_source: pd.DataFrame, summary: pd.DataFrame) -> None:
        def ordered(df: pd.DataFrame) -> pd.DataFrame:
            if df.empty:
                return pd.DataFrame(columns=MASTER_COLUMNS)
            extras = [c for c in df.columns if c not in MASTER_COLUMNS]
            return df[[c for c in MASTER_COLUMNS if c in df.columns] + extras]

        if not self.workbook:
            # Per-market runs: feed JSON + a CSV for humans, no workbook.
            ordered(master).to_csv(self.csv_path, index=False, encoding="utf-8-sig")
            self.export_json(master)
            log.info("Wrote %s and %s (app feed)", self.csv_path, self.json_path)
            return

        with pd.ExcelWriter(self.xlsx_path, engine="openpyxl") as writer:
            ordered(master).to_excel(writer, sheet_name="Events", index=False)
            ordered(review).to_excel(writer, sheet_name="Needs Review", index=False)
            per_source.to_excel(writer, sheet_name="By Source", index=False)
            summary.to_excel(writer, sheet_name="Run Summary", index=False)
            for sheet in writer.sheets.values():
                for col_cells in sheet.columns:
                    width = max((len(str(c.value)) for c in col_cells
                                 if c.value is not None), default=10)
                    letter = col_cells[0].column_letter
                    sheet.column_dimensions[letter].width = min(max(width + 2, 12), 60)

        ordered(master).to_csv(self.csv_path, index=False, encoding="utf-8-sig")
        self.export_json(master)
        log.info("Wrote %s, %s and %s (app feed)",
                 self.xlsx_path, self.csv_path, self.json_path)

    def export_json(self, master: pd.DataFrame) -> None:
        """Emit the 0 FOMO app feed (schema_version 2; v1 clients ignore the
        extra keys). Upload this file as events.json to the static host."""
        records = ([] if master.empty
                   else [_feed_record(r) for r in master.to_dict(orient="records")])
        feed = {
            "schema_version": FEED_SCHEMA_VERSION,
            "market": FEED_MARKET,
            "country": FEED_COUNTRY,
            "tz": FEED_TZ,
            "generated_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
            "events": records,
        }
        with open(self.json_path, "w", encoding="utf-8") as fh:
            json.dump(feed, fh, ensure_ascii=False, indent=1)


# =============================================================================
# PIPELINE ORCHESTRATION
# =============================================================================

SCRAPER_REGISTRY: dict[str, type[BaseScraper]] = {
    "ticket-flare": TicketFlareScraper,
    "eticketslive": ETicketsLiveScraper,
    "bahaevents": BahaEventsScraper,
    "bid-bahamas": BidBahamasScraper,
    "eventbrite": EventbriteScraper,
    "allevents.in": AllEventsInScraper,
    "bahamaslocal": BahamasLocalScraper,
    "bandsintown": BandsintownScraper,
    "songkick": SongkickScraper,
    "reggaeville": ReggaevilleScraper,
    "ticketmaster": TicketmasterScraper,
    "seatgeek": SeatGeekScraper,
    "manual": ManualEventsScraper,
}


def run_pipeline(args: argparse.Namespace) -> int:
    global FEED_COUNTRY, FEED_MARKET, FEED_TZ
    here = os.path.dirname(os.path.abspath(__file__))
    engine = RequestEngine(base_delay=args.delay)

    # Market mode: markets/<id>.json chooses sources, geo centre and feed dir.
    # No --market = the legacy Nassau run with its workbook outputs at the root.
    market: Optional[Market] = None
    if args.market:
        try:
            market = load_market(args.market)
        except FileNotFoundError:
            log.error("Unknown market %r. Available: %s",
                      args.market, ", ".join(list_markets()) or "(none)")
            return 2
        FEED_COUNTRY, FEED_MARKET, FEED_TZ = market.country, market.id, market.tz
        out_dir = os.path.join(here, market.feed_dir)
        output_basename = "events"
        min_events = args.min_events if args.min_events is not None else market.min_events
    else:
        out_dir = here
        output_basename = args.output
        min_events = args.min_events if args.min_events is not None else 15

    if args.sources:
        wanted = [s.strip().lower() for s in args.sources.split(",")]
    elif market is not None:
        wanted = list(market.sources)
    else:
        wanted = list(SCRAPER_REGISTRY)
    unknown = [s for s in wanted if s not in SCRAPER_REGISTRY]
    if unknown:
        log.error("Unknown source(s): %s. Valid: %s",
                  ", ".join(unknown), ", ".join(SCRAPER_REGISTRY))
        return 2

    allow_js = args.js or HAVE_PLAYWRIGHT   # auto-enable when installed
    if allow_js and not HAVE_PLAYWRIGHT:
        log.warning("--js requested but Playwright is not installed")
    all_events: list[Event] = []
    statuses: list[SourceStatus] = []
    for key in wanted:
        scraper = SCRAPER_REGISTRY[key](
            engine,
            max_pages=args.max_pages,
            fetch_details=not args.no_details,
            detail_cap=args.detail_cap,
            allow_js=allow_js,
            market=market,
            params=(market.sources.get(key) if market else None),
        )
        all_events.extend(scraper.run())   # error-isolated internally
        statuses.append(scraper.status)

    log.info("Raw events captured across all sources: %d", len(all_events))

    dedup = Deduplicator(threshold=args.similarity)
    clusters = dedup.dedupe(all_events)
    log.info("After cross-source dedup: %d unique event(s) "
             "(%d duplicate listing(s) merged)", len(clusters), dedup.merged_away)

    transform = TransformEngine(args.horizon_days,
                                strict_island_filter=args.strict_island,
                                market=market)
    master, review = transform.build_frames(clusters)

    per_source = pd.DataFrame([{
        "Source": s.name,
        "Status": "OK" if s.ok else "FAILED",
        "Events Found": s.events_found,
        "Pages Fetched": s.pages_fetched,
        "Notes": s.note,
    } for s in statuses])

    summary = pd.DataFrame([
        {"Metric": "Run timestamp", "Value": datetime.now().strftime("%Y-%m-%d %H:%M:%S")},
        {"Metric": "Window", "Value": f"{transform.window_start} -> {transform.window_end}"},
        {"Metric": "Raw records captured", "Value": len(all_events)},
        {"Metric": "Unique events after dedup", "Value": len(clusters)},
        {"Metric": "Duplicate listings merged", "Value": dedup.merged_away},
        {"Metric": "Events in window", "Value": len(master)},
        {"Metric": "Records needing review", "Value": len(review)},
        {"Metric": "Similarity threshold", "Value": args.similarity},
        {"Metric": "Sources run", "Value": ", ".join(wanted)},
        {"Metric": "Market", "Value": FEED_MARKET},
    ])

    Exporter(out_dir, output_basename, workbook=market is None).export(
        master, review, per_source, summary)

    print("\n" + "=" * 62)
    print(f" PIPELINE COMPLETE  [{FEED_MARKET}]")
    print("=" * 62)
    print(per_source.to_string(index=False))
    print("-" * 62)
    print(f" Window: {transform.window_start} -> {transform.window_end}")
    print(f" Unique events exported     : {len(master)}")
    print(f" Records parked for review  : {len(review)}")
    print(f" Feed: {os.path.join(out_dir, output_basename + '.json')}")
    print("=" * 62)

    # Dead-man's switch: per-source error isolation means a broken parser
    # exits 0 with an empty payload — the floor makes that failure loud so
    # a stale feed can't ship silently for weeks.
    zero_sources = [s.name for s in statuses if s.ok and s.events_found == 0]
    if zero_sources:
        logging.warning("Sources returning ZERO events: %s",
                        ", ".join(zero_sources))
    if len(master) < min_events:
        logging.error(
            "FLOOR BREACH: only %d event(s) exported (floor %d) — refusing "
            "to bless this run. Feed files were still written for inspection.",
            len(master), min_events)
        return 2
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(
        description="New Providence, Bahamas 2026 events aggregation pipeline")
    ap.add_argument("--sources", default="",
                    help=f"Comma list of sources ({', '.join(SCRAPER_REGISTRY)}). "
                         "Default: all")
    ap.add_argument("--horizon-days", type=int, default=HORIZON_DAYS,
                    help="Rolling window length from today (default 730 = 2 years)")
    ap.add_argument("--output", default=OUTPUT_BASENAME,
                    help="Output basename (default New_Providence_Events)")
    ap.add_argument("--max-pages", type=int, default=6,
                    help="Max listing pages per source (default 6)")
    ap.add_argument("--detail-cap", type=int, default=40,
                    help="Max detail pages fetched per source (default 40)")
    ap.add_argument("--delay", type=float, default=2.0,
                    help="Base per-domain delay in seconds (default 2.0)")
    ap.add_argument("--similarity", type=float, default=SIMILARITY_THRESHOLD,
                    help="Dedup title similarity threshold (default 0.83)")
    ap.add_argument("--no-details", action="store_true",
                    help="Skip detail-page enrichment (faster, sparser data)")
    ap.add_argument("--js", action="store_true",
                    help="Allow Playwright rendering for JS-heavy sites")
    ap.add_argument("--strict-island", action="store_true",
                    help="Hard-drop events confidently on other islands "
                         "(default: park them in Needs Review)")
    ap.add_argument("--min-events", type=int, default=None,
                    help="Exit 2 if fewer unique events are exported "
                         "(dead-man's switch; default 15, or the market's "
                         "min_events when --market is given)")
    ap.add_argument("--market", default="",
                    help="Run one market from markets/<id>.json (sources, geo "
                         "centre, tz); writes feeds/<id>/events.json. "
                         f"Available: {', '.join(list_markets()) or '(none)'}")
    ap.add_argument("--list-markets", action="store_true",
                    help="Print the market ids and exit")
    ap.add_argument("--verbose", "-v", action="store_true",
                    help="Debug logging")
    args = ap.parse_args()
    if args.list_markets:
        print("\n".join(list_markets()))
        return 0

    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s  %(levelname)-7s %(message)s",
        datefmt="%H:%M:%S",
    )
    return run_pipeline(args)


if __name__ == "__main__":
    sys.exit(main())

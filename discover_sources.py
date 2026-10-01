#!/usr/bin/env python3
"""discover_sources.py — the pipeline grows its own source list (G5, 2026-10-01).

For a market it (1) searches the public web for event listings in the market's
language(s), (2) harvests platform slugs (Eventbrite, allevents.in, Luma),
(3) probes every candidate site for something machine-readable — The Events
Calendar REST, WordPress event post types, schema.org Event nodes, iCalendar
links, RSS feeds, server-rendered card listings — and (4) ground-truths each
candidate by running the matching reader in-process and counting upcoming
events that survive the market filter. Candidates that yield enough events are
written into markets/<id>.json with `_auto` provenance; thin-but-alive ones go
to sources_watchlist.json for the weekly re-probe; `_auto` sources that
source_health.py reports dead are retired back to the watchlist. Subreddits
and public Telegram channels found the same way land in inbox/bridges/*.json
for the bridges. `--expand` creates markets for countries that have none,
using the app's bundled gazetteer for the capital, and keeps a country only
when at least one real source was found.

Zero Claude tokens: heuristics + the readers themselves are the judge.

    python discover_sources.py --market lc-castries           # one market
    python discover_sources.py --needy --limit 10             # weakest markets first
    python discover_sources.py --expand --expand-limit 10     # new countries
    python discover_sources.py --retire                       # apply health retirements
    python discover_sources.py --needy --expand --retire --notify   # the Sunday run
    add --dry-run to print what would change without writing anything.
"""
from __future__ import annotations

import argparse
import json
import logging
import os
import random
import re
import sys
import time
import unicodedata
import xml.etree.ElementTree as ET
from datetime import datetime, timedelta, timezone
from html import unescape
from typing import Optional
from urllib.parse import parse_qs, quote_plus, unquote, urljoin, urlparse

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import comprehensive_bahamas_scraper as S  # noqa: E402

MARKETS_DIR = os.path.join(HERE, "markets")
FEEDS = os.path.join(HERE, "feeds")
HEALTH_DIR = os.path.join(FEEDS, "health")
STATE_PATH = os.path.join(HEALTH_DIR, "discovery.json")
SEARCH_CACHE = os.path.join(HEALTH_DIR, "search_cache.json")
REPORT = os.path.join(HEALTH_DIR, "report.json")
WATCHLIST = os.path.join(HERE, "sources_watchlist.json")
TEMPLATES = os.path.join(HERE, "source_templates.json")
REGIONS = os.path.join(MARKETS_DIR, "regions.json")
GEO_DIR = os.path.join(HERE, "zero_fomo", "app", "src", "main", "assets", "geo")
BRIDGES_DIR = os.path.join(HERE, "inbox", "bridges")
REDDIT_MAP = os.path.join(BRIDGES_DIR, "reddit_markets.json")
TELEGRAM_MAP = os.path.join(BRIDGES_DIR, "telegram_public.json")

SEARCH_TTL_DAYS = 30
REDISCOVER_DAYS = 21          # a market is "due" again after this
COUNTRY_RETRY_DAYS = 45       # a country that yielded nothing is retried after this
PROBE_WINDOW_DAYS = 365
MAX_SITES_PER_MARKET = 14
MAX_SEARCH_RESULTS = 12
REDDIT_GAP = 20.0             # 8-12 s still drew 429s (2026-09-16, 2026-10-01)
GENERIC_READERS = ("tribe", "wp-posts", "jsonld", "ics", "rss", "html-cards")
PLATFORM_READERS = ("eventbrite", "allevents.in", "luma", "meetup")
EVENT_PATH_RX = re.compile(r"/(events?|whats-?on|what-s-on|calendar|agenda|evenement|evento|veranstaltung)[s]?(/|$|\?)", re.I)

log = logging.getLogger("discover")
UTC = timezone.utc


# ----------------------------------------------------------------- utilities

def utcnow() -> str:
    return datetime.now(UTC).strftime("%Y-%m-%dT%H:%M:%SZ")


def today_iso() -> str:
    return datetime.now().strftime("%Y-%m-%d")


def load_json(path: str, default):
    try:
        with open(path, encoding="utf-8") as fh:
            return json.load(fh)
    except (OSError, ValueError):
        return default


def save_json(path: str, value, dry_run: bool = False) -> None:
    if dry_run:
        return
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8", newline="\n") as fh:
        json.dump(value, fh, ensure_ascii=False, indent=2)
        fh.write("\n")
    os.replace(tmp, path)


def slugify(text: str) -> str:
    text = unicodedata.normalize("NFKD", text).encode("ascii", "ignore").decode()
    text = re.sub(r"[^a-z0-9]+", "-", text.lower()).strip("-")
    return text


def netloc_short(url: str) -> str:
    host = urlparse(url).netloc.lower().removeprefix("www.")
    return host.split(":")[0]


def site_tag(url: str) -> str:
    """'https://www.puregrenada.com/events' -> 'puregrenada' (key suffix)."""
    host = netloc_short(url)
    parts = host.split(".")
    if len(parts) >= 2 and parts[0] in ("events", "calendar", "tickets", "whatson", "agenda") and len(parts) > 2:
        parts = parts[1:]
    return re.sub(r"[^a-z0-9]+", "", parts[0]) or re.sub(r"[^a-z0-9]+", "", host)


def days_since(stamp: str | None) -> float:
    if not stamp:
        return 1e9
    try:
        dt = datetime.strptime(stamp, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=UTC)
    except ValueError:
        return 1e9
    return (datetime.now(UTC) - dt).total_seconds() / 86400


# ----------------------------------------------------------------- inputs

class Templates:
    def __init__(self) -> None:
        self.d = load_json(TEMPLATES, {})
        self.langs: dict[str, str] = {}
        for lang, ccs in (self.d.get("languages") or {}).items():
            for cc in str(ccs).split():
                self.langs[cc.upper()] = lang
        self.block = [b.lower() for b in self.d.get("blocklist_domains") or []]
        self.tm = set(str(self.d.get("ticketmaster_countries") or "").split())
        self.sg = set(str(self.d.get("seatgeek_countries") or "").split())
        self.adopt_min = int(self.d.get("adopt_min_events") or 3)
        self.watch_min = int(self.d.get("watch_min_events") or 1)

    def queries(self, cc: str) -> list[str]:
        q = list((self.d.get("search") or {}).get("queries", {}).get("en") or [])
        lang = self.langs.get(cc.upper())
        if lang:
            q += (self.d.get("search") or {}).get("queries", {}).get(lang) or []
        return q

    def social_queries(self) -> list[str]:
        return list((self.d.get("search") or {}).get("social") or [])

    def blocked(self, url: str) -> bool:
        host = netloc_short(url)
        return any(b in host for b in self.block)

    def country_aliases(self, cc: str, country_name: str) -> list[str]:
        """Lower-case words that prove a page / venue is in this country."""
        name = country_name.lower().strip()
        out = [name]
        if name.startswith("saint "):
            out += ["st. " + name[6:], "st " + name[6:]]
        if " and " in name:
            out += [part.strip() for part in name.split(" and ") if len(part.strip()) > 3]
        if name.startswith("the "):
            out.append(name[4:])
        out += [a.lower() for a in (self.d.get("country_aliases") or {}).get(cc.upper(), [])]
        return list(dict.fromkeys(a for a in out if len(a) > 2))

    def postal_pattern(self, cc: str) -> Optional[re.Pattern]:
        rx = (self.d.get("postal_patterns") or {}).get(cc.upper())
        return re.compile(rx) if rx else None


class Gazetteer:
    """The app's bundled countries/cities (offline, already in the repo)."""

    def __init__(self) -> None:
        self.countries = {c["cc"].upper(): c for c in load_json(os.path.join(GEO_DIR, "countries.json"), [])}
        self.cities = load_json(os.path.join(GEO_DIR, "cities.json"), [])
        self.regions = load_json(REGIONS, {"regions": [], "countries": {}})

    def country_name(self, cc: str) -> str:
        return str((self.countries.get(cc.upper()) or {}).get("name") or cc).strip()

    def anchor(self, cc: str) -> dict | None:
        rows = [c for c in self.cities if c.get("cc", "").upper() == cc.upper()]
        if not rows:
            return None
        rows.sort(key=lambda c: (-(int(c.get("cap") or 0)), -int(c.get("p") or 0)))
        return rows[0]

    def region_of(self, cc: str) -> str:
        return str((self.regions.get("countries") or {}).get(cc.upper()) or "")

    def region_order(self, cc: str) -> int:
        rid = self.region_of(cc)
        for r in self.regions.get("regions") or []:
            if r.get("id") == rid:
                return int(r.get("order") or 99)
        return 99


# ----------------------------------------------------------------- search

class Searcher:
    """DDG lite first (plain HTML, no key), Bing RSS second. Cached per query."""

    UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
          "(KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36")

    def __init__(self, engine: S.RequestEngine, engines: list[str], dry_run: bool) -> None:
        self.engine = engine
        self.order = engines or ["ddg", "bing"]
        self.cache = load_json(SEARCH_CACHE, {})
        self.dry_run = dry_run
        self.calls = 0

    def search(self, query: str, must_mention: list[str] | None = None) -> list[tuple[str, str]]:
        """Results whose title or URL mentions one of `must_mention` (the city /
        country words). Bing's RSS endpoint answers off-topic for small places
        (hurricane shutters for 'Bridgetown Barbados events', typing tutors for
        Havana) and DuckDuckGo lite rate-limits with HTTP 202, so results are
        relevance-filtered and the engines are tried in order with a back-off."""
        key = query.strip().lower()
        hit = self.cache.get(key)
        if hit and days_since(hit.get("at")) < SEARCH_TTL_DAYS:
            results = [tuple(r) for r in hit.get("results") or []]
        else:
            results = []
            for eng in self.order:
                try:
                    results = {"ddg": self._ddg, "ddg-html": self._ddg_html, "bing": self._bing}[eng](query)
                except Exception as exc:  # noqa: BLE001
                    log.debug("search %s failed: %s", eng, exc)
                    results = []
                if results:
                    break
            self.calls += 1
            time.sleep(3.0 + random.random() * 3.0)
            self.cache[key] = {"at": utcnow(), "results": results[:MAX_SEARCH_RESULTS * 2]}
            save_json(SEARCH_CACHE, self.cache, self.dry_run)
        words = [w.lower() for w in (must_mention or []) if len(w) > 2]
        if words:
            results = [(u, t) for u, t in results
                       if any(w in (t or "").lower() or w.replace(" ", "") in u.lower().replace("-", "")
                              for w in words)]
        return results[:MAX_SEARCH_RESULTS]

    def _ddg_fetch(self, url: str) -> Optional[str]:
        """DDG answers 202 (challenge) when hurried; one pause and retry."""
        for attempt in range(2):
            resp = self.engine.get(url, quiet=True)
            if resp is None:
                return None
            if resp.status_code == 202 or "anomaly" in resp.text[:3000].lower():
                if attempt == 0:
                    time.sleep(25 + random.random() * 10)
                    continue
                return None
            return resp.text
        return None

    def _ddg(self, query: str) -> list[tuple[str, str]]:
        text = self._ddg_fetch("https://lite.duckduckgo.com/lite/?q=" + quote_plus(query))
        if not text:
            return []
        out = []
        for href, title in re.findall(r'<a rel="nofollow" href="([^"]+)" class=\'result-link\'>(.*?)</a>',
                                      text, re.S):
            out.append((self._ddg_href(href), re.sub(r"<[^>]+>", "", unescape(title)).strip()))
        return [(h, t) for h, t in out if h.startswith("http")]

    def _ddg_html(self, query: str) -> list[tuple[str, str]]:
        text = self._ddg_fetch("https://html.duckduckgo.com/html/?q=" + quote_plus(query))
        if not text:
            return []
        out = []
        for href, title in re.findall(r'<a rel="nofollow" class="result__a" href="([^"]+)"[^>]*>(.*?)</a>',
                                      text, re.S):
            out.append((self._ddg_href(href), re.sub(r"<[^>]+>", "", unescape(title)).strip()))
        return [(h, t) for h, t in out if h.startswith("http")]

    @staticmethod
    def _ddg_href(href: str) -> str:
        href = unescape(href)
        if "uddg=" in href:
            href = unquote(parse_qs(urlparse(href).query).get("uddg", [""])[0])
        if href.startswith("//"):
            href = "https:" + href
        return href

    def _bing(self, query: str) -> list[tuple[str, str]]:
        resp = self.engine.get("https://www.bing.com/search?format=rss&q=" + quote_plus(query), quiet=True)
        if resp is None:
            return []
        try:
            root = ET.fromstring(resp.content)
        except ET.ParseError:
            return []
        out = []
        for item in root.iter("item"):
            link = (item.findtext("link") or "").strip()
            title = (item.findtext("title") or "").strip()
            if link.startswith("http"):
                out.append((link, title))
        return out


# ----------------------------------------------------------------- probing

class Prober:
    """Turns a URL into reader configs, then runs the readers to count events."""

    def __init__(self, engine: S.RequestEngine, market: S.Market,
                 aliases: list[str], postal: Optional[re.Pattern],
                 other_countries: list[tuple[str, str]]) -> None:
        self.engine = engine
        self.market = market
        self.aliases = aliases
        self.postal = postal
        self.other_countries = other_countries     # (cc, lower-case name) of every other country
        self.fetched = 0
        self.rejected_country: list[str] = []

    def soup(self, url: str):
        self.fetched += 1
        return self.engine.soup(url)

    def in_country(self, url: str, page_text: str) -> bool:
        """The site must show it belongs to the market's country: a ccTLD, or
        the country named at least as often as any other country (a Bahamas
        lodge site that mentions its Turks & Caicos branch once is not a
        Turks & Caicos source), or (US/CA/GB/AU, where city names repeat) a
        postal code pattern plus the city name. 'hamiltonevents.ca' for
        Bermuda fails here."""
        host = netloc_short(url)
        cc = self.market.country.lower()
        if host.endswith("." + cc):
            return True
        text = page_text.lower()
        ours = sum(text.count(a) for a in self.aliases)
        if ours:
            rival = max((text.count(n) for c, n in self.other_countries if c != self.market.country.upper()),
                        default=0)
            if ours >= rival:
                return True
        if self.postal is not None and self.postal.search(page_text) \
                and self.market.name.split(",")[0].strip().lower() in text:
            return True
        return False

    def foreign_share(self, events) -> float:
        """Fraction of events whose venue / stated country names another
        country and not ours — the per-event twin of the site gate."""
        if not events:
            return 0.0
        foreign = 0
        for ev in events:
            blob = f"{ev.venue} {ev.country_text}".lower()
            if any(a in blob for a in self.aliases):
                continue
            if any(n in blob for c, n in self.other_countries if c != self.market.country.upper()):
                foreign += 1
        return foreign / len(events)

    def site_candidates(self, entry_url: str) -> list[tuple[str, dict, str]]:
        """(reader_key, params, note) for one site. At most ~6 requests."""
        cands: list[tuple[str, dict, str]] = []
        parsed = urlparse(entry_url)
        base = f"{parsed.scheme}://{parsed.netloc}"
        venue_default = self.market.name
        soup = self.soup(entry_url)
        if soup is None:
            return cands
        html_text = str(soup)[:400000]
        if not self.in_country(entry_url, re.sub(r"<[^>]+>", " ", html_text)):
            self.rejected_country.append(netloc_short(entry_url))
            return cands

        # 1. schema.org Event nodes right on the entry page
        if S.extract_json_ld_events(soup):
            cands.append(("jsonld", {"urls": [entry_url], "venue_default": venue_default}, "ld+json on page"))

        # 2. declared feeds
        ics_links, rss_links = [], []
        for link in soup.find_all("link", href=True):
            t = str(link.get("type") or "").lower()
            href = urljoin(entry_url, link["href"])
            if "text/calendar" in t or href.lower().endswith(".ics") or "ical" in href.lower():
                ics_links.append(href)
            elif "rss" in t or "atom" in t:
                rss_links.append(href)
        for a in soup.find_all("a", href=True):
            href = urljoin(entry_url, a["href"])
            if href.lower().endswith(".ics") or "ical=1" in href.lower() or "/ical" in href.lower():
                ics_links.append(href)
        ics_links = list(dict.fromkeys(ics_links))[:2]
        rss_links = sorted(dict.fromkeys(rss_links), key=lambda h: ("event" not in h.lower(), len(h)))[:2]
        if ics_links:
            cands.append(("ics", {"urls": ics_links, "venue_default": venue_default}, "ics link"))
        if rss_links and not any("comments" in r for r in rss_links):
            cands.append(("rss", {"urls": rss_links, "venue_default": venue_default}, "rss link"))

        # 3. WordPress: The Events Calendar REST, then event post types, then ical
        if "wp-content" in html_text or "wp-json" in html_text:
            tribe = self.engine.get(f"{base}/wp-json/tribe/events/v1/events?per_page=5&start_date={today_iso()}",
                                    as_json=True, quiet=True)
            self.fetched += 1
            if isinstance(tribe, dict) and (tribe.get("events") or tribe.get("total")):
                cands.append(("tribe", {"base": base}, "tribe REST"))
            else:
                types = self.engine.get(f"{base}/wp-json/wp/v2/types", as_json=True, quiet=True)
                self.fetched += 1
                if isinstance(types, dict):
                    for slug, meta in types.items():
                        rest = str((meta or {}).get("rest_base") or slug)
                        if re.search(r"event|agenda|calendar|whats", slug, re.I) and "tribe" not in slug:
                            cands.append(("wp-posts", {"base": base, "type": rest, "venue_default": venue_default},
                                          f"wp type {slug}"))
                            break
                if not ics_links:
                    for path in ("/?post_type=tribe_events&ical=1", "/events/?ical=1", "/events/feed/"):
                        r = self.engine.get(base + path, quiet=True)
                        self.fetched += 1
                        if r is None:
                            continue
                        head = r.text[:3000]
                        if "BEGIN:VCALENDAR" in head:
                            cands.append(("ics", {"urls": [base + path], "venue_default": venue_default}, "wp ical"))
                            break
                        if "<rss" in head or "<feed" in head:
                            cands.append(("rss", {"urls": [base + path], "venue_default": venue_default}, "wp events feed"))
                            break

        # 4. card listing: many links sharing an event-ish path marker
        marker = self.best_marker(soup, base)
        if marker:
            cands.append(("html-cards", {"urls": [entry_url], "href_marker": marker,
                                         "venue_default": venue_default, "detail_cap": 20}, f"{marker} cards"))
        elif not EVENT_PATH_RX.search(parsed.path):
            # The entry page was a home page: one look at the usual listing paths.
            for path in ("/events/", "/events", "/whats-on/", "/calendar/"):
                sub = self.soup(base + path)
                if sub is None:
                    continue
                if S.extract_json_ld_events(sub):
                    cands.append(("jsonld", {"urls": [base + path], "venue_default": venue_default}, "ld+json listing"))
                    break
                m2 = self.best_marker(sub, base)
                if m2:
                    cands.append(("html-cards", {"urls": [base + path], "href_marker": m2,
                                                 "venue_default": venue_default, "detail_cap": 20}, f"{m2} cards"))
                    break
        return cands

    @staticmethod
    def best_marker(soup, base: str) -> str:
        counts: dict[str, int] = {}
        for a in soup.find_all("a", href=True):
            href = urljoin(base, a["href"])
            if urlparse(href).netloc != urlparse(base).netloc:
                continue
            m = re.search(r"/(events?|event-details?|whats-?on|evento|evenement|veranstaltung)/[^/?#]{3,}", href, re.I)
            if m:
                marker = "/" + m.group(1).lower() + "/"
                counts[marker] = counts.get(marker, 0) + 1
        if not counts:
            return ""
        marker, n = max(counts.items(), key=lambda kv: kv[1])
        return marker if n >= 5 else ""

    def evaluate(self, key: str, params: dict) -> tuple[int, int, str]:
        """Run the reader. Returns (kept_upcoming, raw_found, note). A result
        whose events mostly name another country counts as zero."""
        clean = {k: v for k, v in params.items() if not k.startswith("_")}
        try:
            scraper = S.build_scraper(key, self.engine, self.market, max_pages=2,
                                      fetch_details=False, detail_cap=8, params=clean)
            events = scraper.run()
        except Exception as exc:  # noqa: BLE001
            return 0, 0, f"{type(exc).__name__}: {exc}"
        self.fetched += scraper.status.pages_fetched
        horizon = (datetime.now() + timedelta(days=PROBE_WINDOW_DAYS)).strftime("%Y-%m-%d")
        kept = [e for e in events if e.date and today_iso() <= e.date <= horizon]
        share = self.foreign_share(kept)
        if kept and share > 0.5:
            return 0, len(events), f"{len(kept)} upcoming but {share:.0%} name another country"
        return len(kept), len(events), scraper.status.note


# ----------------------------------------------------------------- bridges (social)

_reddit_tripped = False


def probe_subreddit(engine: S.RequestEngine, name: str) -> tuple[bool, str]:
    """Plain request, no retry: Reddit answers bursts with 429 even on RSS and
    every retry only lengthens the ban. One 429 parks Reddit for this run."""
    global _reddit_tripped
    import requests
    if _reddit_tripped:
        return False, "reddit rate-limited this run"
    try:
        resp = requests.get(f"https://www.reddit.com/r/{name}/new.rss", timeout=25,
                            headers={"User-Agent": "zerofomo-discovery/1.0 (events; https://0fomo.app)"})
    except requests.RequestException as exc:
        return False, f"error {type(exc).__name__}"
    finally:
        time.sleep(REDDIT_GAP)
    if resp.status_code == 429:
        _reddit_tripped = True
        return False, "429 (parked for this run)"
    if resp.status_code != 200 or "subreddits/search" in resp.url:
        return False, f"http {resp.status_code}"
    try:
        root = ET.fromstring(resp.content)
    except ET.ParseError:
        return False, "not atom"
    atom = "{http://www.w3.org/2005/Atom}"
    recent = 0
    cutoff = datetime.now(UTC) - timedelta(days=90)
    for entry in root.findall(atom + "entry"):
        pub = (entry.findtext(atom + "published") or "").replace("Z", "+00:00")
        try:
            if datetime.fromisoformat(pub) >= cutoff:
                recent += 1
        except ValueError:
            continue
    return recent >= 3, f"{recent} posts in 90d"


def probe_telegram_channel(engine: S.RequestEngine, channel: str) -> tuple[bool, str]:
    resp = engine.get(f"https://t.me/s/{channel}", quiet=True)
    if resp is None:
        return False, "no preview"
    msgs = resp.text.count('class="tgme_widget_message_wrap')
    times = re.findall(r'<time datetime="([^"]+)"', resp.text)
    if msgs < 5 or not times:
        return False, f"{msgs} messages"
    try:
        last = datetime.fromisoformat(times[-1].replace("Z", "+00:00"))
    except ValueError:
        return False, "no dates"
    fresh = (datetime.now(UTC) - last).days <= 60
    return fresh, f"{msgs} messages, last {last.date()}"


# ----------------------------------------------------------------- the engine

class Discovery:
    def __init__(self, args: argparse.Namespace) -> None:
        self.args = args
        self.t = Templates()
        self.geo = Gazetteer()
        self.engine = S.RequestEngine(base_delay=args.delay)
        self.searcher = Searcher(self.engine, (self.t.d.get("search") or {}).get("engines") or [], args.dry_run)
        self.state = load_json(STATE_PATH, {"markets": {}, "countries": {}})
        self.watch = load_json(WATCHLIST, {"watch": []})
        self.reddit_map = load_json(REDDIT_MAP, {})
        self.telegram_map = load_json(TELEGRAM_MAP, {})
        self.changes: list[str] = []      # human lines for the summary / ntfy
        self.other_countries = [(cc, str(row.get("name") or "").lower())
                                for cc, row in self.geo.countries.items()
                                if len(str(row.get("name") or "")) > 3]

    # ---- market selection ------------------------------------------------

    def market_ids(self) -> list[str]:
        ids = S.list_markets()
        if self.args.market:
            return [m for m in self.args.market if m in ids]
        if self.args.all:
            chosen = ids
        elif self.args.needy:
            chosen = self.needy_markets(ids)
        else:
            chosen = []
        due = [m for m in chosen if days_since((self.state["markets"].get(m) or {}).get("last")) >= REDISCOVER_DAYS
               or self.args.force]
        return due[: self.args.limit] if self.args.limit else due

    def needy_markets(self, ids: list[str]) -> list[str]:
        """Below floor / health drop / fewest events first, then stalest."""
        report = load_json(REPORT, {})
        drops = {a["market"] for a in report.get("alerts", []) if a.get("state") == "drop"}
        rows = []
        for mid in ids:
            st = load_json(os.path.join(FEEDS, mid, "status.json"), {})
            exported = int(st.get("exported") or 0)
            try:
                floor = max(int(S.load_market(mid).min_events), 20)
            except Exception:  # noqa: BLE001
                floor = 20
            score = (0 if mid in drops else 1, 0 if exported < floor else 1, exported,
                     -days_since((self.state["markets"].get(mid) or {}).get("last")))
            rows.append((score, mid))
        rows.sort()
        return [mid for _, mid in rows]

    # ---- per-market discovery -------------------------------------------

    def fill(self, template: str, market: S.Market, city: str) -> str:
        cc = market.country.upper()
        country = self.geo.country_name(cc)
        return (template.replace("{city}", city).replace("{country}", country)
                .replace("{city_slug}", slugify(city)).replace("{cc}", cc).replace("{cc_lower}", cc.lower())
                .replace("{City}", slugify(city).title().replace("-", "%20"))
                .replace("{country_nospace}", re.sub(r"[^A-Za-z]", "", country))
                .replace("{city_nospace}", re.sub(r"[^A-Za-z]", "", city)))

    def discover_market(self, market: S.Market, md: dict) -> dict:
        """Mutates md['sources'] (the market file dict). Returns a summary."""
        city = market.name.split(",")[0].strip()
        existing = md.setdefault("sources", {})
        country = self.geo.country_name(market.country)
        aliases = self.t.country_aliases(market.country, country)
        prober = Prober(self.engine, market, aliases, self.t.postal_pattern(market.country),
                        self.other_countries)
        summary = {"market": market.id, "adopted": [], "watch": [], "probed": 0, "searches": 0}
        log.info("=== %s (%s) ===", market.id, market.name)

        # 0. re-validate what discovery adopted earlier: a reader that no
        #    longer yields (site changed, or a reader fix now rejects what it
        #    once counted) goes back to the watchlist instead of lingering
        #    until the health watch calls it dead.
        for key in list(existing):
            params = existing[key] or {}
            if not params.get("_auto"):
                continue
            kept, raw, note = prober.evaluate(key, params)
            summary["probed"] += 1
            if kept < self.t.adopt_min:
                log.info("  %-12s re-check kept=%d raw=%d -> pruned %s", key, kept, raw, note)
                self.add_watch({"name": f"{market.id} {key} (pruned, {kept} upcoming)", "kind": "status",
                                "url": str(params.get("base") or (params.get("urls") or [""])[0] or key),
                                "alert_when_alive": False,
                                "adopt": f"{market.id}: {key} {json.dumps({k: v for k, v in params.items() if k != '_auto'})}",
                                "_auto": {"pruned": today_iso(), "probe_events": kept}})
                del existing[key]
                self.changes.append(f"PRUNE {market.id}: {key} ({kept} upcoming)")
            else:
                log.info("  %-12s re-check kept=%d raw=%d ok", key, kept, raw)

        # 1. search the web in the market's language(s); a result must at
        #    least mention the city or the country to be looked at.
        mention = [city] + aliases
        results: list[tuple[str, str, str]] = []   # (url, title, query)
        for q in self.t.queries(market.country):
            query = self.fill(q, market, city)
            for url, title in self.searcher.search(query, must_mention=mention):
                results.append((url, title, query))
            summary["searches"] += 1

        # 2. platform candidates: harvested slugs + guesses (one per platform)
        platform_cands: dict[str, tuple[dict, str]] = {}
        for key, spec in (self.t.d.get("platforms") or {}).items():
            if key in existing and not (existing[key] or {}).get("_auto"):
                continue    # hand-written entry wins
            rx = spec.get("url_rx")
            if rx:
                for url, _, query in results:
                    m = re.search(rx, url, re.I)
                    if m and key not in platform_cands:
                        platform_cands[key] = ({spec["param"]: m.group(1)}, f"search:{query}")
            if key not in platform_cands:
                for g in spec.get("guess") or []:
                    platform_cands[key] = ({spec["param"]: self.fill(g, market, city)}, "guess")
                    break
        for key, (params, via) in platform_cands.items():
            # Multi-country platforms carry a country per event (JSON-LD
            # addressCountry, Meetup venue.country, Luma country_code): gate
            # every record on it, so a guessed `hamilton` slug that resolves
            # to Ontario yields zero for Bermuda instead of eighty.
            if key != "eventbrite":
                params = dict(params, countries=aliases)
            kept, raw, note = prober.evaluate(key, params)
            summary["probed"] += 1
            log.info("  %-12s %-40s kept=%d raw=%d %s", key, json.dumps(params)[:40], kept, raw, note)
            self.decide(md, market, key, params, kept, via, summary, url="")

        # 3. generic sites from the search results
        seen_hosts: set[str] = set()
        site_entries: list[tuple[str, str]] = []
        for url, title, query in results:
            host = netloc_short(url)
            if not host or host in seen_hosts or self.t.blocked(url):
                continue
            if any(host in str(v) for v in existing.values()):
                continue    # already a source for this market
            seen_hosts.add(host)
            site_entries.append((url, query))
        for url, query in site_entries[:MAX_SITES_PER_MARKET]:
            try:
                cands = prober.site_candidates(url)
            except Exception as exc:  # noqa: BLE001
                log.debug("probe %s failed: %s", url, exc)
                continue
            if not cands:
                log.info("  %-50s %s", netloc_short(url),
                         "not this country" if netloc_short(url) in prober.rejected_country else "nothing readable")
                continue
            best: tuple[int, str, dict, str] | None = None
            for key, params, note in cands:
                kept, raw, rnote = prober.evaluate(key, params)
                summary["probed"] += 1
                log.info("  %-50s %-10s kept=%d raw=%d (%s) %s", netloc_short(url), key, kept, raw, note, rnote)
                if best is None or kept > best[0]:
                    best = (kept, key, params, note)
            if best:
                kept, key, params, note = best
                self.decide(md, market, f"{key}#{site_tag(url)}", params, kept, f"search:{query}", summary, url=url)

        # 4. social: subreddits + public Telegram channels
        if not self.args.no_social:
            self.discover_social(market, city, results, summary)

        self.state["markets"][market.id] = {"last": utcnow(), "adopted": summary["adopted"],
                                            "watch": summary["watch"], "probed": summary["probed"]}
        self.save_side_files()
        return summary

    def save_side_files(self) -> None:
        """Watchlist + bridge maps after every market, so a crash or a kill
        later in the run never loses what was already found."""
        save_json(WATCHLIST, self.watch, self.args.dry_run)
        if self.reddit_map:
            save_json(REDDIT_MAP, dict(sorted(self.reddit_map.items())), self.args.dry_run)
        if self.telegram_map:
            save_json(TELEGRAM_MAP, dict(sorted(self.telegram_map.items())), self.args.dry_run)

    def decide(self, md: dict, market: S.Market, key: str, params: dict, kept: int,
               via: str, summary: dict, url: str) -> None:
        base_key = S.registry_key(key)
        probe_url = url or str(params.get("base") or (params.get("urls") or [""])[0] or "")
        if kept >= self.t.adopt_min:
            entry = dict(params)
            entry["_auto"] = {"adopted": today_iso(), "via": via, "probe_events": kept,
                              "from": probe_url}
            prev = md["sources"].get(key)
            if prev and not (prev or {}).get("_auto"):
                return
            md["sources"][key] = entry
            summary["adopted"].append(f"{key} ({kept})")
            self.changes.append(f"ADOPT {market.id}: {key} {kept} events")
            self.unwatch(probe_url or key)
        elif kept >= self.t.watch_min:
            self.add_watch({"name": f"{market.id} {key} ({kept} upcoming)", "kind": "status",
                            "url": probe_url or f"https://{base_key}", "alert_when_alive": False,
                            "adopt": f"{market.id}: {key} {json.dumps(params)}",
                            "_auto": {"seen": today_iso(), "probe_events": kept}})
            summary["watch"].append(f"{key} ({kept})")

    def add_watch(self, item: dict) -> None:
        for w in self.watch.get("watch", []):
            if w.get("url") == item["url"]:
                w.update({k: v for k, v in item.items() if k != "name"})
                return
        self.watch.setdefault("watch", []).append(item)

    def unwatch(self, url: str) -> None:
        self.watch["watch"] = [w for w in self.watch.get("watch", []) if w.get("url") != url]

    def discover_social(self, market: S.Market, city: str, results, summary: dict) -> None:
        cc = market.country.upper()
        # Subreddits: guesses first (they are the usual r/<Country>), then search hits.
        subs: list[str] = []
        for g in self.t.d.get("subreddit_guesses") or []:
            name = self.fill(g, market, city)
            if name and name.lower() not in [s.lower() for s in subs]:
                subs.append(name)
        telegram: list[str] = []
        for q in self.t.social_queries():
            for url, _ in self.searcher.search(self.fill(q, market, city)):   # no mention filter: t.me titles are channel names
                m = re.search(r"reddit\.com/r/([A-Za-z0-9_]+)", url)
                if m and m.group(1).lower() not in [s.lower() for s in subs]:
                    subs.append(m.group(1))
                m = re.search(r"t\.me/(?:s/)?([A-Za-z0-9_]{5,})", url)
                if m and m.group(1).lower() not in ("joinchat", "share", "addstickers") \
                        and m.group(1) not in telegram:
                    telegram.append(m.group(1))
        for sub in subs[:3]:
            if sub.lower() in {k.lower() for k in self.reddit_map} or _reddit_tripped:
                continue
            alive, note = probe_subreddit(self.engine, sub)
            log.info("  r/%-28s %s %s", sub, "ALIVE" if alive else "quiet", note)
            if alive:
                self.reddit_map[sub] = market.id
                self.changes.append(f"REDDIT {market.id}: r/{sub} ({note})")
                summary["adopted"].append(f"r/{sub}")
        for ch in telegram[:5]:
            if ch in self.telegram_map:
                continue
            alive, note = probe_telegram_channel(self.engine, ch)
            log.info("  t.me/%-25s %s %s", ch, "ALIVE" if alive else "quiet", note)
            if alive:
                self.telegram_map[ch] = market.id
                self.changes.append(f"TELEGRAM {market.id}: t.me/{ch} ({note})")
                summary["adopted"].append(f"t.me/{ch}")

    # ---- retirement -------------------------------------------------------

    def retire(self) -> None:
        report = load_json(REPORT, {})
        dead = {(a["market"], a["source"]) for a in report.get("alerts", [])
                if a.get("state") in ("dead", "failed")}
        if not dead:
            return
        for mid in S.list_markets():
            path = os.path.join(MARKETS_DIR, f"{mid}.json")
            md = load_json(path, None)
            if not md:
                continue
            changed = False
            for key in list((md.get("sources") or {}).keys()):
                params = md["sources"][key] or {}
                if (mid, key) in dead and params.get("_auto"):
                    url = str(params.get("base") or (params.get("urls") or [""])[0] or key)
                    self.add_watch({"name": f"{mid} {key} (retired)", "kind": "status", "url": url,
                                    "alert_when_alive": True,
                                    "adopt": f"{mid}: {key} {json.dumps({k: v for k, v in params.items() if k != '_auto'})}",
                                    "_auto": {"retired": today_iso(), "reason": "dead/failed"}})
                    del md["sources"][key]
                    changed = True
                    self.changes.append(f"RETIRE {mid}: {key}")
            if changed:
                save_json(path, md, self.args.dry_run)

    # ---- expansion ----------------------------------------------------------

    def expand(self) -> None:
        have = {S.load_market(m).country.upper() for m in S.list_markets()}
        order = []
        for cc, row in self.geo.countries.items():
            if cc in have or row.get("continent") == "AN":
                continue
            if self.args.countries and cc not in self.args.countries:
                continue
            last = (self.state["countries"].get(cc) or {}).get("last")
            if days_since(last) < COUNTRY_RETRY_DAYS and not self.args.force:
                continue
            order.append((self.geo.region_order(cc), row.get("name", ""), cc))
        order.sort()
        todo = [cc for _, _, cc in order]
        if self.args.expand_limit:
            todo = todo[: self.args.expand_limit]
        log.info("expansion: %d countr%s to try: %s", len(todo), "y" if len(todo) == 1 else "ies", " ".join(todo))
        for cc in todo:
            anchor = self.geo.anchor(cc)
            if not anchor:
                self.state["countries"][cc] = {"last": utcnow(), "result": "no gazetteer city"}
                continue
            name = str(anchor.get("a") or anchor.get("n"))
            mid = f"{cc.lower()}-{slugify(name)}"
            pop = int(anchor.get("p") or 0)
            radius = 25 if pop < 200_000 else 40 if pop < 1_000_000 else 50
            country = self.geo.country_name(cc)
            md = {
                "id": mid, "name": f"{anchor.get('n')}, {country}" if anchor.get("n") != country else country,
                "country": cc, "tz": anchor.get("tz") or "UTC",
                "lat": float(anchor["lat"]), "lng": float(anchor["lng"]), "radius_km": radius,
                "currency": str((self.geo.countries.get(cc) or {}).get("currency") or "USD"),
                "auto": True, "created": today_iso(), "sources": {},
            }
            if cc in self.t.tm:
                md["sources"]["ticketmaster"] = {}
            if cc in self.t.sg:
                md["sources"]["seatgeek"] = {}
            market = S.Market(id=mid, name=md["name"], country=cc, tz=md["tz"], lat=md["lat"],
                              lng=md["lng"], radius_km=radius, currency=md["currency"], sources={})
            summary = self.discover_market(market, md)
            real = [k for k in md["sources"] if S.registry_key(k) not in ("community", "manual")]
            if real:
                md["sources"]["community"] = {}
                save_json(os.path.join(MARKETS_DIR, f"{mid}.json"), md, self.args.dry_run)
                self.changes.append(f"NEW MARKET {mid}: {', '.join(real)}")
                self.state["countries"][cc] = {"last": utcnow(), "result": f"market {mid}", "sources": real}
            else:
                self.state["countries"][cc] = {"last": utcnow(), "result": "no sources yet",
                                               "probed": summary["probed"]}
                log.info("  %s: nothing adoptable yet (retry in %d days)", cc, COUNTRY_RETRY_DAYS)
            save_json(STATE_PATH, self.state, self.args.dry_run)

    # ---- run ---------------------------------------------------------------

    def run(self) -> int:
        if self.args.retire:
            self.retire()
        for mid in self.market_ids():
            path = os.path.join(MARKETS_DIR, f"{mid}.json")
            md = load_json(path, None)
            if not md:
                continue
            market = S.load_market(mid)
            before = json.dumps(md.get("sources"), sort_keys=True)
            summary = self.discover_market(market, md)
            if json.dumps(md.get("sources"), sort_keys=True) != before:
                save_json(path, md, self.args.dry_run)
            log.info("  -> adopted %s | watch %s | %d probes, %d searches",
                     summary["adopted"] or "-", summary["watch"] or "-", summary["probed"], summary["searches"])
            save_json(STATE_PATH, self.state, self.args.dry_run)
        if self.args.expand:
            self.expand()
        self.save_side_files()
        save_json(STATE_PATH, self.state, self.args.dry_run)
        print("\n".join(self.changes) if self.changes else "discovery: no changes")
        if self.args.notify and self.changes:
            notify("\n".join(self.changes[:25]) + (f"\n(+{len(self.changes) - 25} more)" if len(self.changes) > 25 else ""))
        return 0


def notify(body: str) -> None:
    topic = os.environ.get("FIE_NTFY_TOPIC", "").strip()
    if not topic:
        print("FIE_NTFY_TOPIC not set - summary printed only")
        return
    import requests
    try:
        requests.post(f"https://ntfy.sh/{topic}", data=body.encode("utf-8"), timeout=10,
                      headers={"Title": "0 FOMO source discovery", "Priority": "default", "Tags": "mag"})
    except requests.RequestException as exc:
        print(f"notify failed: {exc}")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--market", action="append", help="market id (repeatable)")
    ap.add_argument("--needy", action="store_true", help="weakest markets first (drops, below floor, fewest events)")
    ap.add_argument("--all", action="store_true", help="every market that is due")
    ap.add_argument("--limit", type=int, default=10, help="max markets per run (0 = no cap)")
    ap.add_argument("--force", action="store_true", help="ignore the re-discovery / retry cooldowns")
    ap.add_argument("--expand", action="store_true", help="create markets for countries without one")
    ap.add_argument("--expand-limit", type=int, default=10, help="countries per run (0 = all)")
    ap.add_argument("--countries", default="", help="comma list of ISO codes to expand (default: all without a market)")
    ap.add_argument("--retire", action="store_true", help="retire _auto sources that source_health reports dead")
    ap.add_argument("--no-social", action="store_true", help="skip subreddit / Telegram channel discovery")
    ap.add_argument("--delay", type=float, default=1.5, help="per-domain request delay (s)")
    ap.add_argument("--dry-run", action="store_true", help="print decisions, write nothing")
    ap.add_argument("--notify", action="store_true", help="push the change list via ntfy (FIE_NTFY_TOPIC)")
    ap.add_argument("-v", "--verbose", action="store_true")
    args = ap.parse_args()
    args.countries = {c.strip().upper() for c in args.countries.split(",") if c.strip()}
    if not (args.market or args.needy or args.all or args.expand or args.retire):
        ap.print_help()
        return 1
    logging.basicConfig(level=logging.DEBUG if args.verbose else logging.INFO,
                        format="%(asctime)s %(message)s", datefmt="%H:%M:%S")
    logging.getLogger("bahamas_scraper").setLevel(logging.ERROR)
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except (AttributeError, ValueError):
        pass
    return Discovery(args).run()


if __name__ == "__main__":
    sys.exit(main())

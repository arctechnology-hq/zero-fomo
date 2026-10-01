# Source discovery — the pipeline grows its own source list (G5, 2026-10-01)

Until G5 every source was found by hand: a survey, a probe, a market file
edit. `discover_sources.py` does that loop on its own, weekly, at zero Claude
tokens, for every market — and opens markets in countries that have none.
Nothing changes about *what* is allowed: official APIs, public pages that
need no login, community forwarding and bots communities add themselves
(docs/GLOBAL_DESIGN.md §1). Discovery only finds more of the same.

## What one run does for a market

1. **Search the public web** in the market's language(s): the query templates
   in `source_templates.json` (`{city} events calendar`, `{city} what's on this
   week`, `{country} boletos eventos`, `{city} agenda événements`, …) go to
   DuckDuckGo lite first and Bing's RSS endpoint second. Results are cached
   30 days (`feeds/health/search_cache.json`) so a re-run costs nothing.
2. **Harvest platform slugs** from the results — Eventbrite `/d/<slug>/`,
   allevents.in `/<city>`, luma.com `/<slug>` — and guess the rest (Meetup
   `<cc>--<City>`, allevents `<city-slug>`). Hand-written platform entries in
   the market file always win; discovery only fills gaps.
3. **Probe every other domain** (blocklist: social networks, OTAs, wikis,
   ticket resellers, the platforms above) with at most ~6 requests each:
   schema.org Event nodes on the page → `jsonld`; `<link>` to a calendar or
   feed → `ics` / `rss`; WordPress → The Events Calendar REST (`tribe`), an
   event post type (`wp-posts`), or the plugin's iCal export; ≥ 5 links that
   share an event path → `html-cards`. A home page gets one look at
   `/events/`, `/whats-on/`, `/calendar/`.
4. **Ground-truth** every candidate by running the reader class in-process
   for this market (`build_scraper`) and counting events dated within the
   next 365 days that survive the market filter (coordinates inside the
   radius, or the market's `countries` / `cities` words). The reader is the
   judge, not a heuristic about the page.
5. **Decide.** `kept ≥ adopt_min_events` (3) → written into
   `markets/<id>.json` as a source with `_auto` provenance (date, query or
   guess, probe count, URL); a site may appear several times under
   `reader#site` keys (`jsonld#festscanner`, `tribe#puregrenada`).
   `1–2` events → `sources_watchlist.json` with an `adopt` hint, re-probed
   every Sunday by `source_health.py --probe`. Nothing → forgotten.
6. **Social**: subreddit guesses (`r/<Country>`, `r/<City>`) and `site:t.me`
   / `site:reddit.com` searches are probed for life (≥ 3 posts in 90 days;
   ≥ 5 channel posts, the last within 60 days) and written to
   `inbox/bridges/reddit_markets.json` and `inbox/bridges/telegram_public.json`.
   The Reddit bridge and the new `telegram_public_bridge.py` (public
   `t.me/s/<channel>` previews, no account) re-read those files every poll.

## Retirement

`source_health.py --report` already flags a source **dead** (three zero runs
after a productive baseline) or **failed** (two consecutive exceptions).
`discover_sources.py --retire` removes such sources *when they carry `_auto`*
and parks them on the watchlist with `retired`; a hand-written source is never
touched by the machine. If the site comes back the Sunday probe says so and the
next discovery run re-adopts it.

## Expansion (`--expand`)

For every ISO country without a market file (Antarctica excluded) the capital
— or largest place — from the app's own gazetteer (`assets/geo/cities.json`)
becomes a candidate market: id `<cc>-<city-slug>`, radius 25/40/50 km by
population, timezone and currency from the same assets, Ticketmaster /
SeatGeek added where their coverage lists say so. Discovery then runs as
above; the file is written **only if at least one real source was adopted**
(community forwarding alone does not open a market — an empty feed helps
nobody). Countries that yield nothing are retried after 45 days. Regions
(`markets/regions.json`, mirrored to the app assets) order the work: Caribbean
first, then the Americas, Europe, Africa, Asia, Oceania.

## Schedule and what gets committed

`scrape_and_publish.ps1` runs discovery on Sundays (or any day with
`-Discover`): weakest markets first (`--needy`, markets with a health drop or
under their floor, then the fewest events, then the stalest), ten per run,
then ten new countries, then retirements, then an ntfy summary. Market files,
the watchlist and the bridge maps are committed to `main` by the task
(`discovery: sources/markets adopted on <date>`) so CI runs the same sources,
and the bridge maps are copied to fie-worker-1. The daily scrape picks new
markets up automatically; the app sees them in `feeds/markets.json` on the
next manifest build — no app release needed.

```
python discover_sources.py --market lc-castries --dry-run -v   # see what it would do
python discover_sources.py --needy --limit 10                   # weakest markets
python discover_sources.py --expand --expand-limit 0            # every country (hours)
python discover_sources.py --retire --notify
```

First dry run (2026-10-01, Castries — 3 events, flagged *drop*): adopted
allevents.in (8 upcoming), stluciatickets.com JSON-LD (8), festscanner.com
JSON-LD (30); r/SaintLucia too quiet; Reddit rate-limits after one probe
(parked for the run). 12 probes, 5 searches, about four minutes.

## Readers discovery can adopt

| Key | Reads | Params |
|---|---|---|
| `ics` | public iCalendar feeds (Google Calendar, WP `?ical=1`, MEC) | `urls[]`, `venue_default` |
| `rss` | RSS / Atom items with a date in title or body (forums, Discourse, WP categories); strict event-word prefilter | `urls[]`, `venue_default`, `keywords[]` |
| `html-cards` | server-rendered listings whose links share a marker; detail pages visited (own cap) | `urls[]`, `href_marker`, `venue_default`, `detail_cap` |
| `jsonld` / `tribe` / `wp-posts` | as before (2026-09-25) | |
| `allevents.in` | any city slug (was Nassau-only) | `city`, `venue_default` |
| `meetup` | public find-events page, in-person events, venue country gate | `location` (`us--fl--Miami`, `bb--Bridgetown`) |
| `luma` | luma.com city pages with coordinates | `slug` |

Every source key may carry a `#suffix` so one market can hold several sites
behind the same reader; `status.json` and the health watch track each one.

## Limits (honest)

* Facebook, Instagram, TikTok, WhatsApp stay where they were: forwarding and
  the bot bridges. Discovery does not log in anywhere.
* Resident Advisor, 10times, Songkick's search and Bandsintown's city pages
  answered 403/406 from this network on 2026-10-01 and are blocklisted until
  someone finds a readable door.
* DuckDuckGo answers `site:` queries with HTTP 202 (bot check); Bing RSS
  covers those. Reddit tolerates one RSS probe per ~20 s per IP.
* A JSON-LD aggregator (festscanner-style) is adopted on yield like any site;
  the health watch and dedup handle quality, not discovery.

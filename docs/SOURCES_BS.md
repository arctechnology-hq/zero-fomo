# Bahamas source survey (2026-09-25)

Question asked: beyond the 11 original Nassau scrapers, which Bahamian sites,
blogs, social accounts and forums can the pipeline actually read? Every
candidate below was fetched with plain `curl` (Chrome user agent) and, where
the page was empty HTML, rendered in Playwright to catch the data call. The
verdicts are what the probe showed on 2026-09-25, not what the sites claim.

## Added to the pipeline (6 new adapters, `SCRAPER_REGISTRY`)

| Key | Site | How it is read | What it yields | Markets |
|---|---|---|---|---|
| `bahamas.com` | Ministry of Tourism national calendar | `POST /ajax/functions.php?operation=search_events` (body `data[0][page]=1`) returns every approved event as one JSON object; the HTML page is an empty shell. Records carry island/area, venue, price, recurrence, `seo_name`. | 55-57 records nationwide; ~14 New Providence, ~7 Grand Bahama, the rest Family Islands (homecomings, regattas, fishing tournaments). Weekly items resolve to the next weekday. | bs-nassau, bs-freeport (`areas` filter) |
| `tourismtoday` | tourismtoday.com (Ministry of Tourism industry site, Drupal) | `/events?island=<id>&page=N` server-rendered `.views-row` teasers + detail fields (`field--name-field-event-venue/-address/-island/-date/-category`). Island ids: 34 Nassau & PI, 6 Bimini, 28 Eleuthera & Harbour Island, `All`. | ~21 events, mostly Family Islands; 2-3 Nassau. Featured slider is unfiltered. | bs-nassau (`island: 34`) |
| `nassauparadiseisland` | Nassau Paradise Island Promotion Board calendar | Server-rendered `<article class="event card">`: month/day spans (no year, ranges), tagline categories, location, time, external Learn More link. `?page=` does nothing. | 15-17 resort / culinary / sporting dates on New Providence. | bs-nassau |
| `atlantis` | atlantisbahamas.com/events (Nuxt, SSR) | `div[class*=headline-3]` title + weekday date without year + time span + venue row. Cards carry no per-event link. | 5 headline events (Bocelli NYE, Alex Warren, Battle 4 Atlantis, R&B brunch, Cirque). | bs-nassau |
| `bahamar` | bahamar.com/special-events (WordPress) | `.callout-text-container` cards: `.cal-healdine`, `.description`, `.date` ("October 21 – 25", "Friday & Saturday"), Learn More link. | 5 (Culinary & Arts Festival, WSOP Paradise, Baha Mar Cup, prix fixe season, Turbo Hold'em). | bs-nassau |
| `tikkets` | bahamas.tikkets.com (Bahamian ticketing start-up) | Public JSON `GET /api/events` (ISO start/end, venue, city, price, category, slug → `/events/<slug>`). No paging observed. | 1 today (Paradise Plates); clean feed worth polling as it grows. | bs-nassau, bs-freeport |

Shared helpers added with them: `span_dates()` (year-less dates and ranges:
past single dates roll a year forward, ranges already underway resolve to
today), `_next_weekday_date()` / `_weekday_fallback()` (recurring weekday
listings), and `location_verdict()` now lets the venue field win when it names
another island (Smith's Point Fish Fry, Freeport was landing in Nassau because
"fish fry" is a New Providence keyword).

## Evaluated and rejected (for now)

| Site | Probe result | Why not |
|---|---|---|
| Facebook groups "Nassau News & Events", "Bahamas Local Events"; Facebook event pages | 200 but login wall; no post content in HTML | No public API; the share-to-0 FOMO forwarding path is the integration. |
| Instagram @bahamasnightlifenow and promoter accounts | 200, login wall, 0 captions | Hashtag bridge is built and blocked on Meta App Review (`docs/META_APP_REVIEW.md`). |
| events.gbpa.com (Grand Bahama Port Authority EventHub) | WordPress, RSS + sitemap OK, `tribe/events` REST 404 | 1 upcoming (customer-service training), 40+ past. Revisit when GBPA posts public events. |
| nagb.org.bs (National Art Gallery) | Tribe Events REST works, returns 0 upcoming | Calendar empty; keep the endpoint in mind: `/wp-json/tribe/events/v1/events`. |
| bahamascarnival.com | Tribe Events REST + iCal (`/events/?ical=1`) | 0 events off-season; seasonal (May). Add when the 2027 dates post. |
| grandbahamavacations.com festivals page | Static month grids, no events for Sep-Nov | Year-round blurbs only (fish fry, Tony Macaroni, brunches) — covered by bahamas.com. |
| Nassau Guardian, Tribune242, Eyewitness News | News only; Guardian's BLOX `/calendar/` JSON returns 0 rows; Tribune has no calendar | Coverage is editorial, not listings. RSS could feed the inbox extractor later. |
| 100 JAMZ `/events/`, `/local-events` | 65-byte stubs, 429 on first hit | Nothing published. |
| Dundas Centre `/whats-on` | Squarespace text blurb of the 2025 season | No dated listings. |
| bahamasnet.com calendars | Archive from 2003-2011 | Dead. |
| Bahamas National Trust, ZNS, Bahamas Bowl, BAAA, Rotary, Fusion Superplex, Bahamas Weekly, Chamber of Commerce | 404 / DNS failure / no events section | Nothing machine-readable. |
| Sandyport, Margaritaville Nassau calendars | Month selector with generic resort activities | Rock climbing / scavenger hunts, not events. |
| Meetup (Nassau), Luma (`luma.com/nassau`), Posh, Humanitix, 10times, Yelp, eventseeker | 0 Nassau events, 403, or 404 | No Bahamas inventory yet; Posh "explore" ignores the city parameter. |
| allevents.in/freeport, bandsintown Freeport | 1 card / 403 (Cloudflare) | Existing adapters can take a Freeport URL when there is inventory; Bandsintown needs the Playwright path. |

## Where the next real gains are

1. **Promoter flyers** still live on Instagram/WhatsApp. The forwarding inbox
   plus the Telegram/Discord/Reddit bridges are the answer, not scraping.
2. **Tikkets and Ticket Flare** are the local ticketing rails; both are
   already read. Watch for new Bahamian platforms the same way (probe
   `/api/events`, `wp-json/tribe`, `__NEXT_DATA__` first).
3. **Family Islands**: bahamas.com + tourismtoday now give enough inventory to
   open Exuma / Eleuthera / Abaco markets when the app wants them; the
   `areas` parameter on `bahamas.com` does the split without new code.

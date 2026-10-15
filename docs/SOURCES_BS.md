# Bahamas source survey (2026-09-25)

Question asked: beyond the 11 original Nassau scrapers, which Bahamian sites,
blogs, social accounts and forums can the pipeline actually read? Every
candidate below was fetched with plain `curl` (Chrome user agent) and, where
the page was empty HTML, rendered in Playwright to catch the data call. The
verdicts are what the probe showed on 2026-09-25, not what the sites claim.

## Community layer — churches, charities, schools (2026-10-06)

Question asked: the feed had the resorts, the ticketing rails and the
promoters, but not the parish fun fair, the church tea, the college fair at
Most Holy Trinity or the Rotary gala. 37 Nassau sites (dioceses, parishes,
ministries, the university, the National Trust, charities, museums, radio
stations, the newspapers' religion desks, two Facebook-event mirrors) were
probed through `discover_sources.py --seed` and the new community query bank
(`docs/SOURCE_DISCOVERY.md` → *Seeds and the community query bank*). Readers
judged, not page looks.

### Adopted (hand-written in `markets/bs-nassau.json`)

| Key | Site | How it is read | What it yields |
|---|---|---|---|
| `jsonld#stayhappening` | stayhappening.com/nassau + happeningnext.com/nassau (same backend) | Facebook public events mirrored as schema.org `Event` nodes with venue, address and start date; one page of 40, `?page=` ignored, category pages are subsets | 37 upcoming on adoption: Annual College Fair (Most Holy Trinity), Our Lady's Church Fair 2026, International Cultural Festival, GFNY Bahamas, Haunted Homecoming, Vashawn Mitchell gospel concert, Boarding Schools Fair, promoter parties — the church / school / community events the forwarding inbox was the only path to |
| `tribe#rotarybahamas` | rotarybahamas.org (Rotary Clubs of The Bahamas district calendar, The Events Calendar REST) | 100 records, 90 of them club and committee meetings; `exclude_title` regex (`meeting|committee|board|…`) keeps the public ones | Foundation Gala, Fox Hill Fun Run/Walk, Vision Health Expo, Rotaract pub crawl, CPR training, fellowship nights |
| `tribe#cancersociety` | cancersocietybahamas.org (The Events Calendar REST) | standard tribe | 3 upcoming fundraisers on adoption |

### Alive but nothing dated 2026 yet (watchlist, re-probed every Sunday)

| Site | What the probe showed | Adopt as |
|---|---|---|
| bahtcianglican.org (Anglican Diocese of The Bahamas & TCI) | Modern Events Calendar, 85 event posts: Epiphany Annual Church Fair, Holy Spirit Steak Out & Fair, St. Margaret's Church Fair (souse out 7 AM), Catechists' Conference, Festival of Lights & Music — all 2025 dates. The `wp-posts` reader now dates MEC posts from the single pages (`mec-start-date-label`), so the day a 2026 fair is posted the watch flips | `wp-posts#bahtcianglican` `base=https://bahtcianglican.org type=mec-events` |
| archdioceseofnassau.org (eCatholic) | `/icalendar.ics` exists and is empty; `/events` says "no upcoming events" | `ics` on the feed URL |

### Evaluated and not adoptable now

| Site | Probe result |
|---|---|
| stjosephbahamas.com/events (St. Joseph's Parish) | GoDaddy builder, events as prose paragraphs ("November 16th … Fun Run Walk & Health Fair", 90th-anniversary 2025); no dates in markup. Seed kept; the inbox extractor is the right reader for pages like this |
| southbahamasconference.org (SDA) | Static 2024 calendar text, nothing newer |
| bahamasconference.org, bahamasmethodist.org, bfmionline.com (BFMI), evangelistictemple.org, bnt.bs, archdiocese `/calendar` | JS shells / no event markup; seeds kept |
| ub.edu.bs, bahamashumane.org, handsforhunger.org, lyfordcayfoundations.org, jabahamas.org, bahamashistoricalsociety.com, nagb.org.bs (tribe REST still 0), thebahamaschamber.com, ewnews.com, guardiantalkradio.com, islandfmonline.com, graycliff.com, johnwatlings.com, pirates-of-nassau.com, thenassauguardian.com/religion RSS, tribune242.com | Feeds / post types answer but carry no dated upcoming events (the Guardian religion RSS is AP wire and columns; church concerts appear only as features) |
| rcsen.org, portal.clubrunner.ca (Rotary club sites) | ClubRunner, JS-rendered; covered by the district calendar above |
| thingstodoinnassau.com/events | Year-round blurbs, "dates vary yearly" |

The honest remainder is unchanged: most parishes and ministries announce on
Facebook and WhatsApp only. The Facebook mirrors above catch the events that
promoters mark public; the share-to-0 FOMO inbox catches the rest.

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

## BahamasLocal re-platformed (2026-10-13)

`bahamaslocal.com` had read **zero** since at least 2026-10-06: the adapter's
`/events.html` and `/eventsbrowse/…` URLs are now 404. The site's "Monthly
Calendar" widget loads `/json-events.php` — a FullCalendar feed of every
upcoming event (32 on 2026-10-13, through 2027-01-02) with `start`/`end`
datetimes and the detail URL (titles truncated, `end` often junk). The adapter
now reads the feed for *which/when*, the `/events/` cards (`div.media h6 a`
+ `<strong><i>date</i></strong> <i>time</i>` + text) for titles and
descriptions of the next two weeks, and the detail page (`#content .cblock
h4`) for the rest (detail cap applies). No structured venue; `location_verdict`
reads the island from the text. First run: 32 found, 31 exported, 1 parked.

## Dead on 2026-10-13: bahaevents.com, bid-bahamas.com

Both Nassau adapters have read zero since at least 2026-10-06. Probed
2026-10-13: both hostnames resolve to Cloudflare, plain HTTP answers 409,
HTTPS fails the TLS handshake (curl/schannel) and Chrome shows an error page
— the sites are gone, not bot-blocked. The adapters stay registered (the
health watch keeps them at zero at no cost) so a relaunch is noticed; nothing
to fix. Also checked: `events.gbpa.com` (watchlist, Freeport) is a WordPress
site whose newest content is 2019 — no event post type, no Tribe REST, posts
endpoint empty; `100jamz.com/events/` answers 429; `fusionsuperplex.com`
does not connect.

## Family Islands open (2026-10-15): Abaco, Exuma, Eleuthera, Bimini

Question asked: the feed covered Nassau and Freeport only, while `bahamas.com`
had been carrying Family Island inventory all along (55 records nationwide on
2026-10-15: 14 Great Exuma, 15 Eleuthera, 8 Grand Bahama, 2 Bimini, 1 Abaco).
Which islands have enough *machine-readable* inventory to stand as markets of
their own, and what else is out there? Every candidate was fetched and, where
it looked alive, run through the reader in-process with the market filter on.

### Markets opened

| Market | Centre | Sources (hand-written) | On adoption |
|---|---|---|---|
| `bs-marsh-harbour` — Marsh Harbour, Abaco | 26.54, -77.06, 70 km (Treasure Cay, Hope Town, Green Turtle Cay) | `bahamas.com` (`areas` = Abaco settlements and cays), `dated-list#abacosun`, `dated-list#waterwayguide` | Abaco Sun 22 exact-dated (Pink Rose Ball, pickleball tournament, Hope Town Music Festival, Christmas Sparkle Market, Merrymakers show, Junkanoo + fireworks, Devour! The Beach, the spring fishing tournaments); Waterway Guide 6; bahamas.com 1 |
| `bs-george-town` — George Town, Exuma | 23.51, -75.76, 90 km (Staniel Cay to Little Exuma) | `bahamas.com` (`areas` = Exuma settlements and cays) | 14: National Family Island Regatta, Exuma Junkanoo, Farmer's Cay Festival, Barraterre / Rolleville regattas, the homecomings, bonefishing championship |
| `bs-governors-harbour` — Governor's Harbour, Eleuthera | 25.20, -76.24, 70 km (Spanish Wells to Bannerman Town) | `bahamas.com`, `tourismtoday` (`island: 28`), `dated-list#waterwayguide` | bahamas.com 15, tourismtoday 17 (the Ministry's Eleuthera & Harbour Island calendar), Waterway Guide 5 (Friday fish fry, Spanish Wells markets, Beach Walk, Governor's Harbour Homecoming, Music in the Garden) |
| `bs-alice-town` — Alice Town, Bimini | 25.73, -79.30, 30 km | `bahamas.com`, `dated-list#waterwayguide`, `ics#rwbimini` (Resorts World's Events Calendar iCal, proposed by a discovery dry run from the seed list: DJ nights, countdown party; room offers dropped by title) | Resorts World 6; Waterway Guide 23 (Homecoming, Junkanoo, Tom Tucker tournament, the Ministry's boating flings, Black Boaters Bash, Slicker Wahoo) minus the public holidays dropped by `exclude_title`; bahamas.com 2 |

First full runs (2026-10-15, `--no-details`): Abaco 25 exported / 0 parked,
Exuma 14 / 1, Eleuthera 26 / 0, Bimini 24 / 1; Freeport stays at 8 (its
Eventbrite slug now resolves and keeps one Freeport-venued event).

Family Island markets do **not** set `island_filter` (that is the New
Providence verdict); they bound their sources with `areas` (bahamas.com),
`island` (tourismtoday) and `cities` lists of settlement names, so a Nassau
record never lands in an island feed.

### The `dated-list` reader (new, 2026-10-15)

The Family Islands publish their year as prose, not cards: Waterway Guide's
per-island calendars (`January 8-12, 2026 -- Back to Da' Cay Regatta, Cupid's
Cay.`; a date on a line of its own followed by `Title -- Place`; `Jan 1st —
Title`), the Abaco Sun's month table (`NOVEMBER 2026` / `13-15` / title /
place). `dated-list` reads all four shapes, resolves year-less dates with the
same roll-forward policy as `span_dates()`, turns "Every Friday" / "Every 2nd
& 4th Saturday" into the next occurrence, and takes only exact dates —
"Mid-February", "October TBD", "Easter Weekend" are skipped rather than
guessed (the Abaco Waterway page is two-thirds such lines; the Abaco Sun has
the exact ones). Discovery now proposes it for any page with five or more
date-led lines (`docs/SOURCE_DISCOVERY.md`).

Two reader bugs surfaced on the way and are fixed for every market:

* `allevents.in` city pages without JSON-LD (`/exuma`, `/eleuthera`,
  `/marsh-harbour`, `/freeport`) are padded with worldwide picks — Bangalore
  jewel shows, Gujarat expos — whose cards carry no venue; the card fallback
  stamped them with `venue_default` and they passed the market gate (144
  records parked, 10 exported on the first Exuma run). In city mode the reader
  now trusts JSON-LD only. The pages stay on the watchlist (`html` marker
  `"@type": "Event"`) so inventory is adopted the day it appears.
* `eventbrite` city slugs with no local inventory (`the-bahamas--exuma`,
  `the-bahamas--freeport`) answer with the worldwide *online* events feed
  (empty venue); the reader now applies `cities` / `countries` when a market
  names them. `bs-freeport`'s slug is corrected to `the-bahamas--freeport`
  (`bahamas--freeport` was a 404 since the market opened).
* `infer_category`: the keyword `tour` matched inside *tournament*, so every
  fishing tournament was a concert; it is a whole-word regex now, and
  tournaments / fishing / wahoo / billfish / marlin / bonefish are sport.

### Evaluated and not adopted (2026-10-15)

| Site | Probe result |
|---|---|
| visitfreeport.com (The Events Calendar, 467 upcoming) | Freeport, **Maine** — Pineland Farms, Maine GearShare. A trap for any "Freeport events" query. |
| gbchamber.org (Grand Bahama Chamber, Events Calendar REST + iCal) | Alive, 0 upcoming — watchlist (`tribe`) |
| grandbahamavacations.com festivals page | Month grid with no dated listings (the reader finds two grid cells); seed only |
| events.gbpa.com | WordPress, newest content 2019 |
| portlucaya.com `/entertainment` | Blurbs, no dates |
| scb.gov.bs/events (Securities Commission) | Events Manager widget: business outlooks + public holidays; not a listings source |
| eleutheranews.com | Cloudflare challenge (403) to plain fetches; seed kept for the rendered path |
| exumabahamas.com/events, harbourislandguide.com/events, myoutislands.com/events, bimini.com/events, eleuthera-map.com | 404 / cookie wall / empty |
| georgetowncruisingregatta.com | One event (Feb 17 - Mar 3); `/events-schedule/` 404 |
| discover-eleuthera-bahamas.com festivals page | "Usually the first week in June" — no dates |
| theabaconian.com, nfir.bs, exumaregatta.com, andros.bs, longislandbahamas.com, junkanoo.com (for sale), islandhousenassau.com, nassauyachtclub.com | Dead or no DNS |
| Waterway Guide: no 2026 calendar for Exuma, Grand Bahama, Andros, the Berry Islands or Nassau (only Abaco 12686, Eleuthera 12675, Bimini 12710) | Re-check in Q1 when the 2027 pages post; the `dated-list` sources take a URL change only |

Still true: the cays and settlements announce on Facebook and WhatsApp. The
share-to-0 FOMO inbox and the bridges remain the path for those; the markets
above give the app a dated backbone (regattas, homecomings, tournaments,
festivals) on each island so a visitor opening it in Hope Town or George Town
no longer sees an empty screen.

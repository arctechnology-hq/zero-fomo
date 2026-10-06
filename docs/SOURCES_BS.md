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

# 0 FOMO — Global Design

Written 2026-09-15. Turns the Bahamas launch app into a product that works in
any country: the phone (or the user) says where they are, the mark and accents
take that country's colours, and events arrive from every source that can be
tapped legitimately. Companion docs: `ROADMAP.md` (phases), `CLOUD_ARCHITECTURE.md`
(backend), `BRAND.md` (identity).

## 1. Decisions (user, 2026-09-15)

| Question | Decision | Consequence |
|---|---|---|
| Social / chat sources | **Compliant APIs + community forwarding + public-page scraping.** No automation of ARC-owned WhatsApp numbers or Instagram accounts inside groups. | WhatsApp and private groups reach the feed only when a member forwards a post to the app. Scrapers stay per-source isolated and expect to break. |
| Phone location | **Approximate only** (`ACCESS_COARSE_LOCATION`). | City-level fix, matched offline to the bundled gazetteer; coordinates never stored or sent. Precise GPS is never requested. |
| Country colours | **Mark + accents.** | The two arms of the mark and the primary/secondary accents take the flag colours; ground, surfaces, Coral and type are brand-fixed. |
| Platform | **Android first, then KMP.** | The global UI ships on the current codebase now; `ROADMAP.md` Phase 3 ports it. |

## 2. What shipped in slice 1 (app v0.9.0)

```
assets/geo/countries.json   252 ISO countries: name, capital, continent, currency, flag colours a/b
assets/geo/cities.json      12,556 places (GeoNames: pop >= 50k, every capital, top-5 of small countries)
model/Country, Place, Geo   pure Kotlin; haversine + radius -> bounding box
data/location/Gazetteer     offline: search(prefix, country hint, prefer country), nearest(lat,lng), placesIn(cc)
data/location/DeviceLocationProvider   coarse one-shot fix (fused provider), network/SIM/locale country
data/location/UserLocationStore        persisted "where I'm looking from" (DEFAULT < NETWORK < DEVICE < MANUAL)
data/location/PhotonGeocoder           OSM/Photon for postal codes and admin areas the gazetteer cannot answer
ui/theme/ContrastMath, CountryTheme    WCAG-checked flag palette; BrandMark draws the mark with the arms tinted
ui/feed                     header = country-tinted mark + "FOMO" + location pill; location sheet
                            (use my location / city search / country list); island chips for BS,
                            Near-city / All-country / Everywhere chips elsewhere
Feed schema v2              country, market, tz per record (v1 feeds still accepted; default BS)
```

Location resolution order, unchanged in spirit from v1: Bahamas islands (offline)
→ postal patterns → world gazetteer (offline) → Photon (online) → Everywhere.

### Country colour rules (BRAND.md §9)

1. Flag colour A → left arm and primary accent; B → right arm and secondary.
2. Arms must clear 3:1 against the ground (Void in dark, Paper in light);
   accents 4.5:1. Only lightness is nudged, never hue.
3. A white/black/grey flag colour is kept where it already contrasts (white arm
   on Void) and otherwise replaced by the brand accent (Volt / Aqua). Grey is
   never an accent.
4. If the two arms collapse into one colour, the right arm falls back to Aqua.
5. The bar is always Paper; the app icon, notification icon and store art stay
   the brand Volt/Aqua. Country colour is an in-app state, not a new brand.

`CountryThemeTest` runs every bundled country through both grounds and fails the
build if any rule is violated.

## 2b. What shipped in slice 2 (G2, 2026-09-15)

```
markets/<id>.json            11 first-wave markets: bs-nassau, bs-freeport, jm-kingston,
                             jm-montego-bay, tt-port-of-spain, bb-bridgetown, ky-george-town,
                             us-miami, us-fort-lauderdale, us-orlando, us-atlanta
scraper --market <id>        per-market run: sources + params from the JSON, geo centre, tz;
                             writes feeds/<id>/events.json (+ csv); --list-markets
TicketmasterScraper          Discovery API v2, geo search (TICKETMASTER_API_KEY)
SeatGeekScraper              Platform API, geo search (SEATGEEK_CLIENT_ID [+ _SECRET])
EventbriteScraper            per-market location slug ("fl--miami", "jamaica--kingston")
build_markets_manifest.py    feeds/markets.json: id, name, country, centroid, radius, availability
scrape-and-publish.yml       Nassau strict, every other market best-effort, manifest, feeds/ on Pages
app: Market + MarketSelector nearest <= 3 markets within radius + 250 km (else nearest 1);
                             legacy single feed when the manifest is unreachable
app: EventRepository         per-market replace (never touches other markets or favourites),
                             past rows of unsynced markets purged, resync when the city changes
```

Keys are optional: a keyed source without its secret reports `skipped` and the
market still publishes from its other sources. Eventbrite answers GitHub's
runner IPs with HTTP 405 (it did for the Nassau run before G2 too), and a
rendered Playwright session is refused as well (verified 2026-09-15, run
34991978083). From a residential IP the plain request works, so
`scrape_and_publish.ps1` on RR-002 (task `ZeroFomoFeedScrape`, daily 06:00)
runs every market and force-pushes `feeds/` as the single-commit branch
`feed-data`; the Pages workflow checks that branch out and `merge_feeds.py`
takes, per market, the richer feed that is not older than 36 h. The
Ticketmaster and SeatGeek APIs remain the sustainable route for US markets
once their keys are registered as repo secrets. Rows without coordinates get the
market centroid outside the Bahamas so "Near <city>" still finds them.

Known follow-ups: the category taxonomy is Bahamas-flavoured ("Junkanoo /
Cultural" fires on "heritage" in Miami); rename to "Culture / Heritage" when the
app and pipeline can roll a slug change together. Bandsintown, Songkick and the
Bahamian ticketing sites remain Nassau-only source adapters.

## 2c. What shipped in slice 3 (G3 forwarding, 2026-09-15)

```
app: ShareActivity           SEND target for text/plain + image/* ("Send to 0 FOMO" in every share sheet);
                             shows source app, market, optional note; queues locally
app: InboxRepository         Room `submissions` (v3), image re-encoded <= 1600 px JPEG in app storage,
                             UploadWorker (WorkManager, network constraint, backoff) -> POST /submit;
                             file deleted after upload; anonymous per-install device id for rate limits
inbox/server.py              stdlib HTTP receiver: POST /submit, GET /health; validates market/kind,
                             30/device/hour, 6 MB cap, optional X-Inbox-Token; stores
                             data/<market>/<id>/{submission.json,image.jpg}
inbox/extract.py             Gemini (vision + text, JSON mode) -> extracted.json with confidence;
                             DeepSeek text fallback when Gemini is overloaded; images wait for Gemini
inbox/review.py              list / show / approve [--set field=value] / reject / auto (>= 0.85,
                             dated, venued) -> inbox/approved/<market>.json
pipeline `community` source  CommunityEventsScraper ingests inbox/approved/<market>.json per market;
                             community rows dedupe against scraped/API rows like any other source
inbox/deploy/                systemd unit, Apache vhosts (port-80 redirect + TLS proxy to 127.0.0.1:8787),
                             install.sh; TLS by certbot dns-cloudflare with a 0fomo.app-scoped token
                             (port 80 is closed on fie-worker-1, so HTTP-01 cannot work there)
```

Operational loop (RR-002, alongside the 06:00 scrape): pull `data/` from the
node, run `extract.py`, `review.py auto` for the confident ones, eyeball the
rest with `review.py list` / `show` / `approve`, and the next feed build
carries them. Verified on a synthetic WhatsApp flyer: image → "Sunset Soca
Cruise, 2026-10-03 17:00, Arawak Cay Docks, $65–$80, Nightlife" at confidence
1.0 → auto-approved → in the Nassau feed. Chat noise ("did you see that
meme") extracts to zero events.

## 3. Source matrix

Legend: **API** = official, keyed, in-terms. **Scrape** = public pages, no login,
polite rate; breaks without notice. **Forward** = a user shares a post/flyer/link
into 0 FOMO from the share sheet; the backend extracts the event. **Bot** = a bot
account the community adds to its own group/channel.

| Source | How | Status | Notes |
|---|---|---|---|
| Ticketmaster Discovery | API | to build | Free key, geo search (`latlong`+`radius`), US/CA/UK/IE/EU/AU/NZ/MX. Best single global feed. |
| SeatGeek | API | to build | Free client id, geo search, US/CA/UK. Sports + concerts. |
| Eventbrite | Scrape | live (Nassau) | Public search API was retired in 2020; the `__SERVER_DATA__` parser stays. Per-city URL pattern generalises. |
| Bandsintown | Scrape (Playwright) | live | Cloudflare-guarded; runs on the OCI fleet. Artist API is not geo-searchable. |
| Songkick / Reggaeville / Ticket Flare / ETickets Live / AllEvents.in / BahaEvents / Bid Bahamas / BahamasLocal | Scrape | live (Nassau) | Keep as the Bahamas market's source group. |
| Resident Advisor, Dice, Skiddle (UK API), Luma public pages | Scrape / API | to build | Nightlife depth in EU/US/UK markets. Skiddle has a free API. |
| Meetup | API (paid) | deferred | GraphQL API requires a Meetup Pro subscription; scrape public group pages instead. |
| Instagram | API (hashtag search) | to build | Business account + app review; 30 hashtags / 7 days per account (`#nassauevents`, `#kingstonparty`, …). Posts are images: OCR + LLM extraction. |
| Facebook events / pages | Scrape + Forward | to build | No public events API. Public event pages parse; everything else arrives by forwarding. |
| TikTok, X, Threads | Forward | by design | No affordable search API; forwarding covers them. |
| WhatsApp groups / statuses | Forward | to build | No read API. "Share to 0 FOMO" from any chat is the whole integration, and it fits how promoters actually work. |
| Telegram | Bot + channels | to build | Bot with privacy mode off inside groups that add it; public channels read without joining. |
| Discord | Bot | to build | Same model as Telegram. |
| Reddit | API | to build | Free tier is enough for city subreddits. |
| Organiser portal | First-party | Phase 4 | Claim, correct, upload media; becomes the highest-trust source. |

### Forwarding pipeline (the WhatsApp/Instagram answer)

1. App registers a `SEND` intent filter for `text/*` and `image/*` ("Share to 0 FOMO").
2. Client posts the payload to `api.0fomo.app/inbox` with the user's market and
   an anonymous device token (accounts arrive in Phase 4; until then rate-limit
   by token).
3. Extraction worker: OCR (Cloud Vision or Tesseract on the OCI fleet) + an
   LLM extraction prompt through the FIE tiers (Gemini for images, DeepSeek for
   text) → `{name, starts_at, venue, price, category, source_hint}` with a
   confidence score.
4. Confidence ≥ 0.85 and a venue that geocodes inside the market → published
   with `source = community`; otherwise it lands in a review queue (Monday.com
   board or the organiser portal later).
5. Duplicates melt into existing records through the same fuzzy dedup the
   scraper already uses; the forwarded post becomes an extra `source` entry.

## 4. Market model

A market is `{id, country, name, centroid, radius_km, tz, currency, sources[]}`
in a YAML file under `markets/` (Phase 4 onboarding kit). The scraper runs once
per market with `FEED_COUNTRY / FEED_MARKET / FEED_TZ` set, and the feed builder
writes `feeds/{market}/events.json`. The app keeps one feed URL today; the
multi-market client picks the nearest market feed(s) to the user's place and
falls through to the on-demand geo API (Ticketmaster/SeatGeek proxied and cached
per tile) for anywhere without a curated market. That keeps the "static reads"
principle from `CLOUD_ARCHITECTURE.md` for curated cities and still answers a
user who opens the app in Lisbon.

## 5. Privacy and store compliance

- Play Data safety: Location → Approximate location → collected: **No** (never
  leaves the device), used for app functionality. Prominent disclosure is not
  required because nothing is transmitted.
- `site/privacy.html` updated 2026-09-15 to describe the coarse fix and the
  offline match.
- Forwarded content: the sender's identity is not stored; the post content is
  processed for event fields and the original is discarded after extraction
  unless it is published as the event's media (organiser-uploadable later).

## 6. Next slices

1. ~~Global sources~~ shipped as G2 (above); keys live since 2026-09-15 (US
   markets ~1,000 events each; Ticketmaster and SeatGeek return nothing for
   the Caribbean, which stays Eventbrite + forwarding). Still open: a geo-API
   fallback for users outside every curated market (needs the Cloud Run proxy
   from `CLOUD_ARCHITECTURE.md`), and raising the per-market page cap once the
   app paginates (SeatGeek is capped at 600 by `--max-pages`).
2. **Forwarding**: share-sheet intent, `/inbox` on Cloud Run, extraction worker
   on the FIE tiers, review queue.
3. **Telegram/Discord bots** and **Instagram hashtag** ingestion.
4. **Accounts** (Firebase Auth) so forwarding, saves and reminders sync.
5. **KMP** restructure → iOS with the same UI.

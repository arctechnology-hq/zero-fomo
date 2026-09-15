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

1. **Global sources**: Ticketmaster + SeatGeek adapters, per-market YAML, feed
   builder per market, the app's multi-feed client and the geo-API fallback.
2. **Forwarding**: share-sheet intent, `/inbox` on Cloud Run, extraction worker
   on the FIE tiers, review queue.
3. **Telegram/Discord bots** and **Instagram hashtag** ingestion.
4. **Accounts** (Firebase Auth) so forwarding, saves and reminders sync.
5. **KMP** restructure → iOS with the same UI.

# 0 FOMO — Local Events Platform (launch market: Bahamas)

Monorepo for the **0 FOMO** Android events app and its data pipeline,
covering New Providence, Bahamas (rolling two-year window).

```
comprehensive_bahamas_scraper.py   ETL pipeline: 11 sources -> dedup -> feed
manual_events.json                 Hand-curated events (social-media-only promos)
scrape_and_publish.ps1             Daily scheduled scrape (Windows task, 6 AM)
.github/workflows/                 Cloud scrape + GitHub Pages feed publishing
zero_fomo/                         Android app (Kotlin, Jetpack Compose)
```

## Data pipeline

11 sources with per-source error isolation: Ticket Flare (embedded Vue JSON),
ETickets Live, Eventbrite (`__SERVER_DATA__`), AllEvents.in, Bandsintown
(Playwright — Cloudflare-guarded), Songkick, Reggaeville, BahaEvents,
Bid Bahamas, BahamasLocal, and curated `manual_events.json`. Cross-source
fuzzy deduplication melts cross-listed events into single records.

```
pip install requests beautifulsoup4 lxml pandas python-dateutil openpyxl rapidfuzz playwright
playwright install chromium
python comprehensive_bahamas_scraper.py
```

Outputs: `New_Providence_Events.xlsx` (+ CSV) for humans,
`New_Providence_Events.json` (schema_version 2) as the app feed —
publish it as `events.json` on any static host.

### Markets (global, since 2026-09-15)

One pipeline run = one market. `markets/<id>.json` declares the sources (with
per-source params such as the Eventbrite location slug), the geo centre and
radius for the API sources, timezone and currency:

```
python comprehensive_bahamas_scraper.py --list-markets
python comprehensive_bahamas_scraper.py --market us-miami          # -> feeds/us-miami/events.json
python build_markets_manifest.py                                    # -> feeds/markets.json
```

Keyed global sources (`ticketmaster`, `seatgeek`) read `TICKETMASTER_API_KEY`
and `SEATGEEK_CLIENT_ID` (+ optional `SEATGEEK_CLIENT_SECRET`) and skip cleanly
when unset. The app fetches `feeds/markets.json` and syncs the nearest markets to
the user's city; see `docs/GLOBAL_DESIGN.md`.

## Android app

See [`zero_fomo/README.md`](zero_fomo/README.md) for the full architecture
map, build instructions, and scope decisions. Highlights: offline-first
Room cache; offline world gazetteer (252 countries, 12.5k places) plus the
Bahamian island gazetteer; approximate device location matched on-device;
country-tinted mark and accents; date + place + category + keyword filtering;
calendar export (Google / Outlook / .ics); saved-event reminders.

## Cloud feed (live at https://0fomo.app/events.json)

Push this repo to GitHub, enable **Settings → Pages → Source: GitHub Actions**,
and `.github/workflows/scrape-and-publish.yml` scrapes every 6 hours and
publishes the feed to `https://<user>.github.io/<repo>/events.json`.
Then point `FEED_BASE_URL` in `zero_fomo/app/build.gradle.kts` at it.
Note: Pages on a **private** repo requires a paid GitHub plan — either make
the repo public or use another static host.

## Planning docs

| Doc | Contents |
|---|---|
| [`docs/GLOBAL_DESIGN.md`](docs/GLOBAL_DESIGN.md) | Going global: location model, country colours, source matrix (APIs / scraping / forwarding / bots), market model |
| [`docs/ROADMAP.md`](docs/ROADMAP.md) | Phased execution roadmap, framework decision (KMP + Compose Multiplatform) |
| [`docs/CLOUD_ARCHITECTURE.md`](docs/CLOUD_ARCHITECTURE.md) | GCP core + OCI standby/scrapers + Backblaze B2 media, market/region model |
| [`docs/RELEASE_PLAYBOOK.md`](docs/RELEASE_PLAYBOOK.md) | Google Play (publisher ARC Technology) then App Store, step by step |
| [`docs/BRAND.md`](docs/BRAND.md) | Identity brief, palette, generative-AI logo prompt; assets in `branding/` |

CI: `.github/workflows/android-ci.yml` runs unit tests, lint and a debug APK on
every PR/push; a `v*` tag builds the signed AAB and ships it to the Play internal
track.

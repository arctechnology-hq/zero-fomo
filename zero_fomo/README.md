# 0 FOMO

Android events app for New Providence, Bahamas (2026) — the client for the
`comprehensive_bahamas_scraper.py` ETL pipeline.

## Stack
Kotlin · Jetpack Compose (Material 3) · MVVM with unidirectional state ·
Hilt · Room (offline-first single source of truth) · Retrofit +
kotlinx.serialization · WorkManager (6-hour feed sync) · min SDK 26.

## v1 scope decisions (locked 2026-07-27)
- **Feed**: static `events.json` on a CDN/static host — no app server.
- **Auth**: none; favorites are local (Room `favorites` table).
- **Location** (global since v0.9.0, see `docs/GLOBAL_DESIGN.md`): the
  offline `BahamianIsland` gazetteer keeps first refusal; then postal
  patterns; then the offline world `Gazetteer` (`assets/geo/`); then Photon
  (OSM) via `GeocodingService` for whatever is left. Device location is
  approximate only (`ACCESS_COARSE_LOCATION`), matched to the nearest
  gazetteer place on-device; `UserLocationStore` persists the choice and
  `ZeroFomoTheme(country)` tints the mark and accents with the flag colours.

## Wiring the feed
1. Run the pipeline: `python comprehensive_bahamas_scraper.py`
   → produces `New_Providence_Events_2026.json` (the app feed) alongside
   the Excel/CSV outputs.
2. Upload it as `events.json` to any static host (GitHub Pages, Cloudflare
   Pages, Firebase Hosting). Re-upload on every pipeline run — a cron or CI
   job doing `scrape → upload` is the whole "backend".
3. Set the host in `app/build.gradle.kts`:
   `buildConfigField("String", "FEED_BASE_URL", "\"https://<your-host>/<path>/\"")`
   (the directory that contains `events.json`, trailing slash required).

## Languages
UI strings live in `app/src/main/res/values/strings*.xml` (English) with
generated `values-es`, `values-fr`, `values-pt`, `values-ht`, `values-nl`.
Regenerate or check translations with `python tools/translate_strings.py
[langs] [--check|--from-raw]` (FIE flash tier, zero Claude tokens). Android 13+
users pick the app language in system settings via `xml/locales_config.xml`;
older devices follow the system locale.

## Building
Open the `zero_fomo/` folder in Android Studio (Koala or newer). First build
downloads the Gradle wrapper if prompted; or install Gradle 8.7+ and run:

    gradle :app:assembleDebug
    gradle :app:testDebugUnitTest      # LocationEngine classification tests

## Architecture map
```
model/            Event, Country, Place/Geo, BahamianIsland (gazetteer),
                  LocationQuery/Filter, DateRangeFilter — pure Kotlin, no Android deps
data/location/    LocationEngine: classify() + polymorphic resolve();
                  Gazetteer (offline world), DeviceLocationProvider (coarse),
                  UserLocationStore (persisted choice), PhotonGeocoder (online)
ui/theme/         Theme + CountryTheme/ContrastMath (flag palette), BrandMark
data/db/          Room: events + favorites, the two query shapes
data/network/     Retrofit DTOs ↔ entity mapping (defensive, row-level)
data/             EventRepository — offline-first orchestration
data/sync/        SyncWorker (Hilt + WorkManager, fails soft offline)
ui/feed/          Home: date chips, island chips, grouped event list
ui/detail/        Event page: Add to Calendar sheet, Get Tickets, Share
ui/calendar/      CalendarExporter: ACTION_INSERT / Outlook deep link / .ics
ui/saved/         Local favorites (fully offline)
```

## Before Play Store release
- Replace the placeholder vector launcher icon with adaptive mipmaps.
- Add real App Links (`https://` domain) alongside the `zerofomo://` scheme.
- Turn on crash reporting of your choice and write a privacy policy
  (trivial: no accounts, no PII, no location permission).

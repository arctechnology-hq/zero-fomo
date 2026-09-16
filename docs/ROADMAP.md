# 0 FOMO — Phased Execution Roadmap

Written 2026-09-11. Companion docs: `BRAND.md`, `CLOUD_ARCHITECTURE.md`,
`RELEASE_PLAYBOOK.md`. Weeks are calendar weeks from today; one part-time
engineer plus CI is the assumed capacity.

## Framework decision: Kotlin Multiplatform + Compose Multiplatform

The app is native Kotlin + Jetpack Compose today (Room, Hilt, Retrofit,
WorkManager, four unit-test suites). The question is how to reach iOS, not how
to start over.

| Option | Reuse of current code | iOS quality | Team fit | Verdict |
|---|---|---|---|---|
| **Kotlin Multiplatform + Compose Multiplatform** | `model/`, `data/location`, `EventRepository`, tests: as-is. UI: Compose files port with import changes. Room → Room KMP, Retrofit → Ktor, Hilt → Koin or manual DI | Native rendering via Skia; stable for iOS since 2025; Material 3 shared | One language, one repo, Android release never blocked | **Recommended** |
| Flutter | 0 % (Dart rewrite) | Excellent, most mature cross-platform UI | New language; 8–10 weeks before parity with today's Android app | Only if the team were starting from zero |
| React Native (Expo) | 0 % (TypeScript rewrite) | Good; native modules for calendar/notifications needed | Fits a web-heavy team | No web team here; no advantage |

If a Dart or JS codebase is a hard requirement for other reasons, choose Flutter;
otherwise KMP protects the existing investment and ships Android to Play in weeks,
not months.

## Phase 0 — Rebrand and foundation (done 2026-09-11)

- [x] All references renamed: display name, package `com.arctechnology.zerofomo`,
      module `zero_fomo/`, classes, deep-link scheme, database, calendar PRODID,
      workflows, README, scheduled-task name. Version 0.7.0 (code 8).
- [x] Brand assets in `branding/`; Android adaptive + notification icons redrawn.
- [x] `android-ci.yml`: tests + lint + debug APK on every PR/push; signed AAB →
      Play internal track on `v*` tags. Build-dir redirect made CI-safe.
- [x] Release signing via environment variables (unsigned locally, signed in CI).
- [ ] Decision: move the GitHub repo from `RGR1686/wah-gwaan` to
      `arctechnology-hq/zero-fomo` (publisher is ARC). The GitHub Pages feed URL
      changes with the transfer, so do it together with the Phase 2 feed cutover.

## Phase 1 — Play Store launch (weeks 1–3)

Gate: signed build on Production, staged to 100 %.

1. Day 1: D-U-N-S application; Play Console organisation account; upload keystore
   generated and vaulted; GitHub secrets + `play-internal` environment.
2. Target API 36 bump (AGP ≥ 8.9, Kotlin/KSP aligned); tests green in CI.
3. Crashlytics + Analytics (Firebase project `zerofomo-prod`); privacy policy page
   published on `arctechnologyhq.com`.
4. Store listing assets from `branding/`; screenshots from a Pixel 8 emulator.
5. Tag `v0.8.0` → internal → closed (1 week) → production staged rollout.
6. Register the local Windows scheduled task under its new name
   (`ZeroFomoFeedScrape`) on RR-002; it is a fallback only once Phase 2 lands.

## Phase G1 — Global client foundation (done 2026-09-15, v0.9.0)

Decisions and design in `GLOBAL_DESIGN.md`. Shipped on the Android codebase:

- [x] Offline world gazetteer (252 countries with flag colours, 12.5k places);
      device approximate location (coarse, matched on-device); typed search
      with country hints; Photon (OSM) for postal codes / admin areas.
- [x] Country-tinted mark and accents (`BrandMark`, `countryPalette`),
      contrast-checked for every country in both themes.
- [x] Feed header with location pill; location sheet (use my location / city
      search / country list); Near-city / All-country chips outside the Bahamas.
- [x] Feed schema v2 (`country`, `market`, `tz`), Room v2, privacy policy
      updated for approximate location.
- [x] G2 Global sources (2026-09-15): Ticketmaster + SeatGeek adapters,
      `markets/*.json` (11 first-wave markets), per-market feeds + manifest on
      Pages, app multi-feed client (nearest ≤ 3 markets). Keys registered
      2026-09-15 (repo secrets + RR-002 env + FIE manifest); first keyed run:
      Miami 1,065 / Fort Lauderdale 1,006 / Orlando 991 / Atlanta 1,320
      events. Open: geo-API fallback beyond curated markets waits for the
      Cloud Run proxy; Caribbean markets have no Ticketmaster/SeatGeek
      coverage and rely on Eventbrite + forwarding (G3).
- [x] G3 Forwarding (2026-09-15): "Send to 0 FOMO" share target → queued
      upload → `inbox/server.py` → Gemini/DeepSeek extraction → review CLI →
      `community` pipeline source. Live at `https://inbox.0fomo.app`
      (fie-worker-1, 2026-09-16; DNS-validated cert, port 80 stays closed).
      RR-002's 06:00 run pulls, extracts and auto-approves submissions.
- [ ] G4 Bots + social: Telegram @zerofomo_app_bot LIVE (2026-09-16).
      **Discord LIVE (2026-09-16):** app "0 FOMO" (id 1549801490304995423,
      account gunbarz, Message Content intent on), bot token + channel map on
      the node via `inbox/deploy/Set-BridgeSecret.ps1`, service
      `zerofomo-discord` active; invited (View Channels + Read Message
      History) to the test server "0 FOMO" (guild 1549810688707526707),
      watching #general 1549810693413539935 → bs-nassau. Add more channels
      with `Set-BridgeSecret.ps1 -Bridge discord -Map 'id=market,...'`;
      public servers invite via
      `discord.com/oauth2/authorize?client_id=1549801490304995423&scope=bot&permissions=66560`.
      Instagram: Meta app "0 FOMO" (id 1601642318227085) created 2026-09-16
      with instagram_basic / pages_show_list / pages_read_engagement /
      business_management; IG professional account @arctechnologyhq (renamed from the typo arctechonologyhq 2026-09-16)
      (IG user id 17841439606653137) linked to the Page "A.R.C Technology"
      (652922774570717) the same day; user token verified via `me/accounts`.
      **BLOCKED by App Review:** `ig_hashtag_search` returns error #10 —
      the "Instagram Public Content Access" feature needs Advanced Access
      even for our own account (dev mode is NOT enough; earlier note was
      wrong), and Meta's dashboard requires "Become a Tech Provider" +
      access/business verification before App Review can be submitted
      (adding the feature also failed with "Something went wrong" until
      that is done). Decision pending: go through business verification +
      App Review (days–weeks, needs legal docs for A.R.C Technology and a
      screencast of the hashtag flow), or park Instagram and rely on the
      share-sheet forwarding that is already live. Once approved:
      Access Token Debugger → Extend, then `Set-BridgeSecret.ps1 -Bridge
      instagram -Token <t> -ClientId 17841439606653137 -Map
      'nassauevents=bs-nassau,...' -Extra @{IG_APP_ID=..;IG_APP_SECRET=..}`.
**Reddit bridge LIVE in RSS mode (2026-09-16):**
      `inbox/reddit_bridge.py` polls `r/<sub>/new.rss` (no credentials;
      Reddit's app creation is approval-gated under its Responsible Builder
      Policy and the public `.json` listings are 403), keyword/flair
      prefilter, text / link / image posts → inbox; unit `zerofomo-reddit`
      watching bahamas, Jamaica, Miami, fortlauderdale, orlando, Atlanta,
      Barbados, TrinidadandTobago, CaymanIslands (8 s gap between requests,
      10-min poll). Switches to OAuth JSON automatically if
      REDDIT_CLIENT_ID/SECRET ever exist. Adjust the map with
      `Set-BridgeSecret.ps1 -Bridge reddit -Map 'sub=market,...'`.

## Phase 2 — Backend v1 on GCP + OCI + B2 (weeks 2–7, overlaps Phase 1)

Gate: `feeds.0fomo.app/bs-nassau/events.json` served from GCS via Cloudflare,
with the OCI standby passing a forced-failover drill; app pointed at it.

1. Acquire `0fomo.app`; Cloudflare zone; Terraform skeleton `infra/gcp`,
   `infra/oci`, `infra/cloudflare` with Workload Identity Federation from
   GitHub Actions.
2. Markets + sources model in Cloud SQL (single zonal for now); migrate the
   scraper into Cloud Run Jobs per source group; Bandsintown/Playwright to the
   OCI Ampere fleet posting to `/ingest`.
3. Feed builder → GCS multi-region; CDN; nightly mirror to OCI Object Storage;
   Cloudflare Load Balancer with health-check failover.
4. Media pipeline → Backblaze B2 (`0fomo-media`), `media.0fomo.app` via Cloudflare.
5. Feed schema v2 (market, tz, media); app accepts v1 and v2.
6. `FEED_BASE_URL` → `https://feeds.0fomo.app/bs-nassau/`; GitHub Pages workflow
   retired; repo transfer to `arctechnology-hq`.

## Phase 3 — Kotlin Multiplatform + iOS (weeks 4–11)

Gate: iOS build on TestFlight from CI; App Store submission.

1. Apple Developer enrolment (see `RELEASE_PLAYBOOK.md` B0 for the entity
   decision).
2. Gradle restructure: `shared/` (KMP: model, data, repository, feed client with
   Ktor, Room KMP), `composeApp/` (shared UI), `androidApp/`, `iosApp/` (Xcode
   shell). Android keeps shipping throughout.
3. `expect/actual` seams: background sync, local notifications, calendar export,
   share sheet, deep links.
4. `ios-ci.yml` on `macos-15` runners with fastlane match; TestFlight on tag.
5. Privacy manifest, App Privacy, screenshots; submit; phased release.

## Phase 4 — US + Caribbean market rollout (weeks 8–16)

Gate: 12 markets live with ≥ 15 events each and freshness ≤ 6 h; accounts and
push in production.

1. Source onboarding kit: a market is a YAML file (sources, cadence, runner,
   tz, currency) plus a smoke test with a minimum-events floor.
2. First wave: `bs-freeport`, `jm-kingston`, `jm-montego-bay`,
   `tt-port-of-spain`, `bb-bridgetown`, `ky-george-town`, `us-miami`,
   `us-fort-lauderdale`, `us-orlando`, `us-atlanta`.
3. Market picker + auto-detect: done in G1 (coarse location, on-device match;
   precise location is never requested).
4. Firebase Auth (Google + Apple + email link), saved-event sync, server-side
   reminders via FCM/APNs; Sign in with Apple ships with Google login.
5. Cloud SQL → regional HA + `us-central1` replica; Cloud Run in two regions;
   Cloud Armor rules; on-call runbook (replica promotion, OCI failover, CDN purge).
6. Organiser portal MVP (web, Next.js on Cloud Run): claim an event, upload media
   to B2 via presigned URLs, correct details.

## Phase 5 — Global foundation (weeks 16+)

- Region as a Terraform variable: `eu-w` (`europe-west2`), `latam`
  (`southamerica-east1`), `apac` (`asia-southeast1`) each add a Cloud Run deploy,
  a regional feed bucket replica, and, for EU, a regional user-data database.
- Localisation: `stringResource` externalised, first languages Spanish and
  French (Caribbean coverage), currency and time formatting by market.
- Ticketing partnerships and affiliate links per market; revenue reporting.
- Compliance: GDPR data-subject requests, App Store / Play data-deletion URLs.

## Cross-cutting rules

- Every phase ends with the deterministic checks green in CI and a tagged release;
  nothing is "done" while a workflow is red.
- Secrets live in the FIE vault the day they are created; the Doctor's
  `repos:unpushed` check enforces that every branch is on GitHub.
- The zero-token FIE audit (`/fie-audit`) runs on every infra module before it is
  applied to production.

# 0 FOMO — Release Playbook (Google Play, then App Store)

Publisher: **ARC Technology**. Package: `com.arctechnology.zerofomo`.

## Part A — Google Play (first production release)

### A0. Blockers to clear before anything else

| # | Item | Why it blocks |
|---|---|---|
| 1 | **Target API 36 (Android 16).** Since 2026-08-31 Google requires new apps to target API 36. The app is on `targetSdk = 35` / AGP 8.5.2. Bump `compileSdk`/`targetSdk` to 36, AGP to ≥ 8.9, Kotlin/KSP to matching versions, rerun tests. | Console rejects the upload otherwise |
| 2 | **Organisation account needs a D-U-N-S number.** Google Play organisation accounts (publisher name "ARC Technology") require a D-U-N-S for the legal entity. Free from Dun & Bradstreet, 5–30 business days. Apply for it on day 1 because Apple needs the same number. | Cannot finish account verification |
| 3 | **Privacy policy URL** on a domain ARC controls (`https://arctechnologyhq.com/0fomo/privacy` now, `0fomo.app/privacy` later). Content: no accounts, no PII, no location permission, network only for the feed, notifications optional. | Required in the listing and the Data safety form |
| 4 | **Upload keystore** generated once and vaulted (`FIE_KeyVault.ps1 -Encrypt`), never committed. | Loss = new app listing |

### A1. Developer account (days 1–10, mostly waiting)

1. Sign in to Play Console with an ARC Workspace identity
   (`rhan.richardson@arctechnologyhq.com`, not a personal Gmail).
2. Pay the one-time USD 25 registration fee.
3. Account type **Organisation**. Legal name exactly as on the Business Licence
   (`A.R.C Technology`, Licence 20118619), D-U-N-S, address, phone, website
   `arctechnologyhq.com`, contact email `info@arctechnologyhq.com`.
   Developer name shown to users: **ARC Technology**.
4. Complete identity verification (Google may ask for the licence document).
5. Enable the **Google Play Android Developer API**, create a service account in
   the GCP project, grant it "Release manager" in Play Console → Users and
   permissions. Its JSON key becomes the `PLAY_SERVICE_ACCOUNT_JSON` secret in
   GitHub Actions.

Check the current Play Console list of supported developer-registration
countries for the Bahamas before paying; if the Bahamas is not on it, register
the organisation with ARC's US mailing address.

### A2. Signing (day 1, 30 minutes)

```
keytool -genkeypair -v -keystore zerofomo-upload.jks -alias zerofomo-upload \
  -keyalg RSA -keysize 4096 -validity 10000
```

- Vault the `.jks`, alias, and both passwords the same day.
- In GitHub → Settings → Secrets → Actions add `ZEROFOMO_UPLOAD_KEYSTORE_B64`
  (`base64 -w0 zerofomo-upload.jks`), `ZEROFOMO_KEYSTORE_PASSWORD`,
  `ZEROFOMO_KEY_ALIAS`, `ZEROFOMO_KEY_PASSWORD`, `PLAY_SERVICE_ACCOUNT_JSON`.
- Create the `play-internal` environment (required reviewer: Rhan) so the release
  job cannot run without a human click.
- Enrol in **Play App Signing** on first upload; Google holds the app signing key,
  the vaulted key is only the upload key.

### A3. Store listing assets (days 2–5)

| Asset | Spec |
|---|---|
| App icon | 512 × 512 PNG, 32-bit, from `branding/zero-fomo-mark.svg` |
| Feature graphic | 1024 × 500 PNG, lockup on Void |
| Phone screenshots | ≥ 2, 16:9 or 9:16, 1080 × 1920 recommended; 7-inch and 10-inch tablet sets if `supportsScreens` allows tablets (it does by default) |
| Short description | ≤ 80 chars: "Everything happening near you, before it sells out." |
| Full description | ≤ 4000 chars; markets covered, offline-first, calendar export, reminders |
| Category | Events |
| Contact | `info@arctechnologyhq.com`, website, privacy policy URL |

Content rating questionnaire: Events/Utility, no user-generated content, no ads.
Data safety: no data collected or shared; optional notifications. App access:
all features available without credentials. Ads: none. Target audience: 18+ (event
listings may include bars/nightlife).

### A4. Build and internal test (days 3–7)

1. Merge the API 36 bump. Tag `v0.8.0` → CI builds the signed AAB and uploads to
   the **Internal testing** track (`.github/workflows/android-ci.yml`).
2. Add up to 100 internal testers by email; install via the opt-in link.
3. Read the **Pre-launch report** (Google runs the app on real devices; fix any
   crash or accessibility flag).
4. Verify on-device: adaptive icon on Pixel and Samsung launchers, themed icon on
   Android 13+, notification icon, deep link `zerofomo://event/<id>`, calendar
   export, feed sync on cold start with airplane mode off/on.

### A5. Closed testing → production (days 7–21)

- Organisation accounts are exempt from the 12-tester / 14-day closed-testing
  rule that applies to new personal accounts, but run a **Closed testing** round
  anyway (Nassau friends-and-family list) for one week; it is the only free crash
  data before launch.
- Promote the same build to **Production** with a **staged rollout**: 20 % →
  50 % → 100 % across three days, halting on any crash-rate spike in Vitals.
- Countries: Bahamas + United States + first-wave Caribbean at launch; add
  countries per market activation, not all at once (reviews and ratings are
  per-country).
- First review by Google typically takes 1–7 days for a new app; subsequent
  updates are usually hours.

### A6. Post-launch

- Crashlytics wired (roadmap Phase 1) before the 50 % step.
- App Links (`https://0fomo.app/event/<id>`) added once the domain and
  `assetlinks.json` exist; keep the custom scheme as fallback.
- Release cadence: tag → internal → production weekly; hotfixes skip closed testing.

## Part B — Apple App Store

### B0. Entity constraint (decide first)

Apple enrols **organisations only if they are a legal entity** (LLC, Ltd,
corporation). A sole trader trading as "ARC Technology" cannot enrol as an
organisation; the alternative is an **Individual** enrolment, where the seller
name shown on the App Store is the person's legal name.

Options:

| Option | Seller name on App Store | Lead time |
|---|---|---|
| B0-a: incorporate ARC Technology Ltd (Bahamas) first, then enrol as Organisation with D-U-N-S | ARC Technology | Incorporation weeks + Apple 2–4 weeks |
| B0-b: enrol as Individual now, migrate to the organisation later (Apple supports account transfer of apps to a new team) | Rhan Richardson | Apple 1–7 days |

Recommendation: B0-b to unblock iOS, with B0-a scheduled so the transfer happens
before marketing spend.

### B1. Technical requirements

| Requirement | Plan |
|---|---|
| iOS codebase | Kotlin Multiplatform + Compose Multiplatform (see `docs/ROADMAP.md`, Phase 3). Shared `model/`, `data/`, and UI; iOS-specific: Room → Room KMP (or SQLDelight), Retrofit → Ktor, WorkManager → BGTaskScheduler via `expect/actual`, calendar export → EventKit, notifications → UNUserNotificationCenter |
| Build host | macOS. Use GitHub Actions `macos-15` runners (Xcode 16+) so no Mac purchase is required; a Mac mini becomes worthwhile once release cadence is weekly |
| Signing | Apple Distribution certificate + App Store provisioning profile managed by **fastlane match** in a private repo (`arctechnology-hq/certificates`), unlocked by a `MATCH_PASSWORD` secret |
| Minimum iOS | 16.0 (Compose Multiplatform floor is 15; 16 covers > 95 % of devices) |
| App Privacy | "Data not collected" until accounts ship; privacy manifest (`PrivacyInfo.xcprivacy`) listing required-reason APIs (UserDefaults, file timestamps) |
| Export compliance | HTTPS only → exempt; set `ITSAppUsesNonExemptEncryption = NO` |
| Sign in with Apple | Required only if any third-party login is offered (Google) — ship both together in Phase 4 |
| Push | APNs key (.p8) uploaded to Firebase for FCM → APNs |
| Universal Links | `apple-app-site-association` at `https://0fomo.app/.well-known/` |
| Review guideline 4.2 (minimum functionality) | Offline cache, calendar export, reminders, and saved events are enough; a pure web-wrapper would be rejected |

### B2. Pipeline (added to `android-ci.yml` as a second workflow `ios-ci.yml`)

1. `macos-15` job: `./gradlew :composeApp:linkReleaseFrameworkIosArm64`, then
   `xcodebuild -scheme ZeroFomo -configuration Release archive`.
2. `fastlane match appstore` → `fastlane pilot upload` to TestFlight on tag.
3. `fastlane deliver` pushes metadata and screenshots from `fastlane/metadata`.

### B3. Timeline (calendar weeks after Play launch, assuming B0-b)

| Week | Milestone |
|---|---|
| 0 | Apple Developer Program enrolment (Individual), D-U-N-S request in flight for the org |
| 0–1 | KMP module split; Android still ships from the same repo |
| 2–5 | iOS UI parity in Compose Multiplatform; internal TestFlight builds from CI |
| 5 | Privacy manifest, App Privacy answers, screenshots (6.7-inch and 6.1-inch sets mandatory; iPad optional if `TARGETED_DEVICE_FAMILY = 1`) |
| 6 | Submit for review. First review 24–48 h typical, up to a week for a new developer; expect one rejection round on metadata |
| 7 | App Store release, phased release on (7-day automatic ramp) |
| later | Transfer the app to the ARC Technology Ltd organisation team once incorporated |

### B4. Costs

| Item | USD |
|---|---|
| Apple Developer Program | 99 / year |
| GitHub macOS runner minutes | ~0.08 / min, ~20 min per build → ~$2 per release build; free-tier minutes do not cover macOS at 10× multiplier, budget ~$30 / month during Phase 3 |
| Google Play registration | 25 once |

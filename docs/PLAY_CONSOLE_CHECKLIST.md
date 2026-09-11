# Google Play — Console Checklist for 0 FOMO

Everything below that says **you** needs the account holder in a browser: Google
verifies identity and takes payment, and neither can be delegated. Everything that
says **CI** or **done** is automated or already in the repo.

## 0. Values to enter (copy exactly)

| Field | Value |
|---|---|
| Developer name (public) | `ARC Technology` |
| Account type | Organisation |
| Legal business name | `A.R.C Technology` (business-name registration; licensee Cappucienne McEva Richardson) |
| Business Licence | Annual No. 20118619, TIN 131-446-276 (Business Licence Act 2023), issued 2026-08-26 |
| NIB employer registration | 100548900 |
| Address | #22 Denice Cay, Nassau, New Providence, The Bahamas |
| Website | `https://arctechnologyhq.com` |
| Contact email (public) | `info@arctechnologyhq.com` |
| Phone | +1 242 804 9467 |
| App name | `0 FOMO: Local Events` |
| Package | `com.arctechnology.zerofomo` |
| Category | Events |
| Privacy policy URL | `https://arctechnology-hq.github.io/zero-fomo/privacy.html` (becomes `https://0fomo.app/privacy.html` once DNS is live) |

## 1. D-U-N-S number (you, day 1, free, 5–30 business days)

1. https://www.dnb.com/duns/get-a-duns.html → "Get a D-U-N-S Number" → country
   Bahamas.
2. Business name `A.R.C Technology`, address above, phone, principal
   Cappucienne McEva Richardson, sole proprietorship, start date 1 April 2025 (per
   NIB), employees 1–4, line of business "Computer related services".
3. Upload the Business Licence PDF if asked. Save the confirmation email; the
   number arrives by email.

## 2. Play Console account (you, ~20 min + verification)

1. Sign in at https://play.google.com/console/signup with
   `rhan.richardson@arctechnologyhq.com` (Workspace account, not Gmail).
2. Choose **Organisation**. Enter the D-U-N-S number when prompted; if it has not
   arrived, the form lets you save and return.
3. Pay the one-time **USD 25** registration.
4. Verification: Google may ask for the Business Licence and an ID for the
   licensee; upload the PDFs from Drive → Registrations & Filings.
5. Developer page: name `ARC Technology`, email `info@arctechnologyhq.com`,
   website. Contact email must be verified by link.

## 3. API access for CI (you, 10 min, once the account is verified)

1. Play Console → Settings → API access → link the GCP project `zerofomo-prod`
   (create it in https://console.cloud.google.com under the ARC organisation if it
   does not exist).
2. Create a service account `play-publisher@zerofomo-prod.iam.gserviceaccount.com`,
   grant it **Release manager** in Play Console → Users and permissions, download
   its JSON key.
3. GitHub → arctechnology-hq/zero-fomo → Settings → Secrets → Actions: add
   `PLAY_SERVICE_ACCOUNT_JSON` (the file contents). The four signing secrets are
   set by `Set-ZeroFomoSecrets.ps1` (see Signing below).

## 4. Signing (done locally, secrets pending)

- Upload keystore generated 2026-09-11:
  `%LOCALAPPDATA%\FIE\secrets\zerofomo\zerofomo-upload.jks`, alias
  `zerofomo-upload`, RSA 4096, valid 27 years. Passwords sit next to it in
  `zerofomo-upload.dpapi` (Windows DPAPI, this user on RR-002 only).
- Vault it: `pwsh ./_FIE/FIE_KeyVault.ps1 -Encrypt` after adding the two files to
  the vault manifest, then `-SyncToCloud`.
- GitHub secrets: run `pwsh %LOCALAPPDATA%\FIE\secrets\zerofomo\Set-ZeroFomoSecrets.ps1`
  from a shell where `gh` is signed in as an admin of the repo
  (`arctechnologyhq`). It sets `ZEROFOMO_UPLOAD_KEYSTORE_B64`,
  `ZEROFOMO_KEYSTORE_PASSWORD`, `ZEROFOMO_KEY_ALIAS`, `ZEROFOMO_KEY_PASSWORD`.
- On first upload choose **Play App Signing** (Google-managed signing key). Then
  copy the *app signing* SHA-256 from Console → Setup → App signing into
  `site/assetlinks.json` and commit.

## 5. Create the app (you, 10 min)

Console → Create app: name `0 FOMO: Local Events`, default language English (US),
App, Free. Declarations: no ads; not designed for children; no government app.

## 6. Store listing (you paste; assets in repo)

| Asset | File |
|---|---|
| App icon 512×512 | `store/graphics/icon-512.png` |
| Feature graphic 1024×500 | `store/graphics/feature-graphic-1024x500.png` |
| Phone screenshots (2–8) | `store/graphics/screenshots/phone/` (pending: needs a device or emulator) |
| Title | `store/listing/en-US/title.txt` |
| Short description | `store/listing/en-US/short_description.txt` |
| Full description | `store/listing/en-US/full_description.txt` |

## 7. Policy forms (you, answers below)

**App content → Privacy policy:** URL from section 0.
**App access:** All functionality is available without special access.
**Ads:** No, the app does not contain ads.
**Content rating (IARC):** category Utility/Productivity/Communication/Other →
no violence, no sexual content, no profanity, no controlled substances (event
listings may link to venues that serve alcohol, but the app does not depict or
promote it), no user interaction, no sharing of location, no purchases.
Expected rating: Everyone / PEGI 3.
**Target audience:** 18 and over. Not appealing to children.
**News app:** No. **COVID-19 tracing:** No. **Data safety:**

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **No** |
| Is all user data encrypted in transit? | Yes (HTTPS) |
| Do you provide a way for users to request deletion? | Not applicable (no data collected) |

**Government apps:** No. **Financial features:** None. **Health:** None.
**Advertising ID:** the app does not use it (declare "No").

## 8. Release (CI + you)

1. Tag `v0.8.0` on `main` → CI builds the signed AAB and uploads to **Internal
   testing** (`android-ci.yml`), gated by the `play-internal` environment
   approval.
2. Console → Testing → Internal → add testers (up to 100 emails) → copy the
   opt-in link → install on a phone → run the on-device checklist in
   `docs/RELEASE_PLAYBOOK.md` A4.
3. Read the **Pre-launch report** (Console → Testing → Pre-launch report).
4. Promote the same release to **Closed testing** for a week, then
   **Production**, countries: Bahamas + United States to start, staged rollout
   20 % → 50 % → 100 %.

## 9. Blockers cleared in this repo

- [x] Target API 36 (AGP 8.11.2, Gradle 8.13, Kotlin 2.1.21).
- [x] Privacy policy page served from the Pages site.
- [x] Store icon + feature graphic rendered from the mark.
- [x] Listing copy.
- [ ] Phone screenshots.
- [ ] Crash reporting (Crashlytics) — not required for submission; planned before
      the 50 % rollout step.

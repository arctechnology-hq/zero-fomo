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
| Privacy policy URL | `https://0fomo.app/privacy.html` |

## 1. D-U-N-S number (SUBMITTED 2026-09-11, free 30-business-day track)

Requested through **Dunsguide** (CIAL Dun & Bradstreet, the D&B partner for
Central America and the Caribbean) — the D&B US "Google developer" flow is
US-only (requires a US ZIP). Account holder: Cappucienne Richardson
(rhan.richardson@arctechnologyhq.com). Workflow:
https://www.dunsguide.com/workflows/2ab8a361-c1a9-4630-a440-acf313c7965b/success

Submitted: legal name A.R.C Technology (trade name ARC Technology), Bahamas,
TIN 131446276, founded 2025, industry "Computer related consulting services",
+1 242 804 9467, arctechnologyhq.com, 2 employees, annual sales USD 10,000
(estimate), owner Cappucienne McEva Richardson associated since 2025-04-01,
address #22 Denice Cay, Venice Bay, Nassau, New Providence; TIN certificate
(Form 18A) uploaded as tax registration; CIAL Business Information Report
workbook completed and uploaded (copy in the FIE secrets folder next to the
keystore). Plan: "Access" (free). Expect the number by email within 30 business
days; D&B may phone +1 242 804 9467 to validate.
**Status 2026-09-16:** Dunsguide My Account shows reference **210745**,
"Delivery in progress", estimated delivery **2026-10-26** (39 days). A paid
plan promises 2 business days if the Play submission can't wait. Check at
https://www.dunsguide.com/my-account (CIAL login as rhan.richardson@arctechnologyhq.com).

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

## 4. Signing (done; secrets set 2026-09-11)

- Upload keystore generated 2026-09-11:
  `%LOCALAPPDATA%\FIE\secrets\zerofomo\zerofomo-upload.jks`, alias
  `zerofomo-upload`, RSA 4096, valid 27 years. Passwords sit next to it in
  `zerofomo-upload.dpapi` (Windows DPAPI, this user on RR-002 only).
- Vault it: `pwsh ./_FIE/FIE_KeyVault.ps1 -Encrypt` after adding the two files to
  the vault manifest, then `-SyncToCloud`.
- GitHub secrets `ZEROFOMO_UPLOAD_KEYSTORE_B64`, `ZEROFOMO_KEYSTORE_PASSWORD`,
  `ZEROFOMO_KEY_ALIAS`, `ZEROFOMO_KEY_PASSWORD` are set (re-run
  `Set-ZeroFomoSecrets.ps1` to rotate). Environment `play-internal` requires
  approval from the org owner and only accepts `v*` tags.
- Until `PLAY_SERVICE_ACCOUNT_JSON` exists, the release job still builds and
  signs the AAB and attaches it to a GitHub Release; upload that file in Play
  Console by hand for the first release (this is also where Play App Signing is
  enrolled).
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
| Phone screenshots (4) | `store/graphics/screenshots/phone/` (Galaxy S24+, 1080×2340) |
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
promote it), no purchases, no sharing of location. **User interaction:** the
"Send to 0 FOMO" share target lets users forward posts; forwarded content is
extracted and reviewed (auto-approved only above 0.85 confidence, otherwise by
a person) before it can appear as a listing, and users never see each other's
raw submissions. Answer the "users can share content" questions accordingly
(content is moderated before publication). Expected rating: Everyone / PEGI 3.
**Target audience:** 18 and over. Not appealing to children.
**News app:** No. **COVID-19 tracing:** No.

**Data safety** (what the app actually does as of v0.9.x; policy text at
`site/privacy.html` matches this table; verify against the code before the
first submission if anything shipped since):

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **Yes** (only via "Send to 0 FOMO") |
| Is all of the user data collected by your app encrypted in transit? | Yes (HTTPS, inbox.0fomo.app) |
| Do you provide a way for users to request that their data is deleted? | Yes (email request; no accounts exist) |
| Account creation / account deletion | Not applicable (no accounts) |

Data types to declare (leave every other type unchecked):

| Data type | Collected | Shared | Optional? | Ephemeral | Purpose | Notes |
|---|---|---|---|---|---|---|
| Location → Approximate location | **No** | No | — | — | — | Coarse fix is matched to the bundled gazetteer on-device; only the city name is kept and nothing is transmitted. Under Play's definition (transmitted off-device) this is not collected. Keep the `ACCESS_COARSE_LOCATION` permission declared in the permissions section only. |
| Photos and videos → Photos | **Yes** | No | Optional (user-initiated) | No (kept ≤ 90 days) | App functionality | Flyer image the user forwards. Processed by Google Gemini API / DeepSeek API as service providers (not "sharing" under Play's definition). |
| App activity → Other user-generated content | **Yes** | No | Optional (user-initiated) | No (kept ≤ 90 days) | App functionality | Forwarded text / link + optional note. |
| Device or other IDs | **Yes** | No | Required for the forward feature only | No | Fraud prevention, security and compliance | Random per-install ID sent only with forwarded posts, for hourly rate limiting. Not the advertising ID, not a hardware ID. |
| Personal info, Financial, Health, Messages, Contacts, Calendar, Files, Audio, Installed apps, Browsing, Crash logs, Diagnostics | No | No | — | — | — | Not collected. Crash reporting is planned before the 50 % rollout; add "Crash logs" + "Diagnostics" (collected, not shared, analytics) when Crashlytics lands and update the privacy page first. |

**Advertising ID:** the app does not use it (declare "No").

**Government apps:** No. **Financial features:** None. **Health:** None.
**Advertising ID:** the app does not use it (declare "No").

## 8. Release (CI + you)

1. Done for `v0.8.1` (versionCode 10): CI built the signed AAB and attached it
   to https://github.com/arctechnology-hq/zero-fomo/releases/tag/v0.8.1 .
   Upload that `app-release.aab` in Play Console for the first internal-testing
   release (enrol in Play App Signing when prompted). Later tags upload
   automatically once `PLAY_SERVICE_ACCOUNT_JSON` is set.
2. Console → Testing → Internal → add testers (up to 100 emails) → copy the
   opt-in link → install on a phone → run the on-device checklist in
   `docs/RELEASE_PLAYBOOK.md` A4.
3. Read the **Pre-launch report** (Console → Testing → Pre-launch report).
4. Promote the same release to **Closed testing** for a week, then
   **Production**, countries: Bahamas + United States to start, staged rollout
   20 % → 50 % → 100 %.

## 9. Blockers cleared in this repo

- [x] Target API 36 (AGP 8.11.2, Gradle 8.13, Kotlin 2.1.21).
- [x] Privacy policy page served from the Pages site at https://0fomo.app/privacy.html (DNS: 4 apex A records + www CNAME, DNS-only, added 2026-09-11).
- [x] Store icon + feature graphic rendered from the mark.
- [x] Listing copy (2026-09-16: location + forwarding lines match v0.9).
- [x] Phone screenshots (4, Galaxy S24+, 1080×2340) retaken 2026-09-16 on
      v0.9.0 with the Bahamas country theme (Nassau feed, detail, weekend
      filter, saved); status bar strip flattened.
- [x] Privacy page + Data safety answers cover "Send to 0 FOMO" (2026-09-16).
- [ ] Crash reporting (Crashlytics) — not required for submission; planned before
      the 50 % rollout step.

# 0 FOMO v0.10.1 — Google Play compliance review (5 Nov 2026)

Scope: Developer Program Policies (effective 29 Oct 2026), Play App Signing
Terms of Service, US export laws. Reviewed against the v0.10.1 (versionCode 14)
source at tag a83e2f8, the store listing in `store/`, `site/privacy.html` and the
inbox pipeline in `inbox/`. Policy text fetched live on 5 Nov 2026 from the Play
Console Help Center.

Verdict: **compliant for the internal-testing upload now; three small fixes
before the production submission** (all under one hour of work, bundled as
v0.10.2). Nothing found that would require a design change.

## 1. Facts the review rests on

| Area | Finding | Evidence |
|---|---|---|
| Target API | compileSdk 36, targetSdk 36, minSdk 26 | `zero_fomo/app/build.gradle.kts:12-17` |
| Permissions | INTERNET, ACCESS_NETWORK_STATE, POST_NOTIFICATIONS, ACCESS_COARSE_LOCATION only | `AndroidManifest.xml:4-8` |
| Location | coarse, requested in context when the user taps "Use my location", matched on-device to the bundled gazetteer, coordinates never stored or sent | `DeviceLocationProvider.kt:19-46`, `FeedScreen.kt:810-812` |
| SDKs | no ads, analytics, crash, billing or social SDKs; only Google SDK is play-services-location | `build.gradle.kts:79-115` |
| Network | `0fomo.app` feed (GET), `inbox.0fomo.app` (POST forwarded posts), `photon.komoot.io` (text geocoding of a typed place name) | `EventsApi.kt`, `InboxApi.kt`, `AppModule.kt:100` |
| Off-device data | only on "Send to 0 FOMO": text/link/flyer image, optional note, source app name, random install id, app version | `InboxApi.kt:18-29` |
| Encryption | no cryptographic code or library in the app; TLS is the OS's | grep of `zero_fomo/app/src/main`: no hits for javax.crypto, Cipher, SQLCipher, Tink, Conscrypt, BouncyCastle |
| Accounts | none; no sign-in | `strings_detail.xml:38` ("No account needed") |
| Payments | paid app, no IAP, no external payment links | grep: none |
| Moderation | every forwarded post goes through `inbox/review.py` (auto-approve at ≥ 0.85 confidence, otherwise a person); rate limit 30/device/hour; 90-day purge runs daily | `review.py:142-159`, `server.py:38`, `scrape_and_publish.ps1:91` |
| Flyer images | never republished; the public feed carries `sourceUrl` only | `EventEntity.kt` has no image field |
| Store listing | title 20/30 chars, no emoji, no rankings/price claims, no testimonials; release notes 318 chars, informational | `store/listing/en-US/*`, `store/whatsnew/whatsnew-en-US` |

## 2. Developer Program Policies

**Target API level** — Pass. New apps must target API 36 (Android 16) since
31 Aug 2026; the app targets 36.

**Permissions and APIs that access sensitive information** — Pass. The narrowest
location scope is used (coarse, foreground, user-initiated), there is no
background location, no READ_MEDIA_* (the share sheet supplies the image), no
QUERY_ALL_PACKAGES, no SMS/call log. The 27 Jan 2027 location-button rule applies
only to apps targeting API 37; not yet relevant.

**User Data** — Pass with two fixes (F1, F3 below).
- Privacy policy: public HTML, names the publisher and the app, lists every
  data type, purposes, processors, retention and the deletion route, contact
  address. Correct for the Console field. **Gap:** the policy must also be
  linked "within the app"; v0.10.1 has no in-app privacy link or About screen
  (grep for "privacy" in `zero_fomo/app/src/main`: only a code comment).
- Prominent disclosure: the share sheet states what is uploaded and that a
  reviewer checks it before anything is sent (`share_reviewer_disclaimer`);
  location is requested only after the user taps "Use my location". Adequate.
- Data safety form (checklist §7): consistent with the policy and the code.
  Images and text handed to Gemini/DeepSeek are "processing by a service
  provider", which Google's form does not count as sharing — keep "shared: No".
- Deletion: no accounts, so the account-deletion requirement does not apply;
  email deletion for forwarded posts satisfies the Data safety deletion question.

**User-Generated Content** — Pass as an editorial pipeline, with hardening
recommended (F2). Forwarded posts are not shown to other users; they become
event listings only after review, so the app is closer to a "send us a tip"
feature than a UGC platform. To remove doubt with the reviewer, add an in-app
"Report this listing" action and a one-line rights/terms statement in the share
sheet. Both are cheap and strengthen the Data safety "moderation" answer.

**AI-Generated Content** — Not applicable. The policy covers apps that generate
content from user prompts (chatbots, image generators). 0 FOMO uses models
server-side to extract fields from forwarded posts; nothing is generated for
users.

**Intellectual Property / Impersonation** — Pass. Event facts (name, date, venue,
price, link) are not copyrightable; flyer images are not republished; ticket
links go to the source. Ticketmaster and SeatGeek are consumed through their
official keyed APIs; Eventbrite through its public data with an optional API
token. Brand names appear only as plain-text source descriptions (nominative
use); no third-party logos are used anywhere in the listing or the app. Keep
the API agreements to hand in case Google asks for evidence of rights.

**Metadata (Store Listing and Promotion)** — Pass with one wording fix (F3).
"One tap to the official ticket page, never a resale middleman" is not accurate
while SeatGeek (a resale marketplace) is a source. Replace with "One tap to the
ticket page".

**Payments** — Pass. The only charge is the up-front price through Play Billing;
no in-app purchases, no external payment links. Merchant setup (bank + tax
profile) is a Console prerequisite, not a policy issue.

**Device and Network Abuse / Deceptive Behaviour / Malware / MUwS** — Pass. No
dynamic code loading, no self-update, no settings changes, no hidden behaviour;
release build has network logging off.

**Families** — Pass. Target audience 18+, not designed for children, no child
content; answer the target-audience form accordingly and never tick a
child age group.

**29 Oct 2026 policy update** — No item applies (Local Network and Manage
Contacts permissions, AppFunctions, subscription discounts, SMS/call-log, crypto,
Japan seller authorisation, Play Age Signals). Trader status declared 5 Nov.
Memory/code-optimisation thresholds arrive Feb 2027 for app quality; revisit then.

## 3. Play App Signing Terms of Service (effective 1 Aug 2021)

Accepting is appropriate and is what the signing design already assumes
(upload key in `%LOCALAPPDATA%\FIE\secrets\zerofomo\`, Google-generated app
signing key). What the terms mean for ARC:
- Google generates and holds the app signing key; **it cannot be retrieved
  afterwards** (§3.1) and Google keeps a backup indefinitely. Moving the app to
  another store under the same signature later is not possible — acceptable for
  a Play-only Android app.
- ARC grants Google a non-exclusive licence for the life of the app to build
  APKs from the bundle and optimise them (§2.1); Google states this does not
  change the app's purpose (§2.2).
- Google may change the terms with notice; continued use is acceptance (§4.1).
- The upload key stays ARC's responsibility (RSA ≥ 2048, keep secret, 2SV on
  Console users); if lost, Google resets it on request.

## 4. US export laws

- Google is the exporter from US servers; the acknowledgement checkbox is
  accurate for this app.
- The APK contains **no encryption functionality**: no crypto library is bundled
  and OkHttp uses the Android platform TLS stack. The app therefore is not
  itself an encryption item under EAR Category 5 Part 2, and no ENC
  self-classification report or CCATS is owed by ARC (a non-US company
  exporting from The Bahamas; the standard-TLS position mirrors the
  "uses only OS-provided encryption" exemption every platform applies).
- Embargoed destinations: Google blocks downloads to embargoed countries
  itself. The `cu-havana` market is informational content about events, not a
  service delivered into Cuba, and Play is not available there; no action.
- Nothing in the app (events listings) is controlled technology.

## 5. Fixes before the production submission (v0.10.2)

| # | Fix | Where | Effort |
|---|---|---|---|
| F1 | In-app privacy policy link (and the licence/about text) — an "About" entry in the feed overflow menu opening https://0fomo.app/privacy.html | `FeedScreen.kt` menu + a string | 20 min |
| F2 | "Report this listing" on the event detail screen (mailto info@arctechnologyhq.com with event name/date/id prefilled) and one line in the share sheet: "Only forward posts you have the right to share. By sending you agree to our privacy policy." | `DetailScreen.kt`, `ShareActivity.kt`, strings | 30 min |
| F3 | Store description: "One tap to the ticket page" (drop "never a resale middleman"); privacy policy: name the vision fallback processors (OpenRouter, NVIDIA) or disable those lanes; reword "We do not store who forwarded a post" to "We store only the random installation ID with the post, never an account or identity"; soften "We do not log" to "Our hosting providers keep standard short-lived server logs" | `store/listing/en-US/full_description.txt`, `site/privacy.html` | 10 min |

Optional: make `review.py purge` also drop unreviewed submissions older than
90 days so the retention statement holds without a human in the loop.

None of the three blocks the first upload to the **internal testing** track,
which is not reviewed against store policies. They must be in before
**production** review.

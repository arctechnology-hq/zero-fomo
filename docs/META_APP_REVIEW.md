# Meta App Review package — 0 FOMO Instagram hashtag bridge

Prepared 2026-09-17 so the submission is copy-paste once the two gates clear.
Decision (user, 2026-09-17): PURSUE App Review (not park).

## 0. Gate status

| Gate | Status | Where |
|---|---|---|
| Business verification (portfolio 2760677060993251 "ARC Technology") | SUBMITTED 2026-09-17, ~2 business days | Business Suite → Settings → Security Center |
| App connected to the portfolio | DONE 2026-09-17 (app 1601642318227085 owned by the portfolio) | Business Suite → Accounts → Apps |
| Tech Provider / access verification | NOT STARTED — unlocks after business verification | App Dashboard → App settings → Basic → Verification |
| Instagram Public Content Access (Advanced Access) | NOT REQUESTED — needs the two above | App Dashboard → App Review → Permissions and features |
| Claude-in-Chrome site grant for developers.facebook.com | NOT GRANTED (blocked 2026-09-17) | extension site permissions |

## 1. Identities

- Meta app **0 FOMO**, app id `1601642318227085` (Instagram app id `947236534516964`), owner = business portfolio 2760677060993251.
- Facebook Page **ARC Technology** id `652922774570717` (name change from "A.R.C Technology" under review).
- Instagram professional account **@arctechnologyhq**, IG user id `17841439606653137`, linked to the Page.
- Legal entity on documents: Cappucienne McEva Richardson trading as **A.R.C Technology**, Business Licence Annual-20118619 (expires 2026-12-31), TIN 131446276, #22 Denice Cay, Nassau, New Providence.
- Publisher / privacy contact: info@arctechnologyhq.com. Privacy policy: https://0fomo.app/privacy.html. App site: https://0fomo.app/.

## 2. What to request

Permissions and features (App Review → Permissions and features → Request Advanced Access):

1. `instagram_basic` — read the connected IG professional account (required by hashtag search).
2. `pages_show_list` — enumerate the Page the IG account is linked to.
3. `pages_read_engagement` — Page read needed by the `me/accounts?fields=instagram_business_account` lookup.
4. **Instagram Public Content Access** (feature) — `ig_hashtag_search` and `/{hashtag-id}/recent_media`. This is the one that returns error #10 today.

`business_management` is already granted and is NOT needed by the bridge; do not request Advanced Access for it (extra scrutiny for nothing).

## 3. Use-case narrative (paste into "How will your app use this permission?")

> 0 FOMO is a free Android events-discovery app published by ARC Technology (Nassau, The Bahamas). Its "community forwarding" feature surfaces public event announcements (parties, concerts, pop-ups, community meetings) that organisers post publicly on Instagram under market hashtags such as #nassauevents or #kingstonparty.
>
> The app's server-side bridge (`inbox/instagram_bridge.py`) calls `ig_hashtag_search` for a fixed, small list of hashtags (well under the 30-per-week limit) using our own Instagram professional account (@arctechnologyhq), then reads `recent_media` for each hashtag. Only PUBLIC posts are returned by this endpoint. Each candidate post is scored by an automated extractor (event date, venue, city) and then moderated before publication (auto-approve at confidence >= 0.85, otherwise a human reviewer). Approved items appear in the app's feed as an event card that links back to the original Instagram post and credits the poster's username.
>
> We store: the media id, caption text, permalink, timestamp and poster username, for at most 90 days (purged by code). We do not store or display private content, follower data, or anything about the app's own end users. No data is sold or shared with third parties; the extractor runs on our own server (Gemini/DeepSeek as processors, disclosed in the privacy policy).
>
> `instagram_basic`, `pages_show_list` and `pages_read_engagement` are used only to resolve our own professional account id from the linked Page at setup time and to keep the long-lived token valid.

## 4. Data-use checkup answers

- Data collected from the API: public media id, caption, permalink, timestamp, username.
- Retention: 90 days max (`inbox/review.py` purge + node `find -mtime +90`).
- Sharing: none. Processors: Google Gemini, DeepSeek (text extraction only).
- End-user data: none touched via these permissions.
- Data deletion: instructions on https://0fomo.app/privacy.html ("Forwarding a post" section) and by email to info@arctechnologyhq.com; a poster's request removes the card and the stored record.

## 5. Screencast script (required for Instagram Public Content Access)

Record with OBS or Windows Game Bar, 1080p, under 3 min, English notes in the description box. Show, in order:

1. Facebook Login in the Graph API Explorer as the app owner (re-auth URL below), granting instagram_basic / pages_show_list / pages_read_engagement.
2. `GET me/accounts?fields=name,id,instagram_business_account{id,username}` returning the ARC Technology Page and IG id 17841439606653137.
3. `GET ig_hashtag_search?user_id=17841439606653137&q=nassauevents` returning a hashtag id.
4. `GET {hashtag-id}/recent_media?user_id=...&fields=id,caption,permalink,timestamp` returning public posts.
5. The 0 FOMO app on a phone (or emulator) showing the resulting event card with the "via @poster on Instagram" credit and the permalink opening in Instagram.

Re-auth URL (Explorer callback):
`https://www.facebook.com/v22.0/dialog/oauth?client_id=1601642318227085&redirect_uri=https%3A%2F%2Fdevelopers.facebook.com%2Ftools%2Fexplorer%2Fcallback&scope=instagram_basic%2Cpages_show_list%2Cpages_read_engagement&response_type=token&display=page`

Until Advanced Access is granted, step 3 returns error #10. Record steps 1–2 and 5 with a stub feed if the reviewer accepts a partial screencast; otherwise wait for the feature to be addable in dev mode after Tech Provider verification.

## 6. App settings to complete before submitting

App Dashboard → App settings → Basic:
- Display name `0 FOMO`; contact email `info@arctechnologyhq.com`; privacy policy URL `https://0fomo.app/privacy.html`; user data deletion = URL `https://0fomo.app/privacy.html`; category **Entertainment**; app icon 1024x1024 (launcher asset); business verification = the ARC portfolio; **Verification → Tech Provider** (complete after business verification lands).
- Platform: add **Android** with package `com.arctechnology.zerofomo` and the release key SHA-1 (from the signing keystore, see PLAY_CONSOLE_CHECKLIST §4). Not strictly required for a server-side bridge, but reviewers expect a platform.

## 7. After approval

1. Graph API Explorer → generate a user token with the three permissions → Access Token Debugger → **Extend** (60-day long-lived token).
2. From the repo root:
   `./inbox/deploy/Set-BridgeSecret.ps1 -Bridge instagram -Token '<long-lived>' -ClientId 17841439606653137 -Map 'nassauevents=bs-nassau,kingstonparty=jm-kingston,...' -Extra @{IG_APP_ID='1601642318227085';IG_APP_SECRET='<secret>'}`
   (the bridge refreshes its own token every ~45 days when IG_APP_ID/SECRET are set).
3. `systemctl status zerofomo-instagram` on fie-worker-1; confirm one hashtag poll lands in the inbox; register `IG_ACCESS_TOKEN`/`IG_APP_SECRET` in `_FIE/dotclaude/.sync-manifest.json` and vault them.
4. Flip ROADMAP G4 Instagram from BLOCKED to LIVE.

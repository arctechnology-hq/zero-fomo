# Caribbean source survey (2026-09-25)

Follow-up to `SOURCES_BS.md`: which sites across the rest of the Caribbean can
the pipeline read, and how to add an island without writing code. Every
candidate was fetched with `curl` (Chrome user agent); JS-only pages were
rendered in Playwright to catch the data call. Verdicts are what the probes
showed on 2026-09-25.

## How islands are added now

A market is a file in `markets/<cc>-<city>.json`. Two kinds of source key:

* **Generic readers** point at a site: `tribe` (WordPress "The Events
  Calendar" REST, `base`), `wp-posts` (any WordPress custom post type with a
  date in meta/ACF, `base` + `type`), `jsonld` (pages that embed schema.org
  Event nodes, `urls`). No code per island.
* **Regional platforms** list many countries at once; each record is kept for
  a market only if `keep_for_market` passes: coordinates inside the market
  radius (x1.5) when the source has them, else the market file's `countries`
  / `cities` substring filters against the record's country, city and venue.

```json
"trinijunglejuice": {"countries": ["grenada"]},
"caribtix":         {"countries": ["jamaica"], "cities": ["kingston", "portmore"]},
"tribe":            {"base": "https://www.puregrenada.com"},
"jsonld":           {"urls": ["https://www.visitcaymanislands.com/en-us/events"], "venue_default": "Cayman Islands"}
```

## Adapters added (`SCRAPER_REGISTRY`)

| Key | Source | How it is read | Coverage seen 2026-09-25 |
|---|---|---|---|
| `trinijunglejuice` | trinijunglejuice.com regional fete / carnival calendar | The Next.js site calls a public Laravel API, `staging.trinijunglejuice.com/api/events?type=all&timestamped=true`, grouped by date; records carry venue coordinates, timezone, cost, registration link. UTC times are converted to the venue zone. | 43 upcoming: Trinidad Carnival 2027 fetes, Tobago, Dominica WCMF, St. Maarten, Belize, plus the Miami / New York diaspora carnival circuit. Bermuda/Cayman/others appear seasonally. |
| `caribtix` | caribtix.com (Jamaica-centred ticketing) | Home page is a Next.js flight payload with every listed event: venue lat/lng, UTC start/end, `eventTimezone`, `minPrice`, `buyUrl`. Regexed per event. | 95 records: Kingston (47 for the market), Portmore, Bahamas, Turks & Caicos, South Florida (34). |
| `ticketpal` | secure.ticketpal.com (Eastern Caribbean box office) and secure.ticketpaljamaica.com | Server-rendered `.singleEvent` rows: date text, epoch `data-start-date`, price range, country line. | Barbados, St. Vincent, Dominica, Antigua, Grenada; Jamaica site had a demo only. |
| `islandetickets` | islandetickets.com (Trinidad promoter platform, also Barbados / Curaçao / Miami) | Home list (`a.event-list-item`, ~300 links in date order) then per-event page; the ld+json is broken by leaked PHP so fields are regexed (name, startDate, endDate, location, price). Detail cap 60. | Barbados 5 in the first week, Trinidad film festival, Curaçao events when listed. |
| `ticketsplus` | ticketsplus.ky (Cayman box office; also Antigua, Barbados, Grenada, Saint Lucia) | Home hero slides (`/en/event/...` links, venue h5, "Sat 24 Oct 2026, 7:00 PM"). The `/en/events` grid is JS-only. | 2 Cayman events. |
| `beatstorapon` | beatstorapon.com/events/<cc>/ | Server-rendered `a.event-card`: ISO date, Plus-Code address, blurb. | jm 17, tt 14 (incl. Tobago Carnival); every other country page is empty. |
| `tribe` (generic) | WordPress The Events Calendar REST | `/wp-json/tribe/events/v1/events?start_date=today` paged. | puregrenada.com 6, bonaireisland.com 47, visitmontserrat.com 16 (mostly holidays), bahamascarnival.com seasonal, nagb.org.bs empty. |
| `wp-posts` (generic) | WordPress REST custom post types | `/wp-json/wp/v2/<type>` with `_piecal_start_date` / ACF date keys, text-date fallback. | visitantiguabarbuda.com `events_festivals` 35 (annual festival pages; only the refreshed ones carry 2026 dates). discoversvg.com `events` has no dates (dropped). |
| `jsonld` (generic) | Any page with schema.org Event nodes | Existing JSON-LD helpers, ItemList aware, `venue_default`. | visitcaymanislands.com/en-us/events 8. |
| `tikkets` (extended) | *.tikkets.com | `base` param: bahamas, jamaica, trinidad, guyana deployments. | 1 each (BZR WKND Kingston, Bacchanal Road 2027, Paradise Plates). |

## Markets (33 files)

Existing: bs-nassau, bs-freeport, jm-kingston, jm-montego-bay, tt-port-of-spain,
bb-bridgetown, ky-george-town, us-miami, us-fort-lauderdale, us-orlando, us-atlanta.

New: ag-st-johns, lc-castries, gd-st-georges, kn-basseterre, dm-roseau,
vc-kingstown, aw-oranjestad, cw-willemstad, bq-kralendijk, bm-hamilton,
tc-providenciales, ms-brades, vi-charlotte-amalie, pr-san-juan,
do-santo-domingo, do-punta-cana, sx-philipsburg, gy-georgetown, bz-belize-city,
ht-port-au-prince, cu-havana, tt-scarborough (Tobago).

Eventbrite location slugs were verified with a 200 + event JSON-LD on
2026-09-25 for every new market that lists one (Bermuda and Turks & Caicos
sit under `united-kingdom--…`, USVI under `united-states--charlotte-amalie`).
Slugs that could not be verified before Eventbrite rate-limited the probe
(Tobago, Bonaire, Anguilla, BVI, Martinique, Guadeloupe) are left out.
The app picks markets by distance from the manifest, so a new file is live
as soon as its feed publishes; no app change is needed.

## Evaluated and rejected (for now)

| Site | Probe result | Why not |
|---|---|---|
| visitjamaica.com, vacationstmaarten.com (Simpleview CMS) | Listing HTML only; `rest_v2` events endpoint 403 without the page token | Revisit with a token capture; Eventbrite + Caribtix + Beats To Rap On cover Jamaica. |
| visitbarbados.org, visittci.com, gotobermuda.com (Drupal), bvitourism.com, aruba.com, curacao.com, godominicanrepublic.com | No events API; Tribe REST 404 or HTML; calendars are JS grids | Per-site parsers, low yield vs. Eventbrite. |
| stlucia.org, visitantiguabarbuda.com Simpleview probe | HTML, not JSON | Antigua reached via `wp-posts` instead. |
| discoversvg.com `events` post type | JSON works but posts carry no dates | Dropped from vc-kingstown. |
| discoverdominica.com, visitstkitts.com, nevisisland.com, discoverpuertorico.com, visitusvi.com, travelbelize.org, caymancompass.com | 404 / 403 / JS-only | Nothing machine-readable. |
| ticketgateway.com | JS-rendered listing (Toronto / NY / Jamaica / Trinidad) | Would need Playwright; TJJ and Caribtix cover the same promoters. |
| ticketfederation.com | 403 | Cloudflare challenge. |
| wesplash.com | "getting things ready" | Not launched. |
| caribbeanevents.com, caribbeancompass.com | WordPress editorial festival lists (WooCommerce / posts), no event post type | Good for a manual annual-festival seed, not a live source. |
| Facebook groups / Instagram promoters | Login wall | Forwarding inbox and bridges. |

## Notes for the scheduled run

* `scrape_and_publish.ps1` runs every market with `--no-details`; the new
  adapters that need detail pages (Island E-Tickets) fetch them regardless,
  the others (Ticketpal, TicketsPlus, Tourism Today) simply skip enrichment.
* 33 markets at 1.5 s per request is roughly an hour end to end. Eventbrite
  rate-limited a burst probe (HTTP 429) on 2026-09-25; the pipeline's
  per-domain delay has not tripped it, but watch `scrape_and_publish.log`.
* `staging.trinijunglejuice.com` is the host the production site itself
  calls; if it moves, set `api_base` on the source params.

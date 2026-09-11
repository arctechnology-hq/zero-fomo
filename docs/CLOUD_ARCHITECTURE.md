# 0 FOMO — Cloud Architecture (US + Caribbean, global-ready)

Status: target design, 2026-09-11. Today's production is a single static
`events.json` on GitHub Pages rebuilt every 6 h; the app is offline-first and
never talks to a server except to download that file. The design below keeps
that property — **the read path stays a CDN-cached static feed** — and adds the
pieces needed for many markets, media, accounts, and failover.

## 1. Design principles

1. **Static reads, dynamic writes.** Clients read per-market feed files from a CDN.
   Only ingestion, accounts, and push need live compute. This is what makes the
   platform cheap at 10 markets and boring at 200.
2. **Market is the unit of scale.** A market is a city/island cluster with its own
   sources, timezone, currency, and feed (`bs-nassau`, `us-miami`, `jm-kingston`,
   `tt-port-of-spain`). Adding a region = adding markets, never re-architecting.
3. **Three clouds, one job each.** GCP runs everything stateful and user-facing.
   OCI runs browser-heavy scrapers and holds the warm-standby copy. Backblaze B2
   holds media. Cloudflare fronts all of it.
4. **No single region in the request path.** Global load balancer, multi-region
   Cloud Run, regional-HA database with a cross-region replica, multi-region
   buckets.

## 2. Topology

```
                         ┌──────────────────────────────┐
   apps (Android/iOS)    │ Cloudflare (DNS, CDN, WAF,   │
   ───────────────────▶  │ health-check failover)        │
                         └──────┬───────────────┬───────┘
                                │               │
              feeds.0fomo.app   │               │  media.0fomo.app
              api.0fomo.app     │               │
                                ▼               ▼
   ┌────────────────────────────────────┐   ┌──────────────────┐
   │ GCP                                │   │ Backblaze B2     │
   │  Global External HTTPS LB          │   │  bucket: 0fomo-  │
   │   ├─ GCS multi-region (US) ──feeds │   │  media (S3 API)  │
   │   └─ Cloud Run (us-east1,          │   │  ← Cloud Run     │
   │        us-central1) ── api         │   │    image worker  │
   │  Cloud SQL Postgres 16 + PostGIS   │   └──────────────────┘
   │   regional HA us-east1,            │
   │   read replica us-central1         │   ┌──────────────────┐
   │  Cloud Run Jobs + Scheduler        │   │ OCI              │
   │   (light HTTP scrapers, feed build)│   │  Ampere A1 fleet │
   │  Pub/Sub  events.raw / events.norm │◀──│  (Playwright     │
   │  Firebase Auth + FCM               │   │   scrapers)      │
   │  Secret Manager, Cloud Monitoring  │   │  Object Storage  │
   └────────────────────────────────────┘   │  (feed standby)  │
                                            └──────────────────┘
```

### 2.1 GCP — core

| Component | Choice | Why |
|---|---|---|
| Edge | Global External Application LB, serverless NEGs, Cloud Armor | One anycast IP, per-region backends, WAF |
| API | Cloud Run (`api`), min-instances 1 in `us-east1`, 0 in `us-central1`; scale to 50 | Serverless, per-region, no cluster ops. us-east1 is the closest GCP region to the Caribbean |
| Feed origin | GCS bucket `0fomo-feeds` (US multi-region), Cloud CDN | 11 nines durability, no compute in the read path |
| Catalogue DB | Cloud SQL PostgreSQL 16, PostGIS, regional HA (`us-east1`), read replica `us-central1`, PITR 7 d | Geo queries, dedup, relational sources model; HA failover < 60 s |
| User data | Same Postgres (`users`, `saves`, `devices`) | One system to back up. Firestore is an option if user data outgrows it |
| Ingestion | Cloud Run Jobs per source group, Cloud Scheduler per market cadence, Pub/Sub `events.raw` → normaliser → `events.normalised` → catalogue writer | Per-source isolation, retries, DLQ |
| Feed builder | Cloud Run Job, triggered after catalogue writes settle (Pub/Sub → Eventarc); writes `feeds/{market}/events.json` + `feeds/{market}/manifest.json`; invalidates CDN | Keeps the client contract (schema_version) |
| Identity | Firebase Auth (Google, Apple, email link) | Free, native SDKs, needed for saved-event sync and organiser accounts |
| Push | FCM (Android) / FCM → APNs (iOS) | Reminders move from local WorkManager to server-side when accounts exist |
| Secrets | Secret Manager, per-environment | Injected into Cloud Run at deploy |
| Observability | Cloud Monitoring + Logging, uptime checks on both hostnames, Error Reporting; Crashlytics in the apps | Alerts to ntfy via a Cloud Function |
| IaC | Terraform in `infra/gcp`, state in GCS, applied by GitHub Actions with Workload Identity Federation (no long-lived keys) | Repeatable per region |

Projects: `zerofomo-prod`, `zerofomo-stage` under the ARC GCP organisation. The
existing `arc-website-507500` project stays for the website only.

### 2.2 OCI — supplementary compute + warm standby

- **Scraper fleet.** Playwright/Chromium scrapers (Bandsintown and any
  Cloudflare-guarded source) are slow and memory-hungry; Ampere A1 (up to 4 OCPU /
  24 GB in the Always Free tier, more on demand) runs them at near-zero cost. Each
  scraper POSTs normalised rows to `api.0fomo.app/ingest` with a per-worker
  service token, so OCI never needs database credentials.
- **Feed standby.** A nightly Cloud Run Job mirrors `0fomo-feeds` to OCI Object
  Storage (`0fomo-feeds-standby`, S3-compatible). Cloudflare Load Balancing health
  checks the GCP origin; on failure it flips `feeds.0fomo.app` to the OCI origin.
  Result: feeds stay readable through a full GCP outage (stale by ≤ 24 h). The
  app is offline-first, so this is the only failover that matters for users.
- **DR database copy.** Optional, later: `pg_dump` nightly to OCI Object Storage,
  or a logical replica on an OCI PostgreSQL instance if RPO must drop below 24 h.
- The existing `fie-worker-1` node is the first fleet member; add nodes via the
  OCI Terraform module in `infra/oci`.

### 2.3 Backblaze B2 — media

- Bucket `0fomo-media` (private) + Cloudflare CDN in front via a Worker or a
  public bucket with Cloudflare cache rules. B2 → Cloudflare egress is free under
  the Bandwidth Alliance; storage ~$6/TB-month.
- Image pipeline: ingestion stores the source image URL only; an `image-worker`
  Cloud Run service fetches, strips EXIF, produces 320/720/1440 px WebP variants,
  writes them to B2 with the S3 API, and records the `media.0fomo.app/...` URLs
  on the event. Clients never load third-party image hosts.
- Organiser uploads (Phase 4): presigned B2 PUT URLs from the API, same variant
  pipeline on a B2 event notification → Pub/Sub.
- Lifecycle rule: variants for events > 90 days past are deleted.

### 2.4 Cloudflare — the front door

DNS for `0fomo.app`, CDN caching of feeds (edge TTL 5 min, stale-while-revalidate
1 h), WAF, rate limiting on `/ingest` and `/auth`, Load Balancer with origin
health checks (GCP primary, OCI standby). ARC already runs Cloudflare for
`arctechnologyhq.com`, so this is the same account and token model.

## 3. Data model (catalogue)

```
markets(id, name, country, tz, currency, centroid geography, status)
sources(id, market_id, kind, url, cadence, runner {gcp_job|oci_fleet}, enabled)
events(id, market_id, source_id, name, starts_at, ends_at, venue_id,
       geom geography(Point), price_min, price_max, is_free, category,
       source_url, description, media[], fingerprint, dedup_group_id, status)
venues(id, market_id, name, address, geom, aliases[])
users(id, firebase_uid, home_market_id, created_at)
saves(user_id, event_id, remind_at)
devices(user_id, platform, fcm_token, locale)
```

The client feed schema (`schema_version`) is versioned independently of the
tables. Feed builder emits `schema_version: 2` with `market`, `tz`, and `media`
added; the app keeps accepting v1 until the v2 client is at 95 %.

## 4. Region and market model

| Region key | GCP region | Markets (first wave) |
|---|---|---|
| `car` Caribbean | `us-east1` | `bs-nassau`, `bs-freeport`, `jm-kingston`, `jm-montego-bay`, `tt-port-of-spain`, `bb-bridgetown`, `ky-george-town` |
| `us-se` US Southeast | `us-east1` | `us-miami`, `us-fort-lauderdale`, `us-orlando`, `us-atlanta` |
| `us-ne` US Northeast | `us-east4` (later) | `us-new-york`, `us-boston` |
| `us-w` US West | `us-west1` (later) | `us-los-angeles`, `us-san-francisco` |

Global foundation: every table carries `market_id`; every feed path carries the
market; API responses carry `region`. Adding `eu-w` (London, `europe-west2`) or
`latam` (`southamerica-east1`) is a Terraform variable plus a Cloud Run deploy,
because nothing in the schema or client is US-specific. Data residency (GDPR) is
handled by keeping EU user rows in an EU Cloud SQL instance when that region opens;
the catalogue is public data and can live anywhere.

## 5. Availability targets

| Path | Target | How |
|---|---|---|
| Feed reads | 99.95 % | GCS multi-region + CDN + OCI standby |
| API (auth, saves, ingest) | 99.9 % | Multi-region Cloud Run behind global LB; regional-HA Cloud SQL |
| Feed freshness | ≤ 6 h per market (≤ 1 h for `now` events) | Scheduler cadence per source |
| RPO / RTO (database) | 15 min / 30 min | PITR + replica promotion runbook |

## 6. Cost envelope (monthly, USD, first 12 markets)

| Item | Est. |
|---|---|
| Cloud Run (api + jobs, mostly idle) | 15–40 |
| Cloud SQL db-custom-2-8192 HA + replica | ~190 (drop to a single zonal ~55 until Phase 4 traffic justifies HA) |
| GCS + Cloud CDN | < 10 |
| Backblaze B2 (100 GB, free egress via Cloudflare) | < 2 |
| OCI Ampere (Always Free) | 0 |
| Cloudflare (Free plan + Load Balancing $5) | 5 |
| Firebase Auth / FCM | 0 at this scale |
| **Total** | **~40–70 pre-HA, ~230 with HA database** |

Recommendation: run a single zonal Cloud SQL through Phase 3, turn on HA + replica
at the Phase 4 (multi-market) gate. Feeds are already redundant from day one.

## 7. Security

- No PII until accounts ship; the current privacy policy can say so truthfully.
- Ingest tokens per OCI worker, rotated by Secret Manager, scoped to `/ingest`.
- Cloud Run services use dedicated service accounts; database access through the
  Cloud SQL connector with IAM auth, no passwords in env.
- App Links: `https://0fomo.app/.well-known/assetlinks.json` and
  `apple-app-site-association` served from GCS via Cloudflare.
- Backups: Cloud SQL automated + nightly export to OCI (cross-cloud copy).

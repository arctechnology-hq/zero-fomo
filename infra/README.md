# 0 FOMO infrastructure (Phase 2 groundwork)

Terraform skeleton for the architecture in `docs/CLOUD_ARCHITECTURE.md`. Nothing
here has been applied yet; it is written so the first apply is a review of a
plan, not a design session.

| Module | What it creates | Credentials needed to apply |
|---|---|---|
| `gcp/` | Project-level APIs, the multi-region feed bucket with public read, the ingest/feed-builder service account, a Cloud Run Job for the scraper and a Cloud Scheduler trigger every 6 hours | `gcloud auth application-default login` on the machine, project `zerofomo-prod` created under the ARC organisation |
| `cloudflare/` | The DNS records that exist today (apex A ×4 + www CNAME to GitHub Pages) as code, plus commented-out records for the future GCP origin | API token with Zone:DNS:Edit on `0fomo.app` (create in Cloudflare → My Profile → API Tokens; store in the FIE vault) |
| `oci/` | Placeholder for the Ampere scraper node; the existing `fie-worker-1` is used first, so this module is documentation until a second node is needed | OCI config file with the tenancy the node lives in |

## Apply order

1. `cloudflare/` first: `terraform import` the five existing records so the state
   matches reality (commands in the module README), then `plan` must show no
   changes.
2. `gcp/`: create the project, run `terraform apply`. Output `feed_bucket_url`.
3. Point the scrape workflow at the bucket: `scrape-and-publish.yml` keeps
   publishing to GitHub Pages until the Cloud Run Job proves itself for a week;
   then the Pages job becomes the standby and Cloudflare's DNS flips to the
   bucket origin.

## Conventions

- Terraform ≥ 1.9, providers pinned in each module's `versions.tf`.
- Remote state: GCS bucket `zerofomo-tfstate` (created by hand once, versioning
  on). Until it exists, state is local and **must not be committed** (see
  `.gitignore`).
- No secrets in `.tfvars`; every token comes from the environment
  (`CLOUDFLARE_API_TOKEN`, `GOOGLE_APPLICATION_CREDENTIALS`).
- Run `/fie-audit infra/<module>` before any production apply (Prime Directive 1).

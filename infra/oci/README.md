# OCI scraper fleet (placeholder)

The Playwright-heavy sources (Bandsintown and any other Cloudflare-guarded site)
run on the existing Ampere node `fie-worker-1` first. No Terraform is needed
until a second node is required.

What runs there today can be prepared now:

- `scripts/oci-scraper.service` (to be added): a systemd timer that runs
  `comprehensive_bahamas_scraper.py --sources bandsintown --publish <ingest url>`
  every 6 hours and posts results with a per-worker bearer token.
- Feed standby: a nightly `rclone sync` from the GCS feed bucket to an OCI Object
  Storage bucket `0fomo-feeds-standby`; Cloudflare's load balancer health-checks
  the GCP origin and fails over to this bucket.

When a second node is needed, add `main.tf` here with the `oci` provider and an
`oci_core_instance` (VM.Standard.A1.Flex, 2 OCPU / 12 GB) in the same VCN as
`fie-worker-1`.

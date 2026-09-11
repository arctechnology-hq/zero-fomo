# 0 FOMO — Cloudflare zone 0fomo.app as code.
# These five records already exist (added by hand 2026-09-11). Import them
# before the first plan so Terraform adopts rather than recreates them:
#
#   export CLOUDFLARE_API_TOKEN=...   # Zone:DNS:Edit on 0fomo.app
#   terraform init
#   terraform import 'cloudflare_dns_record.apex["185.199.108.153"]' <zone_id>/<record_id>
#   ... (one import per record; ids via `curl` to /zones/<zone_id>/dns_records)
#
# Zone ID: see Cloudflare dashboard → 0fomo.app → Overview.

terraform {
  required_version = ">= 1.9"
  required_providers {
    cloudflare = {
      source  = "cloudflare/cloudflare"
      version = "~> 5.0"
    }
  }
}

provider "cloudflare" {} # token from CLOUDFLARE_API_TOKEN

variable "zone_id" {
  type        = string
  description = "Zone ID of 0fomo.app"
}

locals {
  github_pages_ips = [
    "185.199.108.153",
    "185.199.109.153",
    "185.199.110.153",
    "185.199.111.153",
  ]
}

resource "cloudflare_dns_record" "apex" {
  for_each = toset(local.github_pages_ips)
  zone_id  = var.zone_id
  name     = "0fomo.app"
  type     = "A"
  content  = each.value
  ttl      = 1     # auto
  proxied  = false # DNS-only: GitHub Pages issues the certificate
}

resource "cloudflare_dns_record" "www" {
  zone_id = var.zone_id
  name    = "www"
  type    = "CNAME"
  content = "arctechnology-hq.github.io"
  ttl     = 1
  proxied = false
}

# Phase 2 cutover (leave commented until the GCS origin is live):
# resource "cloudflare_dns_record" "feeds" {
#   zone_id = var.zone_id
#   name    = "feeds"
#   type    = "CNAME"
#   content = "c.storage.googleapis.com"
#   ttl     = 1
#   proxied = true
# }

variable "project_id" {
  description = "GCP project that hosts 0 FOMO (create it under the ARC organisation first)."
  type        = string
  default     = "zerofomo-prod"
}

variable "region" {
  description = "Primary region. us-east1 is the closest GCP region to the Caribbean."
  type        = string
  default     = "us-east1"
}

variable "feed_bucket_name" {
  description = "Globally unique bucket name for the static per-market feeds."
  type        = string
  default     = "zerofomo-feeds"
}

variable "scraper_image" {
  description = "Container image for the aggregation pipeline (built from comprehensive_bahamas_scraper.py)."
  type        = string
  default     = "us-east1-docker.pkg.dev/zerofomo-prod/apps/scraper:latest"
}

variable "markets" {
  description = "Market keys that get a feed prefix and a scheduled scrape."
  type        = list(string)
  default     = ["bs-nassau"]
}

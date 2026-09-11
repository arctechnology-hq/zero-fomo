# 0 FOMO — GCP core (Phase 2). Read path = static feeds in GCS; write path =
# Cloud Run Jobs on a schedule. See docs/CLOUD_ARCHITECTURE.md §2.1.

locals {
  services = [
    "run.googleapis.com",
    "cloudscheduler.googleapis.com",
    "artifactregistry.googleapis.com",
    "storage.googleapis.com",
    "iam.googleapis.com",
  ]
}

resource "google_project_service" "apis" {
  for_each           = toset(local.services)
  service            = each.value
  disable_on_destroy = false
}

# --- Feed bucket: US multi-region, public read, uniform access -----------------

resource "google_storage_bucket" "feeds" {
  name                        = var.feed_bucket_name
  location                    = "US"
  storage_class               = "STANDARD"
  uniform_bucket_level_access = true
  force_destroy               = false

  versioning {
    enabled = true
  }

  lifecycle_rule {
    condition {
      num_newer_versions = 10
    }
    action {
      type = "Delete"
    }
  }

  website {
    main_page_suffix = "index.html"
    not_found_page   = "404.html"
  }

  cors {
    origin          = ["*"]
    method          = ["GET", "HEAD"]
    response_header = ["Content-Type", "Cache-Control"]
    max_age_seconds = 300
  }

  depends_on = [google_project_service.apis]
}

resource "google_storage_bucket_iam_member" "feeds_public_read" {
  bucket = google_storage_bucket.feeds.name
  role   = "roles/storage.objectViewer"
  member = "allUsers"
}

# --- Service account for the scraper / feed builder -------------------------

resource "google_service_account" "feed_builder" {
  account_id   = "feed-builder"
  display_name = "0 FOMO feed builder (Cloud Run Jobs)"
}

resource "google_storage_bucket_iam_member" "feed_builder_writer" {
  bucket = google_storage_bucket.feeds.name
  role   = "roles/storage.objectAdmin"
  member = "serviceAccount:${google_service_account.feed_builder.email}"
}

# --- Artifact Registry for the scraper image ---------------------------------

resource "google_artifact_registry_repository" "apps" {
  location      = var.region
  repository_id = "apps"
  format        = "DOCKER"
  depends_on    = [google_project_service.apis]
}

# --- Cloud Run Job per market + Scheduler every 6 hours ----------------------

resource "google_cloud_run_v2_job" "scrape" {
  for_each = toset(var.markets)
  name     = "scrape-${each.value}"
  location = var.region

  template {
    template {
      service_account = google_service_account.feed_builder.email
      timeout         = "1800s"
      max_retries     = 1
      containers {
        image = var.scraper_image
        args  = ["--market", each.value, "--publish", "gs://${google_storage_bucket.feeds.name}/feeds/${each.value}/"]
        resources {
          limits = {
            cpu    = "2"
            memory = "4Gi"
          }
        }
      }
    }
  }

  depends_on = [google_project_service.apis]
}

resource "google_service_account" "scheduler" {
  account_id   = "scheduler-invoker"
  display_name = "Cloud Scheduler → Cloud Run Jobs"
}

resource "google_project_iam_member" "scheduler_run_invoker" {
  project = var.project_id
  role    = "roles/run.invoker"
  member  = "serviceAccount:${google_service_account.scheduler.email}"
}

resource "google_cloud_scheduler_job" "scrape" {
  for_each  = toset(var.markets)
  name      = "scrape-${each.value}-6h"
  region    = var.region
  schedule  = "0 */6 * * *"
  time_zone = "Etc/UTC"

  http_target {
    http_method = "POST"
    uri         = "https://run.googleapis.com/v2/projects/${var.project_id}/locations/${var.region}/jobs/${google_cloud_run_v2_job.scrape[each.value].name}:run"
    oauth_token {
      service_account_email = google_service_account.scheduler.email
    }
  }

  depends_on = [google_project_service.apis]
}

output "feed_bucket_url" {
  value = "https://storage.googleapis.com/${google_storage_bucket.feeds.name}/feeds/"
}

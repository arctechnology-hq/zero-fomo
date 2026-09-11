terraform {
  required_version = ">= 1.9"
  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 6.0"
    }
  }
  # Uncomment once the state bucket exists (see infra/README.md).
  # backend "gcs" {
  #   bucket = "zerofomo-tfstate"
  #   prefix = "gcp"
  # }
}

provider "google" {
  project = var.project_id
  region  = var.region
}

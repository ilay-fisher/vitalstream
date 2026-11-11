# LocalStack variant: S3 + SQS only. It reuses the AWS modules, so applying it proves the same
# module code works end to end. Never point this at real AWS: the credentials are fake and
# every endpoint is overridden.
terraform {
  required_version = ">= 1.7.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 5.81.0, < 6.0.0"
    }
  }
}

variable "endpoint" {
  description = "LocalStack edge URL."
  type        = string
  default     = "http://localhost:4566"
}

variable "archive_bucket_name" {
  type    = string
  default = "vitalstream-archive"
}

provider "aws" {
  region     = "us-east-1"
  access_key = "test"
  secret_key = "test"

  s3_use_path_style           = true
  skip_credentials_validation = true
  skip_metadata_api_check     = true
  skip_requesting_account_id  = true

  endpoints {
    s3  = var.endpoint
    sqs = var.endpoint
  }
}

module "s3" {
  source = "../aws/modules/s3"

  bucket_name   = var.archive_bucket_name
  force_destroy = true
  # LocalStack does not report lifecycle rules as propagated, which makes the provider time out.
  enable_lifecycle = false
}

module "sqs" {
  source = "../aws/modules/sqs"
}

output "bucket_name" {
  value = module.s3.bucket_name
}

output "queue_url" {
  value = module.sqs.queue_url
}

output "dlq_url" {
  value = module.sqs.dlq_url
}

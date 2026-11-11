# Plan-only tests against a mocked provider: nothing is created anywhere.
mock_provider "aws" {}

variables {
  bucket_name = "vitalstream-archive"
}

run "bucket_is_versioned" {
  command = plan

  assert {
    condition     = aws_s3_bucket_versioning.this.versioning_configuration[0].status == "Enabled"
    error_message = "archive bucket must be versioned"
  }
}

run "public_access_is_fully_blocked" {
  command = plan

  assert {
    condition = alltrue([
      aws_s3_bucket_public_access_block.this.block_public_acls,
      aws_s3_bucket_public_access_block.this.block_public_policy,
      aws_s3_bucket_public_access_block.this.ignore_public_acls,
      aws_s3_bucket_public_access_block.this.restrict_public_buckets,
    ])
    error_message = "all four public access block flags must be true"
  }
}

run "default_encryption_is_sse_s3" {
  command = plan

  assert {
    condition     = one(aws_s3_bucket_server_side_encryption_configuration.this.rule).apply_server_side_encryption_by_default[0].sse_algorithm == "AES256"
    error_message = "default encryption must be AES256 when no KMS key is given"
  }
}

run "kms_key_switches_encryption" {
  command = plan

  variables {
    kms_key_arn = "arn:aws:kms:eu-west-1:123456789012:key/11111111-2222-3333-4444-555555555555"
  }

  assert {
    condition     = one(aws_s3_bucket_server_side_encryption_configuration.this.rule).apply_server_side_encryption_by_default[0].sse_algorithm == "aws:kms"
    error_message = "KMS key must switch the algorithm to aws:kms"
  }
}

run "bucket_policy_denies_plain_http" {
  command = plan

  assert {
    condition     = strcontains(aws_s3_bucket_policy.this.policy, "aws:SecureTransport")
    error_message = "bucket policy must deny non-TLS requests"
  }
}

run "bucket_name_matches_contract_default" {
  command = plan

  assert {
    condition     = aws_s3_bucket.this.bucket == "vitalstream-archive"
    error_message = "bucket name must come from the variable"
  }
}

run "invalid_bucket_name_is_rejected" {
  command = plan

  variables {
    bucket_name = "Not_A_Valid_Bucket"
  }

  expect_failures = [var.bucket_name]
}

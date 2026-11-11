mock_provider "aws" {}

variables {
  name                      = "vs-test"
  vpc_id                    = "vpc-1"
  subnet_ids                = ["subnet-1", "subnet-2"]
  allowed_security_group_id = "sg-1"
  auth_token                = "0123456789abcdefghijklmnopqrstuv"
}

run "auth_and_encryption_are_on" {
  command = plan

  assert {
    condition     = aws_elasticache_replication_group.this.transit_encryption_enabled && aws_elasticache_replication_group.this.at_rest_encryption_enabled
    error_message = "encryption in transit and at rest are required"
  }

  assert {
    condition     = aws_elasticache_replication_group.this.auth_token != null
    error_message = "Redis AUTH must be configured"
  }
}

run "null_token_is_rejected" {
  command = plan

  variables {
    auth_token = null
  }

  expect_failures = [var.auth_token]
}

run "short_token_is_rejected" {
  command = plan

  variables {
    auth_token = "short"
  }

  expect_failures = [var.auth_token]
}

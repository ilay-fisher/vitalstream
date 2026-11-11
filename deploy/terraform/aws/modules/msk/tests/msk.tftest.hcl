mock_provider "aws" {}

variables {
  name                      = "vs-test"
  vpc_id                    = "vpc-1"
  subnet_ids                = ["subnet-1", "subnet-2", "subnet-3"]
  allowed_security_group_id = "sg-1"
}

run "iam_auth_is_the_only_client_authentication" {
  command = plan

  assert {
    condition     = aws_msk_cluster.this.client_authentication[0].sasl[0].iam
    error_message = "SASL/IAM must be enabled"
  }

  assert {
    condition     = aws_msk_cluster.this.client_authentication[0].unauthenticated == false
    error_message = "unauthenticated access must be disabled"
  }
}

run "client_traffic_is_tls_only" {
  command = plan

  assert {
    condition     = aws_msk_cluster.this.encryption_info[0].encryption_in_transit[0].client_broker == "TLS"
    error_message = "client_broker must be TLS"
  }
}

run "plaintext_is_not_selectable" {
  command = plan

  variables {
    client_broker_encryption = "TLS_PLAINTEXT"
  }

  expect_failures = [var.client_broker_encryption]
}

run "only_the_iam_port_is_open" {
  command = plan

  assert {
    condition     = length(aws_security_group.this.ingress) == 1 && one(aws_security_group.this.ingress).from_port == 9098
    error_message = "only 9098 (SASL/IAM over TLS) may be reachable"
  }
}

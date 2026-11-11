mock_provider "aws" {}

variables {
  name_prefix       = "vs-test"
  oidc_provider_arn = "arn:aws:iam::123456789012:oidc-provider/oidc.eks.eu-west-1.amazonaws.com/id/ABCDEF"
  oidc_provider_url = "oidc.eks.eu-west-1.amazonaws.com/id/ABCDEF"
  namespace         = "vitalstream"
  sqs_queue_arn     = "arn:aws:sqs:eu-west-1:123456789012:alert-escalations"
  s3_bucket_arn     = "arn:aws:s3:::vitalstream-archive"
  msk_cluster_arn   = "arn:aws:kafka:eu-west-1:123456789012:cluster/vs-test/uuid-1"
}

run "ingest_gateway_may_only_write_vitals_raw" {
  command = plan

  assert {
    condition = (
      strcontains(aws_iam_role_policy.kafka["ingest-gateway"].policy, "topic/vs-test/uuid-1/vitals.raw") &&
      strcontains(aws_iam_role_policy.kafka["ingest-gateway"].policy, "kafka-cluster:WriteData") &&
      !strcontains(aws_iam_role_policy.kafka["ingest-gateway"].policy, "kafka-cluster:ReadData") &&
      !strcontains(aws_iam_role_policy.kafka["ingest-gateway"].policy, "alerts.detected")
    )
    error_message = "ingest-gateway: write vitals.raw only"
  }
}

run "clinical_api_reads_alerts_and_writes_the_dlq" {
  command = plan

  assert {
    condition = (
      strcontains(aws_iam_role_policy.kafka["clinical-api"].policy, "topic/vs-test/uuid-1/alerts.detected") &&
      strcontains(aws_iam_role_policy.kafka["clinical-api"].policy, "topic/vs-test/uuid-1/alerts.detected.dlq") &&
      !strcontains(aws_iam_role_policy.kafka["clinical-api"].policy, "vitals.raw")
    )
    error_message = "clinical-api: alerts.detected + dlq only"
  }
}

run "stream_processor_reads_vitals_and_writes_alerts" {
  command = plan

  assert {
    condition = (
      strcontains(aws_iam_role_policy.kafka["stream-processor"].policy, "topic/vs-test/uuid-1/vitals.raw") &&
      strcontains(aws_iam_role_policy.kafka["stream-processor"].policy, "topic/vs-test/uuid-1/alerts.detected")
    )
    error_message = "stream-processor: vitals.raw in, alerts.detected out"
  }
}

run "no_kafka_wildcard_actions_or_bare_wildcard_resource" {
  command = plan

  assert {
    condition = alltrue([
      for p in aws_iam_role_policy.kafka : !strcontains(p.policy, "kafka-cluster:*") && !strcontains(p.policy, "\"*\"")
    ])
    error_message = "kafka permissions must not use wildcard actions or a bare wildcard resource"
  }
}

run "ingest_gateway_role_trusts_only_its_service_account" {
  command = plan

  assert {
    condition     = strcontains(aws_iam_role.ingest_gateway.assume_role_policy, "system:serviceaccount:vitalstream:ingest-gateway")
    error_message = "ingest-gateway role must trust its own service account"
  }
}

run "trust_policy_is_scoped_to_one_service_account" {
  command = plan

  assert {
    condition     = strcontains(aws_iam_role.clinical_api.assume_role_policy, "system:serviceaccount:vitalstream:clinical-api")
    error_message = "clinical-api role must only trust its own service account"
  }

  assert {
    condition     = strcontains(aws_iam_role.stream_processor.assume_role_policy, "system:serviceaccount:vitalstream:stream-processor")
    error_message = "stream-processor role must only trust its own service account"
  }
}

run "clinical_api_can_send_but_not_receive_or_purge" {
  command = plan

  assert {
    condition     = strcontains(aws_iam_role_policy.clinical_api_sqs.policy, "sqs:SendMessage") && !strcontains(aws_iam_role_policy.clinical_api_sqs.policy, "sqs:DeleteMessage") && !strcontains(aws_iam_role_policy.clinical_api_sqs.policy, "sqs:PurgeQueue")
    error_message = "clinical-api needs send only"
  }
}

run "stream_processor_is_limited_to_the_archive_bucket" {
  command = plan

  assert {
    condition     = strcontains(aws_iam_role_policy.stream_processor_s3.policy, "arn:aws:s3:::vitalstream-archive/*") && !strcontains(aws_iam_role_policy.stream_processor_s3.policy, "s3:*")
    error_message = "stream-processor must be limited to object access on the archive bucket"
  }
}

run "consumer_groups_match_the_services" {
  command = plan

  assert {
    condition = (
      strcontains(aws_iam_role_policy.kafka["stream-processor"].policy, "group/vs-test/uuid-1/spark-kafka-source-*") &&
      strcontains(aws_iam_role_policy.kafka["clinical-api"].policy, "group/vs-test/uuid-1/clinical-api\"") &&
      !strcontains(aws_iam_role_policy.kafka["ingest-gateway"].policy, "kafka-cluster:AlterGroup")
    )
    error_message = "stream-processor uses spark-kafka-source-*, clinical-api uses group clinical-api, ingest-gateway has no group access"
  }

  assert {
    condition     = !strcontains(aws_iam_role_policy.kafka["stream-processor"].policy, "stream-processor*")
    error_message = "the guessed stream-processor group prefix must be gone"
  }
}

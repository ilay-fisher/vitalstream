mock_provider "aws" {}

variables {
  name_prefix       = "vs-test"
  oidc_provider_arn = "arn:aws:iam::123456789012:oidc-provider/oidc.eks.eu-west-1.amazonaws.com/id/ABCDEF"
  oidc_provider_url = "oidc.eks.eu-west-1.amazonaws.com/id/ABCDEF"
  namespace         = "vitalstream"
  sqs_queue_arn     = "arn:aws:sqs:eu-west-1:123456789012:alert-escalations"
  s3_bucket_arn     = "arn:aws:s3:::vitalstream-archive"
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

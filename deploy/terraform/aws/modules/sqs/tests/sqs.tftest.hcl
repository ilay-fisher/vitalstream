# Mocked provider with apply: values the real API would compute are generated locally, nothing is created.
mock_provider "aws" {
  mock_resource "aws_sqs_queue" {
    defaults = {
      arn = "arn:aws:sqs:us-east-1:123456789012:mock"
    }
  }
}

variables {
  queue_name = "alert-escalations"
}

run "queue_names_follow_the_contract" {
  command = apply

  assert {
    condition     = aws_sqs_queue.main.name == "alert-escalations" && aws_sqs_queue.dlq.name == "alert-escalations-dlq"
    error_message = "queue and DLQ names must be <name> and <name>-dlq"
  }
}

run "redrive_after_five_receives" {
  command = apply

  assert {
    condition     = jsondecode(aws_sqs_queue.main.redrive_policy).maxReceiveCount == 5
    error_message = "maxReceiveCount must be 5"
  }
}

run "queues_are_encrypted_at_rest" {
  command = apply

  assert {
    condition     = aws_sqs_queue.main.sqs_managed_sse_enabled && aws_sqs_queue.dlq.sqs_managed_sse_enabled
    error_message = "both queues need SQS managed SSE"
  }
}

run "dlq_retains_longer_than_main_queue" {
  command = apply

  assert {
    condition     = aws_sqs_queue.dlq.message_retention_seconds > aws_sqs_queue.main.message_retention_seconds
    error_message = "DLQ must keep messages longer so they can be inspected"
  }
}

run "max_receive_count_must_be_positive" {
  command = plan

  variables {
    max_receive_count = 0
  }

  expect_failures = [var.max_receive_count]
}

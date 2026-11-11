#!/bin/bash
# LocalStack ready hook: idempotently creates the S3 bucket and SQS queues (CONTRACTS section 5).
set -euo pipefail

BUCKET=vitalstream-archive
REGION=us-east-1

awslocal s3api head-bucket --bucket "$BUCKET" 2>/dev/null || awslocal s3api create-bucket --bucket "$BUCKET"

# create-queue is idempotent for identical attributes. DLQ first so its ARN exists for the redrive policy.
awslocal sqs create-queue --queue-name alert-escalations-dlq >/dev/null
DLQ_ARN=$(awslocal sqs get-queue-attributes \
  --queue-url "http://sqs.${REGION}.localhost.localstack.cloud:4566/000000000000/alert-escalations-dlq" \
  --attribute-names QueueArn --query 'Attributes.QueueArn' --output text)

awslocal sqs create-queue --queue-name alert-escalations \
  --attributes "{\"RedrivePolicy\":\"{\\\"deadLetterTargetArn\\\":\\\"${DLQ_ARN}\\\",\\\"maxReceiveCount\\\":\\\"5\\\"}\"}" >/dev/null

echo "localstack init done: bucket=$BUCKET queues=alert-escalations,alert-escalations-dlq"

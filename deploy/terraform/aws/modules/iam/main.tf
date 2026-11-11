# IRSA: one role per service account, trusted only for that exact service account.
# Annotate the Helm service account with eks.amazonaws.com/role-arn=<output>.
locals {
  accounts = {
    clinical-api     = "clinical-api"
    stream-processor = "stream-processor"
  }
}

data "aws_iam_policy_document" "trust" {
  for_each = local.accounts

  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [var.oidc_provider_arn]
    }

    condition {
      test     = "StringEquals"
      variable = "${var.oidc_provider_url}:sub"
      values   = ["system:serviceaccount:${var.namespace}:${each.value}"]
    }

    condition {
      test     = "StringEquals"
      variable = "${var.oidc_provider_url}:aud"
      values   = ["sts.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "clinical_api" {
  name               = "${var.name_prefix}-clinical-api"
  assume_role_policy = data.aws_iam_policy_document.trust["clinical-api"].json
  tags               = var.tags
}

resource "aws_iam_role" "stream_processor" {
  name               = "${var.name_prefix}-stream-processor"
  assume_role_policy = data.aws_iam_policy_document.trust["stream-processor"].json
  tags               = var.tags
}

resource "aws_iam_role_policy" "clinical_api_sqs" {
  name = "send-alert-escalations"
  role = aws_iam_role.clinical_api.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["sqs:SendMessage", "sqs:GetQueueUrl", "sqs:GetQueueAttributes"]
      Resource = var.sqs_queue_arn
    }]
  })
}

resource "aws_iam_role_policy" "stream_processor_s3" {
  name = "write-archive"
  role = aws_iam_role.stream_processor.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["s3:PutObject", "s3:GetObject", "s3:AbortMultipartUpload", "s3:ListMultipartUploadParts"]
        Resource = "${var.s3_bucket_arn}/*"
      },
      {
        Effect   = "Allow"
        Action   = ["s3:ListBucket", "s3:GetBucketLocation"]
        Resource = var.s3_bucket_arn
      }
    ]
  })
}

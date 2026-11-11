# IRSA: one role per service account, trusted only for that exact service account.
# Annotate the Helm service account with eks.amazonaws.com/role-arn=<output>.
locals {
  accounts = {
    ingest-gateway   = "ingest-gateway"
    clinical-api     = "clinical-api"
    stream-processor = "stream-processor"
  }

  # MSK resource ARNs are derived from the cluster ARN (cluster/<name>/<uuid>).
  topic_arn_prefix = replace(var.msk_cluster_arn, ":cluster/", ":topic/")
  group_arn_prefix = replace(var.msk_cluster_arn, ":cluster/", ":group/")

  # Least privilege per CONTRACTS section 2: each service touches only its own topics and groups.
  # Consumer groups: clinical-api uses group.id clinical-api; Spark Structured Streaming generates
  # spark-kafka-source-* ids. ingest-gateway only produces and needs no group access.
  kafka_access = {
    ingest-gateway = {
      write  = ["vitals.raw"]
      read   = []
      groups = []
    }
    stream-processor = {
      write  = ["alerts.detected"]
      read   = ["vitals.raw"]
      groups = ["spark-kafka-source-*"]
    }
    clinical-api = {
      write  = ["alerts.detected.dlq"]
      read   = ["alerts.detected"]
      groups = ["clinical-api"]
    }
  }

  kafka_policy = { for svc, a in local.kafka_access : svc => jsonencode({
    Version = "2012-10-17"
    Statement = concat(
      [{
        Effect   = "Allow"
        Action   = ["kafka-cluster:Connect"]
        Resource = [var.msk_cluster_arn]
      }],
      length(a.write) > 0 ? [{
        Effect   = "Allow"
        Action   = ["kafka-cluster:DescribeTopic", "kafka-cluster:WriteData"]
        Resource = [for t in a.write : "${local.topic_arn_prefix}/${t}"]
        }, {
        Effect   = "Allow"
        Action   = ["kafka-cluster:WriteDataIdempotently"]
        Resource = [var.msk_cluster_arn]
      }] : [],
      length(a.read) > 0 ? [{
        Effect   = "Allow"
        Action   = ["kafka-cluster:DescribeTopic", "kafka-cluster:ReadData"]
        Resource = [for t in a.read : "${local.topic_arn_prefix}/${t}"]
        }, {
        Effect   = "Allow"
        Action   = ["kafka-cluster:DescribeGroup", "kafka-cluster:AlterGroup"]
        Resource = [for g in a.groups : "${local.group_arn_prefix}/${g}"]
      }] : [],
    )
  }) }
}

resource "aws_iam_role" "ingest_gateway" {
  name               = "${var.name_prefix}-ingest-gateway"
  assume_role_policy = local.trust["ingest-gateway"]
  tags               = var.tags
}

locals {
  role_ids = {
    ingest-gateway   = aws_iam_role.ingest_gateway.id
    clinical-api     = aws_iam_role.clinical_api.id
    stream-processor = aws_iam_role.stream_processor.id
  }
}

resource "aws_iam_role_policy" "kafka" {
  for_each = local.kafka_policy

  name   = "kafka-${each.key}"
  role   = local.role_ids[each.key]
  policy = each.value
}

# jsonencode (not a policy-document data source) keeps the trust policy known at plan time.
locals {
  trust = { for name in keys(local.accounts) : name => jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Action    = "sts:AssumeRoleWithWebIdentity"
      Principal = { Federated = var.oidc_provider_arn }
      Condition = {
        StringEquals = {
          "${var.oidc_provider_url}:sub" = "system:serviceaccount:${var.namespace}:${local.accounts[name]}"
          "${var.oidc_provider_url}:aud" = "sts.amazonaws.com"
        }
      }
    }]
  }) }
}

resource "aws_iam_role" "clinical_api" {
  name               = "${var.name_prefix}-clinical-api"
  assume_role_policy = local.trust["clinical-api"]
  tags               = var.tags
}

resource "aws_iam_role" "stream_processor" {
  name               = "${var.name_prefix}-stream-processor"
  assume_role_policy = local.trust["stream-processor"]
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

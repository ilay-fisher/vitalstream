variable "name_prefix" {
  type = string
}

variable "oidc_provider_arn" {
  description = "ARN of the EKS cluster OIDC identity provider."
  type        = string
}

variable "oidc_provider_url" {
  description = "Issuer URL of the OIDC provider without https://."
  type        = string
}

variable "namespace" {
  description = "Kubernetes namespace of the Helm release."
  type        = string
  default     = "vitalstream"
}

variable "sqs_queue_arn" {
  description = "alert-escalations queue ARN (clinical-api sends CRITICAL alerts)."
  type        = string
}

variable "s3_bucket_arn" {
  description = "Archive bucket ARN (stream-processor writes Parquet)."
  type        = string
}

variable "msk_cluster_arn" {
  description = "MSK cluster ARN (arn:aws:kafka:<region>:<account>:cluster/<name>/<uuid>); per-topic ARNs are derived from it."
  type        = string
}

variable "tags" {
  type    = map(string)
  default = {}
}

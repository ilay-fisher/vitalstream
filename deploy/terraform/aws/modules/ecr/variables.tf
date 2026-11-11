variable "repositories" {
  description = "Service names; each becomes <namespace>/<name>."
  type        = list(string)
  default     = ["ingest-gateway", "stream-processor", "clinical-api", "copilot", "dashboard"]
}

variable "namespace" {
  description = "Repository prefix, matches image.repository in the Helm chart."
  type        = string
  default     = "vitalstream"
}

variable "kms_key_arn" {
  description = "Optional KMS key for repository encryption (AES256 when null)."
  type        = string
  default     = null
}

variable "keep_last_images" {
  type    = number
  default = 30
}

variable "force_delete" {
  type    = bool
  default = false
}

variable "tags" {
  type    = map(string)
  default = {}
}

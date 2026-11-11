variable "bucket_name" {
  description = "Archive bucket name (CONTRACTS section 5: vitalstream-archive). S3 names are global, so add an account or org suffix when the default is taken."
  type        = string
  default     = "vitalstream-archive"

  validation {
    condition     = can(regex("^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$", var.bucket_name))
    error_message = "bucket_name must be 3-63 characters of lowercase letters, digits, dots and hyphens."
  }
}

variable "kms_key_arn" {
  description = "Customer managed KMS key for default encryption. Null uses SSE-S3 (AES256)."
  type        = string
  default     = null
}

variable "force_destroy" {
  description = "Allow terraform destroy to delete a non-empty bucket. Keep false outside throwaway environments."
  type        = bool
  default     = false
}

variable "noncurrent_version_expiration_days" {
  description = "Days after which superseded object versions are deleted."
  type        = number
  default     = 90
}

variable "tags" {
  type    = map(string)
  default = {}
}

variable "enable_lifecycle" {
  description = "Manage the lifecycle rule. Only turn off for emulators (LocalStack never reports the rule as propagated, so the provider times out)."
  type        = bool
  default     = true
}

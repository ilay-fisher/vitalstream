variable "queue_name" {
  description = "Main queue name (CONTRACTS section 5: alert-escalations). The DLQ is <queue_name>-dlq."
  type        = string
  default     = "alert-escalations"
}

variable "max_receive_count" {
  description = "Receives before a message moves to the DLQ."
  type        = number
  default     = 5

  validation {
    condition     = var.max_receive_count >= 1
    error_message = "max_receive_count must be at least 1."
  }
}

variable "visibility_timeout_seconds" {
  type    = number
  default = 60
}

variable "message_retention_seconds" {
  description = "Retention on the main queue (default 4 days)."
  type        = number
  default     = 345600
}

variable "dlq_message_retention_seconds" {
  description = "Retention on the DLQ (default 14 days, the SQS maximum)."
  type        = number
  default     = 1209600
}

variable "tags" {
  type    = map(string)
  default = {}
}

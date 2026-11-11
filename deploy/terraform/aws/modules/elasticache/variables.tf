variable "name" {
  type = string
}

variable "vpc_id" {
  type = string
}

variable "subnet_ids" {
  type = list(string)
}

variable "allowed_security_group_id" {
  type = string
}

variable "engine_version" {
  type    = string
  default = "7.1"
}

variable "node_type" {
  type    = string
  default = "cache.t4g.small"
}

variable "num_cache_clusters" {
  description = "Primary plus replicas. Two or more enables Multi-AZ failover."
  type        = number
  default     = 2
}

variable "auth_token" {
  description = "Redis AUTH token (16-128 chars, required). The root module generates it with random_password and stores it in Secrets Manager."
  type        = string
  sensitive   = true

  validation {
    condition     = var.auth_token != null && try(length(var.auth_token) >= 16 && length(var.auth_token) <= 128, false)
    error_message = "auth_token is required and must be 16-128 characters; Redis must not run without authentication."
  }
}

variable "tags" {
  type    = map(string)
  default = {}
}

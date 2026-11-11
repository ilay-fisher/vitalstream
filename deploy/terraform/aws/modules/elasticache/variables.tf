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
  description = "Optional Redis AUTH token (16-128 chars). Pass via TF_VAR_redis_auth_token, never commit it. If set, put it in REDIS_URL."
  type        = string
  default     = null
  sensitive   = true
}

variable "tags" {
  type    = map(string)
  default = {}
}

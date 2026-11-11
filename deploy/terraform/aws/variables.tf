variable "project" {
  type    = string
  default = "vitalstream"
}

variable "environment" {
  description = "dev, staging or prod; used in resource names."
  type        = string
}

variable "region" {
  type    = string
  default = "eu-west-1"
}

variable "extra_tags" {
  type    = map(string)
  default = {}
}

variable "k8s_namespace" {
  description = "Namespace of the Helm release; IRSA roles trust service accounts in it."
  type        = string
  default     = "vitalstream"
}

# ---- network
variable "vpc_cidr" {
  type    = string
  default = "10.20.0.0/16"
}

variable "az_count" {
  description = "Number of availability zones (MSK needs a broker count that is a multiple of it)."
  type        = number
  default     = 3
}

variable "single_nat_gateway" {
  type    = bool
  default = true
}

# ---- EKS
variable "kubernetes_version" {
  type    = string
  default = "1.31"
}

variable "eks_endpoint_public_access" {
  type    = bool
  default = true
}

variable "eks_public_access_cidrs" {
  description = "Who may reach the public Kubernetes API. Replace 0.0.0.0/0 with your office or VPN range."
  type        = list(string)
  default     = ["0.0.0.0/0"]
}

variable "node_instance_types" {
  type    = list(string)
  default = ["t3.large"]
}

variable "node_min_size" {
  type    = number
  default = 2
}

variable "node_desired_size" {
  type    = number
  default = 2
}

variable "node_max_size" {
  type    = number
  default = 6
}

# ---- data stores
variable "rds_instance_class" {
  type    = string
  default = "db.t4g.medium"
}

variable "rds_multi_az" {
  type    = bool
  default = false
}

variable "rds_deletion_protection" {
  type    = bool
  default = true
}

variable "rds_skip_final_snapshot" {
  type    = bool
  default = false
}

variable "msk_broker_count" {
  type    = number
  default = 3
}

variable "msk_instance_type" {
  type    = string
  default = "kafka.t3.small"
}

variable "msk_client_broker_encryption" {
  type    = string
  default = "TLS"
}

variable "redis_node_type" {
  type    = string
  default = "cache.t4g.small"
}

variable "redis_num_cache_clusters" {
  type    = number
  default = 2
}

variable "redis_auth_token" {
  description = "Optional Redis AUTH token. Supply with TF_VAR_redis_auth_token."
  type        = string
  default     = null
  sensitive   = true
}

# ---- storage and registry
variable "archive_bucket_name" {
  description = "S3 names are global: use something like vitalstream-archive-<account-id>."
  type        = string
  default     = "vitalstream-archive"
}

variable "archive_force_destroy" {
  type    = bool
  default = false
}

variable "ecr_force_delete" {
  type    = bool
  default = false
}

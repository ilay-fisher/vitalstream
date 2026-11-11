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
  description = "Expose the Kubernetes API publicly. Off by default; reach it from inside the VPC."
  type        = bool
  default     = false
}

variable "eks_public_access_cidrs" {
  description = "Who may reach the public Kubernetes API. Required when eks_endpoint_public_access is true; 0.0.0.0/0 is rejected."
  type        = list(string)
  default     = []

  validation {
    condition     = !contains(var.eks_public_access_cidrs, "0.0.0.0/0")
    error_message = "eks_public_access_cidrs must not contain 0.0.0.0/0."
  }
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

variable "redis_node_type" {
  type    = string
  default = "cache.t4g.small"
}

variable "redis_num_cache_clusters" {
  type    = number
  default = 2
}

# ---- storage and registry
variable "archive_bucket_name" {
  description = "Override the archive bucket name. Null derives <project>-archive-<account-id>-<region>, which is globally unique."
  type        = string
  default     = null
}

variable "archive_force_destroy" {
  type    = bool
  default = false
}

variable "ecr_force_delete" {
  type    = bool
  default = false
}

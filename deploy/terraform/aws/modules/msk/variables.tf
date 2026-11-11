variable "name" {
  type = string
}

variable "vpc_id" {
  type = string
}

variable "subnet_ids" {
  description = "Private subnets, one broker per subnet; the broker count must be a multiple of the subnet count."
  type        = list(string)
}

variable "allowed_security_group_id" {
  type = string
}

variable "kafka_version" {
  type    = string
  default = "3.8.x"
}

variable "broker_count" {
  type    = number
  default = 3
}

variable "instance_type" {
  type    = string
  default = "kafka.t3.small"
}

variable "volume_size_gb" {
  type    = number
  default = 50
}

variable "client_broker_encryption" {
  description = "TLS (secure default, port 9094; clients need security.protocol=SSL, e.g. SPRING_KAFKA_SECURITY_PROTOCOL=SSL) or TLS_PLAINTEXT (also opens 9092)."
  type        = string
  default     = "TLS"

  validation {
    condition     = contains(["TLS", "TLS_PLAINTEXT"], var.client_broker_encryption)
    error_message = "client_broker_encryption must be TLS or TLS_PLAINTEXT."
  }
}

variable "tags" {
  type    = map(string)
  default = {}
}

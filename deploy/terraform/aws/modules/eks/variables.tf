variable "name" {
  type = string
}

variable "kubernetes_version" {
  type    = string
  default = "1.31"
}

variable "private_subnet_ids" {
  type = list(string)
}

variable "endpoint_public_access" {
  description = "Expose the API server publicly. Restrict with public_access_cidrs or turn off and use a VPN/bastion."
  type        = bool
  default     = true
}

variable "public_access_cidrs" {
  description = "CIDRs allowed to reach the public API endpoint. Change from the default before real use."
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

variable "tags" {
  type    = map(string)
  default = {}
}

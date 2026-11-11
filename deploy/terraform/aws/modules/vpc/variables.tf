variable "name" {
  type = string
}

variable "region" {
  type = string
}

variable "vpc_cidr" {
  type    = string
  default = "10.20.0.0/16"
}

variable "azs" {
  description = "Availability zones, one public and one private subnet each."
  type        = list(string)
}

variable "single_nat_gateway" {
  description = "One shared NAT gateway (cheap, single point of failure) instead of one per AZ."
  type        = bool
  default     = true
}

variable "tags" {
  type    = map(string)
  default = {}
}

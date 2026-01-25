variable "name_prefix" {
  type = string
}

variable "environment" {
  type = string
}

variable "vpc_cidr" {
  type = string
}

variable "tags" {
  type    = map(string)
  default = {}
}

variable "multi_az_nat" {
  type        = bool
  description = "Create one NAT Gateway per AZ (higher availability, higher cost)."
  default     = true
}

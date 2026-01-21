variable "name_prefix" {
  type = string
}

variable "environment" {
  type = string
}

variable "vpc_id" {
  type = string
}

variable "private_subnet_ids" {
  type = list(string)
}

variable "allowed_cidr_blocks" {
  type        = list(string)
  description = "CIDR blocks that are allowed to connect to Redis (port 6379). Typically your VPC CIDR."
}

variable "node_type" {
  type    = string
  default = "cache.t3.micro"
}

variable "tags" {
  type    = map(string)
  default = {}
}

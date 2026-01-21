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


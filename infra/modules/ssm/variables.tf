variable "name_prefix" {
  type = string
}

variable "environment" {
  type = string
}

variable "db_url" {
  type = string
}

variable "auth_issuer" {
  type = string
}

variable "auth_audience" {
  type = string
}

variable "auth_jwks_uri" {
  type = string
}

variable "tags" {
  type    = map(string)
  default = {}
}


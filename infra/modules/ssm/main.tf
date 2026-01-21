locals {
  base = "/${var.name_prefix}/${var.environment}"
}

resource "aws_ssm_parameter" "db_url" {
  name  = "${local.base}/db/url"
  type  = "String"
  value = var.db_url
  tags  = var.tags
}

resource "aws_ssm_parameter" "auth_issuer" {
  name  = "${local.base}/auth/issuer"
  type  = "String"
  value = var.auth_issuer
  tags  = var.tags
}

resource "aws_ssm_parameter" "auth_audience" {
  name  = "${local.base}/auth/audience"
  type  = "String"
  value = var.auth_audience
  tags  = var.tags
}

resource "aws_ssm_parameter" "auth_jwks_uri" {
  name  = "${local.base}/auth/jwks_uri"
  type  = "String"
  value = var.auth_jwks_uri
  tags  = var.tags
}


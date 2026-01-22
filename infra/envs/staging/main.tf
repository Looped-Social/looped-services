provider "aws" {
  region = var.aws_region
}

data "aws_caller_identity" "current" {}

data "aws_ecr_repository" "api" {
  name = var.ecr_repository_name
}

locals {
  env = var.environment

  tags = {
    App         = "looped"
    Environment = local.env
    ManagedBy   = "opentofu"
  }
}

module "network" {
  source      = "../../modules/network"
  name_prefix = var.name_prefix
  environment = local.env
  vpc_cidr    = var.vpc_cidr
  tags        = local.tags
}

module "storage" {
  source      = "../../modules/storage"
  name_prefix = var.name_prefix
  environment = local.env
  account_id  = data.aws_caller_identity.current.account_id
  aws_region  = var.aws_region
  tags        = local.tags
}

module "redis" {
  source              = "../../modules/redis"
  name_prefix         = var.name_prefix
  environment         = local.env
  vpc_id              = module.network.vpc_id
  private_subnet_ids  = module.network.private_subnet_ids
  allowed_cidr_blocks = [var.vpc_cidr]
  node_type           = "cache.t3.micro"
  tags                = local.tags
}

module "ssm" {
  source        = "../../modules/ssm"
  name_prefix   = var.name_prefix
  environment   = local.env
  db_url        = var.db_url
  auth_issuer   = var.auth_issuer
  auth_audience = var.auth_audience
  auth_jwks_uri = var.auth_jwks_uri
  tags          = local.tags
}

module "api" {
  source      = "../../modules/api"
  name_prefix = var.name_prefix
  environment = local.env
  tags        = local.tags

  vpc_id             = module.network.vpc_id
  public_subnet_ids  = module.network.public_subnet_ids
  private_subnet_ids = module.network.private_subnet_ids

  domain_name         = var.domain_name
  acm_certificate_arn = var.acm_certificate_arn

  ecr_image = "${data.aws_ecr_repository.api.repository_url}:${var.image_tag}"

  desired_count = var.desired_count
  min_capacity  = var.min_capacity
  max_capacity  = var.max_capacity

  cors_allowed_origins = var.cors_allowed_origins

  cloudfront_domain      = module.storage.cloudfront_domain_name
  s3_media_bucket        = module.storage.media_bucket_name
  s3_verification_bucket = module.storage.verification_bucket_name
  s3_dm_bucket           = module.storage.dm_bucket_name

  redis_primary_endpoint = module.redis.primary_endpoint_address

  email_from              = var.email_from
  email_reply_to          = var.email_reply_to
  email_configuration_set = var.email_configuration_set
  email_verify_base_url   = var.email_verify_base_url

  ssm_auth_audience_arn = module.ssm.auth_audience_parameter_arn
  ssm_auth_issuer_arn   = module.ssm.auth_issuer_parameter_arn
  ssm_auth_jwks_uri_arn = module.ssm.auth_jwks_uri_parameter_arn
  ssm_db_url_arn        = module.ssm.db_url_parameter_arn

  firebase_admin_secret_arn    = var.firebase_admin_secret_arn
  openai_moderation_secret_arn = var.openai_moderation_secret_arn
  db_credentials_secret_arn    = var.db_credentials_secret_arn

  moderation_enabled                     = var.moderation_enabled
  moderation_openai_enabled              = var.moderation_openai_enabled
  moderation_openai_model                = var.moderation_openai_model
  moderation_openai_base_url             = var.moderation_openai_base_url
  moderation_openai_timeout_millis       = var.moderation_openai_timeout_millis
  moderation_openai_category_blocklist   = var.moderation_openai_category_blocklist
  moderation_report_quarantine_threshold = var.moderation_report_quarantine_threshold

  logo_dev_token  = var.logo_dev_token
  logo_dev_retina = var.logo_dev_retina
}

provider "aws" {
  region = var.aws_region
}

data "aws_caller_identity" "current" {}

data "aws_ecr_repository" "api" {
  name = var.ecr_repository_name
}

data "aws_ecr_repository" "notif_worker" {
  count = var.enable_notif_worker ? 1 : 0
  name  = var.notif_worker_ecr_repository_name
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
  source       = "../../modules/network"
  name_prefix  = var.name_prefix
  environment  = local.env
  vpc_cidr     = var.vpc_cidr
  multi_az_nat = var.multi_az_nat
  tags         = local.tags
}

module "storage" {
  source                         = "../../modules/storage"
  name_prefix                    = var.name_prefix
  environment                    = local.env
  account_id                     = data.aws_caller_identity.current.account_id
  aws_region                     = var.aws_region
  cors_allowed_origins           = var.cors_allowed_origins
  cloudfront_aliases             = var.media_cloudfront_aliases
  cloudfront_acm_certificate_arn = var.media_cloudfront_acm_certificate_arn
  tags                           = local.tags
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

module "notifications" {
  source      = "../../modules/notifications"
  name_prefix = var.name_prefix
  environment = local.env
  tags        = local.tags
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

  email_from                               = var.email_from
  email_admin_from                         = var.email_admin_from
  email_reply_to                           = var.email_reply_to
  email_configuration_set                  = var.email_configuration_set
  email_verify_base_url                    = var.email_verify_base_url
  app_minimum_supported_version            = var.app_minimum_supported_version
  app_minimum_supported_version_message    = var.app_minimum_supported_version_message
  app_minimum_supported_version_update_url = var.app_minimum_supported_version_update_url
  universal_links_apple_team_id            = var.universal_links_apple_team_id
  universal_links_ios_bundle_id            = var.universal_links_ios_bundle_id
  universal_links_cache_max_age_seconds    = var.universal_links_cache_max_age_seconds
  universal_links_aasa_version             = var.universal_links_aasa_version

  ssm_auth_audience_arn = module.ssm.auth_audience_parameter_arn
  ssm_auth_issuer_arn   = module.ssm.auth_issuer_parameter_arn
  ssm_auth_jwks_uri_arn = module.ssm.auth_jwks_uri_parameter_arn
  ssm_db_url_arn        = module.ssm.db_url_parameter_arn

  firebase_admin_secret_arn    = var.firebase_admin_secret_arn
  openai_moderation_secret_arn = var.openai_moderation_secret_arn
  db_credentials_secret_arn    = var.db_credentials_secret_arn
  admin_edge_secret_arn        = var.admin_edge_secret_arn

  moderation_enabled                     = var.moderation_enabled
  moderation_openai_enabled              = var.moderation_openai_enabled
  moderation_openai_model                = var.moderation_openai_model
  moderation_openai_base_url             = var.moderation_openai_base_url
  moderation_openai_timeout_millis       = var.moderation_openai_timeout_millis
  moderation_openai_category_blocklist   = var.moderation_openai_category_blocklist
  moderation_report_quarantine_threshold = var.moderation_report_quarantine_threshold

  logo_dev_token  = var.logo_dev_token
  logo_dev_retina = var.logo_dev_retina

  rate_limit_enabled     = var.rate_limit_enabled
  rl_ip_window_seconds   = var.rl_ip_window_seconds
  rl_ip_max_requests     = var.rl_ip_max_requests
  rl_user_window_seconds = var.rl_user_window_seconds
  rl_user_max_requests   = var.rl_user_max_requests

  sqs_notif_queue_url = var.enable_push_notifications ? module.notifications.notif_queue_url : ""
  sqs_notif_queue_arn = var.enable_push_notifications ? module.notifications.notif_queue_arn : ""
}

module "security_baseline" {
  source      = "../../modules/security-baseline"
  name_prefix = var.name_prefix
  environment = local.env
  account_id  = data.aws_caller_identity.current.account_id
  aws_region  = var.aws_region
  tags        = local.tags

  alb_arn = module.api.alb_arn

  enable_waf              = true
  enable_account_baseline = true
}

module "notif_worker" {
  count       = var.enable_notif_worker ? 1 : 0
  source      = "../../modules/notif-worker"
  name_prefix = var.name_prefix
  environment = local.env
  tags        = local.tags

  vpc_id             = module.network.vpc_id
  private_subnet_ids = module.network.private_subnet_ids
  cluster_name       = module.api.ecs_cluster_name

  aws_region = var.aws_region
  ecr_image  = "${data.aws_ecr_repository.notif_worker[0].repository_url}:${var.notif_worker_image_tag}"

  sqs_notif_queue_url = module.notifications.notif_queue_url
  sqs_notif_queue_arn = module.notifications.notif_queue_arn

  apns_bundle_id              = var.apns_bundle_id
  apns_team_id                = var.apns_team_id
  apns_key_id                 = var.apns_key_id
  apns_sandbox                = var.apns_sandbox
  apns_auth_key_p8_secret_arn = var.apns_auth_key_p8_secret_arn
}

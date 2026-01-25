variable "name_prefix" {
  type = string
}

variable "environment" {
  type = string
}

variable "tags" {
  type    = map(string)
  default = {}
}

variable "vpc_id" {
  type = string
}

variable "public_subnet_ids" {
  type = list(string)
}

variable "private_subnet_ids" {
  type = list(string)
}

variable "domain_name" {
  type = string
}

variable "acm_certificate_arn" {
  type        = string
  description = "ACM cert ARN for HTTPS; empty means HTTP-only."
  default     = ""
}

variable "ecr_image" {
  type = string
}

variable "desired_count" {
  type = number
}

variable "enable_container_insights" {
  type        = bool
  description = "Enable ECS Container Insights for the cluster."
  default     = true
}

variable "health_check_grace_period_seconds" {
  type        = number
  description = "Time in seconds to ignore failing ELB health checks on newly launched tasks (gives the JVM time to warm up)."
  default     = 120
}

variable "min_capacity" {
  type = number
}

variable "max_capacity" {
  type = number
}

variable "enable_alerts" {
  type        = bool
  description = "Create an SNS topic and wire basic CloudWatch alarms to it."
  default     = true
}

variable "extra_alarm_actions" {
  type        = list(string)
  description = "Additional CloudWatch alarm action ARNs (e.g., SNS topics, OpsGenie)."
  default     = []
}

variable "extra_ok_actions" {
  type        = list(string)
  description = "Additional CloudWatch OK action ARNs."
  default     = []
}

variable "cors_allowed_origins" {
  type = string
}

variable "cloudfront_domain" {
  type = string
}

variable "s3_media_bucket" {
  type = string
}

variable "s3_verification_bucket" {
  type = string
}

variable "s3_dm_bucket" {
  type = string
}

variable "redis_primary_endpoint" {
  type        = string
  description = "Primary Redis endpoint address (host)."
  default     = ""
}

variable "email_from" {
  type = string
}

variable "email_reply_to" {
  type = string
}

variable "email_configuration_set" {
  type = string
}

variable "email_verify_base_url" {
  type        = string
  description = "Base URL used in verification emails. Should be a URL (e.g. https://www.mylooped.app/verify). Empty disables link generation (codes still included)."
  default     = ""
}

variable "ssm_auth_audience_arn" {
  type = string
}

variable "ssm_auth_issuer_arn" {
  type = string
}

variable "ssm_auth_jwks_uri_arn" {
  type = string
}

variable "ssm_db_url_arn" {
  type = string
}

variable "firebase_admin_secret_arn" {
  type = string
}

variable "openai_moderation_secret_arn" {
  type = string
}

variable "openai_moderation_secret_json_key" {
  type        = string
  description = "If the OpenAI secret value is JSON, use this key (e.g. 'key'). Leave empty if the secret value is the raw API key string."
  default     = "key"
}

variable "db_credentials_secret_arn" {
  type = string
}

variable "moderation_enabled" {
  type = bool
}

variable "moderation_openai_enabled" {
  type = bool
}

variable "moderation_openai_model" {
  type = string
}

variable "moderation_openai_base_url" {
  type = string
}

variable "moderation_openai_timeout_millis" {
  type = number
}

variable "moderation_openai_category_blocklist" {
  type = string
}

variable "moderation_report_quarantine_threshold" {
  type = number
}

variable "logo_dev_token" {
  type        = string
  description = "Logo.dev public token used to generate default community logos. Leave empty to disable logo.dev fallback."
  default     = ""
  sensitive   = true
}

variable "logo_dev_retina" {
  type        = bool
  description = "If true, add retina=true to logo.dev URLs."
  default     = true
}

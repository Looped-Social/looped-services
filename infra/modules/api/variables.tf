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

variable "email_admin_from" {
  type        = string
  description = "Optional 'from' address used for admin digests/alerts. Defaults to email_from when empty."
  default     = ""
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

variable "app_minimum_supported_version" {
  type        = string
  description = "Soft minimum app version exposed via /v1/app-config."
  default     = ""
}

variable "app_minimum_supported_version_message" {
  type        = string
  description = "Optional custom message shown with soft minimum app version prompt."
  default     = ""
}

variable "app_minimum_supported_version_update_url" {
  type        = string
  description = "Optional update URL (for example App Store) exposed via /v1/app-config."
  default     = ""
}

variable "devices_app_attest_mode" {
  type        = string
  description = "App Attest rollout mode for the API (disabled, observe, enforce)."
  default     = "disabled"
}

variable "devices_app_attest_challenge_ttl" {
  type        = string
  description = "Challenge TTL passed to the API (for example 5m)."
  default     = "5m"
}

variable "devices_app_attest_trust_ttl" {
  type        = string
  description = "Trust TTL passed to the API (for example 30d)."
  default     = "30d"
}

variable "devices_app_attest_allow_insecure_observed_trust" {
  type        = bool
  description = "Temporary rollout flag. When true, opaque observed proofs can be marked trusted without Apple verification."
  default     = false
}

variable "universal_links_apple_team_id" {
  type        = string
  description = "Apple Developer Team ID used in AASA appID (<TEAM_ID>.<BUNDLE_ID>)."
  default     = ""
}

variable "universal_links_ios_bundle_id" {
  type        = string
  description = "iOS bundle identifier used in AASA appID (<TEAM_ID>.<BUNDLE_ID>)."
  default     = ""
}

variable "universal_links_cache_max_age_seconds" {
  type        = number
  description = "Cache-Control max-age for apple-app-site-association responses."
  default     = 300
}

variable "universal_links_aasa_version" {
  type        = string
  description = "AASA version token used as ETag. Bump when AASA paths/appID change."
  default     = "v1"
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

variable "admin_edge_secret_arn" {
  type        = string
  description = "Optional Secrets Manager ARN containing the ADMIN_EDGE_SECRET value. When set, API requires X-Admin-Edge-Secret for /v1/admin/*."
  default     = ""
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

variable "moderation_openai_daily_request_budget" {
  type = number
}

variable "moderation_openai_budget_redis_prefix" {
  type = string
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

variable "sqs_notif_queue_url" {
  type        = string
  description = "Optional SQS queue URL used for push notifications (enables SQS producer when set)."
  default     = ""
}

variable "sqs_notif_queue_arn" {
  type        = string
  description = "Optional SQS queue ARN used for IAM permissions (sqs:SendMessage)."
  default     = ""
}

variable "rate_limit_enabled" {
  type        = bool
  description = "Enable per-IP/per-user request rate limiting."
  default     = true
}

variable "rl_ip_window_seconds" {
  type        = number
  description = "Sliding window size for per-IP rate limiting."
  default     = 60
}

variable "rl_ip_max_requests" {
  type        = number
  description = "Max requests per IP within rl_ip_window_seconds."
  default     = 120
}

variable "rl_user_window_seconds" {
  type        = number
  description = "Sliding window size for per-user rate limiting."
  default     = 60
}

variable "rl_user_max_requests" {
  type        = number
  description = "Max requests per user within rl_user_window_seconds."
  default     = 180
}

variable "reco_people_active_community_rail_enabled" {
  type        = bool
  description = "Enable the active-community recommendation rail."
  default     = false
}

variable "reco_people_open_report_exclusion_threshold" {
  type        = number
  description = "Exclude recommended users once open distinct reporter count reaches this threshold."
  default     = 3
}

variable "reco_people_experiment_bucket_b_percent" {
  type        = number
  description = "Percent of users assigned to experiment bucket B for people recommendations."
  default     = 50
}

variable "reco_people_max_viewer_exposure_per_candidate_24h" {
  type        = number
  description = "Per-viewer exposure cap per candidate within 24h."
  default     = 3
}

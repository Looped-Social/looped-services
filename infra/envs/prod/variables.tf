variable "aws_region" {
  type    = string
  default = "us-east-1"
}

variable "environment" {
  type    = string
  default = "prod"
}

variable "name_prefix" {
  type        = string
  description = "Prefix used for naming AWS resources."
  default     = "looped"
}

variable "vpc_cidr" {
  type    = string
  default = "10.30.0.0/16"
}

variable "multi_az_nat" {
  type        = bool
  description = "Create one NAT Gateway per AZ (higher availability, higher cost)."
  default     = true
}

variable "domain_name" {
  type        = string
  description = "Public API domain for this environment."
  default     = "api.mylooped.app"
}

variable "acm_certificate_arn" {
  type        = string
  description = "ACM certificate ARN for HTTPS on the ALB."
}

variable "additional_acm_certificate_arns" {
  type        = list(string)
  description = "Additional ACM certificate ARNs to attach to the ALB HTTPS listener for legacy hostnames during migration."
  default     = []
}

variable "ecr_repository_name" {
  type        = string
  description = "ECR repo name containing the API image."
  default     = "looped-api"
}

variable "image_tag" {
  type        = string
  description = "ECR image tag to run (e.g. prod)."
  default     = "main"
}

variable "desired_count" {
  type    = number
  default = 2
}

variable "min_capacity" {
  type    = number
  default = 2
}

variable "max_capacity" {
  type    = number
  default = 6
}

variable "cors_allowed_origins" {
  type    = string
  default = "https://admin.mylooped.app,https://admin.looped-social.com,https://mylooped.app,https://www.mylooped.app,https://looped-social.com,https://www.looped-social.com"
}

variable "email_from" {
  type    = string
  default = "verify@mylooped.app"
}

variable "email_admin_from" {
  type        = string
  description = "From address used for admin digests/alerts. Defaults to system@mylooped.app."
  default     = "system@mylooped.app"
}

variable "email_reply_to" {
  type    = string
  default = "support@mylooped.app"
}

variable "email_configuration_set" {
  type    = string
  default = "looped-email-events"
}

variable "email_community_request_from" {
  type        = string
  description = "From address used for community request emails."
  default     = "no-reply@looped-social.com"
}

variable "share_base_url" {
  type        = string
  description = "Base URL used for public share links."
  default     = "https://looped-social.com"
}

variable "media_public_domain" {
  type        = string
  description = "Optional public media host to emit in API responses (for example media.looped-social.com). Defaults to the CloudFront distribution hostname when empty."
  default     = ""
}

variable "app_minimum_supported_version" {
  type        = string
  description = "Soft minimum app version exposed via /v1/app-config."
  default     = "1.0.3"
}

variable "app_minimum_supported_version_message" {
  type        = string
  description = "Optional custom message shown with soft minimum app version prompt."
  default     = ""
}

variable "app_minimum_supported_version_update_url" {
  type        = string
  description = "Optional update URL (for example App Store) exposed via /v1/app-config."
  default     = "https://apps.apple.com/us/app/looped-social/id6758413180"
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
  description = "Temporary rollout flag. When true, observed proofs can be treated as trusted without Apple verification."
  default     = false
}

variable "universal_links_apple_team_id" {
  type        = string
  description = "Apple Developer Team ID for AASA appID."
  default     = ""
}

variable "universal_links_ios_bundle_id" {
  type        = string
  description = "iOS bundle ID for AASA appID."
  default     = ""
}

variable "universal_links_cache_max_age_seconds" {
  type        = number
  description = "AASA Cache-Control max-age."
  default     = 300
}

variable "universal_links_aasa_version" {
  type        = string
  description = "AASA version token used for ETag."
  default     = "v1"
}

variable "firebase_admin_secret_arn" {
  type        = string
  description = "Secrets Manager ARN containing FIREBASE_ADMIN_CREDENTIALS_JSON (string)."
}

variable "openai_moderation_secret_arn" {
  type        = string
  description = "Secrets Manager ARN containing MODERATION_OPENAI_API_KEY (string)."
}

variable "db_credentials_secret_arn" {
  type        = string
  description = "Secrets Manager ARN with JSON keys 'username' and 'password' for DB creds."
}

variable "admin_edge_secret_arn" {
  type        = string
  description = "Optional Secrets Manager ARN containing ADMIN_EDGE_SECRET (string). When set, /v1/admin/* requires X-Admin-Edge-Secret."
  default     = ""
}

variable "db_url" {
  type        = string
  description = "JDBC URL without embedded credentials."
}

variable "auth_issuer" {
  type        = string
  description = "Firebase JWT issuer."
}

variable "auth_audience" {
  type        = string
  description = "Firebase JWT audience."
}

variable "auth_jwks_uri" {
  type        = string
  description = "JWKS URI."
}

variable "moderation_enabled" {
  type    = bool
  default = true
}

variable "moderation_openai_enabled" {
  type    = bool
  default = true
}

variable "moderation_openai_model" {
  type    = string
  default = "omni-moderation-latest"
}

variable "moderation_openai_base_url" {
  type    = string
  default = "https://api.openai.com/v1"
}

variable "moderation_openai_timeout_millis" {
  type    = number
  default = 5000
}

variable "moderation_openai_daily_request_budget" {
  type    = number
  default = 9000
}

variable "moderation_openai_budget_redis_prefix" {
  type    = string
  default = "moderation:openai:requests"
}

variable "moderation_openai_category_blocklist" {
  type    = string
  default = "hate,hate/threatening,sexual,sexual/minors,self-harm,self-harm/intent,self-harm/instructions,violence,violence/graphic"
}

variable "moderation_report_quarantine_threshold" {
  type    = number
  default = 10
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
  default     = true
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

variable "enable_notif_worker" {
  type        = bool
  description = "When true, provisions the notif-worker ECS service (SQS consumer) for this environment."
  default     = false
}

variable "enable_push_notifications" {
  type        = bool
  description = "When true, provisions the notifications SQS queue and enables the API SQS push producer."
  default     = true
}

variable "notif_worker_ecr_repository_name" {
  type        = string
  description = "ECR repo name containing the notif-worker image."
  default     = "looped-notif-worker"
}

variable "notif_worker_image_tag" {
  type        = string
  description = "ECR image tag to run for notif-worker."
  default     = "prod"
}

variable "apns_bundle_id" {
  type        = string
  description = "APNs bundle id (topic) for this environment."
  default     = ""
}

variable "apns_team_id" {
  type        = string
  description = "Apple Developer Team ID."
  default     = ""
}

variable "apns_key_id" {
  type        = string
  description = "APNs Auth Key ID (kid)."
  default     = ""
}

variable "apns_sandbox" {
  type        = bool
  description = "If true, notif-worker uses the APNs sandbox host."
  default     = false
}

variable "apns_auth_key_p8_secret_arn" {
  type        = string
  description = "Secrets Manager ARN containing base64-encoded APNs auth key (.p8)."
  default     = ""
}

variable "media_cloudfront_aliases" {
  type        = list(string)
  description = "Optional custom domain aliases for the media CloudFront distribution (e.g. [\"media.mylooped.app\"])."
  default     = []
}

variable "media_cloudfront_acm_certificate_arn" {
  type        = string
  description = "ACM certificate ARN (must be in us-east-1) for the media CloudFront distribution when aliases are set."
  default     = ""
}

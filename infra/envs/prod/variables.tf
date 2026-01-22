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

variable "domain_name" {
  type        = string
  description = "Public API domain for this environment."
  default     = "api.mylooped.app"
}

variable "acm_certificate_arn" {
  type        = string
  description = "ACM certificate ARN for HTTPS on the ALB."
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
  default = "https://admin.mylooped.app"
}

variable "email_from" {
  type    = string
  default = "verify@mylooped.app"
}

variable "email_reply_to" {
  type    = string
  default = "support@mylooped.app"
}

variable "email_configuration_set" {
  type    = string
  default = "looped-email-events"
}

variable "email_verify_base_url" {
  type        = string
  description = "Base URL used in verification emails. Should be a URL (e.g. https://www.mylooped.app/verify). Empty disables link generation."
  default     = ""
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

variable "moderation_openai_category_blocklist" {
  type    = string
  default = "hate,hate/threatening,sexual,sexual/minors,self-harm,self-harm/intent,self-harm/instructions,violence,violence/graphic"
}

variable "moderation_report_quarantine_threshold" {
  type    = number
  default = 10
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

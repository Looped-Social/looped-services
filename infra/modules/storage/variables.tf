variable "name_prefix" {
  type = string
}

variable "environment" {
  type = string
}

variable "account_id" {
  type = string
}

variable "aws_region" {
  type = string
}

variable "cors_allowed_origins" {
  type        = string
  description = "Comma-separated list of allowed origins for browser-based direct S3 uploads (e.g. admin dash on localhost)."
}

variable "cloudfront_aliases" {
  type        = list(string)
  description = "Optional alternate domain names for the media CloudFront distribution (e.g. [\"media.mylooped.app\"])."
  default     = []
}

variable "cloudfront_acm_certificate_arn" {
  type        = string
  description = "ACM certificate ARN (must be in us-east-1) to use when cloudfront_aliases is non-empty."
  default     = ""
}

variable "tags" {
  type    = map(string)
  default = {}
}

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

variable "cloudfront_aliases" {
  type        = list(string)
  description = "Optional alternate domain names for the media CloudFront distribution (e.g. [\"media.mylooped.app\"])."
  default     = []
}

variable "cloudfront_acm_certificate_arn" {
  type        = string
  description = "ACM certificate ARN (must be in us-east-1) to use when cloudfront_aliases is non-empty."
  default     = ""

  validation {
    condition     = length(var.cloudfront_aliases) == 0 || trimspace(var.cloudfront_acm_certificate_arn) != ""
    error_message = "cloudfront_acm_certificate_arn is required when cloudfront_aliases is non-empty."
  }
}

variable "tags" {
  type    = map(string)
  default = {}
}

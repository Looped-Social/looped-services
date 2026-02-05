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

variable "alb_arn" {
  type        = string
  description = "ARN of the public ALB to protect with WAF."
}

variable "enable_waf" {
  type        = bool
  description = "Attach AWS WAFv2 WebACL to the ALB."
  default     = true
}

variable "waf_rate_limit" {
  type        = number
  description = "WAF rate-based rule limit (requests per 5 minutes) per source IP."
  default     = 2000
}

variable "enable_account_baseline" {
  type        = bool
  description = "Enable account-wide baseline services (CloudTrail, GuardDuty, Security Hub) once per account."
  default     = false
}

variable "tags" {
  type    = map(string)
  default = {}
}


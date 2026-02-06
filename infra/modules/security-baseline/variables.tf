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

variable "enable_github_infra_role" {
  type        = bool
  description = "Create a GitHub Actions OIDC role intended for OpenTofu plan/apply (per environment)."
  default     = true
}

variable "github_repo" {
  type        = string
  description = "GitHub repo in OWNER/REPO form."
  default     = "Looped-Social/looped-services"
}

variable "github_infra_environment" {
  type        = string
  description = "GitHub Actions environment name required to assume the infra role (e.g. prod-infra)."
  default     = ""
}

variable "tags" {
  type    = map(string)
  default = {}
}

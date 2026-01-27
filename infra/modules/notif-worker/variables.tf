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

variable "private_subnet_ids" {
  type = list(string)
}

variable "cluster_name" {
  type = string
}

variable "desired_count" {
  type    = number
  default = 1
}

variable "ecr_image" {
  type        = string
  description = "Full ECR image reference for the notif-worker (e.g. <repo_url>:main)."
}

variable "aws_region" {
  type    = string
  default = "us-east-1"
}

variable "sqs_notif_queue_url" {
  type = string
}

variable "sqs_notif_queue_arn" {
  type = string
}

variable "apns_bundle_id" {
  type = string
}

variable "apns_team_id" {
  type = string
}

variable "apns_key_id" {
  type = string
}

variable "apns_sandbox" {
  type    = bool
  default = true
}

variable "apns_auth_key_p8_secret_arn" {
  type        = string
  description = "Secrets Manager ARN containing base64-encoded APNS auth key (.p8)."
}

variable "cpu_architecture" {
  type        = string
  description = "Fargate CPU architecture. Use ARM64 or X86_64."
  default     = "ARM64"
}

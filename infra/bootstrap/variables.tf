variable "aws_region" {
  type        = string
  description = "AWS region for state resources."
  default     = "us-east-1"
}

variable "state_bucket_name" {
  type        = string
  description = "Globally unique S3 bucket name to store OpenTofu state."
}

variable "lock_table_name" {
  type        = string
  description = "DynamoDB table name for state locking."
  default     = "looped-tofu-lock"
}


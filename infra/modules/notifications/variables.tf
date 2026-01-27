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

variable "max_receive_count" {
  type        = number
  description = "How many times a message can be received before being moved to the DLQ."
  default     = 5
}


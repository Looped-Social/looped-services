locals {
  name = "${var.name_prefix}-${var.environment}"
}

resource "aws_sqs_queue" "notif_dlq" {
  name                      = "${local.name}-notif-events-dlq"
  message_retention_seconds = 1209600 # 14 days
  sqs_managed_sse_enabled   = true

  tags = var.tags
}

resource "aws_sqs_queue" "notif_events" {
  name                       = "${local.name}-notif-events"
  visibility_timeout_seconds = 30
  message_retention_seconds  = 345600 # 4 days
  sqs_managed_sse_enabled    = true

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.notif_dlq.arn
    maxReceiveCount     = max(1, var.max_receive_count)
  })

  tags = var.tags
}

output "notif_queue_url" {
  value = aws_sqs_queue.notif_events.url
}

output "notif_queue_arn" {
  value = aws_sqs_queue.notif_events.arn
}

output "notif_dlq_url" {
  value = aws_sqs_queue.notif_dlq.url
}

output "notif_dlq_arn" {
  value = aws_sqs_queue.notif_dlq.arn
}


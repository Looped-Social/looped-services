output "alb_dns_name" {
  value = module.api.alb_dns_name
}

output "alb_zone_id" {
  value = module.api.alb_zone_id
}

output "cloudfront_domain_name" {
  value = module.storage.cloudfront_domain_name
}

output "notif_queue_url" {
  value = module.notifications.notif_queue_url
}

output "notif_worker_service_name" {
  value = var.enable_notif_worker ? module.notif_worker[0].service_name : null
}

output "redis_primary_endpoint" {
  value = module.redis.primary_endpoint_address
}

output "s3_media_bucket" {
  value = module.storage.media_bucket_name
}

output "s3_verification_bucket" {
  value = module.storage.verification_bucket_name
}

output "alerts_topic_arn" {
  value = module.api.alerts_topic_arn
}

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

output "waf_web_acl_arn" {
  value = module.security_baseline.waf_web_acl_arn
}

output "cloudtrail_trail_arn" {
  value = module.security_baseline.cloudtrail_trail_arn
}

output "guardduty_detector_id" {
  value = module.security_baseline.guardduty_detector_id
}

output "github_infra_role_arn" {
  value = module.security_baseline.github_infra_role_arn
}

output "github_infra_environment" {
  value = module.security_baseline.github_infra_environment
}

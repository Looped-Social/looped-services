output "alb_dns_name" {
  value = module.api.alb_dns_name
}

output "alb_zone_id" {
  value = module.api.alb_zone_id
}

output "cloudfront_domain_name" {
  value = module.storage.cloudfront_domain_name
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


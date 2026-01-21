output "media_bucket_name" {
  value = aws_s3_bucket.media.bucket
}

output "verification_bucket_name" {
  value = aws_s3_bucket.verification.bucket
}

output "dm_bucket_name" {
  value = aws_s3_bucket.dm.bucket
}

output "cloudfront_domain_name" {
  value = aws_cloudfront_distribution.media.domain_name
}


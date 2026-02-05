output "waf_web_acl_arn" {
  value = var.enable_waf ? aws_wafv2_web_acl.alb[0].arn : null
}

output "cloudtrail_trail_arn" {
  value = var.enable_account_baseline ? aws_cloudtrail.account[0].arn : null
}

output "guardduty_detector_id" {
  value = var.enable_account_baseline ? aws_guardduty_detector.this[0].id : null
}

output "securityhub_enabled" {
  value = var.enable_account_baseline ? true : false
}


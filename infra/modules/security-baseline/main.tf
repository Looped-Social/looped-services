locals {
  name                     = "${var.name_prefix}-${var.environment}"
  github_infra_environment = trimspace(var.github_infra_environment) != "" ? trimspace(var.github_infra_environment) : "${var.environment}-infra"
  github_oidc_provider_arn = "arn:aws:iam::${var.account_id}:oidc-provider/token.actions.githubusercontent.com"
  github_workflow_ref      = "${var.github_repo}/.github/workflows/infra-apply-${var.environment}.yml@refs/heads/main"
}

#
# WAFv2 (Regional) attached to the public ALB.
#
resource "aws_wafv2_web_acl" "alb" {
  count = var.enable_waf ? 1 : 0

  name  = "${local.name}-alb-waf"
  scope = "REGIONAL"

  default_action {
    allow {}
  }

  visibility_config {
    cloudwatch_metrics_enabled = true
    metric_name                = "${local.name}-alb-waf"
    sampled_requests_enabled   = true
  }

  rule {
    name     = "AWSManagedRulesCommonRuleSet"
    priority = 10

    override_action {
      none {}
    }

    statement {
      managed_rule_group_statement {
        name        = "AWSManagedRulesCommonRuleSet"
        vendor_name = "AWS"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${local.name}-common"
      sampled_requests_enabled   = true
    }
  }

  rule {
    name     = "AWSManagedRulesKnownBadInputsRuleSet"
    priority = 20

    override_action {
      none {}
    }

    statement {
      managed_rule_group_statement {
        name        = "AWSManagedRulesKnownBadInputsRuleSet"
        vendor_name = "AWS"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${local.name}-bad-inputs"
      sampled_requests_enabled   = true
    }
  }

  rule {
    name     = "AWSManagedRulesAmazonIpReputationList"
    priority = 30

    override_action {
      none {}
    }

    statement {
      managed_rule_group_statement {
        name        = "AWSManagedRulesAmazonIpReputationList"
        vendor_name = "AWS"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${local.name}-ip-rep"
      sampled_requests_enabled   = true
    }
  }

  rule {
    name     = "RateLimitPerIp"
    priority = 100

    action {
      block {}
    }

    statement {
      rate_based_statement {
        limit              = var.waf_rate_limit
        aggregate_key_type = "IP"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${local.name}-rate"
      sampled_requests_enabled   = true
    }
  }

  tags = merge(var.tags, { Name = "${local.name}-alb-waf" })
}

resource "aws_wafv2_web_acl_association" "alb" {
  count = var.enable_waf ? 1 : 0

  resource_arn = var.alb_arn
  web_acl_arn  = aws_wafv2_web_acl.alb[0].arn
}

#
# GitHub Actions OIDC role for OpenTofu (per env).
#

data "aws_iam_policy_document" "github_infra_assume" {
  count = var.enable_github_infra_role ? 1 : 0

  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [local.github_oidc_provider_arn]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["repo:${var.github_repo}:environment:${local.github_infra_environment}"]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:job_workflow_ref"
      values   = [local.github_workflow_ref]
    }
  }
}

resource "aws_iam_role" "github_infra" {
  count = var.enable_github_infra_role ? 1 : 0

  name               = "${local.name}-gha-infra"
  assume_role_policy = data.aws_iam_policy_document.github_infra_assume[0].json

  tags = merge(var.tags, { Name = "${local.name}-gha-infra" })
}

resource "aws_iam_role_policy_attachment" "github_infra_admin" {
  count = var.enable_github_infra_role ? 1 : 0

  role       = aws_iam_role.github_infra[0].name
  policy_arn = "arn:aws:iam::aws:policy/AdministratorAccess"
}

data "aws_iam_policy_document" "github_infra_denies" {
  count = var.enable_github_infra_role ? 1 : 0

  statement {
    sid    = "DenyOrganizations"
    effect = "Deny"
    actions = [
      "organizations:*"
    ]
    resources = ["*"]
  }

  statement {
    sid    = "DenyCreateLongLivedCredentials"
    effect = "Deny"
    actions = [
      "iam:CreateAccessKey",
      "iam:UpdateAccessKey",
      "iam:DeleteAccessKey",
      "iam:CreateLoginProfile",
      "iam:UpdateLoginProfile",
      "iam:DeleteLoginProfile",
      "iam:CreateUser",
      "iam:DeleteUser",
      "iam:AttachUserPolicy",
      "iam:PutUserPolicy"
    ]
    resources = ["*"]
  }
}

resource "aws_iam_role_policy" "github_infra_denies" {
  count = var.enable_github_infra_role ? 1 : 0

  name   = "${local.name}-gha-infra-denies"
  role   = aws_iam_role.github_infra[0].id
  policy = data.aws_iam_policy_document.github_infra_denies[0].json
}

#
# Account baseline (run once in a "primary" env, usually prod).
# CloudTrail + GuardDuty + Security Hub.
#

resource "aws_s3_bucket" "cloudtrail" {
  count  = var.enable_account_baseline ? 1 : 0
  bucket = "${var.name_prefix}-cloudtrail-${var.account_id}"

  lifecycle {
    prevent_destroy = true
  }

  tags = merge(var.tags, { Name = "${var.name_prefix}-cloudtrail-${var.account_id}" })
}

resource "aws_s3_bucket_public_access_block" "cloudtrail" {
  count = var.enable_account_baseline ? 1 : 0

  bucket                  = aws_s3_bucket.cloudtrail[0].id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_versioning" "cloudtrail" {
  count  = var.enable_account_baseline ? 1 : 0
  bucket = aws_s3_bucket.cloudtrail[0].id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "cloudtrail" {
  count  = var.enable_account_baseline ? 1 : 0
  bucket = aws_s3_bucket.cloudtrail[0].id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

data "aws_iam_policy_document" "cloudtrail_bucket" {
  count = var.enable_account_baseline ? 1 : 0

  statement {
    sid     = "AWSCloudTrailAclCheck"
    effect  = "Allow"
    actions = ["s3:GetBucketAcl"]
    resources = [
      aws_s3_bucket.cloudtrail[0].arn
    ]
    principals {
      type        = "Service"
      identifiers = ["cloudtrail.amazonaws.com"]
    }
  }

  statement {
    sid     = "AWSCloudTrailWrite"
    effect  = "Allow"
    actions = ["s3:PutObject"]
    resources = [
      "${aws_s3_bucket.cloudtrail[0].arn}/AWSLogs/${var.account_id}/*"
    ]
    principals {
      type        = "Service"
      identifiers = ["cloudtrail.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "s3:x-amz-acl"
      values   = ["bucket-owner-full-control"]
    }
  }
}

resource "aws_s3_bucket_policy" "cloudtrail" {
  count = var.enable_account_baseline ? 1 : 0

  bucket = aws_s3_bucket.cloudtrail[0].id
  policy = data.aws_iam_policy_document.cloudtrail_bucket[0].json
}

resource "aws_cloudtrail" "account" {
  count = var.enable_account_baseline ? 1 : 0

  name                          = "${var.name_prefix}-account-trail"
  s3_bucket_name                = aws_s3_bucket.cloudtrail[0].id
  include_global_service_events = true
  is_multi_region_trail         = true
  enable_log_file_validation    = true

  event_selector {
    read_write_type           = "All"
    include_management_events = true
  }

  tags = merge(var.tags, { Name = "${var.name_prefix}-account-trail" })
}

resource "aws_guardduty_detector" "this" {
  count  = var.enable_account_baseline ? 1 : 0
  enable = true

  tags = merge(var.tags, { Name = "${var.name_prefix}-guardduty" })
}

resource "aws_securityhub_account" "this" {
  count = var.enable_account_baseline ? 1 : 0
}

resource "aws_securityhub_standards_subscription" "aws_foundational" {
  count = var.enable_account_baseline ? 1 : 0

  standards_arn = "arn:aws:securityhub:${var.aws_region}::standards/aws-foundational-security-best-practices/v/1.0.0"

  depends_on = [aws_securityhub_account.this]
}

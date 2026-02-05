locals {
  suffix                      = "${var.environment}-${var.account_id}"
  media_bucket_name           = "${var.name_prefix}-media-${local.suffix}"
  verification_bucket_name    = "${var.name_prefix}-verification-${local.suffix}"
  dm_bucket_name              = "${var.name_prefix}-dm-media-${local.suffix}"
  cloudfront_use_default_cert = trimspace(var.cloudfront_acm_certificate_arn) == ""
  cors_origins                = [for o in split(",", var.cors_allowed_origins) : trimspace(o) if trimspace(o) != ""]
}

resource "aws_s3_bucket" "media" {
  bucket = local.media_bucket_name
  tags   = merge(var.tags, { Name = local.media_bucket_name })
}

resource "aws_s3_bucket_public_access_block" "media" {
  bucket                  = aws_s3_bucket.media.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "media" {
  bucket = aws_s3_bucket.media.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_cors_configuration" "media" {
  bucket = aws_s3_bucket.media.id

  cors_rule {
    allowed_methods = ["GET", "HEAD", "PUT", "POST"]
    allowed_origins = local.cors_origins
    allowed_headers = ["*"]
    expose_headers  = ["ETag"]
    max_age_seconds = 3000
  }
}

resource "aws_s3_bucket" "verification" {
  bucket = local.verification_bucket_name
  tags   = merge(var.tags, { Name = local.verification_bucket_name })
}

resource "aws_s3_bucket_public_access_block" "verification" {
  bucket                  = aws_s3_bucket.verification.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "verification" {
  bucket = aws_s3_bucket.verification.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_cors_configuration" "verification" {
  bucket = aws_s3_bucket.verification.id

  cors_rule {
    allowed_methods = ["GET", "HEAD", "PUT", "POST"]
    allowed_origins = local.cors_origins
    allowed_headers = ["*"]
    expose_headers  = ["ETag"]
    max_age_seconds = 3000
  }
}

resource "aws_s3_bucket" "dm" {
  bucket = local.dm_bucket_name
  tags   = merge(var.tags, { Name = local.dm_bucket_name })
}

resource "aws_s3_bucket_public_access_block" "dm" {
  bucket                  = aws_s3_bucket.dm.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "dm" {
  bucket = aws_s3_bucket.dm.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_cors_configuration" "dm" {
  bucket = aws_s3_bucket.dm.id

  cors_rule {
    allowed_methods = ["GET", "HEAD", "PUT", "POST"]
    allowed_origins = local.cors_origins
    allowed_headers = ["*"]
    expose_headers  = ["ETag"]
    max_age_seconds = 3000
  }
}

# CloudFront for public media reads (bucket stays private; CloudFront uses Origin Access Control).
resource "aws_cloudfront_origin_access_control" "media" {
  name                              = "${var.name_prefix}-${var.environment}-media-oac"
  description                       = "OAC for Looped media bucket (${var.environment})"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

resource "aws_cloudfront_distribution" "media" {
  enabled = true
  comment = "Looped media CDN (${var.environment})"

  aliases = var.cloudfront_aliases

  origin {
    domain_name              = aws_s3_bucket.media.bucket_regional_domain_name
    origin_id                = "s3-media"
    origin_access_control_id = aws_cloudfront_origin_access_control.media.id
  }

  default_cache_behavior {
    target_origin_id       = "s3-media"
    viewer_protocol_policy = "redirect-to-https"
    compress               = true

    allowed_methods = ["GET", "HEAD", "OPTIONS"]
    cached_methods  = ["GET", "HEAD"]

    forwarded_values {
      query_string = false
      cookies {
        forward = "none"
      }
    }
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  viewer_certificate {
    cloudfront_default_certificate = local.cloudfront_use_default_cert
    acm_certificate_arn            = local.cloudfront_use_default_cert ? null : var.cloudfront_acm_certificate_arn
    ssl_support_method             = local.cloudfront_use_default_cert ? null : "sni-only"
    minimum_protocol_version       = local.cloudfront_use_default_cert ? null : "TLSv1.2_2021"
  }

  tags = var.tags
}

data "aws_iam_policy_document" "media_bucket_policy" {
  statement {
    sid     = "AllowCloudFrontRead"
    effect  = "Allow"
    actions = ["s3:GetObject"]
    resources = [
      "${aws_s3_bucket.media.arn}/*"
    ]
    principals {
      type        = "Service"
      identifiers = ["cloudfront.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "AWS:SourceArn"
      values   = [aws_cloudfront_distribution.media.arn]
    }
  }
}

resource "aws_s3_bucket_policy" "media" {
  bucket = aws_s3_bucket.media.id
  policy = data.aws_iam_policy_document.media_bucket_policy.json
}

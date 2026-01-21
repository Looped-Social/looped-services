locals {
  name = "${var.name_prefix}-${var.environment}"
}

resource "aws_security_group" "redis" {
  name        = "${local.name}-redis-sg"
  description = "Redis SG for ${local.name}"
  vpc_id      = var.vpc_id

  ingress {
    description = "TLS Redis from allowed CIDRs"
    from_port   = 6379
    to_port     = 6379
    protocol    = "tcp"
    cidr_blocks = var.allowed_cidr_blocks
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(var.tags, { Name = "${local.name}-redis-sg" })
}

resource "aws_elasticache_subnet_group" "this" {
  name       = "${local.name}-redis-subnets"
  subnet_ids = var.private_subnet_ids
  tags       = var.tags
}

resource "aws_elasticache_replication_group" "this" {
  replication_group_id       = "${local.name}-redis"
  description                = "Looped Redis (${var.environment})"
  engine                     = "valkey"
  node_type                  = var.node_type
  port                       = 6379
  subnet_group_name          = aws_elasticache_subnet_group.this.name
  security_group_ids         = [aws_security_group.redis.id]
  at_rest_encryption_enabled = true
  transit_encryption_enabled = true
  automatic_failover_enabled = true
  multi_az_enabled           = true
  num_cache_clusters         = 2
  snapshot_retention_limit   = 1
  snapshot_window            = "07:00-08:00"
  apply_immediately          = true

  tags = var.tags
}

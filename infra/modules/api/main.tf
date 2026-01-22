data "aws_region" "current" {}

locals {
  name                     = "${var.name_prefix}-${var.environment}"
  https_enabled            = var.acm_certificate_arn != null && trimspace(var.acm_certificate_arn) != ""
  redis_url                = (var.redis_primary_endpoint == null || trimspace(var.redis_primary_endpoint) == "") ? "" : "rediss://${var.redis_primary_endpoint}:6379"
  openai_secret_value_from = (var.openai_moderation_secret_json_key == null || trimspace(var.openai_moderation_secret_json_key) == "") ? var.openai_moderation_secret_arn : "${var.openai_moderation_secret_arn}:${var.openai_moderation_secret_json_key}::"
}

resource "aws_security_group" "alb" {
  name        = "${local.name}-alb-sg"
  description = "ALB SG for ${local.name}"
  vpc_id      = var.vpc_id

  ingress {
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(var.tags, { Name = "${local.name}-alb-sg" })
}

resource "aws_security_group" "ecs" {
  name        = "${local.name}-ecs-sg"
  description = "ECS tasks SG for ${local.name}"
  vpc_id      = var.vpc_id

  ingress {
    description     = "From ALB to API"
    from_port       = 8080
    to_port         = 8080
    protocol        = "tcp"
    security_groups = [aws_security_group.alb.id]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(var.tags, { Name = "${local.name}-ecs-sg" })
}

resource "aws_lb" "this" {
  name               = substr("${local.name}-api", 0, 32)
  internal           = false
  load_balancer_type = "application"
  security_groups    = [aws_security_group.alb.id]
  subnets            = var.public_subnet_ids

  enable_deletion_protection = var.environment == "prod" ? true : false

  tags = merge(var.tags, { Name = "${local.name}-alb" })
}

resource "aws_lb_target_group" "api" {
  name        = substr("${local.name}-tg", 0, 32)
  port        = 8080
  protocol    = "HTTP"
  target_type = "ip"
  vpc_id      = var.vpc_id

  health_check {
    enabled             = true
    path                = "/actuator/health"
    matcher             = "200-399"
    interval            = 30
    timeout             = 10
    healthy_threshold   = 2
    unhealthy_threshold = 2
  }

  tags = var.tags
}

resource "aws_lb_listener" "http_forward" {
  count             = local.https_enabled ? 0 : 1
  load_balancer_arn = aws_lb.this.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.api.arn
  }
}

resource "aws_lb_listener" "http_redirect" {
  count             = local.https_enabled ? 1 : 0
  load_balancer_arn = aws_lb.this.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type = "redirect"
    redirect {
      port        = "443"
      protocol    = "HTTPS"
      status_code = "HTTP_301"
    }
  }
}

resource "aws_lb_listener" "https" {
  count             = local.https_enabled ? 1 : 0
  load_balancer_arn = aws_lb.this.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-Res-2021-06"
  certificate_arn   = var.acm_certificate_arn

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.api.arn
  }
}

resource "aws_cloudwatch_log_group" "api" {
  name              = "/ecs/${local.name}-api"
  retention_in_days = 30
  tags              = var.tags
}

resource "aws_iam_role" "execution" {
  name = "${local.name}-ecs-execution-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Action    = "sts:AssumeRole"
      Effect    = "Allow"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
    }]
  })

  tags = var.tags
}

resource "aws_iam_role_policy_attachment" "execution" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

resource "aws_iam_role_policy" "execution_secrets" {
  name = "${local.name}-execution-secrets"
  role = aws_iam_role.execution.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = ["secretsmanager:GetSecretValue"]
        Resource = [
          var.firebase_admin_secret_arn,
          var.openai_moderation_secret_arn,
          var.db_credentials_secret_arn
        ]
      },
      {
        Effect = "Allow"
        Action = ["ssm:GetParameters", "ssm:GetParameter"]
        Resource = [
          var.ssm_db_url_arn,
          var.ssm_auth_audience_arn,
          var.ssm_auth_issuer_arn,
          var.ssm_auth_jwks_uri_arn
        ]
      }
    ]
  })
}

resource "aws_iam_role" "task" {
  name = "${local.name}-ecs-task-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Action    = "sts:AssumeRole"
      Effect    = "Allow"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
    }]
  })

  tags = var.tags
}

resource "aws_iam_role_policy" "task_app" {
  name = "${local.name}-task-app"
  role = aws_iam_role.task.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "s3:GetObject",
          "s3:PutObject",
          "s3:DeleteObject",
          "s3:AbortMultipartUpload",
          "s3:ListBucket",
          "s3:PutObjectTagging",
          "s3:GetObjectTagging"
        ]
        Resource = [
          "arn:aws:s3:::${var.s3_media_bucket}",
          "arn:aws:s3:::${var.s3_media_bucket}/*",
          "arn:aws:s3:::${var.s3_verification_bucket}",
          "arn:aws:s3:::${var.s3_verification_bucket}/*",
          "arn:aws:s3:::${var.s3_dm_bucket}",
          "arn:aws:s3:::${var.s3_dm_bucket}/*"
        ]
      },
      {
        Effect   = "Allow"
        Action   = ["ses:SendEmail", "ses:SendRawEmail"]
        Resource = "*"
      }
    ]
  })
}

resource "aws_ecs_cluster" "this" {
  name = "${local.name}-cluster"
  setting {
    name  = "containerInsights"
    value = "disabled"
  }
  tags = var.tags
}

resource "aws_ecs_task_definition" "api" {
  family                   = "${local.name}-api"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = "512"
  memory                   = "1024"
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn

  container_definitions = jsonencode([
    {
      name      = "api"
      image     = var.ecr_image
      essential = true
      portMappings = [
        {
          containerPort = 8080
          hostPort      = 8080
          protocol      = "tcp"
          appProtocol   = "http"
        }
      ]
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          awslogs-group         = aws_cloudwatch_log_group.api.name
          awslogs-region        = data.aws_region.current.name
          awslogs-stream-prefix = "api"
        }
      }
      environment = [
        { name = "AWS_REGION", value = data.aws_region.current.name },
        { name = "CORS_ALLOWED_ORIGINS", value = var.cors_allowed_origins },
        { name = "S3_BUCKET", value = var.s3_media_bucket },
        { name = "S3_VERIFICATION_BUCKET", value = var.s3_verification_bucket },
        { name = "S3_DM_BUCKET", value = var.s3_dm_bucket },
        { name = "S3_VERIFICATION_REGION", value = data.aws_region.current.name },
        { name = "S3_DM_REGION", value = data.aws_region.current.name },
        { name = "CLOUDFRONT_DOMAIN", value = var.cloudfront_domain },
        { name = "LOGO_DEV_TOKEN", value = var.logo_dev_token },
        { name = "LOGO_DEV_RETINA", value = tostring(var.logo_dev_retina) },
        { name = "EMAIL_FROM", value = var.email_from },
        { name = "EMAIL_REPLY_TO", value = var.email_reply_to },
        { name = "EMAIL_CONFIGURATION_SET", value = var.email_configuration_set },
        { name = "EMAIL_VERIFY_BASE_URL", value = var.email_verify_base_url },
        { name = "MODERATION_ENABLED", value = tostring(var.moderation_enabled) },
        { name = "MODERATION_OPENAI_ENABLED", value = tostring(var.moderation_openai_enabled) },
        { name = "MODERATION_OPENAI_MODEL", value = var.moderation_openai_model },
        { name = "MODERATION_OPENAI_BASE_URL", value = var.moderation_openai_base_url },
        { name = "MODERATION_OPENAI_TIMEOUT_MILLIS", value = tostring(var.moderation_openai_timeout_millis) },
        { name = "MODERATION_OPENAI_CATEGORY_BLOCKLIST", value = var.moderation_openai_category_blocklist },
        { name = "MODERATION_REPORT_QUARANTINE_THRESHOLD", value = tostring(var.moderation_report_quarantine_threshold) },
        { name = "REDIS_URL", value = local.redis_url }
      ]
      secrets = [
        { name = "AUTH_AUDIENCE", valueFrom = var.ssm_auth_audience_arn },
        { name = "AUTH_ISSUER", valueFrom = var.ssm_auth_issuer_arn },
        { name = "AUTH_JWKS_URI", valueFrom = var.ssm_auth_jwks_uri_arn },
        { name = "DB_URL", valueFrom = var.ssm_db_url_arn },
        { name = "DB_USERNAME", valueFrom = "${var.db_credentials_secret_arn}:username::" },
        { name = "DB_PASSWORD", valueFrom = "${var.db_credentials_secret_arn}:password::" },
        { name = "FIREBASE_ADMIN_CREDENTIALS_JSON", valueFrom = var.firebase_admin_secret_arn },
        { name = "MODERATION_OPENAI_API_KEY", valueFrom = local.openai_secret_value_from }
      ]
    }
  ])
}

resource "aws_ecs_service" "api" {
  name            = "${local.name}-api"
  cluster         = aws_ecs_cluster.this.id
  task_definition = aws_ecs_task_definition.api.arn
  desired_count   = var.desired_count
  launch_type     = "FARGATE"

  enable_execute_command            = true
  health_check_grace_period_seconds = var.health_check_grace_period_seconds

  network_configuration {
    subnets          = var.private_subnet_ids
    security_groups  = [aws_security_group.ecs.id]
    assign_public_ip = false
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.api.arn
    container_name   = "api"
    container_port   = 8080
  }

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  depends_on = [aws_lb_listener.http_forward, aws_lb_listener.http_redirect, aws_lb_listener.https]
}

resource "aws_appautoscaling_target" "ecs" {
  max_capacity       = var.max_capacity
  min_capacity       = var.min_capacity
  resource_id        = "service/${aws_ecs_cluster.this.name}/${aws_ecs_service.api.name}"
  scalable_dimension = "ecs:service:DesiredCount"
  service_namespace  = "ecs"
}

resource "aws_appautoscaling_policy" "cpu" {
  name               = "${local.name}-cpu-scaling"
  policy_type        = "TargetTrackingScaling"
  resource_id        = aws_appautoscaling_target.ecs.resource_id
  scalable_dimension = aws_appautoscaling_target.ecs.scalable_dimension
  service_namespace  = aws_appautoscaling_target.ecs.service_namespace

  target_tracking_scaling_policy_configuration {
    predefined_metric_specification {
      predefined_metric_type = "ECSServiceAverageCPUUtilization"
    }
    target_value       = 60
    scale_in_cooldown  = 120
    scale_out_cooldown = 60
  }
}

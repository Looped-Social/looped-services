locals {
  name = "${var.name_prefix}-${var.environment}"
}

resource "aws_cloudwatch_log_group" "worker" {
  name              = "/ecs/${local.name}-notif-worker"
  retention_in_days = 30
  tags              = var.tags
}

resource "aws_security_group" "worker" {
  name        = "${local.name}-notif-worker-sg"
  description = "ECS tasks SG for notif-worker (${local.name})"
  vpc_id      = var.vpc_id

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(var.tags, { Name = "${local.name}-notif-worker-sg" })
}

resource "aws_iam_role" "execution" {
  name = "${local.name}-notif-worker-exec-role"

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
  name = "${local.name}-notif-worker-exec-secrets"
  role = aws_iam_role.execution.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["secretsmanager:GetSecretValue"]
        Resource = [var.apns_auth_key_p8_secret_arn]
      }
    ]
  })
}

resource "aws_iam_role" "task" {
  name = "${local.name}-notif-worker-task-role"

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

resource "aws_iam_role_policy" "task_sqs" {
  name = "${local.name}-notif-worker-sqs"
  role = aws_iam_role.task.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "sqs:ReceiveMessage",
          "sqs:DeleteMessage",
          "sqs:GetQueueAttributes",
          "sqs:ChangeMessageVisibility"
        ]
        Resource = [var.sqs_notif_queue_arn]
      }
    ]
  })
}

resource "aws_ecs_task_definition" "worker" {
  family                   = "${local.name}-notif-worker"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = "256"
  memory                   = "512"
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn
  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = var.cpu_architecture
  }

  container_definitions = jsonencode([
    {
      name      = "notif-worker"
      image     = var.ecr_image
      essential = true
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          awslogs-group         = aws_cloudwatch_log_group.worker.name
          awslogs-region        = var.aws_region
          awslogs-stream-prefix = "notif-worker"
        }
      }
      environment = [
        { name = "AWS_REGION", value = var.aws_region },
        { name = "SQS_NOTIF_QUEUE_URL", value = var.sqs_notif_queue_url },
        { name = "APNS_BUNDLE_ID", value = var.apns_bundle_id },
        { name = "APNS_TEAM_ID", value = var.apns_team_id },
        { name = "APNS_KEY_ID", value = var.apns_key_id },
        { name = "APNS_SANDBOX", value = tostring(var.apns_sandbox) }
      ]
      secrets = [
        { name = "APNS_AUTH_KEY_P8", valueFrom = var.apns_auth_key_p8_secret_arn }
      ]
    }
  ])
}

resource "aws_ecs_service" "worker" {
  name            = "${local.name}-notif-worker"
  cluster         = var.cluster_name
  task_definition = aws_ecs_task_definition.worker.arn
  desired_count   = var.desired_count
  launch_type     = "FARGATE"

  enable_execute_command = true

  network_configuration {
    subnets          = var.private_subnet_ids
    security_groups  = [aws_security_group.worker.id]
    assign_public_ip = false
  }

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }
}

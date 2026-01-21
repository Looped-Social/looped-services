# ECS Fargate Deploy — Looped API

This file is a concise checklist + sample task definition for deploying the API to ECS Fargate behind an ALB.

Preferred: provision AWS resources via OpenTofu in this repo (`infra/README.md`, `infra/RUNBOOK.md`). This doc is kept as a manual/legacy reference for what the IaC creates.

## Prerequisites
- AWS account with admin or appropriate IAM permissions
- VPC with public subnets (for ALB) and private subnets (for ECS tasks)
- Route 53 domain (optional) and ACM certificate (for HTTPS on ALB)
- Postgres (Neon/RDS/Aurora) reachable from ECS tasks
- Redis (ElastiCache) or compatible endpoint
- S3 bucket for media + CloudFront distribution
- CloudWatch Logs log group (e.g., `/ecs/looped-api`)

## Build & Push Image
- Create ECR repo: `aws ecr create-repository --repository-name looped-api`
- Authenticate to ECR: `aws ecr get-login-password --region <region> | docker login --username AWS --password-stdin <acct>.dkr.ecr.<region>.amazonaws.com`
- Build: `docker build -t looped-api:dev -f apps/api/Dockerfile .`
- Tag: `docker tag looped-api:dev <acct>.dkr.ecr.<region>.amazonaws.com/looped-api:<tag>`
- Push: `docker push <acct>.dkr.ecr.<region>.amazonaws.com/looped-api:<tag>`

## IAM Roles
- Task execution role (pull from ECR, write logs): attach `AmazonECSTaskExecutionRolePolicy` and a minimal CloudWatch Logs policy
- Task role (runtime):
  - `s3:PutObject` on `arn:aws:s3:::<media-bucket>/media/original/*`
  - `ssm:GetParameter`, `secretsmanager:GetSecretValue` for referenced secrets

## Secrets & Config (SSM/Secrets Manager)
Create the following parameters/secrets (names are examples):
- SSM: `/looped/auth/issuer`, `/looped/auth/audience`, `/looped/auth/jwks_uri`
- SSM: `/looped/db/url` (e.g., `jdbc:postgresql://<host>:5432/looped`)
- Secrets Manager: `looped/db/username`, `looped/db/password`, `looped/media/callback-secret`
- Plain env (in task def): `S3_BUCKET`, `AWS_REGION`, `REDIS_URL`, `CLOUDFRONT_DOMAIN`, `PORT=8080`

## Sample Task Definition (Fargate)
Save as `deploy/ecs-taskdef.sample.json` and replace placeholders.

```json
{
  "family": "looped-api",
  "networkMode": "awsvpc",
  "cpu": "512",
  "memory": "1024",
  "requiresCompatibilities": ["FARGATE"],
  "runtimePlatform": {"cpuArchitecture": "X86_64", "operatingSystemFamily": "LINUX"},
  "executionRoleArn": "arn:aws:iam::<acct>:role/ecsTaskExecutionRole",
  "taskRoleArn": "arn:aws:iam::<acct>:role/loopedApiTaskRole",
  "containerDefinitions": [
    {
      "name": "api",
      "image": "<acct>.dkr.ecr.<region>.amazonaws.com/looped-api:<tag>",
      "portMappings": [{"containerPort": 8080, "hostPort": 8080, "protocol": "tcp"}],
      "essential": true,
      "environment": [
        {"name":"PORT","value":"8080"},
        {"name":"AWS_REGION","value":"us-east-1"},
        {"name":"S3_BUCKET","value":"<media-bucket>"},
        {"name":"REDIS_URL","value":"redis://<redis-host>:6379"},
        {"name":"CLOUDFRONT_DOMAIN","value":"<cloudfront-domain>"}
      ],
      "secrets": [
        {"name":"DB_URL","valueFrom":"arn:aws:ssm:<region>:<acct>:parameter/looped/db/url"},
        {"name":"DB_USERNAME","valueFrom":"arn:aws:secretsmanager:<region>:<acct>:secret:looped/db/username"},
        {"name":"DB_PASSWORD","valueFrom":"arn:aws:secretsmanager:<region>:<acct>:secret:looped/db/password"},
        {"name":"AUTH_ISSUER","valueFrom":"arn:aws:ssm:<region>:<acct>:parameter/looped/auth/issuer"},
        {"name":"AUTH_AUDIENCE","valueFrom":"arn:aws:ssm:<region>:<acct>:parameter/looped/auth/audience"},
        {"name":"AUTH_JWKS_URI","valueFrom":"arn:aws:ssm:<region>:<acct>:parameter/looped/auth/jwks_uri"},
        {"name":"MEDIA_CALLBACK_SECRET","valueFrom":"arn:aws:secretsmanager:<region>:<acct>:secret:looped/media/callback-secret"}
      ],
      "logConfiguration": {
        "logDriver": "awslogs",
        "options": {
          "awslogs-region": "<region>",
          "awslogs-group": "/ecs/looped-api",
          "awslogs-stream-prefix": "api"
        }
      },
      "healthCheck": {
        "command": ["CMD-SHELL","wget -qO- http://127.0.0.1:8080/health || exit 1"],
        "interval": 30,
        "timeout": 5,
        "retries": 3,
        "startPeriod": 20
      }
    }
  ]
}
```

## Service + ALB
- Create target group (HTTP, port 8080), health check path `/health`, healthy threshold 2, interval 30s
- Create ALB (HTTPS -> HTTP target group), attach ACM certificate
- ECS service (Fargate):
  - Cluster: `looped`
  - Launch type: Fargate (awsvpc)
  - Desired count: 1+ (auto-scale on CPU/mem later)
  - Select private subnets; assign security group allowing inbound 8080 from ALB SG
  - Attach to target group

## Networking & Security Groups
- ALB SG: allow inbound 443/80 from the internet; outbound to ECS SG
- ECS SG: allow inbound 8080 from ALB SG; outbound to DB/Redis/S3 endpoints

## Autoscaling (later)
- Scale on ALB 5xx rate, p95 latency, CPU/Mem; set min=1

## Observability
- CloudWatch Logs: JSON logs via Logback; include `request_id` and `principal` MDC fields
- Alarms: 5xx rate, high latency, task failures, DLQ depth (if workers are used)

## Rollouts / Rollbacks
- Use new image tag per deploy; ECS service with minimum healthy percent (e.g., 100%) and maximum percent (200%) for rolling updates
- Roll back by reverting the task definition to previous revision

## Checklist
- [ ] ECR repo exists (image built and pushed)
- [ ] VPC/subnets and ALB created with ACM cert
- [ ] CloudWatch log group `/ecs/looped-api` exists
- [ ] Task execution role and task role created with required policies
- [ ] SSM parameters & Secrets Manager secrets created and populated
- [ ] S3 media bucket and CloudFront configured
- [ ] ECS cluster + service created; service linked to target group
- [ ] Security groups applied (ALB ↔ ECS ↔ DB/Redis/S3)
- [ ] Health checks passing; `/v1/me` protected; e2e smoke test OK

## Notes
- For staging, restrict ALB to specific IPs or basic auth via ALB rules
- When moving to Aurora, keep the same `DB_URL` semantics; increase pool size and add RDS Proxy if needed
- If Redis is unavailable, post idempotency degrades (can retry safely); rate limits will be lenient

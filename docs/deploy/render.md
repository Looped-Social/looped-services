# Render Deploy and Migration (ECS -> Render)

This doc summarizes Render basics and a Looped-specific plan to migrate from ECS/ALB.

## Render basics (quick reference)

- Service types
  - Web service: public HTTP(S), gets an `onrender.com` URL, supports custom domains.
  - Private service: internal-only, no public URL. Receives traffic only from other Render services in the same region.
  - Background worker: runs continuously, no inbound traffic (good for queue consumers).
  - Cron job: runs on a schedule; Render builds/pulls code each run.

- Docker-first for JVM apps
  - Render supports Docker images and Dockerfile builds.
  - For Java/Spring Boot, use Docker.

- Port binding + health checks
  - Web services must bind to `0.0.0.0`.
  - Default expected port is `10000`; you can set a custom port.
  - Use `/health` as the health check path.

- Deploys
  - Auto-deploys on Git branch updates (unless you deploy a prebuilt image).
  - Zero-downtime deploys for most services (except when using persistent disks).

- Config + secrets
  - Environment variables and secret files via the Render dashboard.
  - Environment groups let you share env vars across services.

- Filesystem
  - Ephemeral by default. Use persistent disks only if you must keep local state.

- Networking
  - Private networking for service-to-service traffic in the same region.
  - Render uses shared outbound IP ranges per region; allowlist those if you connect to IP-restricted resources.

## Looped mapping (AWS -> Render)

| AWS/ECS component | Render equivalent | Notes |
|---|---|---|
| ECS Fargate service + ALB | Render Web Service | ALB replaced by Render edge. Use `/health` checks. |
| ECS task definition | Render Docker service config | Dockerfile + env vars + optional pre-deploy command. |
| SQS workers | Render Background Worker | Poll SQS (or use Render Key Value + workers). |
| Scheduled jobs (cron) | Render Cron Job | Runs per schedule, single-run guarantee. |
| ElastiCache Redis | Render Key Value (Valkey) | Redis-compatible (Valkey 8). |
| CloudWatch logs/metrics | Render logs + external APM | Ship logs if needed. |
| IAM task roles | Access keys / external auth | Render has no IAM roles; use scoped keys. |

## Looped external dependencies

### S3 + CloudFront
- Keep as-is if media is already in S3 + CloudFront.
- On Render, provide `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, and `AWS_REGION` (scoped IAM user).
- CloudFront stays in front of S3; no Render-specific changes needed.

### SES
- SES works from Render using the AWS SDK or SMTP.
- Use IAM access keys scoped to SES.
- If you have IP allowlists, allowlist Render outbound IP ranges for your region.

### Redis / Valkey
- If Redis is in ElastiCache inside a private VPC, Render cannot reach it directly.
  - Options: move to Render Key Value, use a public Redis provider, or build a VPN/tunnel.
- If you switch to Render Key Value, update `REDIS_URL`.

### CloudWatch
- Render does not write to CloudWatch by default.
- If you need CloudWatch metrics/logs, ship them yourself or use another APM.

## Migration checklist (ECS -> Render)

1. Inventory current config
   - ECS task definition env vars and secrets.
   - ALB health check path.
   - AWS dependencies: S3, SES, CloudFront, Redis, DB.

2. Pick a Render region
   - Use the closest region to your users and other dependencies.

3. Create Render services
   - API: Web Service (Docker runtime).
   - Workers: Background Workers (Docker runtime).
   - Cron: Cron Jobs if needed.

4. Configure Docker settings
   - Dockerfile path: `apps/api/Dockerfile`.
   - Set `PORT=8080` and bind to `0.0.0.0`.
   - Set health check path to `/health`.

5. Set env vars and secrets
   - `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `AUTH_*`, `MEDIA_CALLBACK_SECRET`.
   - `S3_BUCKET`, `CLOUDFRONT_DOMAIN`, `REDIS_URL`.
   - AWS credentials for S3/SES (scoped IAM user).

6. Update network allowlists
   - If DB or Redis is IP-restricted, allowlist Render outbound IP ranges for the region.

7. Smoke tests
   - `/health` returns 200.
   - `/v1/me` returns 401 without token.
   - Media presign and upload works.
   - CloudFront serves media.

8. Cutover
   - Add custom domain in Render.
   - Update DNS to point to Render.
   - Verify TLS and traffic.

## Keep AWS progress without paying for it

If you might return to ECS later, keep the configuration but delete billable resources.

### Keep (store in repo or backups)
- ECS task definitions and service config.
- ALB + target group + listener config.
- Security groups, VPC subnets, and IAM roles.
- Any IaC (Terraform/CloudFormation) or recreate scripts.

### Decommission to stop billing
- ECS services: set desired count to 0.
- ALB + target groups: delete them (ALB bills even idle).
- NAT gateways + EIPs: delete if unused (high idle cost).
- RDS/Aurora: stop or snapshot and delete.
- ElastiCache: snapshot and delete.
- ECR: remove old images if not needed (storage cost).
- CloudWatch: check log retention (storage cost).

### Data safety
- Snapshot databases before deletion.
- Keep S3 + CloudFront only if you still serve media or need backups.

## How hard is it to link Render to the AWS pieces?

- Easy: S3 + CloudFront + SES (credentials + allowlists).
- Moderate: Redis/Valkey if currently inside an AWS VPC (may need a service move).
- Hard: VPC-only dependencies with no public access or outbound allowlisting path.

## Render docs to bookmark

- Web services: https://render.com/docs/web-services
- Docker: https://render.com/docs/docker
- Deploys: https://render.com/docs/deploys
- Health checks: https://render.com/docs/health-checks
- Env vars + secrets: https://render.com/docs/configure-environment-variables
- Background workers: https://render.com/docs/background-workers
- Cron jobs: https://render.com/docs/cronjobs
- Key Value (Valkey): https://render.com/docs/key-value
- Blueprints (IaC): https://render.com/docs/infrastructure-as-code
- Outbound IP ranges: https://render.com/docs/outbound-ip-addresses

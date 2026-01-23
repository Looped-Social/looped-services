## Backend Setup — Config and Platform Steps

This guide lists all required configuration for local, staging, and production, and the steps to obtain values from Firebase and AWS.

### Env Vars (by category)

- Core server
  - `PORT` — API port (default 8080)

- Database (Postgres)
  - `DB_URL` — JDBC URL, e.g. `jdbc:postgresql://<host>:5432/looped`
  - `DB_USERNAME`, `DB_PASSWORD`
  - Optional: `DB_POOL_SIZE`

- Redis
  - `REDIS_URL` — e.g. `redis://<host>:6379`

- Auth (Firebase)
  - `AUTH_ISSUER` — `https://securetoken.google.com/<project-id>`
  - `AUTH_AUDIENCE` — `<project-id>`
  - `AUTH_JWKS_URI` — `https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com`

- Media (AWS)
  - `AWS_REGION` — e.g. `us-east-1`
  - `S3_BUCKET` — media bucket name
  - `CLOUDFRONT_DOMAIN` — CDN domain (e.g. `dxxxxx.cloudfront.net`)
  - `MEDIA_MAX_IMAGE_SIZE`, `MEDIA_MAX_VIDEO_SIZE` (optional, e.g. `20MB`, `256MB`)
  - Backward compatible: `MEDIA_MAX_IMAGE_BYTES`, `MEDIA_MAX_VIDEO_BYTES`
  - `MEDIA_CALLBACK_SECRET` — HMAC for `/v1/media/callback`

- Verification
  - `VERIFICATION_ECHO_CODE` — `true` in dev to echo email code in response; set `false` in prod
  - `VERIFICATION_CODE_TTL_SECONDS` — email code lifetime (default 600)

- Rate Limits (optional overrides)
  - `RL_IP_WINDOW_SECONDS`, `RL_IP_MAX_REQUESTS`
  - `RL_USER_WINDOW_SECONDS`, `RL_USER_MAX_REQUESTS`

- SQS (optional, for push worker)
  - `SQS_NOTIF_QUEUE_URL`

Copy `.env.example` to `.env` for local reference; export vars in your shell. In production, use SSM/Secrets Manager.

---

## Firebase Setup (Auth)

1) Create project(s)
   - Recommended: separate Firebase projects per environment.
     - Prod: `looped-prod`
     - Staging: `looped-staging`
   - Firebase Console → “Add project” → note the Project ID for each.

2) Configure iOS app (optional client step)
   - Add an iOS app in Firebase for your bundle identifier; download `GoogleService-Info.plist` into the iOS app.

3) Set backend envs (per environment)
   - `AUTH_ISSUER = https://securetoken.google.com/<project-id>` (must match the token `iss`)
   - `AUTH_AUDIENCE = <project-id>` (must match the token `aud`)
   - `AUTH_JWKS_URI = https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com`

No secrets are needed for verification — the backend verifies JWTs via JWKS.

4) (Optional) Firebase Admin SDK (admin actions)
   - Only needed if you want the API to call Firebase Admin (e.g., delete Firebase users from server-side flows).
   - Create a service account key JSON in the matching Firebase project and provide it via:
     - `FIREBASE_ADMIN_CREDENTIALS_JSON` (recommended in ECS via Secrets Manager), or
     - `FIREBASE_ADMIN_CREDENTIALS_PATH` (local/dev).
   - If storing in Secrets Manager, store the entire service account JSON as the secret value (either as plaintext or as key/value JSON); the API expects the full JSON document.

### If deploying via OpenTofu (recommended)
- Set `auth_issuer` and `auth_audience` in:
  - `infra/envs/staging/terraform.tfvars`
  - `infra/envs/prod/terraform.tfvars`
- OpenTofu writes these to SSM parameters under `/${name_prefix}/${environment}/auth/*` and injects them into the ECS task as `AUTH_ISSUER`/`AUTH_AUDIENCE`.

---

## AWS Setup

### Recommended: OpenTofu (IaC) for staging + prod
This repo includes OpenTofu config under `infra/` that provisions the core AWS pieces:
- VPC (public+private subnets + NAT), ALB, ECS Fargate service, CloudWatch logs
- ElastiCache Valkey/Redis (TLS + at-rest encryption)
- S3 buckets (private) + CloudFront (OAC) for media
- SSM params (non-secret config) and Secrets Manager (secrets by ARN only; secret values are not stored in OpenTofu state)

Start here:
- `infra/README.md`
- `infra/RUNBOOK.md`

High-level workflow:
1) Create required Secrets Manager secrets (Firebase Admin JSON, DB creds JSON, OpenAI moderation key).
2) Create ACM cert(s) in `us-east-1` for `api-staging.mylooped.app` and `api.mylooped.app` (DNS validation via Cloudflare).
3) Bootstrap OpenTofu remote state: `cd infra/bootstrap && tofu apply ...`
4) Apply staging, then prod: `cd infra/envs/<env> && tofu init && tofu apply`
5) Point Cloudflare CNAMEs at the new ALB DNS names from `tofu output`.

### Manual AWS setup (console) (legacy)
#### 1) Postgres
Choose one:
- Neon (managed Postgres): create DB; get connection string/hostname.
- RDS/Aurora: create instance/cluster in your VPC; ensure ECS tasks can reach it.

Set:
- `DB_URL = jdbc:postgresql://<host>:5432/<db>`
- `DB_USERNAME`, `DB_PASSWORD`

#### 2) Redis (ElastiCache)
- Create a Redis cluster; allow access from ECS tasks.
- Set `REDIS_URL = redis://<host>:6379`

#### 3) S3 + CloudFront (Media)
- Create S3 bucket (e.g., `looped-media-prod`).
- Create CloudFront distribution with the bucket as origin; note the distribution domain.
- Add bucket policy to restrict content-type/path (`media/original/*`, `media/processed/*`).
- Set:
  - `AWS_REGION`
  - `S3_BUCKET`
  - `CLOUDFRONT_DOMAIN`
  - `MEDIA_CALLBACK_SECRET` (store in Secrets Manager)

#### 4) ECR + ECS Fargate + ALB
- Create ECR repo (e.g., `looped-api`).
- Create ECS cluster and a Fargate service with one container named `api` listening on 8080.
- Create task execution role (pull from ECR, write logs) and task role (runtime permissions):
  - `s3:PutObject` on `arn:aws:s3:::<bucket>/media/original/*`
  - `ssm:GetParameter` and `secretsmanager:GetSecretValue` for parameters/secrets below
- Create ALB + target group (HTTP 8080), health check path `/health`.
- CloudWatch Logs: ensure log group `/ecs/looped-api` exists.

#### 5) SSM Parameters and Secrets Manager
Recommended names (map in task definition `secrets`):
- SSM Parameters
  - `/looped/auth/issuer` → `AUTH_ISSUER`
  - `/looped/auth/audience` → `AUTH_AUDIENCE`
  - `/looped/auth/jwks_uri` → `AUTH_JWKS_URI`
  - `/looped/db/url` → `DB_URL`
  - `/looped/redis/url` → `REDIS_URL`
- Secrets Manager
  - `looped/db/username` → `DB_USERNAME`
  - `looped/db/password` → `DB_PASSWORD`
  - `looped/media/callback-secret` → `MEDIA_CALLBACK_SECRET`

Map plain envs in the task def (non-secret): `PORT=8080`, `AWS_REGION`, `S3_BUCKET`, `CLOUDFRONT_DOMAIN`. See `deploy/ecs-taskdef.sample.json` for an example.

#### 6) (Optional) SQS for notifications
- Create standard queue `notif-events` and DLQ `notif-events-dlq`.
- Set redrive policy on main queue.
- Set `SQS_NOTIF_QUEUE_URL` to the main queue URL.

---

## CI/CD (GitHub Actions)

1) Configure AWS OIDC for your GitHub org/repo (least-privilege IAM role allowing ECR push + ECS update service + SSM/Secrets read).
2) Workflow steps:
- Checkout → Docker build (from `apps/api/Dockerfile`) → ECR login → push `:GIT_SHA` tag → render task def with new image → `aws ecs update-service --force-new-deployment` → wait for stable.

For deploy checklist and sample task definition, see `docs/deploy/ecs-fargate.md` and `deploy/ecs-taskdef.sample.json`.
For Render migration notes, see `docs/deploy/render.md`.

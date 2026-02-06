# Looped OpenTofu Runbook (staging + prod)

## 0) Preconditions
- AWS account: `179388324981` (current), region: `us-east-1`
- OpenTofu installed: `tofu -version`
- Enough free disk space for provider downloads (`tofu init`): ~1GB+
- Cloudflare controls DNS for `mylooped.app`

## 1) Create required secrets (AWS Secrets Manager)
Create these *once* (per AWS account). Store **secret values** here (not in OpenTofu).

- `firebase_admin_secret_arn`: secret value = Firebase service-account JSON (single string)
- `openai_moderation_secret_arn`: secret value = OpenAI API key (single string)
- `db_credentials_secret_arn`: secret value = JSON: `{"username":"...","password":"..."}`
- (Push) `apns_auth_key_p8_secret_arn`: secret value = **base64-encoded** APNs Auth Key (`.p8`)

You can create them in the AWS Console or via CLI:

```bash
aws secretsmanager create-secret --name looped/firebase-admin --secret-string '{"type":"service_account", ... }'
aws secretsmanager create-secret --name looped/openai/moderation --secret-string 'sk-...'
aws secretsmanager create-secret --name looped/db/credentials --secret-string '{"username":"...","password":"..."}'
aws secretsmanager create-secret --name looped/staging/apns/auth-key-p8 --secret-string '<base64-of-AuthKey_XXXXXX.p8>'
aws secretsmanager create-secret --name looped/prod/apns/auth-key-p8 --secret-string '<base64-of-AuthKey_XXXXXX.p8>'
```

## 2) Create ACM certificates (for ALB HTTPS)
Create ACM certs in **us-east-1**:
- Staging: `api-staging.mylooped.app` (optional, but recommended)
- Prod: `api.mylooped.app`

Use **DNS validation** and add the validation CNAME records in Cloudflare.

## 3) Bootstrap remote state (S3 + DynamoDB)
```bash
cd infra/bootstrap
tofu init
tofu apply -var='aws_region=us-east-1' -var='state_bucket_name=looped-tofu-state-179388324981'
```

## 4) Initialize each environment backend
```bash
cd infra/envs/staging
cp backend.hcl.example backend.hcl
tofu init -backend-config=backend.hcl
```

```bash
cd infra/envs/prod
cp backend.hcl.example backend.hcl
tofu init -backend-config=backend.hcl
```

## 5) Configure environment variables (`terraform.tfvars`)
```bash
cd infra/envs/staging
cp terraform.tfvars.example terraform.tfvars
```

Fill in:
- `db_url` (no password embedded)
- `auth_issuer`, `auth_audience`, `auth_jwks_uri` (Firebase)
- `*_secret_arn` values from Secrets Manager
- `acm_certificate_arn` (if using HTTPS)
- `cors_allowed_origins` (admin/iOS dev origins; used for API CORS and browser-based direct S3 uploads)
- (Push) `enable_push_notifications=true` + `enable_notif_worker=true` + APNs config (`apns_*`)

Repeat for prod.

## 6) Apply staging, then prod
```bash
cd infra/envs/staging
tofu apply
tofu output
```

```bash
cd infra/envs/prod
tofu apply
tofu output
```

## 6.1) CI-only applies (recommended)
This repo supports applying infra via GitHub Actions using OIDC roles created by OpenTofu (per environment):
- Staging role output: `github_infra_role_arn` (from `infra/envs/staging`)
- Prod role output: `github_infra_role_arn` (from `infra/envs/prod`)

Workflows:
- `.github/workflows/infra-apply-staging.yml` (environment: `staging-infra`, main only)
- `.github/workflows/infra-apply-prod.yml` (environment: `prod-infra`, main only)
  - Note: CI uses `backend.hcl.example` (since `backend.hcl` is gitignored).

GitHub setup (repo Settings → Environments):
1) Create environment `staging-infra` (optional reviewers).
2) Create environment `prod-infra` and require approvals (recommended).

GitHub setup (repo Settings → Variables):
- `AWS_REGION` = `us-east-1`
- `AWS_ROLE_ARN_INFRA_STAGING` = the staging `github_infra_role_arn` output
- `AWS_ROLE_ARN_INFRA_PROD` = the prod `github_infra_role_arn` output

Once set, use the GitHub Actions UI to run “Infra apply (staging)” / “Infra apply (prod)”.

To reduce footguns for onboarded developers:
- Do not grant developers AWS permissions to assume the `*-gha-infra` roles.
- Remove/avoid long-lived IAM user keys; prefer AWS IAM Identity Center (SSO).
- Optionally restrict write access to the OpenTofu state bucket (`looped-tofu-state-*`) to CI + breakglass only.

## 7) Cloudflare DNS cutover
In Cloudflare DNS:
- `api-staging.mylooped.app` → CNAME → staging `alb_dns_name`
- `api.mylooped.app` → CNAME → prod `alb_dns_name`

Optional (media vanity domain):
- Request an ACM cert in **us-east-1** for `media.mylooped.app` (DNS validate via Cloudflare).
- Set `media.mylooped.app` → CNAME → `cloudfront_domain_name` (from prod outputs).
- Configure the alias + cert ARN via `media_cloudfront_aliases` / `media_cloudfront_acm_certificate_arn` in `infra/envs/prod/terraform.tfvars`.

## 8) Smoke test
Once DNS points at the new ALB:
- `GET https://api.mylooped.app/actuator/health`

## 8.1) Subscribe to alerts (recommended)
After `tofu apply`, subscribe to the `alerts_topic_arn` output for email/Slack/PagerDuty.

## 9) Update deploy pipeline wiring
Your deploy workflow should point at the new ECS cluster/service (see OpenTofu outputs):
- `ecs_cluster_name`
- `ecs_service_name`

For push notifications, there are two deploy targets:
- API: `.github/workflows/deploy-api.yml` and `.github/workflows/deploy-prod.yml`
- notif-worker:
  - staging: `.github/workflows/deploy-notif-worker.yml`
  - prod: `.github/workflows/deploy-notif-worker-prod.yml`

Recommended model:
- OpenTofu manages infra + baseline task definition (including a stable image tag, e.g. `:main` or `:prod`).
- CI deploys by pushing a new image to that tag and running `aws ecs update-service --force-new-deployment`.

## 10) Decommission old console-built infra
After a successful cutover:
- Delete old ECS service + cluster (or scale to 0 and keep briefly)
- Delete old ALB + target group + listeners
- Delete old security groups and log groups that are no longer referenced

Current console-built resources (per your AWS account) that this replaces:
- ECS cluster: `looped-cluster`
- ECS service: `looped-api-svc`
- ALB: `alb-looped-api`

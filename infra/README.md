# OpenTofu (Terraform) Infrastructure — Looped

This folder defines Looped’s AWS infrastructure using **OpenTofu** (Terraform-compatible).

Goals:
- Repeatable **staging** and **prod** environments.
- Least-privilege IAM, private subnets for compute, encryption where available.
- “Create new, validate, then cut over” migration (no importing console-created ECS/ALB state).

## Prereqs
- OpenTofu installed (`tofu -version`)
- AWS CLI access (you already have `--profile william-millen`)
- A domain in Cloudflare (DNS is managed outside AWS in this setup)
- Existing secrets created in AWS Secrets Manager (we **do not** store secret values in OpenTofu state):
  - Firebase Admin JSON (string secret)
  - DB credentials (JSON secret with `username` + `password`)
  - OpenAI moderation key (string secret)

Recommended free disk space before running `tofu init` locally: **~1GB+** (the AWS provider binary is large).

## Layout
- `bootstrap/`: creates remote state bucket + DynamoDB lock table
- `envs/staging/`: staging stack (VPC, ALB, ECS service, Redis, S3+CloudFront, SSM params)
- `envs/prod/`: prod stack (same as staging, separate resources)
- `modules/`: reusable building blocks

Each environment is designed to be **independent** (separate VPC, ALB, Redis, buckets). This costs more (NAT gateways) but is the simplest way to avoid “staging accidentally impacts prod”.

For a step-by-step checklist, see `infra/RUNBOOK.md`.

## 1) Bootstrap remote state
Pick a globally-unique bucket name (example uses your AWS account id).

```bash
cd infra/bootstrap
tofu init
tofu apply -var='aws_region=us-east-1' -var='state_bucket_name=looped-tofu-state-179388324981'
```

This creates:
- S3 bucket for state
- DynamoDB table for state locking

## 2) Configure backend for an environment
Backends don’t accept normal variables; use a `backend.hcl` file.

For staging:

```bash
cd infra/envs/staging
cp backend.hcl.example backend.hcl
$EDITOR backend.hcl
tofu init -backend-config=backend.hcl
```

For prod:

```bash
cd infra/envs/prod
cp backend.hcl.example backend.hcl
$EDITOR backend.hcl
tofu init -backend-config=backend.hcl
```

## 3) Configure per-environment variables
Create `terraform.tfvars` from the example:

```bash
cd infra/envs/staging
cp terraform.tfvars.example terraform.tfvars
$EDITOR terraform.tfvars
```

Do the same for prod.

Important:
- **Do not** put secret values in tfvars (they will land in state). Use **secret ARNs** only.

### Secrets Manager: expected shapes
- `firebase_admin_secret_arn`: secret value is the full Firebase service-account JSON (stored as a single string).
- `openai_moderation_secret_arn`: secret value is the OpenAI API key (stored as a single string).
- `db_credentials_secret_arn`: secret value is JSON:
  - `{"username":"<db_user>","password":"<db_pass>"}`

## 4) Apply
```bash
cd infra/envs/staging
tofu apply
```

```bash
cd infra/envs/prod
tofu apply
```

## 5) Cloudflare cutover
After apply, OpenTofu outputs:
- `alb_dns_name`
- `cloudfront_domain_name`

In Cloudflare DNS:
- Create `api-staging.mylooped.app` → **CNAME** → staging `alb_dns_name`
- Create `api.mylooped.app` → **CNAME** → prod `alb_dns_name`

If you want a custom media domain later (optional):
- Create `media.mylooped.app` → **CNAME** → `cloudfront_domain_name`

## 6) GitHub Actions deploy wiring
Your current workflow (`.github/workflows/deploy-api.yml`) deploys by pushing images to ECR and calling `aws ecs update-service --force-new-deployment`.

After switching to the new infra, update GitHub repo variables:
- `ECS_CLUSTER`
- `ECS_SERVICE`
- `ECR_REGISTRY`
- `ECR_REPO`
- `AWS_REGION`
- `AWS_ROLE_ARN` (OIDC assume-role to deploy)

This repo now uses two deploy targets:
- Staging: `ECS_CLUSTER_STAGING`, `ECS_SERVICE_STAGING` (auto-deploy on push to `main`)
- Prod: `ECS_CLUSTER_PROD`, `ECS_SERVICE_PROD` (manual deploy; promotes selected ref to `:prod`)

### Recommended deploy model (avoids OpenTofu vs CI “drift”)
Treat OpenTofu as the source of truth for **infrastructure**, and GitHub Actions as the source of truth for **application deploys**.

Concretely:
- OpenTofu config sets the ECS task definition image tag to a **stable** tag (e.g. `:main` for staging, `:prod` for production).
- CI pushes a new image to that stable tag and then runs `aws ecs update-service --force-new-deployment` so new tasks pull the latest image for that tag.

This avoids needing to run `tofu apply` on every deploy, and avoids console edits (console changes will drift and get reverted by OpenTofu).

### Staging → Prod promotion (manual approval)
Typical simple pattern:
- **Staging (auto)**: on push to `main`, build+push `:<sha>` and `:main`, then force new deployment on the staging ECS service.
- **Prod (manual)**: on workflow dispatch/release, promote a specific `:<sha>` by tagging it as `:prod`, then force new deployment on the prod ECS service.

If you want, we can add a separate prod deploy workflow that:
- requires manual approval (GitHub Environments), and
- takes an input `sha` to promote.

Recommended:
- Staging deploy on merge to `main`
- Prod deploy on tag/release with a manual approval gate

## Migration strategy (from your current console ECS/ALB)
Because the app isn’t launched yet, simplest is:
1) Apply **staging** and validate end-to-end.
2) Apply **prod** and validate end-to-end.
3) Point Cloudflare `api.mylooped.app` to the new prod ALB DNS name.
4) Update GitHub workflow vars to deploy to the new ECS service.
5) After you’re confident, delete old resources (or keep them temporarily for rollback).

If you want a safer rollback during early testing: keep old ALB in DNS history and just flip the CNAME back.

## Validating the config (recommended)
After `tofu init` completes:

```bash
cd infra/envs/staging
tofu validate
```

```bash
cd infra/envs/prod
tofu validate
```

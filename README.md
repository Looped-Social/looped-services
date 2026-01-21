# Looped Services — Backend Modular Monolith

## Summary
- Backend for the Looped iOS app. Modular monolith with one API and optional workers. Simple now, scales later.
- Stack: Java 25, Spring Boot 3.5.6, Maven 3.9+; Postgres (Neon → Aurora), Redis, S3 + CloudFront, Firebase Auth (JWT/JWKS), optional SQS workers.

## Repo layout
- `apps/api` — Spring Boot REST API (controllers + business logic)
- `workers/notif-worker` — SQS → APNs worker (optional for MVP)
- `workers/feed-worker` — placeholder (later)
- `workers/mod-worker` — placeholder (later)
- `docs/ARCHITECTURE.md` — Architecture Context (authoritative)
- `infra/` — AWS infrastructure via OpenTofu (staging + prod)
- `AGENTS.md` — Agents, responsibilities, interfaces, guardrails
- `config/logging.json` — structured logging template (placeholder)
- `.env.example` — environment variables example for local dev

## Versions & core dependencies
- Java: `25`
- Spring Boot: `3.5.6` (web, validation, actuator)
- Build: Maven (root parent/aggregator)
- Data: Postgres, Redis (ElastiCache later)
- Cloud: S3 + CloudFront, Firebase Auth (JWT / JWKS), SQS (optional)

## Prerequisites
- Install `JDK 25`
- Optional: install `Maven 3.9+` (or use the Maven Wrapper `./mvnw`)
- Optional (local): Postgres and Redis; or point to dev instances via `.env`

## Configuration
- Copy `.env.example` to `.env` and adjust values
- Defaults in `apps/api/src/main/resources/application.yaml` are sane for local
- Health endpoint does not require DB/Redis
 - Auth (Firebase): set `AUTH_ISSUER`, `AUTH_AUDIENCE`, `AUTH_JWKS_URI` or use the Firebase defaults in `application.yaml`. iOS should send `Authorization: Bearer <Firebase ID token>`.

## Quick start
- Start dependencies
  - Option A — Docker Compose (recommended):
    - `docker compose up -d` (uses `docker-compose.yml`)
  - Option B — individual containers:
    - Postgres: `docker run --rm --name looped-pg -e POSTGRES_DB=looped -e POSTGRES_USER=looped -e POSTGRES_PASSWORD=looped -p 5432:5432 -d postgres:16`
    - Redis: `docker run --rm --name looped-redis -p 6379:6379 -d redis:7-alpine`
- Run API (dev): `./mvnw -q -pl apps/api -am spring-boot:run`
  - Alternative (module-only): `./mvnw -q -f apps/api/pom.xml spring-boot:run`
- Build all modules: `./mvnw -q -T 1C -DskipTests package`
- Run tests (requires Docker for Testcontainers): `./mvnw -q -T 1C test`
- API URL: `http://localhost:8080` — health: `GET /health` → `ok`

### Local dev (minimal)
- `cp .env.example .env`
- `docker compose up -d`
- `set -a; source .env; set +a`
- `./mvnw -q -pl apps/api -am spring-boot:run`

### Makefile shortcuts
- Start deps: `make up`
- Start only Postgres: `make database` (aliases: `make db`, `make datbase`)
- Dev run: `make dev`
- Tests: `make test` (Docker required)
- Build: `make build`
- Run JAR: `make jar` (after build)
- Logs: `make logs`
- Stop deps: `make down`

## Maven run notes
- Prefer the Maven Wrapper (`./mvnw`) to get the pinned Maven (3.9.11) automatically; only Java is required.
- The root POM is an aggregator/parent without an application entrypoint, so it sets `spring-boot.run.skip=true`.
- The API module overrides this with `spring-boot.run.skip=false`, letting `spring-boot:run` work when you target `apps/api`.

## Port and environment
- Change port: `PORT=9090 ./mvnw -q -pl apps/api -am spring-boot:run` or `PORT=9090 java -jar apps/api/target/looped-api-0.0.1-SNAPSHOT.jar`.
- The app reads env vars from your shell; `.env` is for local conventions only (not auto‑loaded by Maven).

## Install artifacts locally (optional)
- `./mvnw -q -T 1C -DskipTests install` (installs module jars into your local Maven cache)

## Common tasks
- Package API JAR: `./mvnw -q -pl apps/api -am package`
- Run packaged JAR: `java -jar apps/api/target/looped-api-0.0.1-SNAPSHOT.jar`
- Clean: `mvn -q clean`

## Services (MVP → Later)
- API (ECS Fargate behind ALB): REST endpoints, business logic, data access, media presign, moderation intake
- notif-worker (optional): consumes SQS, sends APNs; retries + DLQ
- feed-worker (later): write-time fan-out for feed
- mod-worker (later): automated moderation

## Links
- AGENTS.md — runtime diagram, agents, interfaces, scaling, guardrails
- docs/ARCHITECTURE.md — high-level context and rationale

## Notes
- Privacy first: verify JWTs every request, no PII in logs, enforce idempotency and media guardrails
- Realtime: polling/SSE now; WebSockets later for chat/presence

## Build + Run (Local)
- Prereqs
  - JDK 25
  - Postgres running locally (Homebrew or Docker)
  - Redis optional for local (required for post idempotency and rate limits)
  - Docker optional for running tests (Testcontainers)
- Compile only (skip tests)
  - `./mvnw -q -pl apps/api -am -DskipTests package`
- Run tests (requires Docker for Testcontainers)
  - `./mvnw -q -pl apps/api -am test`
- Start API (dev)
  - Ensure DB envs match your local Postgres (defaults in `.env.example`)
  - Start Postgres & Redis via Docker (see Quick start) or point to your own instances
  - `./mvnw -q -pl apps/api -am spring-boot:run`
- Quick sanity check
  - Health: `curl http://localhost:8080/health` → `ok`
  - Protected route: `curl -i http://localhost:8080/v1/me` → `401` without a token
  - With a Firebase ID token: `curl -H "Authorization: Bearer <ID_TOKEN>" http://localhost:8080/v1/me`

## Verification Checklist
- Dependencies up
  - If using compose: `docker compose ps` shows both services healthy
  - Postgres running: `psql -h localhost -U looped -d looped -c "select 1"` (password `looped`)
  - Redis running: `redis-cli -h localhost -p 6379 ping` → `PONG`
- Dev server
  - Start: `./mvnw -q -pl apps/api -am spring-boot:run`
  - Health: `curl http://localhost:8080/health` → `ok`
  - Auth sanity: `curl -i http://localhost:8080/v1/me` → `401`
- Tests
  - Run: `./mvnw -q -T 1C test` (Docker required for Testcontainers Postgres/Redis)
  - Expect all tests to pass; failures usually indicate missing Docker or low resources
- Build artifacts
  - Build (skip tests): `./mvnw -q -pl apps/api -am -DskipTests package`
  - JAR present: `ls apps/api/target/looped-api-0.0.1-SNAPSHOT.jar`
  - Run JAR: `java -jar apps/api/target/looped-api-0.0.1-SNAPSHOT.jar` and repeat health check

Notes
- To run without DB for a quick boot (health only):
  - `./mvnw -q -pl apps/api -am -Dspring.flyway.enabled=false -Dspring.datasource.hikari.initialization-fail-timeout=-1 spring-boot:run`
  - Endpoints that touch the DB/Redis still require those services running.
- Stop dependencies
  - Compose: `docker compose down`
  - Individual containers: `docker rm -f looped-pg looped-redis`

## API — Verification

- Start verification
  - `POST /v1/verification/start` with JSON `{ "method": "email" | "video" | "thirdparty" }`
  - email: server generates a 6‑digit code and stores it in Redis with TTL. In dev, the response includes `dev_code` when `verification.echo-code=true`.
  - video: response includes `instructions`. Upload a short video via `/v1/media/presign` (content-type `video/mp4`) and then call `finish` with `mediaKey`.
  - thirdparty: response includes `session_id` (stored in Redis). Complete provider flow client‑side, then call `finish` with `token`.

- Complete verification
  - `POST /v1/verification/finish`
    - email: `{ "method":"email", "code":"123456" }`
    - video: `{ "method":"video", "mediaKey":"media/original/<uuid>" }`
    - thirdparty: `{ "method":"thirdparty", "token":"provider-token" }`
  - Response: `{ "verified": true }` on success; `403` with `{ "error":"invalid_code" }` for bad email codes.

- Me endpoint includes verification block when present
  - `GET /v1/me` →
    - `{"provisioned": true, "user": { "id": ..., "handle": "...", "company_id": ..., "verification": { "method": "email|video|thirdparty", "verified": true|false, "verified_at": "..." }}}`

- Config (application.yaml)
  - `verification.echo-code` (default `true` in dev). Set `VERIFICATION_ECHO_CODE=false` in prod so codes aren’t echoed.
  - `verification.code-ttl-seconds` (default `600`).

## API — Feed Filter Pills

- Followed communities for feed filters
  - `GET /v1/me/followed/communities?limit=50&cursor=&order=relevant`
  - `order` options: `relevant` (default; pinned + manual order + recent activity) or `recent`
  - Response: `{ items: [ { id, name, kind, member_count, is_pinned, sort_order, can_post } ], next_cursor? }`
  - `next_cursor` should be passed back as-is for pagination.

## API — Feed Modes (For You vs New)

- Feed list
  - `GET /v1/feed?mode=for_you|new|following&communityId=...&limit=20&cursor=...`
  - `mode=for_you` (default): popularity-ranked posts (global or within `communityId`).
  - `mode=new`: newest posts by `created_at` (global or within `communityId`).
  - `communityId` is optional; when omitted it fetches all posts.
  - `cursor` is opaque; pass back `next_cursor` from the response.

## API — Admin Dashboard

- Admin endpoints are documented in `docs/admin-api.md`.
- All `/v1/admin/*` routes require a Firebase ID token.

## Docker (build and run)
- Build image (API only): `docker build -t looped-api:dev -f apps/api/Dockerfile .`
- Run locally:
  - `docker run --rm -p 8080:8080 \
      -e PORT=8080 \
      -e DB_URL=jdbc:postgresql://host.docker.internal:5432/looped \
      -e DB_USERNAME=looped -e DB_PASSWORD=looped \
      -e AUTH_ISSUER=https://securetoken.google.com/<project-id> \
      -e AUTH_AUDIENCE=<project-id> \
      -e AUTH_JWKS_URI=https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com \
      -e REDIS_URL=redis://host.docker.internal:6379 \
      -e S3_BUCKET=looped-dev-media -e AWS_REGION=us-east-1 \
      looped-api:dev`
- Healthcheck: container exposes `GET /health` on port `$PORT`.

## Local vs Production
- Config source
  - Local: env vars (e.g., copy `.env.example` and export in your shell)
  - Prod: SSM Parameter Store / Secrets Manager mapped into task env vars
- Identity (Firebase Auth)
  - Same verification path; set `AUTH_ISSUER`, `AUTH_AUDIENCE`, `AUTH_JWKS_URI`
- Data stores
  - Local: Postgres (localhost), optional Redis (localhost)
  - Prod: Neon/Aurora Postgres, ElastiCache Redis
- Media
  - Local: presign requires AWS creds in your shell; writes to your dev S3 bucket
  - Prod: task IAM role signs S3 requests; CloudFront domain configured
- Networking
  - Local: direct HTTP on `localhost:8080`
  - Prod: ALB (HTTPS) → ECS task (HTTP 8080); health checks on `/health`
- Observability
  - Local: JSON logs to console; X-Request-Id header echoed per request
  - Prod: logs to CloudWatch; set alarms on 5xx/latency; rate limits enforced via Redis

## Deploy (ECS Fargate quick notes)
- Preferred: use OpenTofu in `infra/` to provision AWS resources (VPC, ALB, ECS service, Redis, S3+CloudFront, SSM params). Start with `infra/README.md` and `infra/RUNBOOK.md`.
- Build and push to ECR: tag `looped-api:<sha>`; set repository in ECR.
- Task definition env vars: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` (or use Secrets Manager), `AUTH_*`, `REDIS_URL`, `S3_BUCKET`, `AWS_REGION`, `CLOUDFRONT_DOMAIN`, `MEDIA_*`, `S3_MESSAGING_BUCKET` (private DM/channel attachments).
- ALB target group health check: path `/health`, interval 30s.
- IAM task role: allow S3 PutObject for the media bucket (prefix `media/original/*`).
- Secrets: prefer SSM/Secrets Manager and reference via task definition; app reads from env.

For a full checklist and sample task definition, see: `docs/deploy/ecs-fargate.md` and `deploy/ecs-taskdef.sample.json`.

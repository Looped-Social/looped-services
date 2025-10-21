# Looped Services — Agents & Runtime Guide

Looped is an iOS‑first, workplace‑verified social app. The backend is a modular monolith built with Java 25 and Spring Boot 3.5.6, deployed on ECS Fargate behind an ALB. We use Neon Postgres (Aurora later), ElastiCache Redis, S3 + CloudFront for media, Firebase Auth for authentication (JWT/JWKS), and optional SQS workers for push notifications (APNs). Start with simple polling/SSE for realtime; add WebSockets later. Privacy is a first‑class constraint: verify JWTs on every request, avoid PII in logs, and enforce idempotency and media guardrails. See docs/ARCHITECTURE.md for the authoritative Architecture Context.

## System Diagram

```
iOS (Swift/SwiftUI)
   |
   v
[Amazon ALB]
   |
   v
[ECS Fargate: Spring Boot API]
   | \     \        \            \             \
   |  \     \        \            \             \
   |   v     v        v            v             v
   | [Postgres]  [Redis]  [S3 (media)]   [Firebase JWKS]   [SQS notif-events]
   |    |                    |                   |               |
   |    |                    |                   |               v
   |    |                    |                (JWT verify)   [notif-worker] -> APNs
   |    |                    |
   |    |                [CloudFront CDN]
   |    |____________________^
   |
[CloudWatch Logs/Metrics/Alarms], [X-Ray/Tracing (optional)]
[Secrets Manager / SSM Parameters (configuration & secrets)]
```

## Agents & Responsibilities

- iOS client (SwiftUI)
  - Presents feed, posts, reactions, verification flows; uploads media via server‑issued presigned URLs; registers device tokens for push; polls/SSE for updates (WebSockets later).
- API (Spring Boot on ECS Fargate behind ALB)
  - Owns REST endpoints, authentication/authorization, business logic, persistence, caching/rate limits, media presign + callbacks, moderation intake; enqueues push events to SQS (optional).
- notif-worker (SQS → APNs) [MVP optional]
  - Consumes SQS messages from API and delivers push notifications to APNs with retries and DLQ.
- feed-worker [Later]
  - Event-driven fan‑out and denormalized feed writes for future scale/perf.
- mod-worker [Later]
  - Automated moderation checks and triage; updates report status and triggers actions.
- Data stores
  - Postgres (Neon now, Aurora later), Redis (cache/rate limits/idempotency), S3 (+ CloudFront) for media.
- Auth
  - Firebase Auth (JWTs, JWKS verification).
- Queueing
  - SQS (+ DLQ) for async jobs (push now; feed/mod later).
- Observability
  - CloudWatch logs/metrics/alarms; structured JSON logging; request tracing.
- Secrets
  - AWS Secrets Manager/SSM Parameter Store for prod/stage; local .env for dev.

## Interfaces per Agent (Inputs/Outputs)

### iOS Client
- Consumes HTTP (API)
  - Auth: POST /v1/auth/login, GET /v1/me
  - Verification: POST /v1/verification/start, POST /v1/verification/finish
  - Feed & Posts: GET /v1/feed?cursor=, POST /v1/posts, GET /v1/posts/{id}, POST /v1/posts/{id}/react
  - Media: POST /v1/media/presign, POST /v1/media/callback
  - Moderation: POST /v1/reports, GET /v1/reports, PUT /v1/reports/{id}/resolve
  - Devices: POST /v1/devices
- Produces HTTP (API)
  - Sends Idempotency-Key for POST /v1/posts and POST /v1/devices
  - Sends APNs device token to POST /v1/devices
  - Sends OAuth/JWT token in Authorization header (Firebase)
- S3/CloudFront
  - Uploads media to S3 via presigned PUT/POST (keys issued by API)
  - Reads media via CloudFront path /media/*
- Realtime
  - Polling/SSE for updates initially; WebSockets later (no client changes until introduced)

### API (Spring Boot)
- Owns HTTP Endpoints
  - Full “API Surface (MVP)” in Appendix A
- Consumes External
  - Firebase JWKS (JWT verification)
  - S3 (presigned upload; media callbacks)
  - SQS notif-events (producer) [optional in MVP]
- Data access (Postgres)
  - Tables: users, companies, verifications, posts, reactions, media_assets, reports, devices
- Redis (ElastiCache)
  - Caching: post:{id}, feed:{user_id}:{cursor}
  - Rate limits: rl:ip:{ip}, rl:user:{user_id}
  - Idempotency: idem:posts:{key}, idem:devices:{key} (TTL)
- S3 keys/prefixes
  - media/original/{uuid}
  - media/processed/{uuid}/{variant}
- CloudFront paths
  - /media/* (public reads via CDN)
- SQS messages (produced)
  - Queue: notif-events (DLQ: notif-events-dlq)
  - Shape:
    {
      "type": "push.post_created",
      "user_id": 123,
      "apns_token": "string",
      "title": "New post",
      "body": "Alice posted…",
      "deeplink": "looped://posts/{id}",
      "collapse_id": "post-{id}",
      "trace_id": "uuid"
    }

### notif-worker (SQS → APNs) [Optional MVP]
- Input
  - SQS: notif-events (JSON as above)
- Output
  - APNs push
  - Logs/metrics to CloudWatch (success, failures, retries)
- Behavior
  - Retries with exponential backoff (SQS redrive to DLQ after N attempts)
  - Idempotency via SQS messageId or dedupe key when present
  - Partial failures: log + NACK to requeue

### feed-worker [Later]
- Input
  - SQS: feed-events
- Shape (example):
    { "type": "feed.fanout", "post_id": 123, "company_id": 5, "fanout_user_ids": [1,2,3], "trace_id": "uuid" }
- Output
  - Writes denormalized read model (e.g., feed_entries) [not in MVP schema]
- Notes
  - Backpressure-friendly, batch writes, idempotent by (post_id, user_id)

### mod-worker [Later]
- Input
  - SQS: mod-events
- Shape (example):
    { "type": "moderation.review", "report_id": 42, "target_type": "post", "target_id": 123, "trace_id": "uuid" }
- Output
  - Updates reports.status; optional notifications
- Notes
  - External integrations (e.g., content analysis) pluggable later

### Data Stores
- Postgres (Neon → Aurora later)
  - See Appendix B for schema and constraints
- Redis
  - Caching, rate limits, idempotency keys with TTL; no source of truth
- S3 + CloudFront
  - Buckets: looped-media (example)
  - Keys: media/original/*, media/processed/*; callbacks validated via HMAC or presigned policy
  - CDN path: /media/* (immutable URLs; cache-control set)
- Auth: Firebase
  - Verify JWT via JWKS; map claims (iss+sub → external identity; email optional)
- Observability
  - CloudWatch Logs (JSON) with request_id, user_id (when present), trace_id
  - Metrics/Alarms on 5xx, latency, SQS age, DLQ depth
- Secrets
  - Secrets Manager/SSM for DB creds, JWT audience/issuer, APNs keys, S3 bucket names

## Runtime & Scaling Notes

- API (ECS Fargate)
  - Java 25, Spring Boot 3.5.6; JVM tuned for low GC pauses
  - Min 1 task, scale on ALB 5xx rate, p95 latency, CPU/Mem
  - Graceful shutdown with preStop delay; ALB health checks on GET /health
  - DB pool sizing matches Postgres max connections; prefer RDS Proxy when moving to Aurora
- Workers (SQS)
  - One or more notif-worker tasks; scale on queue depth/age
  - Max receive attempts → DLQ; alert on DLQ depth
- Retries & Backoff
  - HTTP client calls (S3/JWKS provider) use exponential backoff + jitter
  - SQS handles retry; API avoids retrying on non‑retryable codes
- Failure Modes
  - Redis down: degrade gracefully (disable cached paths, keep rate limits conservative)
  - S3/CloudFront issues: surface retriable errors to client
  - JWKS provider unavailable: 503 for auth‑required endpoints (or rely on cached keys)
  - Postgres degraded: switch to read‑only where possible; protect with circuit breakers

## Security & Privacy Guardrails

- Verify Firebase JWT on every request (JWKS); enforce audience/issuer; require TLS
- No PII in logs; use structured JSON with request_id/trace_id; redact tokens/headers
- Rate-limit by IP and user in Redis; defensive defaults on failure
- Idempotency-Key required for POST /v1/posts and POST /v1/devices (store in Redis TTL)
- Media guardrails at presign: content-type allowlist, max size, image/video constraints; bucket policy enforces content-type/path
- Principle of least privilege IAM; tasks assume roles; Secrets never in repo
- Dev/staging seed data only; never use real credentials; hash any stored secrets
- Enforce row ownership checks (company_id scoping) on reads/writes

## Realtime Approach

- Now: client polls GET /v1/feed?cursor=… with short TTL caching; optional SSE endpoint for lightweight pushes (non‑critical)
- Later: WebSockets for realtime chat/presence and low‑latency updates; keep API stateless to scale horizontally

## Environments & Secrets

- Local Dev
  - `.env` loaded by Spring profiles; sample in looped-services/.env.example
  - Run Postgres/Redis locally or via containers; use dev S3 bucket
 - Config from Secrets Manager / SSM; access via IAM role on tasks
 - Firebase project + JWKS URL per environment
  - Minimize mutable state; infra via looped-infra (Terraform/CDK optional)

## Developer Quickstart

- Prerequisites
  - JDK 25 installed (Maven optional; use `./mvnw`)
- Dev run (API)
  - `./mvnw -q -pl apps/api -am spring-boot:run`
  - Alternative: `./mvnw -q -f apps/api/pom.xml spring-boot:run`
  - Health: `GET http://localhost:8080/health` → `ok`
- Build & test
  - Build all: `./mvnw -q -T 1C -DskipTests package`
  - Run tests: `./mvnw -q -T 1C test`
  - Package API: `./mvnw -q -pl apps/api -am package`
  - Run JAR: `java -jar apps/api/target/looped-api-0.0.1-SNAPSHOT.jar`
- Port & env
  - Change port: `PORT=9090 ./mvnw -q -pl apps/api -am spring-boot:run`
  - `.env` is for local conventions; production uses Secrets Manager/SSM
- Maven run note
  - Use the Maven Wrapper (`./mvnw`) to download the pinned Maven (3.9.11) on first run.
  - Root POM (aggregator/parent) sets `spring-boot.run.skip=true`. API overrides to allow `spring-boot:run` when targeting `apps/api`.

## Roadmap (MVP → Later) and Ownership

- MVP
  - Auth + JWT verification (Firebase)
  - Verification flows (start/finish)
  - Feed read API + Post create/get/react with idempotency
  - Media presign + callback + CDN delivery
  - Moderation submission + basic list/resolve
  - Device registration (idempotent)
  - Optional: notif-worker + push on post created
- Later
  - Feed fan‑out (feed-worker), read model
  - WebSockets for realtime chat/presence
  - Automated moderation (mod-worker) + heuristics
  - Migrate Neon → Aurora with Proxy
  - Advanced rate limits + abuse prevention
- Ownership Checklist
  - API: apps/api (controllers, services, repos, web/, shared/)
  - Workers: workers/* (notif-worker now; others later)
  - Infra placeholders: looped-infra/*
  - Docs: looped-services/docs/*
  - Observability: logging.json, CloudWatch dashboards/alarms
  - Data: schema changes reviewed, migrations versioned

---

### Appendix A — API Surface (MVP)

- Auth
  - POST /v1/auth/login
  - GET /v1/me
- Verification
  - POST /v1/verification/start
  - POST /v1/verification/finish
- Feed & Posts
  - GET /v1/feed?cursor=
  - POST /v1/posts
  - GET /v1/posts/{id}
  - POST /v1/posts/{id}/react
- Media
  - POST /v1/media/presign
  - POST /v1/media/callback
- Moderation
  - POST /v1/reports
  - GET /v1/reports
  - PUT /v1/reports/{id}/resolve
- Devices
  - POST /v1/devices

### Appendix B — Starter Schema (concise DDL)

```sql
-- companies
CREATE TABLE companies (
  id            BIGSERIAL PRIMARY KEY,
  name          TEXT NOT NULL,
  domain        TEXT NOT NULL UNIQUE,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- users
CREATE TABLE users (
  id            BIGSERIAL PRIMARY KEY,
  firebase_uid  TEXT NOT NULL UNIQUE,
  handle        TEXT NOT NULL UNIQUE,
  company_id    BIGINT NOT NULL REFERENCES companies(id) ON DELETE RESTRICT,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- verifications (one row per user)
CREATE TABLE verifications (
  user_id       BIGINT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  method        TEXT NOT NULL,             -- e.g., "linkedin", "email", "hr", "manual"
  verified      BOOLEAN NOT NULL DEFAULT false,
  verified_at   TIMESTAMPTZ
);

-- media_assets
CREATE TABLE media_assets (
  id               BIGSERIAL PRIMARY KEY,
  owner_id         BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  s3_key           TEXT NOT NULL UNIQUE,
  mime_type        TEXT NOT NULL,
  width            INT,
  height           INT,
  duration_seconds INT,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- posts
CREATE TABLE posts (
  id               BIGSERIAL PRIMARY KEY,
  author_id        BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  company_id       BIGINT NOT NULL REFERENCES companies(id) ON DELETE RESTRICT,
  content          TEXT NOT NULL,
  media_asset_id   BIGINT REFERENCES media_assets(id) ON DELETE SET NULL,
  reactions_count  INT NOT NULL DEFAULT 0,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_posts_company_created_at_desc ON posts(company_id, created_at DESC);

-- reactions
CREATE TABLE reactions (
  id         BIGSERIAL PRIMARY KEY,
  user_id    BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  post_id    BIGINT NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
  type       TEXT NOT NULL,                -- e.g., "like", "clap"
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (user_id, post_id)
);

-- reports
CREATE TABLE reports (
  id           BIGSERIAL PRIMARY KEY,
  target_type  TEXT NOT NULL,              -- e.g., "post", "user", "comment"
  target_id    BIGINT NOT NULL,
  reporter_id  BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  reason       TEXT NOT NULL,
  status       TEXT NOT NULL DEFAULT 'open',  -- "open","resolved","dismissed"
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- devices (idempotent by apns_token)
CREATE TABLE devices (
  id          BIGSERIAL PRIMARY KEY,
  user_id     BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  apns_token  TEXT NOT NULL UNIQUE,
  platform    TEXT NOT NULL,                -- "ios"
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

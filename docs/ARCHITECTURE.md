See also: AGENTS.md for runtime responsibilities and interfaces.

---
Looped Architecture — High-Level Overview (MVP → Later)

Goal: ship an iOS-first, workplace-verified social app quickly, on a stack that’s simple now and ready to grow.

TL;DR
- Client: iOS (Swift/SwiftUI).
- Backend: One Java Spring Boot API (controllers + business logic in one process).
- Infra: ALB → ECS Fargate (Dockerized API), Neon Postgres, S3 + CloudFront, Firebase Auth, ElastiCache Redis, optional SQS + worker for push.
- Realtime: start with polling (or SSE); add WebSockets later for chat/presence.
- Privacy: store only verification facts; no PII in logs; JWT verified on every request.

System (MVP)
iOS → ALB → ECS Fargate (Spring Boot API)
  ├─ Postgres (Neon now; Aurora later)
  ├─ Redis (ElastiCache) for cache/rate limits
  ├─ S3 + CloudFront for media via presigned URLs
  ├─ Firebase Auth (JWTs; verify via JWKS)
  └─ SQS (optional) → notif-worker → APNs

Repos
looped-iOS/ (SwiftUI app, MVVM)
looped-web/ (marketing now; web app later)
looped-services/ (backend modular monolith + workers)
looped-infra/ (optional Terraform/CDK)

Inside looped-services/
apps/api/ (Spring Boot REST)
  com/looped/{auth,users,verification,posts,feed,media,moderation,shared}
workers/
  notif-worker/ (SQS→APNs)
  feed-worker/ (later)
  mod-worker/ (later)
docs/

Minimal Endpoints (MVP)
Auth: POST /v1/auth/login, GET /v1/me
Verification: POST /v1/verification/start, POST /v1/verification/finish
Feed & Posts: GET /v1/feed?cursor=, POST /v1/posts, GET /v1/posts/{id}, POST /v1/posts/{id}/like
Media: POST /v1/media/presign, POST /v1/media/callback
Moderation: POST /v1/reports, GET /v1/reports, PUT /v1/reports/{id}/resolve
Devices: POST /v1/devices

Verification flows (details)
- Methods: `email`, `video`, `thirdparty`. Persist status in `verifications` table (`user_id` PK) with `method`, `verified`, `verified_at`.
- Email
  - Start: API generates a 6‑digit code and stores it in Redis (TTL). In dev, response includes `dev_code` when `verification.echo-code=true`.
  - Finish: API validates `code` from Redis and marks verified on success.
- Video
  - Start: API returns instructions; client uploads short video via `/v1/media/presign` (content-type `video/mp4`).
  - Finish: API accepts `mediaKey` and marks verified (manual review assumed for MVP; mod-worker later).
- Third‑party
  - Start: API issues a `session_id` (Redis TTL). Client completes external flow.
  - Finish: API calls pluggable `ThirdPartyVerifier` to validate a `token` for the `session_id`, then marks verified.
- Me response
  - `GET /v1/me` includes `user.verification = { method, verified, verified_at }` when present.

Starter Schema (Postgres)
users(id, firebase_uid, handle, company_id, created_at)
companies(id, name, domain, created_at)
verifications(user_id, method, verified, verified_at) PK(user_id)
posts(id, author_id, company_id, content, media_asset_id, likes_count, created_at) + index (company_id, created_at desc)
likes(id, user_id, post_id, created_at) unique (user_id, post_id)
media_assets(id, owner_id, s3_key, mime_type, width, height, duration_seconds, created_at)
reports(id, target_type, target_id, reporter_id, reason, status, created_at, updated_at)
devices(id, user_id, apns_token, platform, created_at)

Security & Privacy
- Verify Firebase JWT every request (JWKS)
- No PII in logs; structured JSON with request_id
- Rate-limit by IP/user in Redis
- Idempotency-Key for POST /v1/posts
- Media guardrails at presign (content-type, size) + bucket policy
- Email verification code echoing only in dev; set `verification.echo-code=false` in prod. Never log codes or tokens.
- Seed data only in dev/staging; hash passwords even for fakes

Realtime
- Polling/SSE now; WebSockets later for chat/presence

Improvements adopted
- Idempotency + request tracing
- Device endpoint idempotent
- Moderation hooks enqueue to SQS
- Optional read model for feed

Rationale
- ALB+ECS over API GW+Lambda (Java): avoids cold starts & DB pool pain
- Firebase over custom auth: standards-based, fast to ship
- SQS over Kafka: simple, reliable, perfect for MVP async
- Neon now, Aurora later: best DX now, easy consolidation later
---

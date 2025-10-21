# Looped Backend - TDD Implementation Plan (MVP -> Later)
 
Goal
- Ship the MVP backend iteratively with strict TDD: write tests first, implement minimal code to pass, keep boundaries clean, and verify locally with the Maven Wrapper.
- Stack: Java 25, Spring Boot 3.5.6, Maven Wrapper; Postgres (Neon->Aurora later), Redis, S3+CloudFront, Firebase Auth, optional SQS workers.
 
How to run
- Dev server (API): `./mvnw -q -pl apps/api -am spring-boot:run`
- Tests (all): `./mvnw -q -T 1C test`
- Build: `./mvnw -q -T 1C -DskipTests package`
- Health check: `GET http://localhost:8080/health` -> `ok`
 
Conventions
- Package-by-feature in `apps/api/src/main/java/com/looped/{auth,users,verification,posts,feed,media,moderation,shared}`
- Tests mirror implementation paths in `apps/api/src/test/java/com/looped/...`
- Workers live under `workers/<name>/` (own POM, own tests)
- No PII in logs; JWT verified on every request; idempotency enforced where noted
 
---
 
## Milestone 0 - Foundation
Scope
- Testing + DB migrations baseline; app boots and returns health.
 
Tests first
- `@SpringBootTest` context loads
- `GET /health -> 200 ok`
 
Implement
- Add test deps (JUnit 5, Spring Boot Test, AssertJ)
- Add Flyway; baseline migration from Appendix B schema
 
Verify
- `./mvnw -q -pl apps/api -am test`
- `./mvnw -q -pl apps/api -am spring-boot:run` and curl `/health`
 
DoD
- Green tests; baseline DB schema applied at startup
 
Effort
- ~2 hours (1 session)
 
## Milestone 1 - Auth (Firebase JWT)
Scope
- JWT verification via JWKS and `/v1/me` endpoint.
 
Tests first
- Web slice: no token -> 401; bad token -> 401; good token -> 200 returns subject/claims
- JWKS stub unit tests
 
Implement
- Spring Security OAuth2 Resource Server (JWT via JWKS)
- `GET /v1/me` (minimal response using claims)
- Config: `auth.jwksUri`, `auth.issuer`, `auth.audience` (Firebase defaults provided)
 
Verify
- `./mvnw -q -pl apps/api -am test`
 
DoD
- Protected endpoints require valid JWT; `/v1/me` returns expected claims
 
Effort
- ~4 hours (2 sessions)
 
## Milestone 2 - Users + Devices (Idempotent)
Scope
- Device registration with idempotency and unique APNs tokens.
 
Tests first
- `POST /v1/devices` stores token; repeat with Idempotency-Key -> same result
- Repo unique by `apns_token`
 
Implement
- Entities/repos: `users`, `devices`
- Redis idempotency key storage (TTL)
- `POST /v1/devices` controller/service
 
Verify
- `./mvnw -q -pl apps/api -am test`
 
DoD
- Device saves idempotently; uniqueness enforced; no PII in logs
 
Effort
- ~4 hours (2 sessions)
 
## Milestone 3 - Posts (Create/Get)
Scope
- Create + fetch posts (with optional media reference).
 
Tests first
- `POST /v1/posts` with Idempotency-Key -> 201; re-submit -> same id
- `GET /v1/posts/{id}` -> 200 or 404
 
Implement
- Entities/repos: `posts`, ref to `media_assets` (nullable)
- Controller/service; validation; defaults (reactions_count=0)
 
Verify
- `./mvnw -q -pl apps/api -am test`
 
DoD
- Post create/get live; idempotency via Redis
 
Effort
- ~4 hours (2 sessions)
 
## Milestone 4 - Feed (Read)
Scope
- Timeline read API with pagination and company scoping.
 
Tests first
- `GET /v1/feed?cursor=` returns items sorted by created_at desc, scoped to company
- Cursor encode/decode unit tests
 
Implement
- Query by `(company_id, created_at DESC)` with limit+cursor
- DTO mappers; pagination helpers in `shared`
 
Verify
- `./mvnw -q -pl apps/api -am test`
 
DoD
- Feed paginates correctly with proper scoping
 
Effort
- ~4 hours (2 sessions)
 
## Milestone 5 - Reactions
Scope
- React to post; uniqueness per user/post.
 
Tests first
- Second `POST /v1/posts/{id}/react` is no-op; counts stay correct
 
Implement
- `reactions` unique `(user_id, post_id)`
- Service updates `reactions_count`
 
Verify
- `./mvnw -q -pl apps/api -am test`
 
DoD
- Idempotent reactions; consistent counts
 
Effort
- ~3 hours (2 sessions)
 
## Milestone 6 - Media (Presign + Callback)
Scope
- Presigned upload + callback to persist `media_assets`.
 
Tests first
- `POST /v1/media/presign` enforces content-type allowlist and max size
- `POST /v1/media/callback` validates signature and persists asset
 
Implement
- AWS SDK v2 S3 presigner
- Guardrails: mime/size; keys under `media/original/{uuid}`
 
Verify
- Unit tests for guardrails; optional LocalStack S3 for integration
 
DoD
- Client can upload via presigned URL; callback recorded
 
Effort
- ~6 hours (3 sessions)
 
## Milestone 7 - Moderation
Scope
- Report intake and status updates.
 
Tests first
- `POST /v1/reports` -> 201; `GET /v1/reports` (simple filter); `PUT /v1/reports/{id}/resolve`
 
Implement
- `reports` entity/repo; status transitions; company scoping
 
Verify
- `./mvnw -q -pl apps/api -am test`
 
DoD
- Basic moderation lifecycle
 
Effort
- ~4 hours (2 sessions)
 
## Milestone 8 - Rate Limits + Observability
Scope
- Redis-backed rate limiting + structured logging.
 
Tests first
- Exceed threshold -> 429
- Logging unit test: redact Authorization; include request_id/trace_id
 
Implement
- Sliding window rate limiter (IP + user) via Redis
- Logback JSON config; request filter sets MDC
 
Verify
- `./mvnw -q -pl apps/api -am test`
 
DoD
- 429s on abuse; JSON logs with trace fields
 
Effort
- ~4 hours (2 sessions)
 
## Milestone 9 - Notif Worker (Optional)
Scope
- Produce SQS on post create; worker consumes and sends APNs.
 
Tests first
- API emits `push.post_created`
- Worker consumes, retries with backoff, handles DLQ
 
Implement
- API SQS producer; worker main loop; retry strategy; message schema
 
Verify
- `./mvnw -q -T 1C -DskipTests package`
- Optional local run: `./mvnw -q -pl workers/notif-worker exec:java -Dexec.mainClass=com.looped.notif.NotifWorker`
 
DoD
- Message flow validated; DLQ policy documented
 
Effort
- ~6 hours (3 sessions)
 
## Milestone 10 - SSE (Optional)
Scope
- Server-Sent Events for lightweight realtime.
 
Tests first
- SSE response is `text/event-stream`; heartbeat emitted
 
Implement
- `/v1/events` with auth + heartbeat
 
Verify
- `./mvnw -q -pl apps/api -am test`
 
DoD
- SSE endpoint functional
 
Effort
- ~3 hours (2 sessions)
 
## Milestone 11 - CI + Quality Gates
Scope
- CI builds/tests; basic code style.
 
Implement
- GitHub Actions workflow runs wrapper cache, `./mvnw -q -T 1C -DskipTests package`, then `./mvnw test`
- Optional: Spotless/Checkstyle
 
DoD
- PRs run tests and fail fast
 
Effort
- ~3 hours (2 sessions)
 
## Milestone 12 - Staging Deploy Prep
Scope
- ECS Fargate packaging + secrets.
 
Implement
- Containerize API; ALB health `/health`
- Secrets via Secrets Manager/SSM; env-driven config
 
DoD
- Service boots in staging; health green
 
Effort
- ~6 hours (3 sessions)
 
---
 
# Estimated Timeline (2 hours every other day ~= ~7 hours/week)
 
Core MVP (Milestones 0-8 + 11 + 12; optional 9-10 excluded)
- Total effort ~= 44 hours
  - Breakdown: M0(2), M1(4), M2(4), M3(4), M4(4), M5(3), M6(6), M7(4), M8(4), M11(3), M12(6)
- At ~7 h/week -> ~6-7 weeks
 
Full with Optional (add Milestones 9-10)
- Add ~= 9 hours (M9=6, M10=3) -> ~53 hours total -> ~7-8 weeks
 
Week-by-week (core track)
- Week 1: M0 (foundation) + start M1 (auth)
- Week 2: Finish M1 + M2 (devices)
- Week 3: M3 (posts) + start M4 (feed)
- Week 4: Finish M4 + M5 (reactions)
- Week 5: M6 (media)
- Week 6: M7 (moderation) + M8 (rate limits/logging)
- Week 7: M11 (CI) + M12 (staging prep)
- Optional Weeks 8-9: M9 (notif-worker) + M10 (SSE)
 
Session plan (each session ~= 2 hours)
- M0: 1 session; M1: 2; M2: 2; M3: 2; M4: 2; M5: 2; M6: 3; M7: 2; M8: 2; M9 (opt): 3; M10 (opt): 2; M11: 2; M12: 3
 
Notes
- Front-load tests and domain logic; wire AWS resources in staging later
- Use Testcontainers for DB/Redis once those features land; LocalStack for S3/SQS (optional)
- Keep PRs small (1 milestone or sub-slice per PR); update docs and `.env.example` as you add features

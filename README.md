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
- Run API (dev): `./mvnw -q -pl apps/api -am spring-boot:run`
  - Alternative (module-only): `./mvnw -q -f apps/api/pom.xml spring-boot:run`
- Build all modules: `./mvnw -q -T 1C -DskipTests package`
- Run tests: `./mvnw -q -T 1C test`
- API URL: `http://localhost:8080` — health: `GET /health` → `ok`

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

# People Recommendations (v1) — Backend Spec + Release Notes

## Status
- Implemented server-side in API with new endpoints under `/v1/recommendations/people`.
- Schema migration added (`V101__people_recommendations.sql`) for served-audit, feedback, and suppressions.
- Compile passes.
- Integration tests are present but require Docker/Testcontainers; in this environment they were skipped.

## Backward Compatibility (Old Clients)
- Safe for old iOS clients.
- Changes are additive only:
  - New endpoints only (no changed/removed existing routes).
  - New DB tables only (no breaking mutations to existing tables/contracts).
  - New env vars are optional and defaulted.
- Existing clients that do not call recommendations endpoints are unaffected.

## New Endpoints (v1)
- `GET /v1/recommendations/people/rails`
- `GET /v1/recommendations/people/{rail}` where `rail in {pymk, community, active_community}`
- `POST /v1/recommendations/people/feedback`

The mobile contract is documented in `docs/API_EXTENSIONS.md`.

## v1 Runtime Behavior
- Candidate sources:
  - PYMK graph proximity (mutual follows, shared context)
  - Community membership (verified community)
  - Active in community (feature-flagged rail)
- Hard exclusions:
  - self
  - already followed
  - blocked either direction
  - reported/policy threshold
  - hidden/less-like-this suppressions during cooldown
  - exposure cap per candidate in rolling 24h window (`RECO_PEOPLE_MAX_VIEWER_EXPOSURE_PER_CANDIDATE_24H`)
- Empty-rail protection:
  - if a rail is fully exhausted by exposure caps, backend retries candidate retrieval with relaxed exposure cap for that request to avoid all-empty rails in sparse graphs
- Explanations:
  - reason codes + user-facing reason text per recommendation
- Identity fields:
  - recommendation `user.display_name` is always present in API responses; server falls back to `handle` when profile display name is null/blank
- Controls:
  - feedback endpoint supports `impression`, `profile_open`, `connect_request_sent`, `connect_accepted`, `hide`, `less_like_this`
  - `hide` and `less_like_this` apply immediate suppressions
- Auditability:
  - every served recommendation is written to served-audit with reason codes/text, model version, and experiment bucket

## Rollout Safety
- Feature behavior is tunable via env vars (OpenTofu wired):
  - `RECO_PEOPLE_ACTIVE_COMMUNITY_RAIL_ENABLED`
  - `RECO_PEOPLE_OPEN_REPORT_EXCLUSION_THRESHOLD`
  - `RECO_PEOPLE_EXPERIMENT_BUCKET_B_PERCENT`
  - `RECO_PEOPLE_MAX_VIEWER_EXPOSURE_PER_CANDIDATE_24H`
- Recommended rollout:
  1. Enable in staging.
  2. Enable in prod with conservative settings + monitoring.
  3. Ramp bucket percentage / rail visibility.

## Infra Today vs Future Scaling

### Today (v1)
- Works on current monolith + Postgres + Redis.
- No new mandatory third-party service required.
- ECS/OpenTofu updates were limited to recommendation env knobs.

### As we scale
- Phase 2:
  - Precompute candidate pools periodically (batch job / worker).
  - Cache candidate lists/features in Redis with short TTL.
  - Add CloudWatch metrics/alarms specific to recommendations (latency, degraded-rate, hide-rate).
- Phase 3:
  - Move feedback/audit streams to async pipeline (SQS/Kinesis -> S3/Athena/warehouse).
  - Introduce dedicated online feature store/cache for fast rank features.
  - Optional recommendation worker service for heavier ranking/reranking.
- Phase 4:
  - Embedding/ANN retrieval service (two-tower style retrieval) and decoupled ranker.
  - Keep API contract stable while backend retrieval/ranking internals evolve.

## Operational Notes
- Retention job included:
  - prunes expired suppressions
  - prunes old recommendation audit + feedback rows by configured retention windows
- If recommendation SQL path degrades, service falls back to simpler heuristic retrieval and marks response as degraded.
- Temporary recommendation identity observability:
  - `people_reco.display_name_fallback_total` (tags: `surface`, `rail`)
  - `people_reco.items_total` (tags: `surface`, `rail`)
  - Request-scoped log when fallback occurs: `people_reco_display_name_fallback`

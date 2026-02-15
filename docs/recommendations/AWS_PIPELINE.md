# AWS-First Recommendations Pipeline (Proposal)

Looped can start with deterministic ranking in Postgres and evolve into ML-assisted recommendations without changing the
core product constraint: "most items should be interactable when supply exists".

This document describes an AWS-first pipeline that supports:

- early-stage (10 users) with minimal ops
- mid-scale (1M users) with batched analytics and periodic model training
- large-scale (100M+ users) with streaming ingestion + online aggregates

## Phases

### Phase 0: MVP (Now)

- Ranking: SQL + heuristics (see `FYP_ALGORITHM.md`).
- Logging: add `POST /v1/telemetry/events` and write events to a durable sink.
- Storage: for local/dev, it is acceptable to store telemetry in Postgres for debugging, but do not rely on that for prod.

### Phase 1: Early Production

Goal: capture all events cheaply for analytics and future training.

Recommended AWS components:

- Ingestion API (ECS): the Spring Boot API receives batched telemetry.
- Firehose -> S3 (raw): API writes events to Kinesis Data Firehose which delivers to S3 as newline-delimited JSON.
- Glue Data Catalog: defines table schema over raw S3 data.
- Athena: query raw and create curated datasets (daily aggregates, funnels).

This yields a clean event lake without prematurely committing to an ML model.

### Phase 2: Scale and ML Assist

Goal: add near-real-time aggregates and ML-assisted candidate generation.

Two complementary tracks:

1. **Online aggregates for ranking features**
   - Use Kinesis Data Streams (or SQS) as a streaming bus for events.
   - A consumer (Lambda or ECS worker) updates:
     - Redis (hot counters, short TTL)
     - DynamoDB (durable counters / per-post exposure and engagement)
   - The feed ranker reads these aggregates to compute rate-based scores.

2. **Managed personalization**
   - Optionally use Amazon Personalize for candidate generation (especially Pool B discovery).
   - Train from curated interaction data in S3.
   - Send real-time events via Personalize `PutEvents` if desired.

Important: Personalize is not useful at 10 users; it becomes valuable only after meaningful interaction volume exists.

## Why This Fits Looped

- Keeps Looped constraints (community gate, interactable-majority) outside the ML black box.
- Supports view-only discovery as a measurable funnel (does discovery lead to join/verify?).
- Telemetry is compatible with both heuristics and ML.

## Data Sets (Minimal)

To train any model or compute rates, we need:

- Impressions (what was shown, where, and when)
- Actions (likes/comments/reposts/shares/saves)
- Context (feed mode, community filter, can_interact, lock_reason)

The proposed telemetry endpoint captures impressions and view-funnel events; existing endpoints capture actions.

## Personalize Integration (Optional)

If we adopt Amazon Personalize:

- Items dataset: post metadata (post_id, created_at, community_id, content type flags).
- Interactions dataset: user_id/principal_id, post_id, event_type (like/comment/open/watch), timestamp.
- Real-time events (optional): forward `post_open`, `video_watch`, and other high-signal events to Personalize.

Looped-specific constraints still apply as a final step:

- exclude content the user cannot view (removed/quarantined)
- enforce blocks/bans
- enforce interactable-majority mixing policy
- apply diversity caps

References:

- Amazon Personalize limits and scaling guidance: https://docs.aws.amazon.com/personalize/latest/dg/limits.html
- Amazon Personalize interactions dataset: https://docs.aws.amazon.com/personalize/latest/dg/interactions-datasets.html
- Amazon Personalize PutEvents: https://docs.aws.amazon.com/personalize/latest/dg/recording-events.html


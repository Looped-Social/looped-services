# FYP Scale Alerts (Admin Email)

Looped sends an automated admin email when metrics suggest it is time to upgrade the For You Page (FYP) architecture.

This is intended to be:

- low-touch (automatic once enabled)
- actionable (includes key metrics + next steps)
- safe (no PII or post content included)

## What It Does

When enabled, `AdminFypScaleAlertsJob` runs on a schedule and:

1. Pulls **performance metrics** for the **global For You** feed from Redis (sampled request metrics).
2. Pulls **volume metrics** (24h lookback) from Postgres (posts + telemetry).
3. Evaluates thresholds.
4. Sends a styled admin email (via SES) to all **active** admin users.
5. Uses a Redis cooldown lock so only one email is sent per cooldown window across ECS tasks.

## Metrics Included

Performance (global `GET /v1/feed?mode=for_you` with no community filter):

- Estimated requests/sec (derived from sampled Redis counters)
- Latency p50/p95/p99 (approximate upper-bound histogram buckets)
- Sampled 5xx rate

Volume (last 24h, Postgres):

- Posts created
- Telemetry events ingested
- Feed impressions and interactable impression share (`payload.can_interact`)
- Distinct telemetry users
- Feed request_id counts (if clients include `feed.request_id` in telemetry)
- Interaction-blocked events and join/verify intent events
- Telemetry table size and total DB size

Notes:

- Redis performance metrics are sampled (default 1%). They are approximate but good enough for alerting/trends.
- If telemetry isn’t deployed on all clients yet, Postgres metrics will undercount; Redis perf metrics still work.

## Enabling / Config

### Enable The Alert Job

Set:

- `ADMIN_FYP_ALERTS_ENABLED=true`
- `APP_ENV=prod` (or set `ADMIN_FYP_ALERTS_REQUIRED_ENV` to match your environment)

Key env vars (see `apps/api/src/main/resources/application.yaml`):

- `ADMIN_FYP_ALERTS_CRON` (default hourly, `0 30 * * * *` in `America/New_York`)
- `ADMIN_FYP_ALERTS_COOLDOWN` (default `PT24H`)
- Thresholds:
  - `ADMIN_FYP_ALERTS_GLOBAL_FYP_ESTIMATED_RPS_THRESHOLD` (default `50`)
  - `ADMIN_FYP_ALERTS_GLOBAL_FYP_P95_UPPER_BOUND_MS_THRESHOLD` (default `750`)
  - `ADMIN_FYP_ALERTS_GLOBAL_FYP_SAMPLED_5XX_RATE_THRESHOLD` (default `0.005`)
  - `ADMIN_FYP_ALERTS_TELEMETRY_EVENTS_24H_THRESHOLD` (default `5000000`)
  - `ADMIN_FYP_ALERTS_TELEMETRY_TABLE_BYTES_THRESHOLD` (default `10737418240` = 10GB)

Optional links included in the email:

- `ADMIN_FYP_ALERTS_DASHBOARD_URL`
- `ADMIN_FYP_ALERTS_RUNBOOK_URL`

### Enable Global FYP Perf Sampling

Sampling is enabled by default and writes small aggregated counters into Redis.

Env vars:

- `FEED_METRICS_GLOBAL_FYP_ENABLED` (default `true`)
- `FEED_METRICS_GLOBAL_FYP_SAMPLE_RATE` (default `0.01` = 1% of global FYP requests)
- `FEED_METRICS_GLOBAL_FYP_RETENTION` (default `PT72H`)

## When The Email Fires: What To Do

The alert email includes an “AWS-first” next step checklist. Typical upgrades:

1. **Move telemetry out of Postgres**:
   - API -> Kinesis Firehose -> S3 (raw JSON) -> Glue/Athena (curated datasets).
2. **Precompute a read model**:
   - Introduce a `feed-worker` that fans out posts into per-user feed entries (SQS + Postgres/DynamoDB).
3. **Add online aggregates**:
   - Track per-post impressions and engagement rates in Redis/DynamoDB; move ranking from counts to rates.
4. **Add caching** (mitigation):
   - Short TTL Redis caching for global FYP pages, and reduce candidate windows if needed.

See `AWS_PIPELINE.md` and `FYP_ALGORITHM.md` for the longer-term evolution path.


# Recommendations (FYP) Docs

This folder documents Looped's planned "For You" (FYP) feed architecture, ranking approach, and the telemetry required
to evolve it safely and scalably.

- `FYP_ALGORITHM.md`: product constraints, candidate generation, mixing, ranking, and anti-gaming guardrails.
- `TELEMETRY_API.md`: iOS -> API telemetry events and the proposed ingestion endpoint(s).
- `AWS_PIPELINE.md`: AWS-first data pipeline and scaling plan (MVP -> 100M+).
- `FYP_SCALE_ALERTS.md`: automated admin email alerts for "time to upgrade FYP" based on metrics.

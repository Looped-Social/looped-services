# notif-worker (SQS → APNs)

Purpose
- Consume `notif-events` SQS messages and deliver pushes to APNs.

Message shape (JSON)
```
{
  "type": "push.announcement",
  "user_id": 123,
  "apns_token": "string",
  "title": "New feature",
  "body": "Check out what's new in Looped",
  "deeplink": "looped://announcements/{id}",
  "collapse_id": "announcement-{company_id}",
  "trace_id": "uuid"
}
```

Config (env)
- `SQS_NOTIF_QUEUE_URL`
- `AWS_REGION` (default `us-east-1`)
- `APNS_BUNDLE_ID`
- `APNS_TEAM_ID`
- `APNS_KEY_ID`
- `APNS_AUTH_KEY_P8` (base64-encoded .p8) or `APNS_AUTH_KEY_PATH` (file path)
- `APNS_SANDBOX` (`true` by default)

Retry & DLQ
- SQS redrive policy limits attempts; failures move to `notif-events-dlq`.
- Use exponential backoff + jitter; log to CloudWatch with `trace_id`.

Idempotency
- Deduplicate using SQS messageId or provided `collapse_id` when appropriate.

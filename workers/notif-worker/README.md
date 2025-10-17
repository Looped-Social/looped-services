# notif-worker (SQS → APNs)

Purpose
- Consume `notif-events` SQS messages and deliver pushes to APNs.

Message shape (JSON)
```
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
```

Retry & DLQ
- SQS redrive policy limits attempts; failures move to `notif-events-dlq`.
- Use exponential backoff + jitter; log to CloudWatch with `trace_id`.

Idempotency
- Deduplicate using SQS messageId or provided `collapse_id` when appropriate.


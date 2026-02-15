# Telemetry API (Proposal)

The feed cannot improve without *impressions* and lightweight "view" signals. Actions (likes/comments/reposts/shares)
already exist, but without knowing what was shown, we cannot compute rates, correct for position bias, or train ranking.

This document defines a minimal iOS -> API telemetry ingestion API designed to be:

- privacy-safe (no PII, no content)
- scalable (batching, dedupe, rate limiting)
- forward-compatible (unknown event types can be dropped safely)

## Privacy Guardrails

- Do not send or log:
  - raw post content
  - email, phone, contacts
  - advertising IDs
  - Authorization headers / tokens
- Use internal ids only:
  - `post_id`, `community_id`, `comment_id` (when needed)
- Use stable-but-non-PII client identifiers:
  - `session_id` (UUID) is required
  - `device_id` is not required (avoid unless truly needed)

## Endpoint: Batch Event Ingestion

### `POST /v1/telemetry/events`

Auth:

- Required: Firebase JWT (`Authorization: Bearer <token>`)

Headers:

- `Authorization: Bearer <jwt>`
- `Content-Type: application/json`
- Optional:
  - `X-Client-Version: 1.2.3` (app version)
  - `X-Client-Build: 123` (build number)
  - `X-Platform: ios`

Request body:

```json
{
  "session_id": "7f91d6d4-ef9c-4df5-a8e5-1f2e70b0c10a",
  "sent_at_ms": 1760550000000,
  "events": [
    {
      "event_id": "c4a0c9f5-9c1d-44bb-8f12-7a4af2b1f6f7",
      "type": "feed_impression",
      "occurred_at_ms": 1760549995000,
      "post_id": 123,
      "feed": {
        "mode": "for_you",
        "community_id": null,
        "request_id": "b9a356f7-7e2c-49c2-b9bb-7e3c1a0c3c73",
        "position": 4
      },
      "data": {
        "visible_ms": 1800,
        "can_interact": true,
        "lock_reason": null
      }
    }
  ]
}
```

Event schema:

- Top-level
  - `session_id` (uuid, required)
  - `sent_at_ms` (number, optional but recommended)
  - `events` (array, required; max length recommended: 200)
- Per-event
  - `event_id` (uuid, required; used for dedupe)
  - `type` (string, required)
  - `occurred_at_ms` (number, required; client timestamp)
  - `post_id` (number, optional; required for most feed events)
  - `feed` (object, optional)
    - `mode` (`for_you|new|following`, optional)
    - `community_id` (number|null, optional; present when feed is filtered)
    - `request_id` (uuid, optional but recommended)
    - `position` (int, optional; 0-based or 1-based, choose one and document; recommend 0-based)
  - `data` (object, optional; type-specific fields)

Response (201):

```json
{
  "status": "ok",
  "accepted": 1,
  "dropped": 0
}
```

Errors:

- `400 { "error": "invalid_body" }` (missing fields, wrong types)
- `401 { "error": "unauthorized" }` (missing/invalid JWT)
- `409 { "error": "user_not_provisioned" }`
- `413 { "error": "payload_too_large" }` (too many events / oversized request)
- `429 { "error": "rate_limited" }` (client sending too frequently)

Server behavior:

- Dedupe by `event_id` (recommended: Redis `SETNX` with TTL).
- Unknown event types can be dropped (counted in `dropped`) for forward compatibility.

## Event Types (Initial Set)

All of these are "telemetry-only" (they do not change state), except the optional negative-feedback types if/when we add
stateful hide/mute later.

### `feed_impression`

When a feed cell has been visible for at least a threshold (recommend: 500ms).

Required:

- `post_id`
- `data.visible_ms` (int)

Recommended:

- `feed.request_id`, `feed.position`, `feed.mode`
- `data.can_interact`, `data.lock_reason` (from `viewerCapabilities`)

### `post_open`

When the user taps into a post detail view.

Required:

- `post_id`

Recommended:

- `feed.request_id`, `feed.position`, `data.entry_point` (`feed|notification|profile|link`)

### `comments_open`

When the user opens the comments screen for a post.

Required:

- `post_id`

### `video_watch`

Only for posts with video media.

Required:

- `post_id`
- `data.watch_ms` (int)
- `data.duration_ms` (int, if known)

Recommended:

- `data.completed` (bool)
- `data.autoplay` (bool)

### `interaction_blocked`

When the user attempts to interact (like/comment/vote) but is blocked by community gate.

Required:

- `post_id`
- `data.action` (`like|comment|reply|vote`)
- `data.lock_reason` (use the same values as `viewerCapabilities.lockReason`)

### `community_join_intent`

When the user taps a "Join" CTA from a discovery post (specializations).

Required:

- `data.community_id`

Recommended:

- `post_id` (origin post)

### `community_verify_intent`

When the user taps a "Verify" CTA from a discovery post (non-specialization communities).

Required:

- `data.community_id`

Recommended:

- `post_id` (origin post)

## Notes on Existing Action Endpoints

These actions are already stateful and tracked in Postgres:

- Like: `POST /v1/posts/{id}/like`
- Comment: `POST /v1/posts/{id}/comments`
- Repost: `PUT /v1/posts/{id}/repost` (unique per reposter)
- Share: `POST /v1/posts/{id}/share` (currently not unique per sharer; treat as weak signal)

Telemetry should not duplicate these writes. Telemetry is primarily for impressions and view funnels.

## iOS Implementation Notes (Recommended)

- Generate `session_id` once per foreground session (new UUID when app enters foreground; reuse until background).
- Buffer events locally and send in batches:
  - flush when `events.count >= 25` or every ~10 seconds (whichever comes first)
  - flush on app background/termination
  - keep `event_id` stable when retrying a failed request (server dedupes by `event_id`)
- `feed_impression`:
  - fire when a cell has been visible for >= 500ms
  - include `visible_ms` (total visible time for that cell before it disappears)
  - include `can_interact` + `lock_reason` from the post payload’s `viewerCapabilities`
- `post_open`: fire when the user opens a post detail view from the feed.
- `comments_open`: fire when the user opens the comments screen for a post.
- `video_watch`: fire periodically (or on stop) with `watch_ms`, `duration_ms`, and `completed` when known.
- `interaction_blocked`: fire when the user taps `like/comment/vote` but `viewerCapabilities.canInteract == false`.


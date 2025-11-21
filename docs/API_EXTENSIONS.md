## Extended API Surface (Polling-first)

- **Profile update aliases**
  - `PUT /v1/users/me` and alias `PUT /users/me`
  - Request: `{ "displayName": "optional string|null", "bio": "optional string|null", "isAnonymous": true|false }`
  - Response: user payload matching `/v1/me.user` with `display_name`, `bio`, `is_anonymous`, `profile_image_url`, and `stats.{follower_count,following_count,posts_count,comments_count}`.
- **Employment verification alias**
  - `POST /users/verify-employment` (also available at `/v1/users/verify-employment`)
  - Delegates to existing verification flow; accepts `{ "method": "email|video|thirdparty" }` (defaults to `email` when omitted). Response mirrors `/v1/verification/start`.
- **People search & directory**
  - `GET /v1/users/search?query=&cursor=&limit=` (same-company scope); requires non-blank `query`.
  - `GET /v1/users?cursor=&limit=` default directory ordered by join date/activity.
  - Items: `{ id, handle, username, display_name, bio, company_id, profile_image_url }` + `next_cursor` when more.
- **Direct messages (DMs) with polling**
  - `GET /v1/conversations?cursor=&limit=` → `{ items: [{ id, other_user_id, other_user_profile, last_message, last_message_timestamp, unread_count }], next_cursor }`
  - `POST /v1/conversations` → `{ id, other_user_id, other_user_profile, last_message, last_message_timestamp, unread_count }`
    - Body: `{ "participantUserId": <int> }` (find-or-create DM within company).
  - `GET /v1/conversations/{id}/messages?cursor=&limit=` → message DTOs.
  - `POST /v1/conversations/{id}/messages` → message DTO (201).
    - Body: `{ "content": "<text>", "attachments": [] }`
  - Message DTO: `{ id, sender_id, content, attachments, created_at }`
- **Channels**
  - `GET /v1/channels?cursor=&limit=` → `{ id, name, member_count, is_public }`
  - `GET /v1/channels/{id}/messages?cursor=&limit=` and `POST /v1/channels/{id}/messages` (same shape as DM messages).
- **Notifications**
  - `GET /v1/notifications?cursor=&limit=` → `{ items: [{ id, type, created_at, unread, payload }], next_cursor }`
  - `POST /v1/notifications/{id}/read` → `{ "read": true }`
- **Profile stats & DTO extensions**
  - User DTO now includes `stats` block with follower/following/posts/comments counts; display/bio/anonymity fields included across `/v1/me`, `/v1/users/{id}`, and update alias responses.
  - Post DTOs include `comments_count` and `share_count`.
- **Comments history**
  - `GET /v1/users/{id}/comments?cursor=&limit=` → `{ items: [{ id, post_id, content, created_at, parent_id? }], next_cursor }`

All endpoints enforce Firebase auth + company scoping with `403` for cross-company, `404` for missing resources, and `409` for not-provisioned users. Pagination uses `cursor`/`limit` with `{ items, next_cursor }` envelopes.

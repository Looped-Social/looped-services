# Admin API (Dashboard)

All admin endpoints live under `/v1/admin/*` and require a Firebase ID token.

## Auth (frontend)

1) Sign in with Firebase in the admin web app.
2) Send the ID token on every request:
   - Header: `Authorization: Bearer <ID_TOKEN>`
3) Call `GET /v1/admin/me` to confirm access and load permissions.

Notes
- Admin access is allowlist-based. Owners are seeded in DB; other admins join via invite.
- The backend matches invites by the Firebase email claim. Ensure the email is verified in Firebase.
- If you need MFA enforcement, add a server-side check for MFA claims and block otherwise.

## Endpoints

### GET /v1/admin/me
Returns the admin identity + permissions for the current Firebase user.

Response (200)
```json
{
  "id": 1,
  "email": "admin@company.com",
  "role": "owner",
  "status": "active",
  "permissions": ["manage_admins", "ban_user", "remove_post"]
}
```

Errors
- 401 if no/invalid token
- 403 if not an admin

### POST /v1/admin/invites
Create an invite for a new admin. Requires `manage_admins` permission.

Request
```json
{
  "email": "newadmin@company.com",
  "role": "admin",
  "permissions": ["ban_user", "remove_post"]
}
```

Notes
- `role` can be `admin` or `moderator`. Owners are seeded manually.
- `permissions` is optional. If omitted, defaults are applied per role.

Response (201)
```json
{
  "token": "invite-token-string",
  "expires_at": "2025-01-01T00:00:00Z",
  "role": "admin",
  "permissions": ["ban_user", "remove_post", "create_community"]
}
```

Errors
- 403 if not admin or missing `manage_admins`
- 409 if an invite already exists for that email or admin already exists
- 422 if email/role/permissions are invalid

Frontend usage
- Show the token once and ask the owner to share it out-of-band.
- Do not store the token in logs or analytics.

### POST /v1/admin/invites/accept
Accept an invite and create the admin user.

Request
```json
{
  "token": "invite-token-string"
}
```

Response (200)
```json
{
  "status": "accepted",
  "role": "admin",
  "permissions": ["ban_user", "remove_post", "create_community"]
}
```

Errors
- 422 if token is invalid or expired
- 403 if email does not match invite email
- 409 if user is already an admin

### GET /v1/admin/admins
List admins. Requires `manage_admins`.

Response (200)
```json
{
  "items": [
    {
      "id": 1,
      "email": "owner@company.com",
      "role": "owner",
      "status": "active",
      "permissions": ["manage_admins", "ban_user"],
      "created_at": "2024-01-01T00:00:00Z",
      "last_login_at": "2024-01-02T00:00:00Z"
    }
  ]
}
```

### PATCH /v1/admin/admins/{id}
Update role/status/permissions for an admin. Requires `manage_admins`.

Request
```json
{
  "role": "moderator",
  "status": "active",
  "permissions": ["ban_user", "remove_post"]
}
```

Notes
- Owners cannot be created via API.
- You cannot disable or remove `manage_admins` from yourself.

Response (200)
```json
{
  "id": 2,
  "role": "moderator",
  "status": "active",
  "permissions": ["ban_user", "remove_post"]
}
```

Errors
- 403 if missing `manage_admins`
- 404 if admin not found
- 422 for invalid role/status/permissions

## Roles and default permissions

Roles: `owner`, `admin`, `moderator`

Default permissions when `permissions` is omitted on invite:
- owner: all permissions
- admin: all except `manage_admins`
- moderator: `ban_user`, `remove_post`, `create_community`, `view_reports`, `resolve_reports`, `view_feedback`

Available permissions:
- `manage_admins`, `ban_user`, `remove_post`, `create_community`
- `view_reports`, `resolve_reports`, `verify_users`, `delete_media`, `view_feedback`
- `send_announcements`

## Moderation endpoints

### GET /v1/admin/users
Search users for moderation. Requires `ban_user`.

Query params:
- `query` (optional): handle/email/firebase UID or ID match
- `cursor` (optional)
- `limit` (default 50)

Response (200)
```json
{
  "items": [
    {
      "id": 123,
      "handle": "bullyboy",
      "email": "bully@company.com",
      "company_id": 5,
      "created_at": "2024-01-01T00:00:00Z",
      "ban": {
        "reason": "harassment",
        "created_at": "2024-01-02T00:00:00Z",
        "expires_at": null
      }
    }
  ],
  "next_cursor": "eyJ0Ijo..."
}
```

### GET /v1/admin/users/{id}
Fetch a user record, including active ban and moderation stats. Requires `ban_user`.

Response (200)
```json
{
  "id": 123,
  "handle": "bullyboy",
  "email": "bully@company.com",
  "company_id": 5,
  "created_at": "2024-01-01T00:00:00Z",
  "ban": {
    "reason": "harassment",
    "created_at": "2024-01-02T00:00:00Z",
    "expires_at": null,
    "created_by": 1
  },
  "moderation_stats": {
    "posts_total": 300,
    "posts_removed_total": 12,
    "reports_against_user_total": 400,
    "reports_against_user_open": 1,
    "reports_against_user_resolved": 0,
    "reports_against_user_dismissed": 399,
    "reports_against_posts_total": 600,
    "reports_against_posts_open": 10,
    "reports_against_posts_resolved": 20,
    "reports_against_posts_dismissed": 570,
    "reports_filed_total": 5,
    "reports_filed_open": 0,
    "reports_filed_resolved": 2,
    "reports_filed_dismissed": 3
  }
}
```

### POST /v1/admin/users/{id}/ban
Ban a user. Requires `ban_user`.

Request
```json
{
  "reason": "harassment",
  "duration_seconds": 86400,
  "expires_at": "2025-01-01T00:00:00Z"
}
```

Response (200)
```json
{
  "id": 42,
  "status": "banned",
  "expires_at": "2025-01-01T00:00:00Z"
}
```

### POST /v1/admin/users/{id}/unban
Unban a user. Requires `ban_user`.

Response (200)
```json
{
  "status": "active"
}
```

### GET /v1/admin/posts/{id}
Fetch a post including removal info. Requires `remove_post`.

Response (200)
```json
{
  "id": 987,
  "author_id": 123,
  "author_handle": "bullyboy",
  "author_display_name": "Bully Boy",
  "company_id": 5,
  "community_id": 2,
  "content": "post text",
  "media_asset_id": null,
  "created_at": "2024-02-01T12:00:00Z",
  "removed_at": "2024-02-02T09:00:00Z",
  "removed_reason": "policy_violation",
  "removed_by": 1
}
```

### POST /v1/admin/posts/{id}/remove
Remove a post. Requires `remove_post`.

Request
```json
{
  "reason": "policy_violation"
}
```

Response (200)
```json
{
  "status": "removed"
}
```

### POST /v1/admin/posts/{id}/restore
Restore a removed post. Requires `remove_post`.

Response (200)
```json
{
  "status": "active"
}
```

### GET /v1/admin/reports
List reports. Requires `view_reports`.

Query params:
- `status` (optional): `open`, `resolved`, `dismissed`
- `targetType` (optional): `post`, `user`, `comment`
- `from`/`to` (optional): YYYY-MM-DD
- `sort` (optional): `created_at_desc` (default) or `created_at_asc`
- `cursor` (optional)
- `limit` (default 50)

Response (200)
```json
{
  "items": [
    {
      "id": 555,
      "target_type": "post",
      "target_id": 987,
      "reporter_id": 222,
      "reporter_handle": "alice",
      "reason": "harassment",
      "status": "open",
      "created_at": "2024-02-01T12:00:00Z",
      "updated_at": "2024-02-01T12:00:00Z",
      "resolved_at": null,
      "resolved_by": null,
      "resolved_reason": null
    }
  ],
  "next_cursor": "eyJ0Ijo..."
}
```

### POST /v1/admin/reports/{id}/resolve
Resolve a report. Requires `resolve_reports`.

Request
```json
{
  "reason": "action_taken"
}
```

Response (200)
```json
{
  "status": "resolved"
}
```

### POST /v1/admin/reports/{id}/dismiss
Dismiss a report as rejected. Requires `resolve_reports`.

Request
```json
{
  "reason": "not_a_violation"
}
```

Response (200)
```json
{
  "status": "dismissed"
}
```

### GET /v1/admin/appeals
List appeals. Requires `view_reports`.

Query params:
- `status` (optional): `open`, `approved`, `rejected`
- `targetType` (optional): `user_ban`, `post_removal`
- `userId` (optional)
- `sort` (optional): `created_at_desc` (default) or `created_at_asc`
- `cursor` (optional)
- `limit` (default 50)

Response (200)
```json
{
  "items": [
    {
      "id": 321,
      "user_id": 123,
      "user_handle": "bullyboy",
      "target_type": "user_ban",
      "target_id": 44,
      "reason": "please_reconsider",
      "status": "open",
      "created_at": "2024-02-03T10:00:00Z",
      "updated_at": "2024-02-03T10:00:00Z",
      "reviewed_at": null,
      "reviewed_by": null,
      "reviewed_reason": null
    }
  ],
  "next_cursor": "eyJ0Ijo..."
}
```

### POST /v1/admin/appeals/{id}/approve
Approve an appeal. Requires `resolve_reports`.

Request
```json
{
  "reason": "appeal_granted"
}
```

Response (200)
```json
{
  "status": "approved",
  "action": "user_unbanned"
}
```

### POST /v1/admin/appeals/{id}/reject
Reject an appeal. Requires `resolve_reports`.

Request
```json
{
  "reason": "policy_violation_confirmed"
}
```

Response (200)
```json
{
  "status": "rejected"
}
```

Notes
- Approving a `user_ban` appeal attempts to revoke the active ban.
- Approving a `post_removal` appeal attempts to restore the removed post.

## Community requests (Admin)

### GET /v1/admin/community-requests
List community requests. Requires `create_community`.

Query params:
- `status` (optional): `pending`, `approved`, `rejected`
- `cursor` (optional)
- `limit` (default 50)

Response (200)
```json
{
  "items": [
    {
      "id": 91,
      "user_id": 123,
      "user_handle": "alice",
      "user_email": "alice@company.com",
      "kind": "sector",
      "name": "Product",
      "description": "Product leadership and ICs",
      "image_key": "media/original/uuid",
      "image_url": "https://cdn.looped.com/media/original/uuid",
      "status": "pending",
      "created_at": "2024-02-10T12:00:00Z"
    }
  ],
  "next_cursor": "eyJ0Ijo..."
}
```

### POST /v1/admin/community-requests/{id}/approve
Approve a request and create a community. Requires `create_community`.

Notes
- If `imageUrl` is omitted and the request includes an `image_key`, the community image will default to that request image.

Request (optional overrides)
```json
{
  "kind": "sector",
  "name": "Product",
  "description": "Product leadership and ICs",
  "imageUrl": "https://cdn.looped.com/media/communities/product.jpg",
  "verificationTtlDays": 365
}
```

Response (200)
```json
{
  "status": "approved",
  "community_id": 42
}
```

### POST /v1/admin/community-requests/{id}/reject
Reject a request. Requires `create_community`.

Request
```json
{
  "reason": "Duplicate of existing community"
}
```

Response (200)
```json
{
  "status": "rejected"
}
```

### DELETE /v1/admin/community-requests/{id}
Delete a request. Requires `create_community`.

Response (200)
```json
{
  "status": "deleted"
}
```

## Community logos (Admin)

### GET /v1/admin/communities/{id}/logos
List uploaded logos and the current selection. Requires `create_community`.

Response (200)
```json
{
  "community_id": 2,
  "kind": "company",
  "logo_dev_url": "https://img.logo.dev/shopify.com?token=pk_123&retina=true",
  "selected_source": "logo_dev",
  "selected_image_url": "https://img.logo.dev/shopify.com?token=pk_123&retina=true",
  "selected_upload_id": 10,
  "uploads": [
    {
      "id": 10,
      "media_asset_id": 55,
      "key": "media/communities/logos/uuid",
      "mime_type": "image/png",
      "cdn_url": "https://cdn.looped.com/media/communities/logos/uuid",
      "created_at": "2024-02-01T12:00:00Z"
    }
  ]
}
```

Notes
- `logo_dev_url` is provided only for `company` and `school` communities with a domain.
- `selected_source` is one of `logo_dev`, `upload`, `custom`, or `none`.

### POST /v1/admin/communities/{id}/logos/presign
Presign an upload for a community logo (images only). Requires `create_community`.

Request
```json
{
  "contentType": "image/png",
  "sizeBytes": 12345
}
```

Response (200)
```json
{
  "key": "media/communities/logos/uuid",
  "uploadUrl": "https://s3.amazonaws.com/...",
  "headers": { "Content-Type": "image/png" },
  "callbackSignature": "base64"
}
```

### POST /v1/admin/communities/{id}/logos/callback
Record the uploaded logo asset. Requires `create_community`.

Request
```json
{
  "key": "media/communities/logos/uuid",
  "mimeType": "image/png",
  "width": 512,
  "height": 512
}
```

Response (201)
```json
{
  "status": "created",
  "media_asset_id": 55,
  "key": "media/communities/logos/uuid",
  "mime_type": "image/png",
  "cdn_url": "https://cdn.looped.com/media/communities/logos/uuid"
}
```

### PATCH /v1/admin/communities/{id}/logo
Select the community logo (upload, Logo.dev fallback, or custom URL). Requires `create_community`.

Request (upload)
```json
{
  "imageKey": "media/communities/logos/uuid"
}
```

Request (Logo.dev)
```json
{
  "useLogoDev": true
}
```

Request (custom URL)
```json
{
  "imageUrl": "https://example.com/logo.png"
}
```

Response (200)
```json
{
  "community_id": 2,
  "selected_source": "upload",
  "image_url": "https://cdn.looped.com/media/communities/logos/uuid",
  "selected_upload_id": 10
}
```

## Analytics (Admin)

All analytics endpoints require `view_reports`.

### GET /v1/admin/analytics/communities/leaderboard
Community leaderboard. Metrics are event counts in the selected range.

Query params:
- `metric` (optional): `likes` (default), `shares`, `followers`, `verifications`, `accounts`
- `communityId` (optional): if provided, returns stats for one community
- `from`/`to` (optional): YYYY-MM-DD date range
- `limit` (default 50)

Response (200)
```json
{
  "items": [
    {
      "id": 2,
      "kind": "sector",
      "name": "Product",
      "image_url": "https://cdn.looped.com/media/original/uuid",
      "likes_count": 120,
      "shares_count": 22,
      "followers_count": 80,
      "verifications_count": 45,
      "accounts_total": 125
    }
  ]
}
```

Notes
- `accounts_total` = `followers_count` + `verifications_count`.
- If `from`/`to` are omitted, `verifications_count` reflects active (non‑expired) verifications.

### GET /v1/admin/analytics/hashtags
Hashtag leaderboard.

Query params:
- `communityId` (optional)
- `from`/`to` (optional): YYYY-MM-DD date range
- `limit` (default 50)

Response (200)
```json
{
  "items": [
    { "id": 12, "name": "product", "usage_count": 42 }
  ]
}
```

### GET /v1/admin/analytics/users
User stats.

Query params:
- `from`/`to` (optional): YYYY-MM-DD date range

Response (200)
```json
{
  "total_users": 1200,
  "new_users": 45,
  "deleted_users": 3
}
```

## Feedback (Admin)

### GET /v1/admin/feedback
List feedback submissions. Requires `view_feedback`.

Query params:
- `status` (optional): `open`, `resolved`
- `from`/`to` (optional): YYYY-MM-DD date range
- `cursor` (optional)
- `limit` (default 50)

Response (200)
```json
{
  "items": [
    {
      "id": 44,
      "user_id": 123,
      "user_handle": "alice",
      "email": "alice@company.com",
      "title": "Feature request",
      "message": "Please add X",
      "status": "open",
      "created_at": "2024-03-01T12:00:00Z"
    }
  ],
  "next_cursor": "eyJ0Ijo..."
}
```

## User appeals (API)

### POST /v1/appeals
Submit an appeal. Requires auth (banned users are allowed).

Request
```json
{
  "targetType": "user_ban",
  "reason": "please_reconsider"
}
```

```json
{
  "targetType": "post_removal",
  "targetId": 123,
  "reason": "context_matters"
}
```

Response (201)
```json
{
  "id": 321
}
```

### GET /v1/appeals
List your appeals.

Response (200)
```json
{
  "items": [
    {
      "id": 321,
      "target_type": "post_removal",
      "target_id": 123,
      "reason": "context_matters",
      "status": "open",
      "created_at": "2024-02-03T10:00:00Z",
      "updated_at": "2024-02-03T10:00:00Z",
      "reviewed_at": null,
      "reviewed_by": null,
      "reviewed_reason": null
    }
  ]
}
```

## Community requests (API)

### POST /v1/community-requests
Submit a community request.

Notes
- `kind` supports `company`, `school`, `sector` (alias: `profession` → `sector`).
- To attach an image, upload via `/v1/media/presign` + `/v1/media/callback`, then pass the returned `key` as `imageKey`.
- JSON aliases accepted: `type` → `kind`, `about` → `description`, `image_key` → `imageKey`.

Request
```json
{
  "kind": "sector",
  "name": "Product",
  "description": "Product leadership and ICs",
  "imageKey": "media/original/uuid"
}
```

Response (201)
```json
{
  "id": 91,
  "status": "pending"
}
```

### GET /v1/community-requests
List your community requests.

Query params:
- `status` (optional): `pending`, `approved`, `rejected`

Response (200)
```json
{
  "items": [
    {
      "id": 91,
      "kind": "sector",
      "name": "Product",
      "description": "Product leadership and ICs",
      "image_key": "media/original/uuid",
      "image_url": "https://cdn.looped.com/media/original/uuid",
      "status": "pending",
      "created_at": "2024-02-10T12:00:00Z"
    }
  ]
}
```

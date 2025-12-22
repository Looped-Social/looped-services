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

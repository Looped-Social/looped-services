# Universal Links Troubleshooting

This runbook covers iOS Universal Link support for Looped share URLs.

## Required URL surface

- `https://mylooped.app/.well-known/apple-app-site-association`
- `https://mylooped.app/p/{postId}`
- `https://mylooped.app/u/{slug}`

## AASA endpoint behavior

- Path must be exact: `/.well-known/apple-app-site-association`
- No redirect (must return `200` directly)
- Content-Type must be JSON
- Public access (no auth)
- HTTPS only

In `apps/api`, AASA is served by:

- `apps/api/src/main/java/com/looped/links/UniversalLinksController.java`

Config is controlled by:

- `UL_APPLE_TEAM_ID`
- `UL_IOS_BUNDLE_ID`
- `UL_CACHE_MAX_AGE_SECONDS` (default `300`)
- `UL_AASA_VERSION` (used for `ETag`)

When using Terraform/OpenTofu in this repo (`infra/envs/*`), set:

- `universal_links_apple_team_id`
- `universal_links_ios_bundle_id`
- `universal_links_cache_max_age_seconds`
- `universal_links_aasa_version`

## AASA version bump process

When AASA paths or app identifiers change:

1. Update config (`UL_*`) and deploy.
2. Bump `UL_AASA_VERSION` (for example `v2`, `2026-02-14-1`).
3. Confirm response headers reflect the new `ETag`.
4. Wait for Apple/device AASA cache propagation (can take time).

## Production verification commands

```bash
curl -i https://mylooped.app/.well-known/apple-app-site-association
curl -I https://mylooped.app/p/123
curl -I https://mylooped.app/u/testslug
curl -i https://api.mylooped.app/v1/public/posts/123
curl -i https://api.mylooped.app/v1/public/profiles/testslug
```

## Edge/CDN checklist

- `/.well-known/apple-app-site-association` is excluded from SPA rewrite-to-index rules
- No auth middleware for `/.well-known/*`
- No redirect from `/.well-known/apple-app-site-association`
- No body transform that mutates JSON
- Canonical apex/www behavior is explicit; serve AASA on each host used for links

## API contracts for public previews

Public preview endpoints should return JSON consistently:

- `200`: found
- `404`: not found
- `410`: unavailable (removed/disabled)

No HTML error wrappers.

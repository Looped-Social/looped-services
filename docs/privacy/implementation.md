# **Low-Level Implementation Guide — Do/Don’t, SQL, Safety Checks**

This is the “no foot-guns” reference for privacy-safe anon with full actor features.

## **Crypto & payloads**

* **Persona keys:** Ed25519 client-side.

* **Issuer:** Blind RSA (RSA-PSS, SHA-256) or VOPRF (ristretto255). Use vetted libs.

**Certificate payload** (example):

`{`  
  `"kid": "issuer-key-id",`  
  `"scope_kind": "company",          // or "sector" | "space" | "global"`  
  `"scope_id": "<uuid>",             // companies.id or spaces.id`  
  `"persona_pubkey": "<base64 ed25519>",`  
  `"not_before": "2025-10-18T00:00:00Z",`  
  `"not_after":  "2026-10-18T00:00:00Z"`  
`}`

**Post signature (`anon_sig`)** over canonical form (match server):

`SHA-256( "v1|" ||`  
         `loop_id || "|" ||`  
         `scope_kind || "|" || scope_id || "|" ||`  
         `content_hash || "|" ||`  
         `timestamp_floor_seconds )`

* Never include `author_id`, device IDs, or account info in the signed body.

**Action signatures** (anon likes/follows/saves/delete/edit):

`like:    SHA-256("like|v1|"    || post_id)`  
`unlike:  SHA-256("unlike|v1|"  || post_id)`  
`follow:  SHA-256("follow|v1|"  || followee_principal_id)`  
`unfollow:SHA-256("unfollow|v1|"|| followee_principal_id)`  
`save:    SHA-256("save|v1|"    || post_id)`  
`unsave:  SHA-256("unsave|v1|"  || post_id)`  
`delete:  SHA-256("delete|v1|"  || post_id)`  
`edit:    SHA-256("edit|v1|"    || post_id || "|" || new_content_hash)`

## **Principals: acting as user or anon**

* Always write `author_principal_id` (and for legacy named, you can also set `author_id`).

* For anon, `author_principal_id.kind=anon` and you must verify the persona signature before any write (post, like, follow, save, delete, edit).

## **Endpoints — hardening**

### **`/anon/enroll`**

**Do:** verify named user has a valid `verification_scopes` record (or base `verifications`) for requested scope; issue blinded cert; no durable mapping; short-TTL issuance limit.  
 **Don’t:** log request bodies or user IDs; no idempotency records.

### **`/anon/issuer`**

**Do:** expose issuer **public key PEM** (X.509 SubjectPublicKeyInfo) + `kid` so the client can blind.  
**Don’t:** expose private key or user-linked metadata.

### **`/anon/reset`**

**Do:** clear the enrollment sanction so a verified user can enroll a **new** anon persona.  
**Don’t:** link user → anon profile. Server cannot prove old persona was revoked; client should call `/anon/revoke` separately.

### **`/anon/revoke`**

**Do:** verify anon proof (`anon_cert` + `anon_sig`) and add the persona pubkey to `anon_revocations`.  
**Don’t:** delete old posts; revocation only blocks future actions.

### **`/posts` (anon)**

**Do:**

* Resolve issuer by `anon_cert_kid` → `anon_issuers`.

* Verify `anon_cert` signature, scope, and expiry.

* Load `persona_pubkey` from `anon_profile_id` (or use `anon_ephemeral_pubkey`).

* Verify `anon_sig` over canonical body.

* Enforce **scope match** (company/sector/space/global).

* For attached media, assert `media_assets.owner_id IS NULL`.

* Insert with `author_principal_id(kind=anon)`; `author_id=NULL`.

**Don’t:** read `users` during anonymous writes; no idempotency records.

### **Likes / Follows / Saves (anon)**

* Require action signature (above).

* Insert into `post_likes`, `principal_follows`, `principal_saved_posts` with the **anon** `principal_id`.

## **Verification scopes & auto-grants**

* On successful company verification, write a `verification_scopes` row for `(company, company_id)`.

* Apply `verification_scope_implications`: for each `(from_scope) ⇒ (to_scope)`, add derived rows (or mark derived in `details`).

* For anon cert issuance to sector/space/global, validate that the user holds a valid named scope (direct or derived).

## **Query patterns**

**Feed** (no user join):

`SELECT p.*`  
`FROM posts p`  
`WHERE p.loop_id = $1`  
`ORDER BY p.created_at DESC, p.id DESC`  
`LIMIT $2;`

**My Likes** (current principal):

`SELECT p.*`  
`FROM post_likes pl`  
`JOIN posts p ON p.id = pl.post_id`  
`WHERE pl.liker_principal_id = $1`  
`ORDER BY pl.created_at DESC`  
`LIMIT $2;`

**My Saves** (current principal):

`SELECT p.*`  
`FROM principal_saved_posts s`  
`JOIN posts p ON p.id = s.post_id`  
`WHERE s.saver_principal_id = $1`  
`ORDER BY s.created_at DESC`  
`LIMIT $2;`

**Analytics daily:**

`SELECT *`  
`FROM post_metrics_daily`  
`WHERE post_id = $1`  
`ORDER BY day DESC`  
`LIMIT 60;`

## **Logging & observability**

* **Strip/redact**: IP, user\_id, device\_id, cookies, and headers on `/anon/*` and anon writes.

* Never log `anon_cert`, `anon_sig`, or `persona_pubkey` contents.

* Metrics keyed by scope/loop, not user.

## **Database protections**

* Limit access to crypto fields to the app role.

* Moderator/analyst views exclude raw `anon_cert/sig`.

* Optional RLS to block `author_id` access when `is_anon=true`.

## **Revocation**

* On each anon action, check:

`SELECT 1 FROM anon_revocations WHERE persona_pubkey = $pub LIMIT 1;`

* If you store a cert fingerprint on write, also check by fingerprint.

* Revocation blocks future actions; never deanonymizes.

## **Settings**

* Read/write `principal_settings` for the **current principal** (user or anon).

* Disable push/email for anon principals in UI; still store consent timestamps.

## **Cross-device Anonymous (encrypted backup)**

* **POST /anon/backup**: store `{blob_id, salt, ciphertext}` in `anon_backup_blobs`. No user\_id; rate limit; optional expiry.

* \*\*GET /anon/backup/{blob\_id}`**: return` {salt, ciphertext}\`.

* Client derives key with **Argon2id(passphrase, salt)** → decrypts AES-GCM → imports persona.

* Re-enroll same `persona_pubkey` to refresh cert on new device (still unlinkable).

## **Automated tests (must have)**

* Invariants (`is_anon`, scope match, media owner NULL).

* Cert expiry/scope enforced; bad sig rejected.

* No idempotency records on anon endpoints.

* Logs scrubbing verified.

* Revocation blocks actions.

* Principal actions (like/follow/save) work for both kinds; DMs remain users-only.

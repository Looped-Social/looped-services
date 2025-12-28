# **Looped Database Schema — Technical Overview**

*Last updated: MVP with principals, multi-scope verification, anon certs, analytics, settings, and enrollment sanctions.*

**Privacy first:** Anonymous content never stores a user→persona link. Certain tables intentionally have **no FKs** to preserve unlinkability.

---

## **Big Picture**

* **Spaces → Loops → Posts/Comments**

  * A **space** is a container: `company | sector | global`.

  * Each space has **loops** (topic channels).

  * **Posts** live in loops; **comments** on posts.

* **Principals (actor model)**

  * A **principal** is either a **named user** or an **anonymous persona**.

  * All “actor” features (likes, follows, saves, blocks) reference a **principal**, so anon can behave like a user **without DMs**.

* **Verification & Scopes**

  * Users can be verified for **company/sector/space/global** via `verification_scopes`.

  * `verification_scope_implications` declares **auto-grants** (e.g., verify JPM ⇒ Finance space).

* **Anonymous Protocol**

  * Client generates an Ed25519 persona key.

  * Server issues a **blinded** certificate (scoped, \~365 days).

  * Anonymous posts/comments include proof fields (cert \+ signature).

  * **Revocations** and **sanctions** control abuse without identity linkage.

  * Optional **encrypted backup** lets users reuse the same anonymous persona across devices (no user FK).

* **Settings & Analytics**

  * `principal_settings` stores preferences/consent for both named and anon.

  * `post_impressions` and `post_metrics_daily` power “My analytics.”

---

## **Core Enums**

* Roles & membership: `membership_role`, `membership_status`, `loop_role`

* Messaging type: `conversation_type`

* Verification: `verification_method`, `verification_scope_kind`

* Visibility/status: `profile_visibility`, `profile_status`

* **Actor kind:** `principal_kind { user, anon }`

* Reporting: `report_status`

* Space kind: `space_kind { company, sector, global }`

* Sanctions: `sanction_status { active, expired }`

---

## **Key Tables & Why They Exist**

### **Identity & Actors**

* **`users`** — Named accounts (OIDC subject, handle).

* **`anonymous_profiles`** — **Persona public keys** scoped to a company. **No FK to users** by design.

* **`principals`** — The **actor** abstraction:

  * `kind=user` → links to `users.id`

  * `kind=anon` → links to `anonymous_profiles.id`

  * All actor-driven features reference a `principal_id`.

### **Spaces & Membership**

* **`companies`, `company_profiles`** — Company identity & marketing fields.

* **`spaces`** — Containers (`company | sector | global`), optional `company_id`.

* **`loops`** — Channels within a space.

* **`user_companies`, `user_loops`** — Named membership (for UI/ACLs).

### **Content**

* **`posts`, `comments`** — Both store:

  * `author_principal_id` (always),

  * (legacy) `author_id` for named back-compat,

  * **Anonymous proof fields** when `is_anon=true`:

    * `anon_cert`, `anon_cert_kid`, `anon_sig`, `anon_company_id`,

    * `anon_profile_id` *or* `anon_ephemeral_pubkey`.

* **`media_assets`**, **`post_media`**, **`message_media`** — For anon content, **keep `owner_id` NULL** to avoid back-linking.

### **Social Graph (principal-based)**

* **`post_likes`, `comment_likes`** — `liker_principal_id` (user or anon).

* **`principal_follows`** — principal↔principal follows (anon can follow/be followed).

* **`principal_blocks`** — principal↔principal blocks.

* **`principal_saved_posts`** — bookmarks/saves by principal.

### **Messaging (users only)**

* **`conversations`, `conversation_participants`, `messages`, `message_reads`** — DM/group chats restricted to **users** (anon cannot DM).

### **Verification & Access**

* **`verifications`** — Legacy per-user verified flag \+ method.

* **`verification_scopes`** — **User** verified for `{kind, scope_id}`, with expiry and method.

* **`verification_scope_implications`** — Declarative auto-grants: `(from_kind,id) ⇒ (to_kind,id)`.

  * *No FKs* because targets span multiple tables; validate in code.

### **Anonymous Safety & Portability**

* **`anon_issuers`** — Public keys for the certificate issuer; rotation support.

* **`anon_revocations`** — **Ban** by persona pubkey or cert fingerprint. *No user FK.*

* **`anon_backup_blobs`** (optional) — Encrypted persona bundle by **blob\_id** (recovery code); stores only `salt` \+ `ciphertext`. *No user FK.*

* **`anon_enrollment_sanctions`** — **Blocks new anon certs** at `/anon/issue` for a named user **per community scope**. Does **not** map personas to users.

### **Settings & Analytics**

* **`principal_settings`** — Preferences/consents for named & anon.

* **`post_impressions`** — Raw view events (nullable viewer principal).

* **`post_metrics_daily`** — Aggregated daily metrics for dashboards.

### **Platform/Ops**

* **`reports`** — Trust & safety reports.

* **`devices`** — Mobile devices for push.

* **`idempotency_keys`** — **Do not** use on anon endpoints.

---

## **Privacy Guarantees in the Schema**

* **No user→persona mapping** is stored anywhere.

* Anonymous posts/comments have `author_id = NULL`; actor is `author_principal_id(kind=anon)`.

* Proof fields (`anon_*`) let the server validate membership **without** identity.

* **Media** for anon has `owner_id IS NULL` to prevent reverse lookups.

* **Revocations** and **sanctions** operate on persona keys or named users at **enrollment time**, never by linking personas to users.

---

## **Invariants (enforce in code)**

1. **Anon vs Named**

   * `is_anon=false` ⇒ `author_principal_id.kind = 'user'` AND all `anon_*` NULL.

   * `is_anon=true` ⇒ `author_principal_id.kind = 'anon'` AND  
      (`anon_profile_id` **OR** `anon_ephemeral_pubkey`) present AND  
      `anon_cert`, `anon_sig`, valid scope present.

2. **Company scope match**

   * If loop is in a `company` space, `posts.anon_company_id == spaces.company_id`.

3. **Media neutrality**

   * For anon content, `media_assets.owner_id IS NULL`.

4. **Sanctions at issuance only**

   * `/anon/issue` must deny issuance when an **active** `anon_enrollment_sanctions` row exists for `(user_id, scope_kind, scope_id)`.

---

## **Hot Paths & Example Queries**

**Feed (loop):**

 `SELECT p.*`  
`FROM posts p`  
`WHERE p.loop_id = $1`  
`ORDER BY p.created_at DESC, p.id DESC`  
`LIMIT $2;`

* 

**My Likes (current principal):**

 `SELECT p.*`  
`FROM post_likes pl`  
`JOIN posts p ON p.id = pl.post_id`  
`WHERE pl.liker_principal_id = $principal`  
`ORDER BY pl.created_at DESC`  
`LIMIT $n;`

* 

**My Saves (current principal):**

 `SELECT p.*`  
`FROM principal_saved_posts s`  
`JOIN posts p ON p.id = s.post_id`  
`WHERE s.saver_principal_id = $principal`  
`ORDER BY s.created_at DESC`  
`LIMIT $n;`

* 

**My Analytics (for posts by current principal):**

 `SELECT m.*`  
`FROM post_metrics_daily m`  
`JOIN posts p ON p.id = m.post_id`  
`WHERE p.author_principal_id = $principal`  
`ORDER BY m.day DESC`  
`LIMIT 60;`

* 

---

## **Tables Without FKs (on purpose)**

* **`anon_backup_blobs`** — no user FK (unlinkability).

* **`anon_revocations`** — revoke by persona/cert; no user FK.

* **`verification_scope_implications`** — polymorphic targets (`companies`/`spaces`) make FKs impractical; validate in code or with triggers.

---

## **Operational Notes**

* **Anonymous endpoints**

  * Don’t use `idempotency_keys`.

  * Redact logs (no `user_id`, IPs, device IDs, headers).

  * Do not log `anon_cert`, `anon_sig`, or `persona_pubkey` contents.

* **Issuance & rotation**

  * Maintain `anon_issuers` with `kid` and rotated keys.

  * Validate cert `not_before/not_after` on every anon write.

* **Aggregation**

  * Insert raw impressions batched/streamed; roll up to `post_metrics_daily`.

* **Migrations**

  * Prefer additive changes (NULLable, defaults), backfill, then tighten constraints.

---

## **Common “How do I…?”**

* **Let anons like/follow/save?**  
   Use `principals` \+ `post_likes`, `principal_follows`, `principal_saved_posts` (all by `principal_id`).

* **Stop a banned user from creating new anons?**

  * Revoke current persona: `anon_revocations` (by pubkey).

  * Block future anon issuance for that **named user & scope**: add row in `anon_enrollment_sanctions`. Checked only at `/anon/issue`.

* **Give Finance space access after company verification?**  
   Add an implication in `verification_scope_implications`, or write both scope rows on verification.

* **Sync anon across devices?**  
   Enable **Encrypted Backup** via `anon_backup_blobs` (client-side AES-GCM with Argon2id).  
   No user FK; restore with `blob_id + passphrase`.

---

## **Final Notes**

* The schema is designed so **anonymous activity is fully functional** (post/comment/like/follow/save) while keeping **DMs named-only** and never storing an identity link.

* Some tables (revocations, backups, implications) have **no FKs by design** for privacy or polymorphism; enforce integrity in the application layer.

* If you see a stray line like `Ref: "anon_enrollment_sanctions"."id" < "anon_issuers"."public_key"` from a diagrammer export, **remove it**—there is no such FK.


## DBML

`
Project looped {
  database_type : 'PostgreSQL'
}

Enum membership_role { 
member
admin
mod }

Enum membership_status { 
active
suspended
pending }

Enum loop_role { 
member
owner
mod }

Enum conversation_type { 
dm
group }

Enum verification_method { 
linkedin
email
hr
manual }

Enum report_status { 
OPEN
REVIEWING
RESOLVED
REJECTED }

Enum space_kind { 
company
sector
global }

Enum profile_visibility { 
public
private
hidden }

Enum profile_status { 
unclaimed
claimed
suspended }

Enum principal_kind { 
user
anon }

Enum verification_scope_kind {
company
sector
space
global
}

Enum sanction_status {
active
expired
}


Table companies {
  id uuid [pk]
  name text [not null]
  display_name text
  slug text [unique]
  domain text [unique]
  created_at timestamptz [default: `now()`]

  Indexes { (slug) [unique]
  (domain) [unique] }
}

Table company_profiles {
  company_id uuid [pk, ref: > companies.id]
  bio text
  location text
  website text
  logo_asset_id uuid [ref: > media_assets.id]
  banner_asset_id uuid [ref: > media_assets.id]
  twitter text
  linkedin text
  github text
  visibility profile_visibility [default: 'public']
  status profile_status [default: 'unclaimed']
  created_by uuid [ref: > users.id]
  claimed_at timestamptz
  updated_at timestamptz [default: `now()`]

  Indexes { (visibility)
  (status) }
}

Table users {
  id uuid [pk]
  firebase_uid text [unique]
  handle text [unique]          // store lowercase app-side
  first_name text [not null]
  last_name text [not null]
  date_of_birth date [not null]
  display_name text
  bio text
  company_id uuid [ref: > companies.id]   // optional convenience pointer
  created_at timestamptz [default: `now()`]
  updated_at timestamptz [default: `now()`]

  Indexes { (company_id) }
}

// NEW — “actor” abstraction for user OR anon persona
Table principals {
  id uuid [pk]
  kind principal_kind [not null]              // 'user' or 'anon'
  user_id uuid [ref: > users.id]
  anon_profile_id uuid [ref: > anonymous_profiles.id]
  created_at timestamptz [default: `now()`]

  Indexes { 
  (kind)
  (user_id)
  (anon_profile_id) }

  Note: 'Invariant: (kind=user) -> user_id NOT NULL AND anon_profile_id NULL; (kind=anon) -> anon_profile_id NOT NULL AND user_id NULL.'
}

// Spaces (company/sector/global containers)
Table spaces {
  id uuid [pk]
  kind space_kind [not null]
  company_id uuid [ref: > companies.id]       // only when kind='company'
  slug text
  name text
  created_at timestamptz [default: `now()`]

  Indexes { (kind, slug) [unique]
  (company_id) }

  Note: 'For kind=company, company_id must be non-null.'
}

// MEMBERSHIP
Table user_companies {
  user_id uuid [not null, ref: > users.id]
  company_id uuid [not null, ref: > companies.id]
  role membership_role [default: 'member']
  status membership_status [default: 'active']
  joined_at timestamptz [default: `now()`]

  Indexes { (user_id, company_id) [pk]
  (company_id) }
}

// LOOPS & POSTS
Table loops {
  id uuid [pk]
  space_id uuid [not null, ref: > spaces.id]
  slug text [not null]
  name text [not null]
  is_private boolean [default: false]
  created_by uuid [ref: > users.id]
  created_at timestamptz [default: `now()`]

  Indexes { (space_id, slug) [unique] }
}

Table user_loops {
  user_id uuid [not null, ref: > users.id]
  loop_id uuid [not null, ref: > loops.id]
  role loop_role [default: 'member']
  joined_at timestamptz [default: `now()`]

  Indexes { (user_id, loop_id) [pk]
  (loop_id) }
}

// CHANGED — add author_principal_id; keep anon proof; keep author_id for compat (nullable)
Table posts {
  id uuid [pk]
  // preferred author identifier:
  author_principal_id uuid [not null, ref: > principals.id]   // NEW
  // legacy (named only), keep nullable while migrating:
  author_id uuid [ref: > users.id]
  // anon persona reference (optional; if one-off, use anon_ephemeral_pubkey)
  anon_profile_id uuid [ref: > anonymous_profiles.id]

  loop_id uuid [not null, ref: > loops.id]
  content text [not null]

  // Anonymous verification material
  is_anon boolean [default: false]
  anon_company_id uuid [ref: > companies.id]
  anon_cert bytea
  anon_cert_kid text
  anon_sig bytea
  anon_ephemeral_pubkey bytea

  likes_count int [default: 0]
  comments_count int [default: 0]
  created_at timestamptz [default: `now()`]
  updated_at timestamptz [default: `now()`]
  deleted_at timestamptz

  Indexes {
    (loop_id, created_at, id)
    (is_anon, anon_company_id)
    (anon_cert_kid)
    (author_principal_id, created_at)
  }

  Note: 'App invariants:- is_anon=false ⇒ author_id NOT NULL OR author_principal_id(kind=user); anon_* NULL - is_anon=true  ⇒ author_id NULL; author_principal_id(kind=anon); (anon_profile_id OR anon_ephemeral_pubkey) NOT NULL; anon_cert+anon_sig+anon_company_id present.  - Company scope: if loop.space.kind = company -> anon_company_id = spaces.company_id.'
}

// CHANGED — add author_principal_id; keep anon proof
Table comments {
  id uuid [pk]
  post_id uuid [not null, ref: > posts.id]

  author_principal_id uuid [not null, ref: > principals.id]    // NEW
  author_id uuid [ref: > users.id]                              // legacy (nullable during migration)
  anon_profile_id uuid [ref: > anonymous_profiles.id]
  is_anon boolean [default: false]
  anon_company_id uuid [ref: > companies.id]
  anon_cert bytea
  anon_cert_kid text
  anon_sig bytea
  anon_ephemeral_pubkey bytea

  parent_comment_id uuid [ref: > comments.id]
  content text [not null]
  media_asset_id uuid [ref: > media_assets.id]
  likes_count int [default: 0]
  created_at timestamptz [default: `now()`]
  updated_at timestamptz [default: `now()`]
  deleted_at timestamptz

  Indexes {
    (post_id, created_at)
    (parent_comment_id)
    (is_anon, anon_company_id)
    (author_principal_id, created_at)
  }
}

// CHANGED — like by principal (user or anon)
Table post_likes {
  liker_principal_id uuid [not null, ref: > principals.id]   // CHANGED
  post_id uuid [not null, ref: > posts.id]
  created_at timestamptz [default: `now()`]

  Indexes { (liker_principal_id, post_id) [pk]
   (post_id) }
}

// CHANGED — like by principal (user or anon)
Table comment_likes {
  liker_principal_id uuid [not null, ref: > principals.id]   // CHANGED
  comment_id uuid [not null, ref: > comments.id]
  created_at timestamptz [default: `now()`]

  Indexes { (liker_principal_id, comment_id) [pk]
   (comment_id) }
}

// NEW — follows (user↔user, anon↔user, anon↔anon)
Table principal_follows {
  follower_principal_id uuid [not null, ref: > principals.id]
  followee_principal_id uuid [not null, ref: > principals.id]
  created_at timestamptz [default: `now()`]

  Indexes { (follower_principal_id, followee_principal_id) [pk]
   (followee_principal_id) }
}

// NEW — blocks
Table principal_blocks {
  blocker_principal_id uuid [not null, ref: > principals.id]
  blocked_principal_id uuid [not null, ref: > principals.id]
  created_at timestamptz [default: `now()`]

  Indexes { (blocker_principal_id, blocked_principal_id) [pk]
  (blocked_principal_id) }
}

// MEDIA (unchanged aside from owner nullability)
Table media_assets {
  id uuid [pk]
  owner_id uuid [ref: > users.id]               // NULL when attached to anon content
  s3_key text [not null]
  mime_type text
  width int
  height int
  duration_seconds int
  created_at timestamptz [default: `now()`]

  Indexes { (owner_id, created_at) }
  Note: 'For anonymous uploads, keep owner_id NULL (or service principal) to avoid media→user linkage.'
}

// MESSAGING — keep users-only (anon cannot DM)
Table conversations {
  id uuid [pk]
  type conversation_type [not null]
  created_by uuid [ref: > users.id]
  title text
  created_at timestamptz [default: `now()`]
}

Table conversation_participants {
  conversation_id uuid [not null, ref: > conversations.id]
  user_id uuid [not null, ref: > users.id]
  role membership_role [default: 'member']
  joined_at timestamptz [default: `now()`]
  last_read_message_id uuid

  Indexes { (conversation_id, user_id) [pk]
  (user_id) }
}

Table messages {
  id uuid [pk]
  conversation_id uuid [not null, ref: > conversations.id]
  sender_id uuid [not null, ref: > users.id]
  content text
  created_at timestamptz [default: `now()`]
  edited_at timestamptz
  deleted_at timestamptz

  Indexes { (conversation_id, created_at, id) }
}

Table message_reads {
  message_id uuid [not null, ref: > messages.id]
  user_id uuid [not null, ref: > users.id]
  read_at timestamptz [default: `now()`]

  Indexes { (message_id, user_id) [pk]
  (user_id, message_id) }
}

// ————————————————————————————————————————————————
// TRUST, VERIFICATION, SETTINGS, ANALYTICS
// ————————————————————————————————————————————————

Table verifications {
  user_id uuid [pk, ref: > users.id]
  method verification_method [not null]
  verified boolean [not null]
  verified_at timestamptz
  details jsonb
  updated_at timestamptz [default: `now()`]
}

// NEW — multi-scope verification records (company/sector/space/global)
Table verification_scopes {
  id uuid [pk]
  user_id uuid [not null, ref: > users.id]
  scope_kind verification_scope_kind [not null]
  scope_id uuid                                 // companies.id OR spaces.id (for sector/company/global rows)
  method verification_method [not null]
  verified boolean [not null]
  verified_at timestamptz
  expires_at timestamptz
  details jsonb
  updated_at timestamptz [default: `now()`]

  Indexes { (user_id, scope_kind, scope_id) }
  Note: 'For scope_kind=company -> scope_id=companies.id; for sector/global/company spaces -> scope_id=spaces.id.'
}

// NEW — declare automatic grants (e.g., verify company ⇒ auto grant sector Finance)
Table verification_scope_implications {
  id uuid [pk]
  from_scope_kind verification_scope_kind [not null]
  from_scope_id uuid [not null]
  to_scope_kind verification_scope_kind [not null]
  to_scope_id uuid [not null]

  Indexes { (from_scope_kind, from_scope_id)
  (to_scope_kind, to_scope_id) }
  Note: 'E.g., (company, JPM.id) -> (space, FinanceSpace.id). Backend creates derived entries on verification.'
}

// NEW — settings per principal (user or anon)
Table principal_settings {
  principal_id uuid [pk, ref: > principals.id]
  ui jsonb                  // theme, font size (optional to sync)
  notifications jsonb       // per-channel prefs (app-side validated)
  privacy jsonb             // safety toggles
  tos_accepted_at timestamptz
  privacy_accepted_at timestamptz
  updated_at timestamptz [default: `now()`]
}

// NEW — saves / bookmarks
Table principal_saved_posts {
  saver_principal_id uuid [not null, ref: > principals.id]
  post_id uuid [not null, ref: > posts.id]
  created_at timestamptz [default: `now()`]

  Indexes { (saver_principal_id, post_id) [pk]
  (post_id) }
}

// NEW — analytics (raw impressions)
Table post_impressions {
  post_id uuid [not null, ref: > posts.id]
  viewer_principal_id uuid [ref: > principals.id]  // NULL if not logged in
  viewed_at timestamptz [default: `now()`]

  Indexes { (post_id, viewed_at)
  (viewer_principal_id, viewed_at) }
}

// NEW — analytics (daily aggregates)
Table post_metrics_daily {
  post_id uuid [not null, ref: > posts.id]
  day date [not null]
  views int [default: 0]
  unique_viewers int [default: 0]
  likes int [default: 0]
  comments int [default: 0]

  Indexes { (post_id, day) [pk] }
}

// REPORTING / DEVICES / IDEMPOTENCY (unchanged; remember: no idempotency on anon endpoints)
Table reports {
  id uuid [pk]
  target_type text [not null]
  target_id uuid [not null]
  reporter_id uuid [ref: > users.id]
  reason text
  status report_status [default: 'OPEN']
  created_at timestamptz [default: `now()`]
  updated_at timestamptz [default: `now()`]

  Indexes { (status, created_at) }
}

Table devices {
  id uuid [pk]
  user_id uuid [not null, ref: > users.id]
  apns_token text [not null]
  platform text [default: 'ios']
  created_at timestamptz [default: `now()`]

  Indexes { (user_id, apns_token) [unique] }
}

Table idempotency_keys {
  id uuid [pk]
  user_id uuid [not null, ref: > users.id]
  endpoint text [not null]
  idempotency_key text [not null]
  request_hash text
  response_code int
  created_at timestamptz [default: `now()`]

  Indexes { (user_id, endpoint, idempotency_key) [unique] }
}

// MEDIA RELNS
Table post_media {
  id uuid [pk]
  post_id uuid [not null, ref: > posts.id]
  media_asset_id uuid [not null, ref: > media_assets.id]
  position int [default: 0]
  created_at timestamptz [default: `now()`]

  Indexes { (post_id, position) [unique]
  (post_id, media_asset_id) [unique] }
}

Table message_media {
  id uuid [pk]
  message_id uuid [not null, ref: > messages.id]
  media_asset_id uuid [not null, ref: > media_assets.id]
  position int [default: 0]
  created_at timestamptz [default: `now()`]

  Indexes { (message_id, position) [unique]
  (message_id, media_asset_id) [unique] }
}

// ANONYMOUS PERSONAS & CERTS (unchanged)
Table anonymous_profiles {
  id uuid [pk]
  company_id uuid [not null, ref: > companies.id]
  public_key bytea [unique, not null]
  handle text
  created_at timestamptz [default: `now()`]

  Indexes { (company_id, handle) [unique]
  (company_id) }
  Note: 'No FK to users. Keys generated & stored client-side.'
}

Table anon_issuers {
  id uuid [pk]
  kid text [unique, not null]
  alg text [not null]
  public_key bytea [not null]
  created_at timestamptz [default: `now()`]
  rotated_at timestamptz
}

Table anon_revocations {
  id uuid [pk]
  persona_pubkey bytea
  cert_fingerprint bytea
  reason text
  created_at timestamptz [default: `now()`]

  Indexes { (persona_pubkey)
  (cert_fingerprint) }
  Note: 'Never store user_id here.'
}

// OPTIONAL — Encrypted persona backup blobs (no identity linkage)
Table anon_backup_blobs {
  blob_id uuid [pk]                             // shareable "Recovery Code" (non-secret)
  salt bytea [not null]                         // for Argon2id
  ciphertext bytea [not null]                   // AES-GCM; contains persona_priv/pub/cert
  created_at timestamptz [default: `now()`]
  expires_at timestamptz

  Note: 'No user_id stored. Restore requires blob_id + user passphrase.'
}



Table anon_enrollment_sanctions {         // NEW — blocks new anon certs at /anon/issue
  id uuid [pk]
  user_id uuid [not null, ref: > users.id]                // named account being sanctioned
  scope_kind verification_scope_kind [not null]           // company | sector | space | global
  scope_id uuid                                           // companies.id or spaces.id depending on kind (nullable if global)
  status sanction_status [default: 'active']
  reason text
  imposed_by uuid [ref: > users.id]                       // moderator/admin who imposed it (optional)
  imposed_at timestamptz [default: `now()`]
  expires_at timestamptz                                  // null = indefinite

  Indexes {
    (user_id, scope_kind, scope_id, status)
  }

  Note: 'Evaluated ONLY at /anon/issue. Does not create any user↔persona mapping.'
}


Ref: "anon_enrollment_sanctions"."id" < "anon_issuers"."public_key"
`

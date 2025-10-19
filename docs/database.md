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

* **`anon_enrollment_sanctions`** — **Blocks new anon certs** at `/anon/enroll` for a named user **per scope** (company/sector/space/global). Does **not** map personas to users.

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

4. **Sanctions at enrollment only**

   * `/anon/enroll` must deny issuance when an **active** `anon_enrollment_sanctions` row exists for `(user_id, scope_kind, scope_id)`.

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

  * Block future anon issuance for that **named user & scope**: add row in `anon_enrollment_sanctions`. Checked only at `/anon/enroll`.

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
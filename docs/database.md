# Info on the database

## DBML code

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
Enum loop_role { member
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


Table companies {
  id uuid [pk]
  name text [not null]
  domain text [unique]                // consider lowercasing in app/migration
  created_at timestamptz [default: `now()`]
}

Table users {
  id uuid [pk]
  cognito_sub text [unique]           // OIDC subject from Cognito
  handle text [unique]                // global pseudonymous handle (case-insensitive in app/migration)
  display_name text
  bio text
  // MVP convenience pointer; do NOT use for auth. Use user_companies instead.
  company_id uuid [ref: > companies.id]   // optional
  created_at timestamptz [default: `now()`]
  updated_at timestamptz [default: `now()`]

  Indexes {
    (company_id)
  }
}

// General container: company/sector/global
Table spaces {
  id uuid [pk]
  kind space_kind [not null]               // company | sector | global
  company_id uuid [ref: > companies.id]    // only when kind='company'
  slug text                                // e.g., 'finance', 'all'
  name text
  created_at timestamptz [default: `now()`]

  Indexes {
    (kind, slug) [unique]                  // unique slugs within kind (sector/global)
    (company_id)
  }

  Note: 'For kind=company, company_id must be non-null; enforce via app or CHECK in migrations.'
}

//////////////////////////////////////////////////////
// MEMBERSHIP
//////////////////////////////////////////////////////

Table user_companies {                       // many-to-many: users ↔ companies
  user_id uuid [not null, ref: > users.id]
  company_id uuid [not null, ref: > companies.id]
  role membership_role [default: 'member']
  status membership_status [default: 'active']
  joined_at timestamptz [default: `now()`]

  Indexes {
    (user_id, company_id) [pk]
    (company_id)
  }
}

//////////////////////////////////////////////////////
// LOOPS & POSTS
//////////////////////////////////////////////////////

Table loops {
  id uuid [pk]
  space_id uuid [not null, ref: > spaces.id]
  slug text [not null]                      // e.g., 'interns', 'finance'
  name text [not null]                      // human label
  is_private boolean [default: false]
  created_by uuid [ref: > users.id]
  created_at timestamptz [default: `now()`]

  Indexes {
    (space_id, slug) [unique]               // unique per space
  }

  Note: 'Decide ON DELETE for space: cascade loops or forbid; typically forbid deleting spaces in prod.'
}

Table user_loops {                           // many-to-many: users ↔ loops
  user_id uuid [not null, ref: > users.id]
  loop_id uuid [not null, ref: > loops.id]
  role loop_role [default: 'member']         // member|owner|mod
  joined_at timestamptz [default: `now()`]

  Indexes {
    (user_id, loop_id) [pk]
    (loop_id)
  }

  Note: 'If a loop is deleted, ON DELETE CASCADE is reasonable here.'
}

Table posts {
  id uuid [pk]
  author_id uuid [not null, ref: > users.id]
  loop_id uuid [not null, ref: > loops.id]   // posts live in loops; loop implies space
  content text [not null]
  media_asset_id uuid [ref: > media_assets.id]
  likes_count int [default: 0]               // cached aggregate
  comments_count int [default: 0]            // cached aggregate
  created_at timestamptz [default: `now()`]
  updated_at timestamptz [default: `now()`]
  deleted_at timestamptz

  Indexes {
    (loop_id, created_at, id)                // keyset pagination for loop feed
  }

  Note: 'For company feeds, join loop→space(kind=company) or denormalize if profiling demands.'
}

Table comments {
  id uuid [pk]
  post_id uuid [not null, ref: > posts.id]
  author_id uuid [not null, ref: > users.id]
  parent_comment_id uuid [ref: > comments.id] // null for top-level; supports threads
  content text [not null]
  media_asset_id uuid [ref: > media_assets.id]
  likes_count int [default: 0]
  created_at timestamptz [default: `now()`]
  updated_at timestamptz [default: `now()`]
  deleted_at timestamptz

  Indexes {
    (post_id, created_at)
    (parent_comment_id)
  }

  Note: 'Ensure parent belongs to same post in app/trigger; soft delete recommended.'
}

Table post_likes {
  user_id uuid [not null, ref: > users.id]
  post_id uuid [not null, ref: > posts.id]
  created_at timestamptz [default: `now()`]

  Indexes {
    (user_id, post_id) [pk]                  // toggle-able like
    (post_id)
  }
}

Table comment_likes {
  user_id uuid [not null, ref: > users.id]
  comment_id uuid [not null, ref: > comments.id]
  created_at timestamptz [default: `now()`]

  Indexes {
    (user_id, comment_id) [pk]               // toggle-able like
    (comment_id)
  }
}

//////////////////////////////////////////////////////
// MEDIA
//////////////////////////////////////////////////////

Table media_assets {
  id uuid [pk]
  owner_id uuid [not null, ref: > users.id]
  s3_key text [not null]                     // e.g., space/{id}/posts/{uuid}.jpg
  mime_type text
  width int
  height int
  duration_seconds int
  created_at timestamptz [default: `now()`]

  Indexes {
    (owner_id, created_at)
  }
}

//////////////////////////////////////////////////////
// SOCIAL GRAPH
//////////////////////////////////////////////////////

Table user_follows {
  follower_id uuid [not null, ref: > users.id]   // the one who follows
  followee_id uuid [not null, ref: > users.id]   // the one being followed
  created_at timestamptz [default: `now()`]

  Indexes {
    (follower_id, followee_id) [pk]
    (followee_id)                                // "who follows this user?"
  }
}

//////////////////////////////////////////////////////
// MESSAGING
//////////////////////////////////////////////////////

Table conversations {
  id uuid [pk]
  type conversation_type [not null]              // dm|group
  created_by uuid [ref: > users.id]
  title text                                     // for groups
  created_at timestamptz [default: `now()`]
}

Table conversation_participants {
  conversation_id uuid [not null, ref: > conversations.id]
  user_id uuid [not null, ref: > users.id]
  role membership_role [default: 'member']       // member|admin (for groups)
  joined_at timestamptz [default: `now()`]
  last_read_message_id uuid                      // optional; if FK in SQL, use ON DELETE SET NULL

  Indexes {
    (conversation_id, user_id) [pk]
    (user_id)
  }
}

Table messages {
  id uuid [pk]
  conversation_id uuid [not null, ref: > conversations.id]
  sender_id uuid [not null, ref: > users.id]
  content text                                    // nullable if only media
  media_asset_id uuid [ref: > media_assets.id]
  created_at timestamptz [default: `now()`]
  edited_at timestamptz
  deleted_at timestamptz

  Indexes {
    (conversation_id, created_at, id)            // keyset pagination
  }
}

Table message_reads {                             // per-user read receipts
  message_id uuid [not null, ref: > messages.id]
  user_id uuid [not null, ref: > users.id]
  read_at timestamptz [default: `now()`]

  Indexes {
    (message_id, user_id) [pk]
    (user_id, message_id)
  }
}

//////////////////////////////////////////////////////
// TRUST & SAFETY / PLATFORM
//////////////////////////////////////////////////////

Table verifications {
  user_id uuid [pk, ref: > users.id]
  method verification_method [not null]         // linkedin|email|hr|manual
  verified boolean [not null]
  verified_at timestamptz
  details jsonb                                 // optional non-PII audit crumbs
  updated_at timestamptz [default: `now()`]
}

Table reports {
  id uuid [pk]
  target_type text [not null]                   // post|user|comment|message
  target_id uuid [not null]                     // validated app-side
  reporter_id uuid [ref: > users.id]
  reason text
  status report_status [default: 'OPEN']        // OPEN|REVIEWING|RESOLVED|REJECTED
  created_at timestamptz [default: `now()`]
  updated_at timestamptz [default: `now()`]

  Indexes {
    (status, created_at)
  }
}

Table devices {
  id uuid [pk]
  user_id uuid [not null, ref: > users.id]
  apns_token text [not null]
  platform text [default: 'ios']               // ios|android (later)
  created_at timestamptz [default: `now()`]

  Indexes {
    (user_id, apns_token) [unique]
  }
}

Table idempotency_keys {
  id uuid [pk]
  user_id uuid [not null, ref: > users.id]
  endpoint text [not null]                     // API path
  idempotency_key text [not null]
  request_hash text
  response_code int
  created_at timestamptz [default: `now()`]

  Indexes {
    (user_id, endpoint, idempotency_key) [unique]
  }
}
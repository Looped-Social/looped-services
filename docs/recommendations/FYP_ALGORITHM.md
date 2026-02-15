# For You Feed (FYP) Algorithm (Proposal)

Looped's feed is not "generic social". The defining constraint is community-gated interaction:

- Anyone can view any post.
- To `like`, `comment`, `like comments`, or `post` in a community: the viewer must be verified (or joined, for certain
  specialization communities).

This document proposes an FYP architecture that keeps the feed mostly *actionable* (interactable) while still
sprinkling in discovery/viral content.

## Goals

- Keep the majority of FYP items interactable (when supply exists).
- Work well with near-zero training data (10 users, small communities).
- Scale to 1M+ and 100M+ users by evolving the same architecture:
  - deterministic heuristic baseline now
  - ML-assisted candidate generation later (AWS-first)
  - eventual fan-out/read-model later (feed-worker)
- Resist gaming:
  - shares should be a *minimal* signal (share spam is easy)
  - reposts should be a *small* signal (can come from outside community)
  - likes/comments should dominate
- Preserve Looped trust:
  - blocks, bans, quarantines, moderation outcomes must override ranking
  - no PII in logs; instrumentation must be privacy-safe

## Non-Goals (for now)

- "Perfect" personalization with deep models.
- Real-time online learning.
- WebSockets-driven realtime feed updates.

## Current State (As Of 2026-02-15)

`GET /v1/feed?mode=for_you` is implemented as **FYP v2**:

- Two-pool retrieval:
  - **eligible**: posts from communities the viewer is verified in (non-specialization) or joined (major/field specializations)
  - **discovery**: globally popular posts excluding the eligible set (sprinkle)
- Mixing:
  - default pattern `3 eligible : 1 discovery` (75% eligible) with a supply-aware minimum eligible fraction (default: 50%).
- Ranking (Phase 0 heuristic):
  - saturated engagement (`log1p`) + exponential freshness decay
  - likes/comments dominate; repost is small; shares are minimal
- Pagination:
  - multi-pool cursor `fyp2.*` for the global For You feed
  - community-filtered For You uses a single-pool rank cursor (`RankPagination`)

This baseline works with near-zero training data and keeps Looped's "interactable majority" constraint in control while
still supporting viral discovery.

## Definitions

- `Interactable post`: viewer can like/comment/reply/vote (see `viewerCapabilities.canInteract`).
- `Eligible community`: a community where the viewer can interact:
  - non-specialization communities: verified membership active (not expired)
  - specialization communities (major/field): joined membership required
- `Discovery post`: a post from a community the viewer cannot currently interact with.
  - These are still valuable for curiosity, virality, and join/verify conversion, but should not dominate the feed.

## Architecture Overview (Two-Stage: Retrieve -> Mix/Rank)

1. **Candidate Retrieval (cheap)**
   - Pull candidates from multiple pools with different intent.
2. **Mixing + Ranking (policy + scoring)**
   - Enforce an interactable-majority constraint.
   - Rank within each pool with a spam-resistant score.
   - Merge pools into one page with diversity constraints.

This architecture works with heuristics today and can gradually incorporate ML later.

## Candidate Pools

### Pool A: Eligible (Primary)

Purpose: the feed is mostly content the viewer can act on.

Retrieval:

- Posts from `eligible_community_ids(viewer)` within a time window (e.g., 14-30 days, tuned by supply).
- Pull extra "fresh" content inside eligible communities so new posts are not starved.

### Pool B: Discovery (Sprinkle)

Purpose: serendipity and "what else is happening" across Looped.

Retrieval:

- Trending/popular posts globally (or by viewer company) within a shorter window (e.g., 24h-7d).
- Hard filters still apply (removed/quarantined, blocks, community bans, etc.).

Important: discovery posts are often *non-interactable* for the viewer. Measure whether discovery drives positive
outcomes (open, follow, join/verify conversion) rather than only passive scrolling.

### Pool C: Exploration (Fresh/Low-Exposure)

Purpose: prevent "rich get richer" and help small communities.

Retrieval options (choose one per phase):

- Phase 0 (no impressions yet): newest posts from eligible communities, lightly boosted.
- Phase 1 (with impressions): posts with low impressions but acceptable early engagement.

## Mixing Policy (Interactable Majority, Supply-Aware)

Target composition per page (starting point):

- `eligible`: 70%
- `discovery`: 20%
- `exploration`: 10%

Hard rule (supply-aware):

- When eligible supply exists: `eligible_share >= 50%`.
- If eligible supply is insufficient, backfill from discovery/exploration.

Anti-pattern to avoid: long streaks of non-interactable posts. Enforce a run-length cap such as:

- no more than 2 discovery posts in a row unless eligible supply is exhausted

## Ranking (Heuristic Baseline -> Rate-Based -> ML-Assist)

### Phase 0: Count-Based Heuristic (Works With 10 Users)

Rank within pools using a time-decayed, saturation-based score.

Recommended baseline weights:

- likes: strong
- comments: strong
- reposts: small
- shares: minimal

Example scoring (illustrative):

```
engagement =
  2.0 * log1p(likes_count) +
  1.5 * log1p(comments_count) +
  0.25 * log1p(repost_count) +
  0.10 * log1p(share_count)

freshness = exp(-age_hours / half_life_hours)

score = engagement * freshness
```

Notes:

- Use `log1p` (or caps) so a single viral post does not dominate indefinitely.
- Set `half_life_hours` per pool (eligible can be slower, discovery faster).

### Phase 1: Rate-Based Ranking (Requires Impressions Telemetry)

Counts are biased by exposure. Once we log impressions, shift to rates:

- `like_rate = likes / impressions`
- `comment_rate = comments / impressions`
- `save_rate = saves / impressions` (if/when saves are aggregated cheaply)

Then rank by a blended objective that still decays with age.

### Phase 2: ML-Assisted Candidate Generation (AWS-first)

When we have enough interaction and impression data, use ML to propose candidates for Pool B (discovery) and/or
personalize ordering within Pool A, then apply Looped constraints as a final re-rank step:

- Filter out removed/quarantined content.
- Enforce blocks/bans.
- Enforce mixing policy (interactable-majority).
- Enforce diversity caps.

This avoids "ML as the product" and keeps Looped's constraints in control.

## Guardrails and Anti-Gaming

### Shares

Shares are easy to spam and the current `share_count` increments per call.

Policy:

- In ranking, treat `share_count` as minimal impact.
- Long-term: shift from raw `share_count` to "unique sharers" and/or "shares from trusted cohorts".

### Reposts

Reposts have a uniqueness constraint per reposter, but can happen from outside the community gate.

Policy:

- Use reposts as a small boost, not a primary driver.
- Prefer reposts from followed users (already shown as a banner) as a relevance feature rather than raw repost volume.

### Comments

Comments can be spammy too; prefer stronger variants as data becomes available:

- unique commenters
- comment depth (replies)
- author participation

### Diversity

Enforce at merge time:

- cap items per author per page (e.g., <= 2)
- optional cap per community per page (tunable)

## Product Metrics (What "Good" Means)

Feed quality should be measured with both engagement and "actionability":

- Interactable share: fraction of items where `viewerCapabilities.canInteract = true`
- Like rate: likes / impressions
- Comment rate: comments / impressions
- Save rate (when available)
- "Join/verify conversion": discovery exposure -> join/verify within 7 days
- Negative feedback rate (when added): hides, reports, blocks

## Implementation Plan (Backend-Only First)

1. **Telemetry first**: log impressions, opens, comment opens, and video watch (see `TELEMETRY_API.md`).
2. **Enforce mixing**: split FYP into eligible/discovery/exploration pools and merge per policy.
3. **Improve scoring**: downweight shares; add saturation and stronger time decay.
4. **Add diversity caps** at merge time.
5. **Introduce ML** (AWS Personalize or custom) only after enough data exists; use ML as candidate generation or a
   feature in re-ranking, not as the sole authority.

## Proposed Debug Metadata (Optional, Recommended)

For observability and faster iteration, add opaque metadata fields to the feed response (and include them in telemetry):

- `feed_request_id` (UUID) per page response
- `algorithm` (string) and `algorithm_version`
- `items[].rank` (int) and `items[].source_pool` (`eligible|discovery|exploration`)

This is not user-visible but makes the system debuggable.

## References

- TikTok/ByteDance Monolith (online learning architecture): https://arxiv.org/pdf/2209.07663
- YouTube recommendation system (candidate generation + ranking): https://research.google/pubs/pub45530/

package com.looped.posts;

import com.looped.communities.CommunitiesRepository;
import com.looped.communities.CommunityVerificationsRepository;
import com.looped.communities.SpecializationJoinsRepository;
import com.looped.principals.PrincipalRepository;
import com.looped.shared.Pagination;
import com.looped.shared.RankPagination;
import com.looped.users.FollowsRepository;
import com.looped.users.UserCommunityBanRepository;
import com.looped.users.UserRepository;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.looped.communities.CommunityVisibilityRules.isUserVisible;

@Service
public class FeedService {
    private final PostRepository posts;
    private final UserRepository users;
    private final PrincipalRepository principals;
    private final CommunitiesRepository communities;
    private final CommunityVerificationsRepository communityVerifications;
    private final SpecializationJoinsRepository specializationJoins;
    private final PostStateService postState;
    private final UserCommunityBanRepository communityBans;
    private final FollowsRepository follows;
    private final FypProperties fyp;
    private final TrendingProperties trending;

    public FeedService(PostRepository posts, UserRepository users, PrincipalRepository principals,
                       CommunitiesRepository communities,
                       CommunityVerificationsRepository communityVerifications,
                       SpecializationJoinsRepository specializationJoins,
                       PostStateService postState,
                       UserCommunityBanRepository communityBans,
                       FollowsRepository follows,
                       FypProperties fyp,
                       TrendingProperties trending) {
        this.posts = posts;
        this.users = users;
        this.principals = principals;
        this.communities = communities;
        this.communityVerifications = communityVerifications;
        this.specializationJoins = specializationJoins;
        this.postState = postState;
        this.communityBans = communityBans;
        this.follows = follows;
        this.fyp = fyp;
        this.trending = trending;
    }

    public FeedResult feed(String firebaseUid, String cursor, int limit, Long communityId, String mode) {
        var u = users.findByFirebaseUid(firebaseUid);
        if (u.isEmpty()) {
            return FeedResult.userNotProvisioned();
        }
        if (communityId != null) {
            var community = communities.findById(communityId);
            if (community.isEmpty() || !isUserVisible(community.get().kind, community.get().specializationType)) {
                return FeedResult.communityNotFound();
            }
        }
        Mode resolved = Mode.from(mode);
        var principal = principals.createForUser(u.get().id);
        long viewerUserId = u.get().id;
        long viewerPrincipalId = principal.id;
        if (communityId != null && communityBans.isBanned(viewerUserId, communityId)) {
            return FeedResult.communityBanned();
        }
        boolean hideAnonymousPosts = u.get().hideAnonymousPosts;
        FeedResult result = resolved == Mode.NEW
                ? feedNew(cursor, limit, communityId, viewerUserId, viewerPrincipalId, hideAnonymousPosts)
                : resolved == Mode.FOLLOWING
                ? feedFollowing(cursor, limit, communityId, viewerUserId, viewerPrincipalId, hideAnonymousPosts)
                : feedForYou(cursor, limit, communityId, viewerUserId, viewerPrincipalId, hideAnonymousPosts);
        postState.applyForPrincipal(principal.id, result.items());
        return result;
    }

    public FeedResult hashtagged(String firebaseUid, String cursor, int limit, long communityId) {
        var u = users.findByFirebaseUid(firebaseUid);
        if (u.isEmpty()) {
            return FeedResult.userNotProvisioned();
        }
        var community = communities.findById(communityId);
        if (community.isEmpty() || !isUserVisible(community.get().kind, community.get().specializationType)) {
            return FeedResult.communityNotFound();
        }
        if (communityBans.isBanned(u.get().id, communityId)) {
            return FeedResult.communityBanned();
        }
        var principal = principals.createForUser(u.get().id);
        long viewerUserId = u.get().id;
        long viewerPrincipalId = principal.id;
        boolean hideAnonymousPosts = u.get().hideAnonymousPosts;

        OffsetDateTime cTs = null;
        Long cId = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                var c = Pagination.decode(cursor);
                cTs = c.timestamp();
                cId = c.id();
            } catch (IllegalArgumentException ignored) {
                // treat as no cursor
            }
        }
        var list = posts.findNewHashtagged(communityId, cTs, cId, limit, viewerUserId, viewerPrincipalId, hideAnonymousPosts);
        String next = null;
        if (list.size() == limit) {
            var last = list.get(list.size() - 1);
            next = Pagination.encode(last.createdAt, last.id);
        }
        postState.applyForPrincipal(principal.id, list);
        return FeedResult.ok(list, next);
    }

    private FeedResult feedNew(String cursor, int limit, Long communityId, long viewerUserId, long viewerPrincipalId, boolean hideAnonymousPosts) {
        OffsetDateTime cTs = null;
        Long cId = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                var c = Pagination.decode(cursor);
                cTs = c.timestamp();
                cId = c.id();
            } catch (IllegalArgumentException ignored) {
                // treat as no cursor
            }
        }
        var list = posts.findNew(communityId, cTs, cId, limit, viewerUserId, viewerPrincipalId, hideAnonymousPosts);
        String next = null;
        if (list.size() == limit) {
            var last = list.get(list.size() - 1);
            next = Pagination.encode(last.createdAt, last.id);
        }
        return FeedResult.ok(list, next);
    }

    private FeedResult feedForYou(String cursor, int limit, Long communityId, long viewerUserId, long viewerPrincipalId, boolean hideAnonymousPosts) {
        if (communityId != null) {
            RankPagination.Cursor rankedCursor = null;
            if (cursor != null && !cursor.isBlank()) {
                try {
                    rankedCursor = RankPagination.decode(cursor);
                } catch (IllegalArgumentException ignored) {
                    // treat as no cursor
                }
            }
            OffsetDateTime asOf = rankedCursor == null ? OffsetDateTime.now() : rankedCursor.asOf();
            OffsetDateTime since = asOf.minusDays(Math.max(1, Math.min(365, fyp.getEligibleWindowDays())));
            Long score = rankedCursor == null ? null : rankedCursor.score();
            OffsetDateTime cTs = rankedCursor == null ? null : rankedCursor.timestamp();
            Long cId = rankedCursor == null ? null : rankedCursor.id();

            var rows = posts.findFypPopularInCommunities(
                    List.of(communityId),
                    asOf,
                    since,
                    score,
                    cTs,
                    cId,
                    limit,
                    viewerUserId,
                    viewerPrincipalId,
                    hideAnonymousPosts,
                    fyp.getEligibleHalfLifeHours(),
                    fyp.getEligibleBaselineEngagement()
            );

            List<PostRepository.PostRow> items = new ArrayList<>();
            for (int i = 0; i < rows.size(); i++) {
                var r = rows.get(i);
                r.fypSourcePool = "community";
                r.fypRank = i;
                items.add(r);
            }

            String next = null;
            if (rows.size() == limit) {
                var last = rows.get(rows.size() - 1);
                next = RankPagination.encode(asOf, last.score, last.createdAt, last.id);
            }
            return FeedResult.ok(items, next);
        }

        FypCursor.Cursor fypCursor = FypCursor.decodeOrNull(cursor);
        OffsetDateTime asOf = fypCursor == null ? OffsetDateTime.now() : fypCursor.asOf();
        int patternOffset = fypCursor == null ? 0 : fypCursor.patternOffset();

        RankPagination.Cursor eligibleCursor = null;
        if (fypCursor != null && fypCursor.eligibleCursor() != null) {
            try {
                eligibleCursor = RankPagination.decode(fypCursor.eligibleCursor());
            } catch (IllegalArgumentException ignored) {
                eligibleCursor = null;
            }
        }
        RankPagination.Cursor discoveryCursor = null;
        if (fypCursor != null && fypCursor.discoveryCursor() != null) {
            try {
                discoveryCursor = RankPagination.decode(fypCursor.discoveryCursor());
            } catch (IllegalArgumentException ignored) {
                discoveryCursor = null;
            }
        }

        Set<Long> eligibleCommunityIds = eligibleCommunityIdsForUserId(viewerUserId);

        int multiplier = Math.max(2, Math.min(50, fyp.getCandidatesMultiplier()));
        int fetch = Math.max(limit, limit * multiplier);
        fetch = Math.min(Math.max(50, fyp.getMaxCandidatesPerPool()), fetch);

        OffsetDateTime eligibleSince = asOf.minusDays(Math.max(1, Math.min(365, fyp.getEligibleWindowDays())));
        OffsetDateTime discoverySince = asOf.minusDays(Math.max(1, Math.min(90, fyp.getDiscoveryWindowDays())));

        var eligible = eligibleCommunityIds.isEmpty()
                ? List.<PostRepository.ScoredPostRow>of()
                : posts.findFypPopularInCommunities(
                eligibleCommunityIds,
                asOf,
                eligibleSince,
                eligibleCursor == null ? null : eligibleCursor.score(),
                eligibleCursor == null ? null : eligibleCursor.timestamp(),
                eligibleCursor == null ? null : eligibleCursor.id(),
                fetch,
                viewerUserId,
                viewerPrincipalId,
                hideAnonymousPosts,
                fyp.getEligibleHalfLifeHours(),
                fyp.getEligibleBaselineEngagement()
        );

        var discovery = posts.findFypPopularExcludingCommunities(
                eligibleCommunityIds,
                asOf,
                discoverySince,
                discoveryCursor == null ? null : discoveryCursor.score(),
                discoveryCursor == null ? null : discoveryCursor.timestamp(),
                discoveryCursor == null ? null : discoveryCursor.id(),
                fetch,
                viewerUserId,
                viewerPrincipalId,
                hideAnonymousPosts,
                fyp.getDiscoveryHalfLifeHours(),
                fyp.getDiscoveryBaselineEngagement()
        );

        FypMix mix = mixFypTwoPools(
                eligible,
                discovery,
                limit,
                patternOffset,
                eligibleCommunityIds.size()
        );

        String next = null;
        if (mix.items.size() == limit && (mix.lastEligible != null || mix.lastDiscovery != null)) {
            String nextEligibleCursor = mix.lastEligible == null
                    ? (fypCursor == null ? null : fypCursor.eligibleCursor())
                    : RankPagination.encode(asOf, mix.lastEligible.score, mix.lastEligible.createdAt, mix.lastEligible.id);
            String nextDiscoveryCursor = mix.lastDiscovery == null
                    ? (fypCursor == null ? null : fypCursor.discoveryCursor())
                    : RankPagination.encode(asOf, mix.lastDiscovery.score, mix.lastDiscovery.createdAt, mix.lastDiscovery.id);

            int patternLen = Math.max(1, mix.patternLength);
            int nextOffset = Math.floorMod(patternOffset + mix.items.size(), patternLen);
            next = FypCursor.encode(new FypCursor.Cursor(asOf, nextOffset, nextEligibleCursor, nextDiscoveryCursor));
        }
        return FeedResult.ok(mix.items, next);
    }

    private FeedResult feedFollowing(String cursor, int limit, Long communityId, long viewerUserId, long viewerPrincipalId, boolean hideAnonymousPosts) {
        OffsetDateTime cTs = null;
        Long cId = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                var c = Pagination.decode(cursor);
                cTs = c.timestamp();
                cId = c.id();
            } catch (IllegalArgumentException ignored) {
                // treat as no cursor
            }
        }
        var list = posts.findFollowing(communityId, cTs, cId, limit, viewerUserId, viewerPrincipalId, hideAnonymousPosts);
        String next = null;
        if (list.size() == limit) {
            var last = list.get(list.size() - 1);
            next = Pagination.encode(last.createdAt, last.id);
        }
        return FeedResult.ok(list, next);
    }

    public TrendingResult trending(String firebaseUid, int limit, Long communityId) {
        return trending(firebaseUid, null, limit, communityId);
    }

    public TrendingResult trending(String firebaseUid, String cursor, int limit, Long communityId) {
        var u = users.findByFirebaseUid(firebaseUid);
        if (u.isEmpty()) {
            return TrendingResult.userNotProvisioned();
        }
        if (communityId != null) {
            var community = communities.findById(communityId);
            if (community.isEmpty() || !isUserVisible(community.get().kind, community.get().specializationType)) {
                return TrendingResult.communityNotFound();
            }
        }
        if (communityId != null && communityBans.isBanned(u.get().id, communityId)) {
            return TrendingResult.communityBanned();
        }
        var principal = principals.createForUser(u.get().id);
        RankPagination.Cursor rankedCursor = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                rankedCursor = RankPagination.decode(cursor);
            } catch (IllegalArgumentException ignored) {
                rankedCursor = null;
            }
        }
        OffsetDateTime asOf = rankedCursor == null ? OffsetDateTime.now() : rankedCursor.asOf();
        OffsetDateTime since = asOf.minusDays(Math.max(1, Math.min(30, trending.getWindowDays())));
        Set<Long> eligibleCommunityIds = eligibleCommunityIdsForUserId(u.get().id);
        var followedPrincipalIds = follows.findFolloweePrincipalIds(principal.id, Math.max(1, trending.getMaxFollowedPrincipalBoosts()));
        var list = posts.findTrendingPersonalizedWithMedia(
                asOf,
                since,
                communityId,
                rankedCursor == null ? null : rankedCursor.score(),
                rankedCursor == null ? null : rankedCursor.timestamp(),
                rankedCursor == null ? null : rankedCursor.id(),
                limit,
                u.get().id,
                principal.id,
                u.get().hideAnonymousPosts,
                eligibleCommunityIds,
                followedPrincipalIds,
                trending.getHalfLifeHours(),
                trending.getBaselineEngagement(),
                trending.getCommunityBoost(),
                trending.getFollowingBoost()
        );
        postState.applyForPrincipal(principal.id, list);
        String next = null;
        if (list.size() == limit) {
            var last = list.get(list.size() - 1);
            next = RankPagination.encode(asOf, last.score, last.createdAt, last.id);
        }
        return TrendingResult.ok(list, next);
    }

    private Set<Long> eligibleCommunityIdsForUserId(long userId) {
        Set<Long> verified = communityVerifications.activeVerifiedCommunityIdsForUser(userId);
        Set<Long> joined = specializationJoins.joinedIdsForUser(userId);
        Set<Long> candidate = new HashSet<>(verified);
        candidate.addAll(joined);
        if (candidate.isEmpty()) return Set.of();
        var byId = communities.findByIds(candidate);
        Set<Long> out = new HashSet<>();
        for (var row : byId.values()) {
            if (row == null) continue;
            if (!isUserVisible(row.kind, row.specializationType)) continue;
            out.add(row.id);
        }
        return out;
    }

    private FypMix mixFypTwoPools(List<PostRepository.ScoredPostRow> eligible,
                                  List<PostRepository.ScoredPostRow> discovery,
                                  int limit,
                                  int patternOffset,
                                  int eligibleCommunityCount) {
        int eCount = Math.max(0, fyp.getPatternEligible());
        int dCount = Math.max(0, fyp.getPatternDiscovery());
        if (eCount == 0 && dCount == 0) eCount = 1;
        if (eCount == 0) eCount = 1;
        if (dCount == 0) dCount = 1;
        int patternLen = eCount + dCount;

        double minEligibleFraction = Math.max(0.0, Math.min(1.0, fyp.getMinEligibleFraction()));
        int requiredEligible = (int) Math.ceil(limit * minEligibleFraction);
        int minEligibleTarget = eligible.size() >= requiredEligible ? requiredEligible : 0;
        int maxDiscoveryAllowed = Math.max(0, limit - minEligibleTarget);

        int strictMaxPerAuthor = Math.max(1, Math.min(50, fyp.getMaxPerAuthor()));
        int strictMaxPerCommunity = eligibleCommunityCount <= 1
                ? Integer.MAX_VALUE
                : Math.max(1, Math.min(50, fyp.getMaxPerCommunity()));

        List<PostRepository.PostRow> out = new ArrayList<>(limit);
        Map<Long, Integer> authorCounts = new HashMap<>();
        Map<Long, Integer> communityCounts = new HashMap<>();

        PoolCursor eligibleCur = new PoolCursor(eligible);
        PoolCursor discoveryCur = new PoolCursor(discovery);

        PostRepository.ScoredPostRow lastEligible = null;
        PostRepository.ScoredPostRow lastDiscovery = null;
        int eligiblePicked = 0;
        int discoveryPicked = 0;

        int step = Math.floorMod(patternOffset, patternLen);
        while (out.size() < limit) {
            boolean wantDiscovery = step >= eCount;
            PostRepository.ScoredPostRow picked = null;
            String pool = null;

            if (wantDiscovery && discoveryPicked < maxDiscoveryAllowed) {
                picked = discoveryCur.pickNext(authorCounts, communityCounts, strictMaxPerAuthor, strictMaxPerCommunity);
                if (picked != null) pool = "discovery";
            }
            if (picked == null) {
                picked = eligibleCur.pickNext(authorCounts, communityCounts, strictMaxPerAuthor, strictMaxPerCommunity);
                if (picked != null) pool = "eligible";
            }
            if (picked == null && !wantDiscovery && discoveryPicked < maxDiscoveryAllowed) {
                picked = discoveryCur.pickNext(authorCounts, communityCounts, strictMaxPerAuthor, strictMaxPerCommunity);
                if (picked != null) pool = "discovery";
            }

            if (picked == null) break;

            if ("eligible".equals(pool)) {
                eligiblePicked += 1;
                lastEligible = picked;
            } else {
                discoveryPicked += 1;
                lastDiscovery = picked;
            }

            picked.fypSourcePool = pool;
            picked.fypRank = out.size();
            out.add(picked);

            increment(authorCounts, picked.authorPrincipalId);
            if (picked.communityId != null) increment(communityCounts, picked.communityId);

            step = (step + 1) % patternLen;
        }

        // If we're under-filled (common in early stage), relax caps and backfill from leftovers.
        if (out.size() < limit) {
            int relaxedMaxPerAuthor = Math.max(strictMaxPerAuthor, strictMaxPerAuthor * 3);
            int relaxedMaxPerCommunity = strictMaxPerCommunity == Integer.MAX_VALUE
                    ? Integer.MAX_VALUE
                    : Math.max(strictMaxPerCommunity, strictMaxPerCommunity * 3);

            List<PostRepository.ScoredPostRow> eLeft = eligibleCur.leftovers();
            List<PostRepository.ScoredPostRow> dLeft = discoveryCur.leftovers();
            int ePtr = 0;
            int dPtr = 0;

            // 1) Try to satisfy eligible minimum (if any) with relaxed caps.
            while (out.size() < limit && minEligibleTarget > 0 && eligiblePicked < minEligibleTarget && ePtr < eLeft.size()) {
                PostRepository.ScoredPostRow r = eLeft.get(ePtr++);
                if (out.size() >= limit) break;
                if (minEligibleTarget > 0 && eligiblePicked >= minEligibleTarget) break;
                if (!passesCaps(r, authorCounts, communityCounts, relaxedMaxPerAuthor, relaxedMaxPerCommunity)) continue;
                r.fypSourcePool = "eligible";
                r.fypRank = out.size();
                out.add(r);
                eligiblePicked += 1;
                lastEligible = r;
                increment(authorCounts, r.authorPrincipalId);
                if (r.communityId != null) increment(communityCounts, r.communityId);
            }
            // 2) Fill the remainder, preferring eligible then discovery.
            while (out.size() < limit && ePtr < eLeft.size()) {
                PostRepository.ScoredPostRow r = eLeft.get(ePtr++);
                if (!passesCaps(r, authorCounts, communityCounts, relaxedMaxPerAuthor, relaxedMaxPerCommunity)) continue;
                r.fypSourcePool = "eligible";
                r.fypRank = out.size();
                out.add(r);
                eligiblePicked += 1;
                lastEligible = r;
                increment(authorCounts, r.authorPrincipalId);
                if (r.communityId != null) increment(communityCounts, r.communityId);
            }
            while (out.size() < limit && dPtr < dLeft.size()) {
                PostRepository.ScoredPostRow r = dLeft.get(dPtr++);
                if (out.size() >= limit) break;
                if (minEligibleTarget > 0 && discoveryPicked >= maxDiscoveryAllowed) break;
                if (!passesCaps(r, authorCounts, communityCounts, relaxedMaxPerAuthor, relaxedMaxPerCommunity)) continue;
                r.fypSourcePool = "discovery";
                r.fypRank = out.size();
                out.add(r);
                discoveryPicked += 1;
                lastDiscovery = r;
                increment(authorCounts, r.authorPrincipalId);
                if (r.communityId != null) increment(communityCounts, r.communityId);
            }

            // 3) Last resort: ignore caps to avoid empty feeds in tiny communities.
            if (out.size() < limit) {
                while (out.size() < limit && ePtr < eLeft.size()) {
                    PostRepository.ScoredPostRow r = eLeft.get(ePtr++);
                    r.fypSourcePool = "eligible";
                    r.fypRank = out.size();
                    out.add(r);
                    eligiblePicked += 1;
                    lastEligible = r;
                }
                while (out.size() < limit && dPtr < dLeft.size()) {
                    PostRepository.ScoredPostRow r = dLeft.get(dPtr++);
                    r.fypSourcePool = "discovery";
                    r.fypRank = out.size();
                    out.add(r);
                    discoveryPicked += 1;
                    lastDiscovery = r;
                }
            }
        }

        // Ensure ranks are 0-based and contiguous (backfill paths may not have set correctly).
        for (int i = 0; i < out.size(); i++) {
            out.get(i).fypRank = i;
        }
        return new FypMix(out, lastEligible, lastDiscovery, patternLen);
    }

    private static final class PoolCursor {
        private final List<PostRepository.ScoredPostRow> rows;
        private int idx = 0;
        private final List<PostRepository.ScoredPostRow> skipped = new ArrayList<>();

        PoolCursor(List<PostRepository.ScoredPostRow> rows) {
            this.rows = rows == null ? List.of() : rows;
        }

        PostRepository.ScoredPostRow pickNext(Map<Long, Integer> authorCounts,
                                             Map<Long, Integer> communityCounts,
                                             int maxPerAuthor,
                                             int maxPerCommunity) {
            while (idx < rows.size()) {
                PostRepository.ScoredPostRow row = rows.get(idx++);
                if (passesCaps(row, authorCounts, communityCounts, maxPerAuthor, maxPerCommunity)) {
                    return row;
                }
                skipped.add(row);
            }
            return null;
        }

        List<PostRepository.ScoredPostRow> leftovers() {
            if (rows.isEmpty() && skipped.isEmpty()) return List.of();
            int remaining = Math.max(0, rows.size() - idx);
            List<PostRepository.ScoredPostRow> out = new ArrayList<>(skipped.size() + remaining);
            out.addAll(skipped);
            if (remaining > 0) out.addAll(rows.subList(idx, rows.size()));
            return out;
        }
    }

    private static void increment(Map<Long, Integer> counts, long key) {
        counts.put(key, counts.getOrDefault(key, 0) + 1);
    }

    private static boolean passesCaps(PostRepository.PostRow row,
                                      Map<Long, Integer> authorCounts,
                                      Map<Long, Integer> communityCounts,
                                      int maxPerAuthor,
                                      int maxPerCommunity) {
        if (row == null) return false;
        int a = authorCounts.getOrDefault(row.authorPrincipalId, 0);
        if (a >= maxPerAuthor) return false;
        if (row.communityId != null && maxPerCommunity != Integer.MAX_VALUE) {
            int c = communityCounts.getOrDefault(row.communityId, 0);
            if (c >= maxPerCommunity) return false;
        }
        return true;
    }

    private record FypMix(List<PostRepository.PostRow> items,
                          PostRepository.ScoredPostRow lastEligible,
                          PostRepository.ScoredPostRow lastDiscovery,
                          int patternLength) {}

    public enum Status { OK, USER_NOT_PROVISIONED, COMMUNITY_NOT_FOUND, COMMUNITY_BANNED }
    public enum Mode {
        FOR_YOU, NEW, FOLLOWING;

        static Mode from(String raw) {
            if (raw == null || raw.isBlank()) return FOR_YOU;
            String normalized = raw.trim().toLowerCase();
            if (normalized.equals("new") || normalized.equals("recent")) return NEW;
            if (normalized.equals("following")) return FOLLOWING;
            return FOR_YOU;
        }
    }
    public record FeedResult(Status status, List<PostRepository.PostRow> items, String nextCursor) {
        static FeedResult ok(List<PostRepository.PostRow> items, String next) { return new FeedResult(Status.OK, items, next); }
        static FeedResult userNotProvisioned() { return new FeedResult(Status.USER_NOT_PROVISIONED, List.of(), null); }
        static FeedResult communityNotFound() { return new FeedResult(Status.COMMUNITY_NOT_FOUND, List.of(), null); }
        static FeedResult communityBanned() { return new FeedResult(Status.COMMUNITY_BANNED, List.of(), null); }
    }

    public record TrendingResult(Status status, List<PostRepository.TrendingRow> items, String nextCursor) {
        static TrendingResult ok(List<PostRepository.TrendingRow> items, String next) { return new TrendingResult(Status.OK, items, next); }
        static TrendingResult userNotProvisioned() { return new TrendingResult(Status.USER_NOT_PROVISIONED, List.of(), null); }
        static TrendingResult communityNotFound() { return new TrendingResult(Status.COMMUNITY_NOT_FOUND, List.of(), null); }
        static TrendingResult communityBanned() { return new TrendingResult(Status.COMMUNITY_BANNED, List.of(), null); }
    }
}

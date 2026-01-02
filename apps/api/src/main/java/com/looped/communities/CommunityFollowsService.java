package com.looped.communities;

import com.looped.shared.Pagination;
import com.looped.users.UserRepository;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class CommunityFollowsService {
    private static final int SPECIALIZATION_MAX_PER_TYPE = 2;
    private static final int SPECIALIZATION_COOLDOWN_MONTHS = 6;

    private final UserRepository users;
    private final CommunityFollowsRepository follows;
    private final CommunitiesRepository communities;
    private final SpecializationLimitsRepository specializationLimits;

    public CommunityFollowsService(UserRepository users,
                                   CommunityFollowsRepository follows,
                                   CommunitiesRepository communities,
                                   SpecializationLimitsRepository specializationLimits) {
        this.users = users;
        this.follows = follows;
        this.communities = communities;
        this.specializationLimits = specializationLimits;
    }

    public ListResult followed(String firebaseUid, String cursor, int limit, String order) {
        var actor = provisionedUser(firebaseUid);
        if (actor.isEmpty()) return ListResult.userNotProvisioned();
        Order resolved = Order.from(order);
        if (resolved == Order.RECENT) {
            return followedRecent(actor.get().id, cursor, limit);
        }
        var relevantCursor = CommunityFollowCursor.tryDecode(cursor);
        if (relevantCursor == null && cursor != null && !cursor.isBlank()) {
            return followedRecent(actor.get().id, cursor, limit);
        }
        var rows = follows.findFollowedRelevant(actor.get().id, relevantCursor, limit);
        String next = null;
        if (rows.size() == limit) {
            var last = rows.get(rows.size() - 1);
            next = CommunityFollowCursor.encode(last);
        }
        return ListResult.ok(rows, next);
    }

    private ListResult followedRecent(long userId, String cursor, int limit) {
        var cursorParts = decodeCursor(cursor);
        var rows = follows.findFollowed(userId, cursorParts.timestamp, cursorParts.followId, limit);
        String next = null;
        if (rows.size() == limit) {
            var last = rows.get(rows.size() - 1);
            next = Pagination.encode(last.followedAt, last.followId);
        }
        return ListResult.ok(rows, next);
    }

    private Optional<UserRepository.UserRow> provisionedUser(String firebaseUid) {
        return users.findByFirebaseUid(firebaseUid);
    }

    private CursorParts decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return new CursorParts(null, null);
        }
        try {
            var decoded = Pagination.decode(cursor);
            return new CursorParts(decoded.timestamp(), decoded.id());
        } catch (IllegalArgumentException ignored) {
            return new CursorParts(null, null);
        }
    }

    private record CursorParts(OffsetDateTime timestamp, Long followId) {}

    public enum Status { OK, USER_NOT_PROVISIONED, NOT_FOUND, LIMIT_REACHED, COOLDOWN, INVALID_SPECIALIZATION }
    public enum Order {
        RECENT, RELEVANT;

        static Order from(String raw) {
            if (raw == null || raw.isBlank()) return RELEVANT;
            try {
                return Order.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return RELEVANT;
            }
        }
    }

    public record ListResult(Status status, List<CommunityFollowsRepository.FollowRow> follows, String nextCursor) {
        static ListResult ok(List<CommunityFollowsRepository.FollowRow> follows, String next) { return new ListResult(Status.OK, follows, next); }
        static ListResult userNotProvisioned() { return new ListResult(Status.USER_NOT_PROVISIONED, List.of(), null); }
    }

    public FollowResult follow(String firebaseUid, long communityId) {
        var actor = provisionedUser(firebaseUid);
        if (actor.isEmpty()) return FollowResult.userNotProvisioned();
        var communityOpt = communities.findById(communityId);
        if (communityOpt.isEmpty()) return FollowResult.notFound();
        if (follows.exists(actor.get().id, communityId)) {
            return FollowResult.ok(true, false);
        }
        var community = communityOpt.get();
        String specializationType = null;
        if (isSpecialization(community.kind)) {
            specializationType = normalizeSpecializationType(community.specializationType);
            if (specializationType == null) return FollowResult.invalidSpecialization();
            int count = follows.countSpecializations(actor.get().id, specializationType);
            if (count >= SPECIALIZATION_MAX_PER_TYPE) {
                return FollowResult.limitReached(specializationType, SPECIALIZATION_MAX_PER_TYPE);
            }
            var lastChange = specializationLimits.findLastChange(actor.get().id, specializationType).orElse(null);
            if (lastChange != null) {
                OffsetDateTime cooldownEndsAt = lastChange.plusMonths(SPECIALIZATION_COOLDOWN_MONTHS);
                if (cooldownEndsAt.isAfter(OffsetDateTime.now())) {
                    return FollowResult.cooldown(specializationType, cooldownEndsAt);
                }
            }
        }
        boolean created = follows.insertIfAbsent(actor.get().id, communityId);
        if (created && specializationType != null) {
            specializationLimits.upsertLastChange(actor.get().id, specializationType, OffsetDateTime.now());
        }
        return FollowResult.ok(true, created);
    }

    public FollowResult unfollow(String firebaseUid, long communityId) {
        var actor = provisionedUser(firebaseUid);
        if (actor.isEmpty()) return FollowResult.userNotProvisioned();
        if (communities.findById(communityId).isEmpty()) return FollowResult.notFound();
        boolean deleted = follows.delete(actor.get().id, communityId);
        return FollowResult.ok(false, deleted);
    }

    private boolean isSpecialization(String kind) {
        if (kind == null) return false;
        return "specialization".equalsIgnoreCase(kind);
    }

    private String normalizeSpecializationType(String raw) {
        if (raw == null) return null;
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) return null;
        if (!normalized.equals("major") && !normalized.equals("department")) return null;
        return normalized;
    }

    public record FollowResult(Status status, boolean following, boolean changed,
                               String specializationType, OffsetDateTime cooldownEndsAt, Integer limit) {
        static FollowResult ok(boolean following, boolean changed) {
            return new FollowResult(Status.OK, following, changed, null, null, null);
        }
        static FollowResult userNotProvisioned() { return new FollowResult(Status.USER_NOT_PROVISIONED, false, false, null, null, null); }
        static FollowResult notFound() { return new FollowResult(Status.NOT_FOUND, false, false, null, null, null); }
        static FollowResult limitReached(String specializationType, int limit) {
            return new FollowResult(Status.LIMIT_REACHED, false, false, specializationType, null, limit);
        }
        static FollowResult cooldown(String specializationType, OffsetDateTime cooldownEndsAt) {
            return new FollowResult(Status.COOLDOWN, false, false, specializationType, cooldownEndsAt, null);
        }
        static FollowResult invalidSpecialization() {
            return new FollowResult(Status.INVALID_SPECIALIZATION, false, false, null, null, null);
        }
    }
}

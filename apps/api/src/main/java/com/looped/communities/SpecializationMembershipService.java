package com.looped.communities;

import com.looped.settings.AppSettingsKeys;
import com.looped.settings.AppSettingsRepository;
import com.looped.users.UserRepository;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class SpecializationMembershipService {
    private static final int MAX_PER_TYPE = 2;
    private static final String LIMIT_SCOPE = "join";

    private final UserRepository users;
    private final CommunitiesRepository communities;
    private final CommunityFollowsRepository follows;
    private final SpecializationJoinsRepository joins;
    private final SpecializationLimitsRepository limits;
    private final SpecializationProperties specializationProps;
    private final AppSettingsRepository settings;

    public SpecializationMembershipService(UserRepository users,
                                           CommunitiesRepository communities,
                                           CommunityFollowsRepository follows,
                                           SpecializationJoinsRepository joins,
                                           SpecializationLimitsRepository limits,
                                           SpecializationProperties specializationProps,
                                           AppSettingsRepository settings) {
        this.users = users;
        this.communities = communities;
        this.follows = follows;
        this.joins = joins;
        this.limits = limits;
        this.specializationProps = specializationProps;
        this.settings = settings;
    }

    public enum Status { OK, USER_NOT_PROVISIONED, NOT_FOUND, INVALID_SPECIALIZATION, LIMIT_REACHED, COOLDOWN }

    public record JoinResult(Status status, boolean joined, boolean changed,
                             String specializationType, OffsetDateTime cooldownEndsAt, Integer cooldownMonths, Integer limit) {
        static JoinResult ok(boolean joined, boolean changed) {
            return new JoinResult(Status.OK, joined, changed, null, null, null, null);
        }
        static JoinResult userNotProvisioned() { return new JoinResult(Status.USER_NOT_PROVISIONED, false, false, null, null, null, null); }
        static JoinResult notFound() { return new JoinResult(Status.NOT_FOUND, false, false, null, null, null, null); }
        static JoinResult invalidSpecialization() { return new JoinResult(Status.INVALID_SPECIALIZATION, false, false, null, null, null, null); }
        static JoinResult limitReached(String specializationType, int limit) {
            return new JoinResult(Status.LIMIT_REACHED, false, false, specializationType, null, null, limit);
        }
        static JoinResult cooldown(String specializationType, OffsetDateTime endsAt, int cooldownMonths) {
            return new JoinResult(Status.COOLDOWN, false, false, specializationType, endsAt, cooldownMonths, null);
        }
    }

    public record ListResult(Status status, List<SpecializationJoinsRepository.JoinRow> items, String nextCursor) {
        static ListResult ok(List<SpecializationJoinsRepository.JoinRow> items, String nextCursor) {
            return new ListResult(Status.OK, items, nextCursor);
        }
        static ListResult userNotProvisioned() { return new ListResult(Status.USER_NOT_PROVISIONED, List.of(), null); }
    }

    public record JoinLimitSnapshot(String specializationType, int limit, int joinedCount, int remaining,
                                    boolean cooldownActive, OffsetDateTime cooldownEndsAt, Long cooldownDaysRemaining,
                                    boolean canJoin, String blockedReason, int cooldownMonths) {}

    public record JoinLimitSnapshotsResult(Status status, List<JoinLimitSnapshot> items) {
        static JoinLimitSnapshotsResult ok(List<JoinLimitSnapshot> items) {
            return new JoinLimitSnapshotsResult(Status.OK, items);
        }
        static JoinLimitSnapshotsResult userNotProvisioned() {
            return new JoinLimitSnapshotsResult(Status.USER_NOT_PROVISIONED, List.of());
        }
    }

    public JoinResult join(String firebaseUid, long specializationId) {
        var actor = provisionedUser(firebaseUid);
        if (actor.isEmpty()) return JoinResult.userNotProvisioned();
        var communityOpt = communities.findById(specializationId);
        if (communityOpt.isEmpty()) return JoinResult.notFound();

        var community = communityOpt.get();
        String specializationType = requireMajorOrDepartment(community);
        if (specializationType == null) return JoinResult.invalidSpecialization();

        if (joins.exists(actor.get().id, specializationId)) {
            follows.insertIfAbsent(actor.get().id, specializationId);
            return JoinResult.ok(true, false);
        }

        int count = joins.countJoinedByType(actor.get().id, specializationType);
        if (count >= MAX_PER_TYPE) {
            return JoinResult.limitReached(specializationType, MAX_PER_TYPE);
        }

        int defaultCooldownMonths = defaultJoinCooldownMonths();
        var lastChange = limits.findLastChangeWithCooldownMonths(actor.get().id, specializationType, LIMIT_SCOPE).orElse(null);
        if (lastChange != null && lastChange.lastChangedAt() != null) {
            int months = lastChange.cooldownMonths() != null && lastChange.cooldownMonths() > 0
                    ? lastChange.cooldownMonths()
                    : defaultCooldownMonths;
            OffsetDateTime cooldownEndsAt = lastChange.lastChangedAt().plusMonths(months);
            if (cooldownEndsAt.isAfter(OffsetDateTime.now())) {
                return JoinResult.cooldown(specializationType, cooldownEndsAt, months);
            }
        }

        boolean created = joins.insertIfAbsent(actor.get().id, specializationId);
        follows.insertIfAbsent(actor.get().id, specializationId);
        return JoinResult.ok(true, created);
    }

    public JoinResult unjoin(String firebaseUid, long specializationId) {
        var actor = provisionedUser(firebaseUid);
        if (actor.isEmpty()) return JoinResult.userNotProvisioned();
        var communityOpt = communities.findById(specializationId);
        if (communityOpt.isEmpty()) return JoinResult.notFound();

        var community = communityOpt.get();
        String specializationType = requireMajorOrDepartment(community);
        if (specializationType == null) return JoinResult.invalidSpecialization();

        boolean deleted = joins.delete(actor.get().id, specializationId);
        if (deleted) {
            int cooldownMonths = resolveCooldownMonthsForCommunity(community);
            limits.upsertLastChangeWithCooldownMonths(actor.get().id, specializationType, LIMIT_SCOPE, OffsetDateTime.now(), cooldownMonths);
        }
        return JoinResult.ok(false, deleted);
    }

    public ListResult joined(String firebaseUid, String specializationType, String cursor, int limit) {
        var actor = provisionedUser(firebaseUid);
        if (actor.isEmpty()) return ListResult.userNotProvisioned();

        String normalizedType = normalizeType(specializationType);
        if (specializationType != null && !specializationType.isBlank() && normalizedType == null) {
            normalizedType = "all";
        }
        if ("all".equals(normalizedType)) normalizedType = null;

        var cursorParts = decodeCursor(cursor);
        var rows = joins.listJoined(actor.get().id, normalizedType, cursorParts.timestamp, cursorParts.id, limit);
        String next = null;
        if (rows.size() == limit) {
            var last = rows.get(rows.size() - 1);
            next = com.looped.shared.Pagination.encode(last.createdAt(), last.joinId());
        }
        return ListResult.ok(rows, next);
    }

    public JoinLimitSnapshotsResult joinLimitSnapshots(String firebaseUid, String specializationType) {
        var actor = provisionedUser(firebaseUid);
        if (actor.isEmpty()) return JoinLimitSnapshotsResult.userNotProvisioned();
        String normalizedType = normalizeType(specializationType);
        if (normalizedType == null) {
            return JoinLimitSnapshotsResult.ok(List.of());
        }
        return JoinLimitSnapshotsResult.ok(List.of(joinLimitSnapshotForUserId(actor.get().id, normalizedType)));
    }

    public JoinLimitSnapshotsResult joinLimitSnapshots(String firebaseUid) {
        var actor = provisionedUser(firebaseUid);
        if (actor.isEmpty()) return JoinLimitSnapshotsResult.userNotProvisioned();
        return JoinLimitSnapshotsResult.ok(List.of(
                joinLimitSnapshotForUserId(actor.get().id, "major"),
                joinLimitSnapshotForUserId(actor.get().id, "department")
        ));
    }

    public JoinLimitSnapshot joinLimitSnapshotForUserId(long userId, String specializationType) {
        String normalizedType = normalizeType(specializationType);
        int defaultCooldownMonths = defaultJoinCooldownMonths();
        if (normalizedType == null) {
            return new JoinLimitSnapshot(null, MAX_PER_TYPE, 0, MAX_PER_TYPE, false,
                    null, null, true, null, defaultCooldownMonths);
        }

        int joinedCount = joins.countJoinedByType(userId, normalizedType);
        int remaining = Math.max(0, MAX_PER_TYPE - joinedCount);

        OffsetDateTime now = OffsetDateTime.now();
        var lastChange = limits.findLastChangeWithCooldownMonths(userId, normalizedType, LIMIT_SCOPE).orElse(null);
        int cooldownMonths = lastChange != null && lastChange.cooldownMonths() != null && lastChange.cooldownMonths() > 0
                ? lastChange.cooldownMonths()
                : defaultCooldownMonths;
        OffsetDateTime cooldownEndsAt = (lastChange == null || lastChange.lastChangedAt() == null)
                ? null
                : lastChange.lastChangedAt().plusMonths(cooldownMonths);
        boolean cooldownActive = cooldownEndsAt != null && cooldownEndsAt.isAfter(now);
        Long cooldownDaysRemaining = null;
        if (cooldownActive) {
            long days = java.time.Duration.between(now, cooldownEndsAt).toDays();
            cooldownDaysRemaining = Math.max(0, days);
        }

        String blockedReason = null;
        if (joinedCount >= MAX_PER_TYPE) blockedReason = "limit";
        else if (cooldownActive) blockedReason = "cooldown";

        boolean canJoin = blockedReason == null;
        return new JoinLimitSnapshot(normalizedType, MAX_PER_TYPE, joinedCount, remaining,
                cooldownActive, cooldownEndsAt, cooldownDaysRemaining, canJoin, blockedReason, cooldownMonths);
    }

    private Optional<UserRepository.UserRow> provisionedUser(String firebaseUid) {
        return users.findByFirebaseUid(firebaseUid).filter(u -> u.companyId != null);
    }

    private int defaultJoinCooldownMonths() {
        long configured = settings.findLong(AppSettingsKeys.SPECIALIZATIONS_DEFAULT_JOIN_COOLDOWN_MONTHS)
                .orElse((long) specializationProps.getDefaultJoinCooldownMonths());
        int val = (int) Math.max(0, Math.min(120, configured));
        return val > 0 ? val : 6;
    }

    private int resolveCooldownMonthsForCommunity(CommunitiesRepository.CommunityRow community) {
        if (community != null &&
                community.specializationJoinCooldownMonths != null &&
                community.specializationJoinCooldownMonths > 0) {
            return community.specializationJoinCooldownMonths;
        }
        return defaultJoinCooldownMonths();
    }

    private String requireMajorOrDepartment(CommunitiesRepository.CommunityRow community) {
        if (community == null) return null;
        if (community.kind == null || !"specialization".equalsIgnoreCase(community.kind)) return null;
        return normalizeType(community.specializationType);
    }

    private String normalizeType(String raw) {
        if (raw == null) return null;
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) return null;
        if (!normalized.equals("major") && !normalized.equals("department")) return null;
        return normalized;
    }

    private CursorParts decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return new CursorParts(null, null);
        try {
            var decoded = com.looped.shared.Pagination.decode(cursor);
            return new CursorParts(decoded.timestamp(), decoded.id());
        } catch (IllegalArgumentException ignored) {
            return new CursorParts(null, null);
        }
    }

    private record CursorParts(OffsetDateTime timestamp, Long id) {}
}

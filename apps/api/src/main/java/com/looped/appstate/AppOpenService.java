package com.looped.appstate;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.looped.users.UserRepository;
import com.looped.widgets.WidgetSummaryRepository;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

@Service
public class AppOpenService {
    private static final int MAX_SEEN_COMMUNITIES = 25;

    private final UserRepository users;
    private final WidgetSummaryRepository widgetSummary;

    public AppOpenService(UserRepository users,
                          WidgetSummaryRepository widgetSummary) {
        this.users = users;
        this.widgetSummary = widgetSummary;
    }

    public AppOpenResult open(String firebaseUid, AppOpenRequest request) {
        var actor = requireProvisionedUser(firebaseUid);
        if (actor.isEmpty()) return AppOpenResult.userNotProvisioned();

        OffsetDateTime openedAt = normalizeOpenedAt(request == null ? null : request.openedAt());
        OffsetDateTime effectiveOpenedAt = users.updateLastAppOpenAt(actor.get().id, openedAt);
        if (effectiveOpenedAt == null) {
            effectiveOpenedAt = openedAt;
        }

        LinkedHashSet<Long> communityIds = new LinkedHashSet<>();
        if (request != null) {
            if (request.activeCommunityId() != null && request.activeCommunityId() > 0) {
                communityIds.add(request.activeCommunityId());
            }
            if (request.seenCommunityIds() != null && !request.seenCommunityIds().isEmpty()) {
                for (Long communityId : request.seenCommunityIds()) {
                    if (communityId == null || communityId <= 0) continue;
                    communityIds.add(communityId);
                    if (communityIds.size() >= MAX_SEEN_COMMUNITIES) break;
                }
            }
        }

        List<CommunitySeen> updated = new ArrayList<>();
        for (Long communityId : communityIds) {
            if (communityId == null || communityId <= 0) continue;
            if (!widgetSummary.isActiveVerifiedCommunity(actor.get().id, communityId)) continue;
            OffsetDateTime seenAt = widgetSummary.upsertCommunitySeen(actor.get().id, communityId, effectiveOpenedAt);
            updated.add(new CommunitySeen(communityId, seenAt == null ? effectiveOpenedAt : seenAt));
        }

        return AppOpenResult.ok(new AppOpenResponse(effectiveOpenedAt, updated));
    }

    private OffsetDateTime normalizeOpenedAt(OffsetDateTime openedAt) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (openedAt == null) return now;
        OffsetDateTime utc = openedAt.withOffsetSameInstant(ZoneOffset.UTC);
        // Keep server-tracked opens bounded to reduce bad client clocks.
        if (utc.isAfter(now.plusMinutes(5))) return now;
        if (utc.isBefore(now.minusDays(30))) return now.minusDays(30);
        return utc;
    }

    private Optional<UserRepository.UserRow> requireProvisionedUser(String firebaseUid) {
        var user = users.findByFirebaseUid(firebaseUid);
        if (user.isEmpty() || user.get().companyId == null) return Optional.empty();
        return user;
    }

    public enum Status { OK, USER_NOT_PROVISIONED }

    public record AppOpenResult(Status status, AppOpenResponse response) {
        static AppOpenResult ok(AppOpenResponse response) {
            return new AppOpenResult(Status.OK, response);
        }

        static AppOpenResult userNotProvisioned() {
            return new AppOpenResult(Status.USER_NOT_PROVISIONED, null);
        }
    }

    public record AppOpenRequest(@JsonProperty("opened_at") OffsetDateTime openedAt,
                                 @JsonProperty("active_community_id") Long activeCommunityId,
                                 @JsonProperty("seen_community_ids") List<Long> seenCommunityIds) {}

    public record AppOpenResponse(@JsonProperty("last_app_open_at") OffsetDateTime lastAppOpenAt,
                                  @JsonProperty("updated_communities") List<CommunitySeen> updatedCommunities) {}

    public record CommunitySeen(@JsonProperty("community_id") long communityId,
                                @JsonProperty("seen_at") OffsetDateTime seenAt) {}
}

package com.looped.communities;

import com.looped.users.UserRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class PostableCommunitiesService {
    private final UserRepository users;
    private final CommunityVerificationsRepository verifications;
    private final SpecializationJoinsRepository specializationJoins;
    private final CommunitiesRepository communities;
    private final CommunityFollowsRepository follows;
    private final CommunityMemberCountService memberCounts;

    public PostableCommunitiesService(UserRepository users,
                                      CommunityVerificationsRepository verifications,
                                      SpecializationJoinsRepository specializationJoins,
                                      CommunitiesRepository communities,
                                      CommunityFollowsRepository follows,
                                      CommunityMemberCountService memberCounts) {
        this.users = users;
        this.verifications = verifications;
        this.specializationJoins = specializationJoins;
        this.communities = communities;
        this.follows = follows;
        this.memberCounts = memberCounts;
    }

    public Result list(String firebaseUid) {
        var actor = provisionedUser(firebaseUid);
        if (actor.isEmpty()) return Result.userNotProvisioned();

        Set<Long> activeVerifiedCommunityIds = verifications.activeVerifiedCommunityIdsForUser(actor.get().id);
        Set<Long> joinedSpecializationIds = specializationJoins.joinedIdsByTypes(actor.get().id, List.of("major", "field"));

        java.util.Set<Long> candidateIds = new java.util.HashSet<>();
        candidateIds.addAll(activeVerifiedCommunityIds);
        candidateIds.addAll(joinedSpecializationIds);
        if (candidateIds.isEmpty()) return Result.ok(List.of());

        Map<Long, CommunitiesRepository.CommunityRow> communitiesById = communities.findByIds(candidateIds);
        java.util.Set<Long> postableIds = new java.util.HashSet<>();
        for (var entry : communitiesById.entrySet()) {
            var row = entry.getValue();
            if (row == null || row.kind == null) continue;
            if ("specialization".equalsIgnoreCase(row.kind)) {
                if (requiresSpecializationJoin(row.specializationType) && joinedSpecializationIds.contains(row.id)) {
                    postableIds.add(row.id);
                }
                continue;
            }
            if (activeVerifiedCommunityIds.contains(row.id)) {
                postableIds.add(row.id);
            }
        }
        if (postableIds.isEmpty()) return Result.ok(List.of());

        Map<Long, CommunityFollowsRepository.FollowMeta> followMetaByCommunityId =
                follows.followedMetaByCommunityIds(actor.get().id, postableIds);
        Map<Long, Integer> memberCountsByCommunityId = memberCounts.memberCountsByCommunityRefs(
                postableIds.stream()
                        .map(id -> {
                            var row = communitiesById.get(id);
                            return new CommunityMemberCountService.Ref(id, row == null ? null : row.kind);
                        })
                        .toList()
        );

        List<Item> items = new ArrayList<>();
        for (Long communityId : postableIds) {
            var row = communitiesById.get(communityId);
            if (row == null) continue;
            var follow = followMetaByCommunityId.get(communityId);
            int memberCount = memberCountsByCommunityId.getOrDefault(communityId, 0);
            Map<String, Object> payload = new HashMap<>();
            payload.put("id", communityId);
            payload.put("name", row.name);
            if (row.shortName != null && !row.shortName.isBlank()) {
                payload.put("short_name", row.shortName);
                payload.put("shortName", row.shortName);
            }
            payload.put("kind", row.kind);
            if (row.specializationType != null && !row.specializationType.isBlank()) {
                payload.put("specialization_type", row.specializationType);
                payload.put("specializationType", row.specializationType);
            }
            payload.put("member_count", memberCount);
            payload.put("memberCount", memberCount);
            payload.put("can_post", true);
            payload.put("canPost", true);
            boolean following = follow != null;
            payload.put("is_following", following);
            payload.put("isFollowing", following);
            if (follow != null) {
                payload.put("is_pinned", follow.isPinned());
                payload.put("isPinned", follow.isPinned());
                if (follow.sortOrder() != null) {
                    payload.put("sort_order", follow.sortOrder());
                    payload.put("sortOrder", follow.sortOrder());
                }
            }
            items.add(new Item(payload, follow == null ? false : follow.isPinned(), follow == null ? null : follow.sortOrder(), row.name, communityId));
        }

        items.sort(Comparator
                .comparing(Item::isPinned).reversed()
                .thenComparing(i -> i.sortOrder() == null ? 1 : 0)
                .thenComparing(i -> i.sortOrder() == null ? Integer.MAX_VALUE : i.sortOrder())
                .thenComparing(i -> i.name() == null ? "" : i.name().toLowerCase(Locale.ROOT))
                .thenComparing(Item::id));

        List<Map<String, Object>> payloadItems = items.stream().map(Item::payload).toList();
        return Result.ok(payloadItems);
    }

    private Optional<UserRepository.UserRow> provisionedUser(String firebaseUid) {
        return users.findByFirebaseUid(firebaseUid).filter(u -> u.companyId != null);
    }

    private boolean requiresSpecializationJoin(String specializationType) {
        if (specializationType == null) return false;
        String t = specializationType.trim().toLowerCase(Locale.ROOT);
        return t.equals("major") || t.equals("field");
    }

    private record Item(Map<String, Object> payload, boolean isPinned, Integer sortOrder, String name, long id) {}

    public enum Status {
        OK,
        USER_NOT_PROVISIONED
    }

    public record Result(Status status, List<Map<String, Object>> items) {
        static Result ok(List<Map<String, Object>> items) {
            return new Result(Status.OK, items);
        }

        static Result userNotProvisioned() {
            return new Result(Status.USER_NOT_PROVISIONED, List.of());
        }
    }
}

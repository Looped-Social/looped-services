package com.looped.discovery;

import com.looped.communities.CommunitiesRepository;
import com.looped.communities.CommunityMemberCountService;
import com.looped.communities.CommunityFollowsRepository;
import com.looped.communities.CommunityImageSlots;
import com.looped.communities.CommunityLogoResolver;
import com.looped.communities.SpecializationJoinsRepository;
import com.looped.posts.PostPayloads;
import com.looped.users.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.looped.communities.CommunityVisibilityRules.isUserVisible;

@RestController
@RequestMapping("/v1")
public class DiscoveryController {
    private final DiscoveryService service;
    private final CommunityLogoResolver logos;
    private final UserRepository users;
    private final CommunityFollowsRepository follows;
    private final SpecializationJoinsRepository specializationJoins;
    private final CommunityMemberCountService memberCounts;

    public DiscoveryController(DiscoveryService service,
                               CommunityLogoResolver logos,
                               UserRepository users,
                               CommunityFollowsRepository follows,
                               SpecializationJoinsRepository specializationJoins,
                               CommunityMemberCountService memberCounts) {
        this.service = service;
        this.logos = logos;
        this.users = users;
        this.follows = follows;
        this.specializationJoins = specializationJoins;
        this.memberCounts = memberCounts;
    }

    @GetMapping("/communities/search")
    public ResponseEntity<?> searchCommunities(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam("query") String query,
            @RequestParam(value = "kind", required = false) String kind,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false, defaultValue = "20") int limit
    ) {
        query = normalizeQueryParam(query);
        if (query == null || query.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", "query_required",
                    "message", "query must be provided"
            ));
        }
        int lim = Math.max(1, Math.min(limit, 100));
        boolean kindProvided = kind != null && !kind.isBlank();
        String rawKind = kind == null ? null : kind.trim().toLowerCase(Locale.ROOT);
        if (kindProvided && ("school".equals(rawKind) || "major".equals(rawKind))) {
            return ResponseEntity.ok(Map.of("items", List.of()));
        }
        String normalizedKind = normalizeKind(kind);
        String specializationType = kind != null ? normalizeSpecializationTypeFromKind(kind) : null;
        if (kindProvided && normalizedKind == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "error", "invalid_kind",
                    "message", "kind must be company, specialization, field, or unknown"
            ));
        }
        if ("unknown".equals(normalizedKind)) {
            normalizedKind = null;
        }
        var res = service.searchCommunities(jwt.getSubject(), query, normalizedKind, specializationType, cursor, lim);
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned",
                    "message", "Complete onboarding before searching communities"
            ));
            case OK -> {
                List<CommunitiesRepository.CommunityRow> visibleRows = res.items().stream()
                        .filter(row -> isUserVisible(row.kind, row.specializationType))
                        .toList();
                var fallback = logos.resolveFallbacks(visibleRows.stream()
                        .map(row -> new CommunityLogoResolver.CommunityRef(row.id, row.kind, row.imageUrl))
                        .toList());
                Long userId = users.findByFirebaseUid(jwt.getSubject()).map(u -> u.id).orElse(null);
                java.util.Set<Long> followedIds = userId == null ? java.util.Set.of()
                        : follows.followedIds(userId, visibleRows.stream().map(r -> r.id).toList());
                java.util.Set<Long> joinedIds = userId == null ? java.util.Set.of()
                        : specializationJoins.joinedIds(userId, visibleRows.stream().map(r -> r.id).toList());
                var memberCounts = this.memberCounts.memberCountsByCommunityRefs(
                        visibleRows.stream()
                                .map(r -> new CommunityMemberCountService.Ref(r.id, r.kind))
                                .toList()
                );
                List<Map<String, Object>> items = visibleRows.stream()
                        .map(row -> communityPayload(row, fallback,
                                followedIds.contains(row.id),
                                joinedIds.contains(row.id),
                                memberCounts.getOrDefault(row.id, 0)))
                        .toList();
                Map<String, Object> body = new HashMap<>();
                body.put("items", items);
                if (res.nextCursor() != null) body.put("next_cursor", res.nextCursor());
                yield ResponseEntity.ok(body);
            }
        };
    }

    private static String normalizeQueryParam(String raw) {
        if (raw == null) return null;
        if (!raw.contains("%") && !raw.contains("+")) return raw;
        try {
            return URLDecoder.decode(raw, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            return raw;
        }
    }

    @GetMapping("/communities/recommended")
    public ResponseEntity<?> recommendedCommunities(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(value = "kind", required = false) String kind,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false, defaultValue = "8") int limit
    ) {
        int lim = Math.max(1, Math.min(limit, 50));
        boolean kindProvided = kind != null && !kind.isBlank();
        String rawKind = kind == null ? null : kind.trim().toLowerCase(Locale.ROOT);
        if (kindProvided && ("school".equals(rawKind) || "major".equals(rawKind))) {
            return ResponseEntity.ok(Map.of("items", List.of()));
        }
        String normalizedKind = normalizeKind(kind);
        String specializationType = kind != null ? normalizeSpecializationTypeFromKind(kind) : null;
        if (kindProvided && normalizedKind == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "error", "invalid_kind",
                    "message", "kind must be company, specialization, field, or unknown"
            ));
        }
        if ("unknown".equals(normalizedKind)) {
            normalizedKind = null;
        }
        var res = service.recommendedCommunities(jwt.getSubject(), normalizedKind, specializationType, cursor, lim);
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned",
                    "message", "Complete onboarding before viewing recommended communities"
            ));
            case OK -> {
                List<CommunitiesRepository.RecommendedRow> visibleRows = res.items().stream()
                        .filter(row -> isUserVisible(row.kind, row.specializationType))
                        .toList();
                var fallback = logos.resolveFallbacks(visibleRows.stream()
                        .map(row -> new CommunityLogoResolver.CommunityRef(row.id, row.kind, row.imageUrl))
                        .toList());
                var memberCounts = this.memberCounts.memberCountsByCommunityRefs(
                        visibleRows.stream()
                                .map(r -> new CommunityMemberCountService.Ref(r.id, r.kind))
                                .toList()
                );
                List<Map<String, Object>> items = visibleRows.stream()
                        .map(row -> recommendedPayload(row, fallback, memberCounts.getOrDefault(row.id, 0)))
                        .toList();
                Map<String, Object> body = new HashMap<>();
                body.put("items", items);
                if (res.nextCursor() != null) body.put("next_cursor", res.nextCursor());
                yield ResponseEntity.ok(body);
            }
        };
    }

    @GetMapping("/specializations/recommended")
    public ResponseEntity<?> recommendedSpecializations(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "specializationType", required = false) String specializationTypeAlt,
            @RequestParam(value = "specialization_type", required = false) String specializationType,
            @RequestParam(value = "limit", required = false, defaultValue = "8") int limit
    ) {
        int lim = Math.max(1, Math.min(limit, 50));
        String requested = type != null ? type : (specializationTypeAlt != null ? specializationTypeAlt : specializationType);
        String normalized = normalizeRecommendedSpecializationType(requested);
        if (normalized == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "error", "invalid_specialization_type",
                    "message", "type must be major, field, or all"
            ));
        }
        if ("major".equals(normalized)) {
            return ResponseEntity.ok(Map.of("items", List.of()));
        }

        if ("all".equals(normalized)) {
            var fieldsRes = service.recommendedCommunities(jwt.getSubject(), "specialization", "field", lim);
            var fallback = logos.resolveFallbacks(
                    fieldsRes.items().stream()
                            .map(row -> new CommunityLogoResolver.CommunityRef(row.id, row.kind, row.imageUrl))
                            .toList()
            );
            var memberCounts = this.memberCounts.memberCountsByCommunityRefs(
                    fieldsRes.items().stream()
                            .map(row -> new CommunityMemberCountService.Ref(row.id, row.kind))
                            .toList()
            );
            List<Map<String, Object>> fields = fieldsRes.items().stream()
                    .map(row -> recommendedPayload(row, fallback, memberCounts.getOrDefault(row.id, 0)))
                    .toList();
            return ResponseEntity.ok(Map.of(
                    "majors", List.of(),
                    "fields", fields
            ));
        }

        var res = service.recommendedCommunities(jwt.getSubject(), "specialization", normalized, lim);
        var fallback = logos.resolveFallbacks(res.items().stream()
                .map(row -> new CommunityLogoResolver.CommunityRef(row.id, row.kind, row.imageUrl))
                .toList());
        var memberCounts = this.memberCounts.memberCountsByCommunityRefs(
                res.items().stream().map(r -> new CommunityMemberCountService.Ref(r.id, r.kind)).toList()
        );
        List<Map<String, Object>> items = res.items().stream()
                .map(row -> recommendedPayload(row, fallback, memberCounts.getOrDefault(row.id, 0)))
                .toList();
        return ResponseEntity.ok(Map.of("items", items));
    }

    @GetMapping("/specializations/browse")
    public ResponseEntity<?> browseSpecializations(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "specializationType", required = false) String specializationTypeAlt,
            @RequestParam(value = "specialization_type", required = false) String specializationType,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false, defaultValue = "20") int limit
    ) {
        int lim = Math.max(1, Math.min(limit, 100));
        String requested = type != null ? type : (specializationTypeAlt != null ? specializationTypeAlt : specializationType);
        String normalized = normalizeBrowseSpecializationType(requested);
        if (normalized == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "error", "invalid_specialization_type",
                    "message", "type must be major or field"
            ));
        }
        if ("major".equals(normalized)) {
            return ResponseEntity.ok(Map.of("items", List.of()));
        }

        var res = service.browseSpecializations(jwt.getSubject(), normalized, cursor, lim);
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned",
                    "message", "Complete onboarding before browsing specializations"
            ));
            case OK -> {
                List<CommunitiesRepository.CommunityRow> visibleRows = res.items().stream()
                        .filter(row -> isUserVisible(row.kind, row.specializationType))
                        .toList();
                var fallback = logos.resolveFallbacks(visibleRows.stream()
                        .map(row -> new CommunityLogoResolver.CommunityRef(row.id, row.kind, row.imageUrl))
                        .toList());
                Long userId = users.findByFirebaseUid(jwt.getSubject()).map(u -> u.id).orElse(null);
                java.util.Set<Long> followedIds = userId == null ? java.util.Set.of()
                        : follows.followedIds(userId, visibleRows.stream().map(r -> r.id).toList());
                java.util.Set<Long> joinedIds = userId == null ? java.util.Set.of()
                        : specializationJoins.joinedIds(userId, visibleRows.stream().map(r -> r.id).toList());
                List<Map<String, Object>> items = visibleRows.stream()
                        .map(row -> communityPayload(
                                row,
                                fallback,
                                followedIds.contains(row.id),
                                joinedIds.contains(row.id),
                                row.memberCount
                        ))
                        .toList();
                Map<String, Object> body = new HashMap<>();
                body.put("items", items);
                if (res.nextCursor() != null) body.put("next_cursor", res.nextCursor());
                yield ResponseEntity.ok(body);
            }
        };
    }

    @GetMapping("/loops/search")
    public ResponseEntity<?> searchLoops(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam("query") String query,
            @RequestParam(value = "kind", required = false) String kind,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false, defaultValue = "20") int limit
    ) {
        return searchCommunities(jwt, query, kind, cursor, limit);
    }

    @GetMapping("/hashtags/search")
    public ResponseEntity<?> searchHashtags(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam("query") String query,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false, defaultValue = "20") int limit
    ) {
        if (query == null || query.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", "query_required",
                    "message", "query must be provided"
            ));
        }
        int lim = Math.max(1, Math.min(limit, 100));
        var res = service.searchHashtags(jwt.getSubject(), query, cursor, lim);
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned",
                    "message", "Complete onboarding before searching hashtags"
            ));
            case OK -> {
                List<Map<String, Object>> items = res.items().stream().map(this::hashtagPayload).toList();
                Map<String, Object> body = new HashMap<>();
                body.put("items", items);
                if (res.nextCursor() != null) body.put("next_cursor", res.nextCursor());
                yield ResponseEntity.ok(body);
            }
        };
    }

    @GetMapping("/hashtags/{name}/posts")
    public ResponseEntity<?> hashtagPosts(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("name") String name,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false, defaultValue = "20") int limit
    ) {
        int lim = Math.max(1, Math.min(limit, 100));
        var res = service.postsByHashtag(jwt.getSubject(), name, cursor, lim);
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned",
                    "message", "Complete onboarding before viewing hashtag posts"
            ));
            case INVALID_QUERY -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", "invalid_hashtag",
                    "message", "Hashtag is invalid"
            ));
            case OK -> {
                List<Map<String, Object>> items = res.posts().stream().map(PostPayloads::from).toList();
                Map<String, Object> body = new HashMap<>();
                body.put("items", items);
                if (res.nextCursor() != null) body.put("next_cursor", res.nextCursor());
                yield ResponseEntity.ok(body);
            }
        };
    }

    private Map<String, Object> communityPayload(CommunitiesRepository.CommunityRow row,
                                                 Map<Long, String> fallbacks,
                                                 boolean isFollowing,
                                                 boolean isJoined,
                                                 int memberCount) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", row.id);
        map.put("kind", row.kind);
        map.put("name", row.name);
        if (row.shortName != null && !row.shortName.isBlank()) {
            map.put("short_name", row.shortName);
        }
        map.put("description", row.description);
        map.put("member_count", memberCount);
        map.put("is_following", isFollowing);
        if ("specialization".equalsIgnoreCase(row.kind)) {
            map.put("is_joined", isJoined);
        }
        if (row.specializationType != null) map.put("specialization_type", row.specializationType);
        if ("specialization".equalsIgnoreCase(row.kind)) {
            Map<String, Object> icon = com.looped.communities.SpecializationIcons.payloadOrNull(row.iconKind, row.iconValue);
            if (icon != null) map.put("icon", icon);
            com.looped.communities.SpecializationBrandingPayloads.putPayload(
                    map,
                    row.specializationIconImageUrl,
                    row.specializationBannerImageUrl
            );
        }
        String fallback = fallbacks != null ? fallbacks.get(row.id) : logos.resolve(row.id, row.kind, row.imageUrl);
        CommunityImageSlots.putPayload(map, row.imageUrl, row.profileImageUrl, fallback);
        return map;
    }

    private Map<String, Object> hashtagPayload(HashtagsRepository.HashtagRow row) {
        Map<String, Object> map = new HashMap<>();
        map.put("name", row.name);
        map.put("usage_count", row.usageCount);
        return map;
    }

    private Map<String, Object> recommendedPayload(CommunitiesRepository.RecommendedRow row, Map<Long, String> fallbacks, int memberCount) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", row.id);
        map.put("kind", row.kind);
        map.put("name", row.name);
        if (row.shortName != null && !row.shortName.isBlank()) {
            map.put("short_name", row.shortName);
        }
        map.put("description", row.description);
        map.put("member_count", memberCount);
        map.put("is_following", row.isFollowing);
        map.put("is_joined", row.isJoined);
        if (row.specializationType != null) map.put("specialization_type", row.specializationType);
        if ("specialization".equalsIgnoreCase(row.kind)) {
            Map<String, Object> icon = com.looped.communities.SpecializationIcons.payloadOrNull(row.iconKind, row.iconValue);
            if (icon != null) map.put("icon", icon);
            com.looped.communities.SpecializationBrandingPayloads.putPayload(
                    map,
                    row.specializationIconImageUrl,
                    row.specializationBannerImageUrl
            );
        }
        String fallback = fallbacks != null ? fallbacks.get(row.id) : logos.resolve(row.id, row.kind, row.imageUrl);
        CommunityImageSlots.putPayload(map, row.imageUrl, row.profileImageUrl, fallback);
        return map;
    }

    private String normalizeKind(String raw) {
        if (raw == null) return null;
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) return null;
        if (normalized.equals("unknown")) {
            return "unknown";
        }
        if (normalized.equals("field")) {
            normalized = "specialization";
        }
        if (!normalized.equals("company")) {
            if (!normalized.equals("specialization")) {
                return null;
            }
        }
        return normalized;
    }

    private String normalizeSpecializationTypeFromKind(String raw) {
        if (raw == null) return null;
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) return null;
        if (normalized.equals("field")) {
            return normalized;
        }
        return null;
    }

    private String normalizeRecommendedSpecializationType(String raw) {
        if (raw == null) return "all";
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank() || "all".equals(normalized)) return "all";
        if ("major".equals(normalized) || "field".equals(normalized)) return normalized;
        return null;
    }

    private String normalizeBrowseSpecializationType(String raw) {
        if (raw == null) return null;
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) return null;
        if ("major".equals(normalized) || "field".equals(normalized)) return normalized;
        return null;
    }
}

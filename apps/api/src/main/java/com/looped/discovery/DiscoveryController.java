package com.looped.discovery;

import com.looped.communities.CommunitiesRepository;
import com.looped.communities.CommunityLogoResolver;
import com.looped.posts.PostPayloads;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/v1")
public class DiscoveryController {
    private final DiscoveryService service;
    private final CommunityLogoResolver logos;

    public DiscoveryController(DiscoveryService service, CommunityLogoResolver logos) {
        this.service = service;
        this.logos = logos;
    }

    @GetMapping("/communities/search")
    public ResponseEntity<?> searchCommunities(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam("query") String query,
            @RequestParam(value = "kind", required = false) String kind,
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
        boolean kindProvided = kind != null && !kind.isBlank();
        String normalizedKind = normalizeKind(kind);
        String specializationType = kind != null ? normalizeSpecializationTypeFromKind(kind) : null;
        if (kindProvided && normalizedKind == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "error", "invalid_kind",
                    "message", "kind must be company, school, sector, profession, specialization, major, department, or unknown"
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
                var fallback = logos.resolveFallbacks(res.items().stream()
                        .map(row -> new CommunityLogoResolver.CommunityRef(row.id, row.kind, row.imageUrl))
                        .toList());
                List<Map<String, Object>> items = res.items().stream()
                        .map(row -> communityPayload(row, fallback))
                        .toList();
                Map<String, Object> body = new HashMap<>();
                body.put("items", items);
                if (res.nextCursor() != null) body.put("next_cursor", res.nextCursor());
                yield ResponseEntity.ok(body);
            }
        };
    }

    @GetMapping("/communities/recommended")
    public ResponseEntity<?> recommendedCommunities(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(value = "kind", required = false) String kind,
            @RequestParam(value = "limit", required = false, defaultValue = "8") int limit
    ) {
        int lim = Math.max(1, Math.min(limit, 50));
        boolean kindProvided = kind != null && !kind.isBlank();
        String normalizedKind = normalizeKind(kind);
        String specializationType = kind != null ? normalizeSpecializationTypeFromKind(kind) : null;
        if (kindProvided && normalizedKind == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "error", "invalid_kind",
                    "message", "kind must be company, school, sector, profession, specialization, major, department, or unknown"
            ));
        }
        if ("unknown".equals(normalizedKind)) {
            normalizedKind = null;
        }
        var res = service.recommendedCommunities(jwt.getSubject(), normalizedKind, specializationType, lim);
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned",
                    "message", "Complete onboarding before viewing recommended communities"
            ));
            case OK -> {
                var fallback = logos.resolveFallbacks(res.items().stream()
                        .map(row -> new CommunityLogoResolver.CommunityRef(row.id, row.kind, row.imageUrl))
                        .toList());
                List<Map<String, Object>> items = res.items().stream()
                        .map(row -> recommendedPayload(row, fallback))
                        .toList();
                yield ResponseEntity.ok(Map.of("items", items));
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

    private Map<String, Object> communityPayload(CommunitiesRepository.CommunityRow row, Map<Long, String> fallbacks) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", row.id);
        map.put("kind", row.kind);
        map.put("name", row.name);
        map.put("description", row.description);
        map.put("member_count", row.memberCount);
        if (row.specializationType != null) map.put("specialization_type", row.specializationType);
        String resolved = row.imageUrl;
        if ((resolved == null || resolved.isBlank()) && fallbacks != null) {
            resolved = fallbacks.get(row.id);
        } else if (resolved == null || resolved.isBlank()) {
            resolved = logos.resolve(row.id, row.kind, row.imageUrl);
        }
        if (resolved != null && !resolved.isBlank()) map.put("image_url", resolved);
        return map;
    }

    private Map<String, Object> hashtagPayload(HashtagsRepository.HashtagRow row) {
        Map<String, Object> map = new HashMap<>();
        map.put("name", row.name);
        map.put("usage_count", row.usageCount);
        return map;
    }

    private Map<String, Object> recommendedPayload(CommunitiesRepository.RecommendedRow row, Map<Long, String> fallbacks) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", row.id);
        map.put("kind", row.kind);
        map.put("name", row.name);
        map.put("description", row.description);
        map.put("member_count", row.memberCount);
        map.put("is_following", row.isFollowing);
        if (row.specializationType != null) map.put("specialization_type", row.specializationType);
        String resolved = row.imageUrl;
        if ((resolved == null || resolved.isBlank()) && fallbacks != null) {
            resolved = fallbacks.get(row.id);
        } else if (resolved == null || resolved.isBlank()) {
            resolved = logos.resolve(row.id, row.kind, row.imageUrl);
        }
        if (resolved != null && !resolved.isBlank()) map.put("image_url", resolved);
        return map;
    }

    private String normalizeKind(String raw) {
        if (raw == null) return null;
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) return null;
        if (normalized.equals("profession") || normalized.equals("proffesion")) {
            normalized = "sector";
        }
        if (normalized.equals("unknown")) {
            return "unknown";
        }
        if (normalized.equals("major") || normalized.equals("department")) {
            normalized = "specialization";
        }
        if (!normalized.equals("company") && !normalized.equals("school") && !normalized.equals("sector")) {
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
        if (normalized.equals("major") || normalized.equals("department")) {
            return normalized;
        }
        return null;
    }
}

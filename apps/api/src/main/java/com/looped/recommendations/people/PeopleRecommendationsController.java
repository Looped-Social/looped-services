package com.looped.recommendations.people;

import com.looped.settings.AppConfigService;
import com.looped.users.ProfileImageUrls;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/v1/recommendations/people")
class PeopleRecommendationsController {
    private final PeopleRecommendationService service;
    private final AppConfigService appConfig;

    PeopleRecommendationsController(PeopleRecommendationService service, AppConfigService appConfig) {
        this.service = service;
        this.appConfig = appConfig;
    }

    @GetMapping("/rails")
    public ResponseEntity<?> rails(@AuthenticationPrincipal Jwt jwt,
                                   @RequestParam(value = "surface", required = false) String surface,
                                   @RequestParam(value = "community_id", required = false) Long communityId,
                                   @RequestParam(value = "rails", required = false) String rails,
                                   @RequestParam(value = "limit_per_rail", required = false, defaultValue = "10") int limitPerRail) {
        if (jwt == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "error", "unauthorized",
                    "message", "Authorization is required"
            ));
        }

        var parsedSurface = PeopleRecommendationTypes.Surface.parse(surface);
        if (parsedSurface == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "invalid_surface",
                    "message", "surface must be one of: search, onboarding, feed_card, profile_similar, inbox_empty"
            ));
        }

        int limit = Math.max(1, Math.min(limitPerRail, 25));
        List<PeopleRecommendationTypes.Rail> parsedRails = parseRails(rails);
        if (rails != null && !rails.isBlank() && parsedRails == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "invalid_rails",
                    "message", "rails must be a comma-separated subset of: pymk, community, active_community"
            ));
        }

        var result = service.rails(jwt.getSubject(), parsedSurface, communityId, parsedRails, limit);
        if (result.status() == PeopleRecommendationService.Status.USER_NOT_PROVISIONED) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "user_not_provisioned"));
        }
        if (result.status() == PeopleRecommendationService.Status.COMMUNITY_NOT_FOUND) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "community_not_found",
                    "message", "community_id does not exist"
            ));
        }

        String defaultProfileImageUrl = appConfig.defaultProfileImageUrl();
        List<Map<String, Object>> railPayloads = new ArrayList<>();
        for (var railPage : result.rails()) {
            Map<String, Object> railPayload = new HashMap<>();
            railPayload.put("rail", railPage.rail().wire());
            railPayload.put("title", railPage.title());
            railPayload.put("items", railPage.items().stream().map(item -> itemPayload(item, defaultProfileImageUrl)).toList());
            railPayload.put("has_more", railPage.hasMore());
            if (railPage.nextCursor() != null) railPayload.put("next_cursor", railPage.nextCursor());
            railPayload.put("degraded", railPage.degraded());
            railPayloads.add(railPayload);
        }

        Map<String, Object> body = new HashMap<>();
        body.put("request_id", result.requestId());
        body.put("surface", parsedSurface.wire());
        if (result.community() != null) {
            body.put("community", Map.of(
                    "id", result.community().id(),
                    "name", result.community().name()
            ));
        }
        body.put("rails", railPayloads);
        if (result.experiment() != null) {
            body.put("experiment", Map.of(
                    "key", result.experiment().key(),
                    "bucket", result.experiment().bucket()
            ));
        }
        body.put("degraded", result.degraded());
        body.put("generated_at", OffsetDateTime.now().toString());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/{rail}")
    public ResponseEntity<?> rail(@AuthenticationPrincipal Jwt jwt,
                                  @PathVariable("rail") String railRaw,
                                  @RequestParam(value = "surface", required = false) String surface,
                                  @RequestParam(value = "community_id", required = false) Long communityId,
                                  @RequestParam(value = "limit", required = false, defaultValue = "20") int limit,
                                  @RequestParam(value = "cursor", required = false) String cursor) {
        if (jwt == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "error", "unauthorized",
                    "message", "Authorization is required"
            ));
        }

        var rail = PeopleRecommendationTypes.Rail.parse(railRaw);
        if (rail == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "invalid_rail",
                    "message", "rail must be one of: pymk, community, active_community"
            ));
        }

        var parsedSurface = PeopleRecommendationTypes.Surface.parse(surface);
        if (parsedSurface == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "invalid_surface",
                    "message", "surface must be one of: search, onboarding, feed_card, profile_similar, inbox_empty"
            ));
        }

        int normalizedLimit = Math.max(1, Math.min(limit, 50));

        var result = service.rail(jwt.getSubject(), rail, parsedSurface, communityId, cursor, normalizedLimit);
        if (result.status() == PeopleRecommendationService.Status.USER_NOT_PROVISIONED) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "user_not_provisioned"));
        }
        if (result.status() == PeopleRecommendationService.Status.COMMUNITY_NOT_FOUND) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "community_not_found",
                    "message", "community_id does not exist"
            ));
        }
        if (result.status() == PeopleRecommendationService.Status.INVALID_CURSOR) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "invalid_cursor",
                    "message", "cursor is malformed, expired, or does not match the current rail"
            ));
        }

        String defaultProfileImageUrl = appConfig.defaultProfileImageUrl();

        Map<String, Object> body = new HashMap<>();
        body.put("request_id", result.requestId());
        body.put("rail", result.rail().rail().wire());
        body.put("title", result.rail().title());
        body.put("items", result.rail().items().stream().map(item -> itemPayload(item, defaultProfileImageUrl)).toList());
        body.put("has_more", result.rail().hasMore());
        if (result.rail().nextCursor() != null) body.put("next_cursor", result.rail().nextCursor());
        body.put("degraded", result.rail().degraded());
        if (result.community() != null) {
            body.put("community", Map.of(
                    "id", result.community().id(),
                    "name", result.community().name()
            ));
        }
        if (result.experiment() != null) {
            body.put("experiment", Map.of(
                    "key", result.experiment().key(),
                    "bucket", result.experiment().bucket()
            ));
        }
        return ResponseEntity.ok(body);
    }

    @PostMapping("/feedback")
    public ResponseEntity<?> feedback(@AuthenticationPrincipal Jwt jwt,
                                      @RequestBody(required = false) PeopleRecommendationRequests.FeedbackRequest body) {
        if (jwt == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "error", "unauthorized",
                    "message", "Authorization is required"
            ));
        }

        var res = service.feedback(jwt.getSubject(), body);
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "user_not_provisioned"));
            case INVALID_BODY -> ResponseEntity.badRequest().body(Map.of(
                    "error", "invalid_body",
                    "message", "events array is required"
            ));
            case PAYLOAD_TOO_LARGE -> ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of(
                    "error", "payload_too_large",
                    "message", "Too many events"
            ));
            case OK -> ResponseEntity.ok(Map.of(
                    "request_id", res.requestId(),
                    "accepted", res.accepted(),
                    "deduped", res.deduped(),
                    "dropped", res.dropped(),
                    "suppressed_candidate_ids", res.suppressedCandidateIds()
            ));
            default -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "error", "internal_error"
            ));
        };
    }

    private Map<String, Object> itemPayload(PeopleRecommendationService.RecommendationItem item,
                                            String defaultProfileImageUrl) {
        var row = item.row();
        Map<String, Object> user = new HashMap<>();
        user.put("id", row.userId);
        user.put("handle", row.handle);
        user.put("display_name", resolvedDisplayName(row.displayName, row.handle));
        user.put("avatar_url", ProfileImageUrls.resolve(row.profileImageUrl, defaultProfileImageUrl));
        user.put("headline", row.bio);
        if (row.displayCommunityId != null || row.displayCommunityName != null) {
            Map<String, Object> community = new HashMap<>();
            if (row.displayCommunityId != null) community.put("id", row.displayCommunityId);
            if (row.displayCommunityName != null && !row.displayCommunityName.isBlank()) community.put("name", row.displayCommunityName);
            user.put("community", community);
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("recommendation_id", item.recommendationId());
        payload.put("user", user);
        payload.put("reasons", item.reasons().stream().map(r -> Map.of("code", r.code(), "text", r.text())).toList());
        payload.put("actions", Map.of(
                "can_connect", true,
                "can_hide", true,
                "can_less_like_this", true
        ));
        payload.put("tracking", Map.of(
                "token", item.trackingToken(),
                "position", item.position()
        ));
        return payload;
    }

    private String resolvedDisplayName(String displayName, String handle) {
        if (displayName != null && !displayName.isBlank()) return displayName;
        return handle == null ? "" : handle;
    }

    private List<PeopleRecommendationTypes.Rail> parseRails(String railsRaw) {
        if (railsRaw == null || railsRaw.isBlank()) return List.of();
        List<PeopleRecommendationTypes.Rail> rails = new ArrayList<>();
        for (String part : railsRaw.split(",")) {
            if (part == null || part.isBlank()) continue;
            var rail = PeopleRecommendationTypes.Rail.parse(part.trim().toLowerCase(Locale.ROOT));
            if (rail == null) return null;
            if (!rails.contains(rail)) rails.add(rail);
        }
        return rails;
    }
}

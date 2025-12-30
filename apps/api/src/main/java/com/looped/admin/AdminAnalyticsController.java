package com.looped.admin;

import com.looped.communities.CommunityLogoResolver;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/v1/admin/analytics")
public class AdminAnalyticsController {
    private final AdminAuthService auth;
    private final AdminAnalyticsRepository analytics;
    private final CommunityLogoResolver logos;

    public AdminAnalyticsController(AdminAuthService auth,
                                    AdminAnalyticsRepository analytics,
                                    CommunityLogoResolver logos) {
        this.auth = auth;
        this.analytics = analytics;
        this.logos = logos;
    }

    @GetMapping("/communities/leaderboard")
    public ResponseEntity<?> communityLeaderboard(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(value = "metric", required = false) String metric,
            @RequestParam(value = "communityId", required = false) Long communityId,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "limit", required = false, defaultValue = "50") int limit
    ) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.VIEW_REPORTS);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        var range = parseRange(from, to);
        if (range.status() != RangeStatus.OK) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "invalid_date"));
        }
        String orderByExpr = orderByExpr(metric);
        if (orderByExpr == null) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "invalid_metric"));
        }
        int lim = Math.max(1, Math.min(limit, 200));
        List<AdminAnalyticsRepository.CommunityMetricRow> rows = analytics.communityLeaderboard(
                communityId, range.from, range.to, orderByExpr, lim
        );
        if (communityId != null && rows.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "community_not_found"));
        }
        var fallback = logos.resolveFallbacks(rows.stream()
                .map(row -> new CommunityLogoResolver.CommunityRef(row.id, row.kind, row.imageUrl))
                .toList());
        List<Map<String, Object>> items = rows.stream().map(r -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", r.id);
            map.put("kind", r.kind);
            map.put("name", r.name);
            String resolved = r.imageUrl;
            if ((resolved == null || resolved.isBlank()) && fallback != null) {
                resolved = fallback.get(r.id);
            } else if (resolved == null || resolved.isBlank()) {
                resolved = logos.resolve(r.id, r.kind, r.imageUrl);
            }
            if (resolved != null && !resolved.isBlank()) map.put("image_url", resolved);
            map.put("likes_count", r.likesCount);
            map.put("shares_count", r.sharesCount);
            map.put("followers_count", r.followersCount);
            map.put("verifications_count", r.verificationsCount);
            map.put("accounts_total", r.followersCount + r.verificationsCount);
            return map;
        }).toList();
        return ResponseEntity.ok(Map.of("items", items));
    }

    @GetMapping("/hashtags")
    public ResponseEntity<?> hashtagLeaderboard(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(value = "communityId", required = false) Long communityId,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "limit", required = false, defaultValue = "50") int limit
    ) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.VIEW_REPORTS);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        var range = parseRange(from, to);
        if (range.status() != RangeStatus.OK) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "invalid_date"));
        }
        int lim = Math.max(1, Math.min(limit, 200));
        List<AdminAnalyticsRepository.HashtagMetricRow> rows = analytics.hashtagsLeaderboard(
                communityId, range.from, range.to, lim
        );
        List<Map<String, Object>> items = rows.stream().map(r -> Map.<String, Object>of(
                "id", r.id,
                "name", r.name,
                "usage_count", r.usageCount
        )).toList();
        return ResponseEntity.ok(Map.of("items", items));
    }

    @GetMapping("/users")
    public ResponseEntity<?> userStats(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to
    ) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.VIEW_REPORTS);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        var range = parseRange(from, to);
        if (range.status() != RangeStatus.OK) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "invalid_date"));
        }
        var stats = analytics.userStats(range.from, range.to);
        Map<String, Object> body = new HashMap<>();
        body.put("total_users", stats.totalUsers);
        body.put("new_users", stats.newUsers);
        body.put("deleted_users", stats.deletedUsers);
        return ResponseEntity.ok(body);
    }

    private String orderByExpr(String metric) {
        String normalized = metric != null ? metric.trim().toLowerCase(Locale.ROOT) : "likes";
        return switch (normalized) {
            case "likes" -> "t.likes_count";
            case "shares" -> "t.shares_count";
            case "followers" -> "t.followers_count";
            case "verifications" -> "t.verifications_count";
            case "accounts" -> "(t.followers_count + t.verifications_count)";
            default -> null;
        };
    }

    private Range parseRange(String from, String to) {
        OffsetDateTime fromTs = null;
        OffsetDateTime toTs = null;
        try {
            if (from != null && !from.isBlank()) {
                LocalDate start = LocalDate.parse(from);
                fromTs = start.atStartOfDay().atOffset(ZoneOffset.UTC);
            }
            if (to != null && !to.isBlank()) {
                LocalDate end = LocalDate.parse(to);
                toTs = end.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);
            }
        } catch (DateTimeParseException e) {
            return new Range(RangeStatus.INVALID_DATE, null, null);
        }
        return new Range(RangeStatus.OK, fromTs, toTs);
    }

    private enum RangeStatus { OK, INVALID_DATE }

    private record Range(RangeStatus status, OffsetDateTime from, OffsetDateTime to) {}
}

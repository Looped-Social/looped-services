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
    private static final int DEFAULT_RANGE_DAYS = 30;
    private static final int MAX_RANGE_DAYS = 366;

    private final AdminAuthService auth;
    private final AdminAnalyticsRepository analytics;
    private final CommunityLogoResolver logos;
    private final AdminDashboardAnalyticsService dashboard;

    public AdminAnalyticsController(AdminAuthService auth,
                                    AdminAnalyticsRepository analytics,
                                    CommunityLogoResolver logos,
                                    AdminDashboardAnalyticsService dashboard) {
        this.auth = auth;
        this.analytics = analytics;
        this.logos = logos;
        this.dashboard = dashboard;
    }

    @GetMapping("/dashboard")
    public ResponseEntity<?> dashboard(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(value = "community_id", required = false) Long communityIdSnake,
            @RequestParam(value = "communityId", required = false) Long communityIdCamel,
            @RequestParam(value = "audience", required = false) String audience,
            @RequestParam(value = "to", required = false) String to
    ) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.VIEW_REPORTS);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }

        Long communityId = communityIdSnake != null ? communityIdSnake : communityIdCamel;
        if (communityId != null && !analytics.communityExists(communityId)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "community_not_found"));
        }

        LocalDate toDay;
        try {
            toDay = (to == null || to.isBlank()) ? LocalDate.now(ZoneOffset.UTC) : LocalDate.parse(to);
        } catch (DateTimeParseException e) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "invalid_date"));
        }

        AdminDashboardAudience aud = AdminDashboardAudience.parseOrDefault(audience);
        if (aud == null) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "invalid_audience"));
        }

        return ResponseEntity.ok(dashboard.dashboard(toDay, communityId, aud));
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

    @GetMapping("/kpis/active-users")
    public ResponseEntity<?> activeUsers(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to
    ) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.VIEW_REPORTS);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }

        var range = parseRangeWithDefaults(from, to);
        if (range.status() != RangeStatus.OK) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "invalid_date"));
        }

        long days = java.time.Duration.between(range.from, range.to).toDays();
        if (days <= 0 || days > MAX_RANGE_DAYS) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "invalid_range"));
        }

        var rows = analytics.activeUsersDaily(range.from, range.to);
        List<Map<String, Object>> items = rows.stream().map(r -> {
            Map<String, Object> out = new HashMap<>();
            out.put("day", r.day);
            out.put("dau", r.dau);
            out.put("mau_30d", r.mau30d);
            double ratio = r.mau30d <= 0 ? 0.0 : (double) r.dau / (double) r.mau30d;
            out.put("dau_mau_ratio", ratio);
            return out;
        }).toList();
        return ResponseEntity.ok(Map.of("items", items));
    }

    @GetMapping("/kpis/communities/daily")
    public ResponseEntity<?> communityDaily(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam("communityId") long communityId,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to
    ) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.VIEW_REPORTS);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        if (!analytics.communityExists(communityId)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "community_not_found"));
        }
        var range = parseRangeWithDefaults(from, to);
        if (range.status() != RangeStatus.OK) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "invalid_date"));
        }
        long days = java.time.Duration.between(range.from, range.to).toDays();
        if (days <= 0 || days > MAX_RANGE_DAYS) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "invalid_range"));
        }
        var rows = analytics.communityDailyMetrics(communityId, range.from, range.to);
        List<Map<String, Object>> items = rows.stream().map(r -> {
            Map<String, Object> out = new HashMap<>();
            out.put("day", r.day);
            out.put("posts_count", r.postsCount);
            out.put("comments_count", r.commentsCount);
            out.put("post_likes_count", r.postLikesCount);
            out.put("post_shares_count", r.postSharesCount);
            out.put("unique_posters", r.uniquePosters);
            out.put("unique_commenters", r.uniqueCommenters);
            out.put("unique_post_likers", r.uniquePostLikers);
            out.put("unique_post_sharers", r.uniquePostSharers);
            double commentToPost = r.postsCount <= 0 ? 0.0 : (double) r.commentsCount / (double) r.postsCount;
            out.put("comment_to_post_ratio", commentToPost);
            return out;
        }).toList();
        return ResponseEntity.ok(Map.of(
                "community_id", communityId,
                "items", items
        ));
    }

    @GetMapping("/kpis/communities/retention")
    public ResponseEntity<?> communityRetention(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam("communityId") long communityId,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to
    ) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.VIEW_REPORTS);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        if (!analytics.communityExists(communityId)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "community_not_found"));
        }
        var range = parseRangeWithDefaults(from, to);
        if (range.status() != RangeStatus.OK) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "invalid_date"));
        }
        long days = java.time.Duration.between(range.from, range.to).toDays();
        if (days <= 0 || days > MAX_RANGE_DAYS) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "invalid_range"));
        }
        var rows = analytics.communityRetentionDaily(communityId, range.from, range.to);
        List<Map<String, Object>> items = rows.stream().map(r -> {
            Map<String, Object> out = new HashMap<>();
            out.put("cohort_day", r.cohortDay);
            out.put("cohort_size", r.cohortSize);
            out.put("retained_d1", r.retainedD1);
            out.put("retained_d7", r.retainedD7);
            out.put("retained_d30", r.retainedD30);
            double d1 = r.cohortSize <= 0 ? 0.0 : (double) r.retainedD1 / (double) r.cohortSize;
            double d7 = r.cohortSize <= 0 ? 0.0 : (double) r.retainedD7 / (double) r.cohortSize;
            double d30 = r.cohortSize <= 0 ? 0.0 : (double) r.retainedD30 / (double) r.cohortSize;
            out.put("retention_d1", d1);
            out.put("retention_d7", d7);
            out.put("retention_d30", d30);
            return out;
        }).toList();
        return ResponseEntity.ok(Map.of(
                "community_id", communityId,
                "items", items
        ));
    }

    @GetMapping("/kpis/trust-safety")
    public ResponseEntity<?> trustSafety(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to
    ) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.VIEW_REPORTS);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        var range = parseRangeWithDefaults(from, to);
        if (range.status() != RangeStatus.OK) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "invalid_date"));
        }
        long days = java.time.Duration.between(range.from, range.to).toDays();
        if (days <= 0 || days > MAX_RANGE_DAYS) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "invalid_range"));
        }
        var stats = analytics.trustSafetySummary(range.from, range.to);
        Map<String, Object> out = new HashMap<>();
        out.put("total_users", stats.totalUsers);
        out.put("verified_users_global", stats.verifiedUsersGlobal);
        out.put("verified_users_any_community", stats.verifiedUsersAnyCommunity);
        out.put("verified_percent_global", stats.totalUsers <= 0 ? 0.0 : (double) stats.verifiedUsersGlobal / (double) stats.totalUsers);
        out.put("verified_percent_any_community", stats.totalUsers <= 0 ? 0.0 : (double) stats.verifiedUsersAnyCommunity / (double) stats.totalUsers);

        out.put("posts_total", stats.postsTotal);
        out.put("posts_anon", stats.postsAnon);
        out.put("posts_anon_rate", stats.postsTotal <= 0 ? 0.0 : (double) stats.postsAnon / (double) stats.postsTotal);

        out.put("comments_total", stats.commentsTotal);
        out.put("comments_anon", stats.commentsAnon);
        out.put("comments_anon_rate", stats.commentsTotal <= 0 ? 0.0 : (double) stats.commentsAnon / (double) stats.commentsTotal);

        out.put("likes_total", stats.likesTotal);
        out.put("likes_anon", stats.likesAnon);
        out.put("likes_anon_rate", stats.likesTotal <= 0 ? 0.0 : (double) stats.likesAnon / (double) stats.likesTotal);

        out.put("appeals_reviewed", stats.appealsReviewed);
        out.put("appeals_approved", stats.appealsApproved);
        out.put("appeal_success_rate", stats.appealsReviewed <= 0 ? 0.0 : (double) stats.appealsApproved / (double) stats.appealsReviewed);
        return ResponseEntity.ok(out);
    }

    @GetMapping("/kpis/growth/users/daily")
    public ResponseEntity<?> newUsersDaily(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to
    ) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.VIEW_REPORTS);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        var range = parseRangeWithDefaults(from, to);
        if (range.status() != RangeStatus.OK) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "invalid_date"));
        }
        long days = java.time.Duration.between(range.from, range.to).toDays();
        if (days <= 0 || days > MAX_RANGE_DAYS) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "invalid_range"));
        }

        var rows = analytics.newUsersDaily(range.from, range.to);
        List<Map<String, Object>> items = rows.stream().map(r -> Map.<String, Object>of(
                "day", r.day,
                "new_users", r.createdUsers,
                "deleted_users", r.deletedUsers
        )).toList();
        return ResponseEntity.ok(Map.of("items", items));
    }

    @GetMapping("/kpis/growth/users/weekly")
    public ResponseEntity<?> newUsersWeekly(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to
    ) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.VIEW_REPORTS);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        var range = parseRangeWithDefaults(from, to);
        if (range.status() != RangeStatus.OK) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "invalid_date"));
        }
        long days = java.time.Duration.between(range.from, range.to).toDays();
        if (days <= 0 || days > MAX_RANGE_DAYS) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "invalid_range"));
        }

        var rows = analytics.newUsersWeekly(range.from, range.to);
        List<Map<String, Object>> items = rows.stream().map(r -> Map.<String, Object>of(
                "week_start", r.weekStart,
                "new_users", r.createdUsers,
                "deleted_users", r.deletedUsers
        )).toList();
        return ResponseEntity.ok(Map.of("items", items));
    }

    @GetMapping("/kpis/content/creation/daily")
    public ResponseEntity<?> contentCreationDaily(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to
    ) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.VIEW_REPORTS);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        var range = parseRangeWithDefaults(from, to);
        if (range.status() != RangeStatus.OK) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "invalid_date"));
        }
        long days = java.time.Duration.between(range.from, range.to).toDays();
        if (days <= 0 || days > MAX_RANGE_DAYS) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "invalid_range"));
        }

        var rows = analytics.contentCreationDaily(range.from, range.to);
        List<Map<String, Object>> items = rows.stream().map(r -> {
            Map<String, Object> out = new HashMap<>();
            out.put("day", r.day);
            out.put("active_users", r.activeUsers);
            out.put("creators", r.creators);
            out.put("creator_rate", r.activeUsers <= 0 ? 0.0 : (double) r.creators / (double) r.activeUsers);
            return out;
        }).toList();
        return ResponseEntity.ok(Map.of("items", items));
    }

    @GetMapping("/kpis/communities/posts-per-active/daily")
    public ResponseEntity<?> postsPerActiveCommunityDaily(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(value = "kind", required = false) String kind,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to
    ) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.VIEW_REPORTS);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        String normalizedKind = kind != null && !kind.isBlank() ? kind.trim().toLowerCase(Locale.ROOT) : null;
        var range = parseRangeWithDefaults(from, to);
        if (range.status() != RangeStatus.OK) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "invalid_date"));
        }
        long days = java.time.Duration.between(range.from, range.to).toDays();
        if (days <= 0 || days > MAX_RANGE_DAYS) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "invalid_range"));
        }
        var rows = analytics.postsPerActiveCommunityDaily(range.from, range.to, normalizedKind);
        List<Map<String, Object>> items = rows.stream().map(r -> {
            Map<String, Object> out = new HashMap<>();
            out.put("day", r.day);
            out.put("posts_count", r.postsCount);
            out.put("active_communities", r.activeCommunities);
            out.put("posts_per_active_community", r.activeCommunities <= 0 ? 0.0 : (double) r.postsCount / (double) r.activeCommunities);
            return out;
        }).toList();
        Map<String, Object> body = new HashMap<>();
        body.put("items", items);
        if (normalizedKind != null) body.put("kind", normalizedKind);
        return ResponseEntity.ok(body);
    }

    @GetMapping("/kpis/posts/unique-participants")
    public ResponseEntity<?> uniqueParticipantsPerPost(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam("communityId") long communityId,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to
    ) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.VIEW_REPORTS);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        if (!analytics.communityExists(communityId)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "community_not_found"));
        }
        var range = parseRangeWithDefaults(from, to);
        if (range.status() != RangeStatus.OK) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "invalid_date"));
        }
        long days = java.time.Duration.between(range.from, range.to).toDays();
        if (days <= 0 || days > MAX_RANGE_DAYS) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "invalid_range"));
        }
        var stats = analytics.uniqueParticipantsPerPost(communityId, range.from, range.to);
        return ResponseEntity.ok(Map.of(
                "community_id", communityId,
                "posts_count", stats.postsCount,
                "avg_unique_participants_per_post", stats.avgParticipants,
                "p50_unique_participants_per_post", stats.p50Participants,
                "p90_unique_participants_per_post", stats.p90Participants
        ));
    }

    @GetMapping("/kpis/retention/by-kind")
    public ResponseEntity<?> retentionByKind(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(value = "kinds", required = false) String kinds,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to
    ) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.VIEW_REPORTS);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        var range = parseRangeWithDefaults(from, to);
        if (range.status() != RangeStatus.OK) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "invalid_date"));
        }
        long days = java.time.Duration.between(range.from, range.to).toDays();
        if (days <= 0 || days > MAX_RANGE_DAYS) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "invalid_range"));
        }

        List<String> requestedKinds;
        if (kinds == null || kinds.isBlank()) {
            requestedKinds = List.of("company");
        } else {
            requestedKinds = java.util.Arrays.stream(kinds.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .map(s -> s.toLowerCase(Locale.ROOT))
                    .distinct()
                    .limit(10)
                    .toList();
        }

        List<Map<String, Object>> items = requestedKinds.stream().map(k -> {
            var stats = analytics.retentionByKind(range.from, range.to, k);
            Map<String, Object> out = new HashMap<>();
            out.put("kind", k);
            out.put("cohort_size", stats.cohortSize);
            out.put("retained_d1", stats.retainedD1);
            out.put("retained_d7", stats.retainedD7);
            out.put("retained_d30", stats.retainedD30);
            out.put("retention_d1", stats.cohortSize <= 0 ? 0.0 : (double) stats.retainedD1 / (double) stats.cohortSize);
            out.put("retention_d7", stats.cohortSize <= 0 ? 0.0 : (double) stats.retainedD7 / (double) stats.cohortSize);
            out.put("retention_d30", stats.cohortSize <= 0 ? 0.0 : (double) stats.retainedD30 / (double) stats.cohortSize);
            return out;
        }).toList();

        return ResponseEntity.ok(Map.of(
                "items", items
        ));
    }

    @GetMapping("/kpis/users/time-to-first-actions")
    public ResponseEntity<?> timeToFirstActions(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to
    ) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.VIEW_REPORTS);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        var range = parseRangeWithDefaults(from, to);
        if (range.status() != RangeStatus.OK) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "invalid_date"));
        }
        long days = java.time.Duration.between(range.from, range.to).toDays();
        if (days <= 0 || days > MAX_RANGE_DAYS) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "invalid_range"));
        }
        var stats = analytics.timeToFirstActions(range.from, range.to);
        Map<String, Object> out = new HashMap<>();
        out.put("cohort_size", stats.cohortSize);
        out.put("users_with_meaningful_action", stats.usersWithMeaningful);
        out.put("time_to_first_meaningful_action_p50_seconds", stats.meaningfulP50Sec);
        out.put("time_to_first_meaningful_action_p90_seconds", stats.meaningfulP90Sec);
        out.put("users_with_verification", stats.usersWithVerification);
        out.put("time_to_first_verification_p50_seconds", stats.verifyP50Sec);
        out.put("time_to_first_verification_p90_seconds", stats.verifyP90Sec);
        return ResponseEntity.ok(out);
    }

    @GetMapping("/kpis/users/verification-to-first-actions")
    public ResponseEntity<?> verificationToFirstActions(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to
    ) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.VIEW_REPORTS);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        var range = parseRangeWithDefaults(from, to);
        if (range.status() != RangeStatus.OK) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "invalid_date"));
        }
        long days = java.time.Duration.between(range.from, range.to).toDays();
        if (days <= 0 || days > MAX_RANGE_DAYS) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "invalid_range"));
        }
        var stats = analytics.timeFromVerificationToFirstActions(range.from, range.to);
        Map<String, Object> out = new HashMap<>();
        out.put("verified_cohort_size", stats.cohortSize);
        out.put("users_with_like", stats.usersWithLike);
        out.put("verification_to_first_like_p50_seconds", stats.likeP50Sec);
        out.put("verification_to_first_like_p90_seconds", stats.likeP90Sec);
        out.put("users_with_comment", stats.usersWithComment);
        out.put("verification_to_first_comment_p50_seconds", stats.commentP50Sec);
        out.put("verification_to_first_comment_p90_seconds", stats.commentP90Sec);
        out.put("users_with_post", stats.usersWithPost);
        out.put("verification_to_first_post_p50_seconds", stats.postP50Sec);
        out.put("verification_to_first_post_p90_seconds", stats.postP90Sec);
        return ResponseEntity.ok(out);
    }

    @GetMapping("/kpis/moderation/repeat-offenders")
    public ResponseEntity<?> repeatOffenders(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to
    ) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.VIEW_REPORTS);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        var range = parseRangeWithDefaults(from, to);
        if (range.status() != RangeStatus.OK) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "invalid_date"));
        }
        long days = java.time.Duration.between(range.from, range.to).toDays();
        if (days <= 0 || days > MAX_RANGE_DAYS) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "invalid_range"));
        }
        var stats = analytics.repeatOffenders(range.from, range.to);
        return ResponseEntity.ok(Map.of(
                "violation_events", stats.violationEvents,
                "unique_violators", stats.uniqueViolators,
                "repeat_offenders", stats.repeatOffenders,
                "repeat_offender_rate", stats.uniqueViolators <= 0 ? 0.0 : (double) stats.repeatOffenders / (double) stats.uniqueViolators
        ));
    }

    @GetMapping("/kpis/north-star/unique-interactions")
    public ResponseEntity<?> northStarUniqueInteractions(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(value = "communityId", required = false) Long communityId,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to
    ) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.VIEW_REPORTS);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        if (communityId != null && !analytics.communityExists(communityId)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "community_not_found"));
        }
        var range = parseRangeWithDefaults(from, to);
        if (range.status() != RangeStatus.OK) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "invalid_date"));
        }
        long days = java.time.Duration.between(range.from, range.to).toDays();
        if (days <= 0 || days > MAX_RANGE_DAYS) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "invalid_range"));
        }
        var stats = analytics.northStarInteractions(range.from, range.to, communityId);
        Map<String, Object> out = new HashMap<>();
        if (communityId != null) out.put("community_id", communityId);
        out.put("interactions_total", stats.interactionsTotal);
        out.put("unique_actor_principals", stats.uniqueActors);
        out.put("unique_target_principals", stats.uniqueTargets);
        out.put("unique_principal_pairs", stats.uniquePairs);
        return ResponseEntity.ok(out);
    }

    @GetMapping("/kpis/support/tickets")
    public ResponseEntity<?> supportTickets(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to
    ) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.VIEW_REPORTS);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        var range = parseRangeWithDefaults(from, to);
        if (range.status() != RangeStatus.OK) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "invalid_date"));
        }
        long days = java.time.Duration.between(range.from, range.to).toDays();
        if (days <= 0 || days > MAX_RANGE_DAYS) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "invalid_range"));
        }
        var stats = analytics.supportTickets(range.from, range.to);
        return ResponseEntity.ok(Map.of(
                "feedback_count", stats.feedbackCount,
                "total_users", stats.totalUsers,
                "feedback_per_1000_users", stats.totalUsers <= 0 ? 0.0 : (1000.0 * (double) stats.feedbackCount / (double) stats.totalUsers)
        ));
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

    private Range parseRangeWithDefaults(String from, String to) {
        var parsed = parseRange(from, to);
        if (parsed.status() != RangeStatus.OK) return parsed;

        OffsetDateTime fromTs = parsed.from;
        OffsetDateTime toTs = parsed.to;

        OffsetDateTime defaultTo = LocalDate.now(ZoneOffset.UTC).plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        if (toTs == null) toTs = defaultTo;
        if (fromTs == null) fromTs = toTs.minusDays(DEFAULT_RANGE_DAYS);

        if (!fromTs.isBefore(toTs)) {
            return new Range(RangeStatus.INVALID_DATE, null, null);
        }
        return new Range(RangeStatus.OK, fromTs, toTs);
    }

    private enum RangeStatus { OK, INVALID_DATE }

    private record Range(RangeStatus status, OffsetDateTime from, OffsetDateTime to) {}
}

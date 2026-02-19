package com.looped.admin;

import com.looped.media.MediaRepository;
import com.looped.media.MediaService;
import com.looped.moderation.ReportRepository;
import com.looped.posts.PostRepository;
import com.looped.posts.PostSearchQuery;
import com.looped.shared.RankPagination;
import org.springframework.beans.factory.annotation.Value;
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

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/v1/admin")
public class AdminPostsController {
    private final AdminAuthService auth;
    private final PostRepository posts;
    private final AdminPostSearchRepository postSearch;
    private final AdminAuditRepository audit;
    private final ReportRepository reports;
    private final MediaRepository media;
    private final MediaService mediaService;
    private final String cloudfrontDomain;

    public AdminPostsController(AdminAuthService auth,
                                PostRepository posts,
                                AdminPostSearchRepository postSearch,
                                AdminAuditRepository audit,
                                ReportRepository reports,
                                MediaRepository media,
                                MediaService mediaService,
                                @Value("${cloudfront.domain:}") String cloudfrontDomain) {
        this.auth = auth;
        this.posts = posts;
        this.postSearch = postSearch;
        this.audit = audit;
        this.reports = reports;
        this.media = media;
        this.mediaService = mediaService;
        this.cloudfrontDomain = cloudfrontDomain;
    }

    @GetMapping("/posts/{id}")
    public ResponseEntity<?> get(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") long id) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.check(jwt.getSubject(), email);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        boolean canView = AdminPermissions.hasPermission(authRes.admin(), AdminPermissions.VIEW_POSTS)
                || AdminPermissions.hasPermission(authRes.admin(), AdminPermissions.REMOVE_POST);
        if (!canView) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        var postOpt = posts.findByIdIncludingRemoved(id);
        if (postOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
        }
        var post = postOpt.get();
        Map<String, Object> body = new HashMap<>();
        body.put("id", post.id);
        body.put("author_id", post.authorId);
        body.put("author_handle", post.authorHandle);
        body.put("author_display_name", post.authorDisplayName);
        body.put("company_id", post.companyId);
        body.put("community_id", post.communityId);
        body.put("content", post.content);
        body.put("media_asset_id", post.mediaAssetId);
        if (post.mediaAssetIds != null) body.put("media_asset_ids", post.mediaAssetIds);
        body.put("created_at", post.createdAt);
        body.put("removed_at", post.removedAt);
        body.put("removed_reason", post.removedReason);
        body.put("removed_by", post.removedBy);

        List<Map<String, Object>> mediaItems = buildMediaPayload(post.mediaAssetIds);
        if (!mediaItems.isEmpty()) {
            body.put("media", mediaItems);
        } else {
            body.put("media", List.of());
        }

        return ResponseEntity.ok(body);
    }

    private List<Map<String, Object>> buildMediaPayload(List<Long> mediaAssetIds) {
        if (mediaAssetIds == null || mediaAssetIds.isEmpty()) return List.of();
        List<Long> ids = mediaAssetIds.stream().filter(v -> v != null && v > 0).toList();
        if (ids.isEmpty()) return List.of();

        List<MediaRepository.MediaRow> rows = media.findByIds(ids);
        Map<Long, MediaRepository.MediaRow> byId = rows.stream().collect(Collectors.toMap(r -> r.id, r -> r, (a, b) -> a));

        Set<Long> thumbIds = rows.stream()
                .map(r -> r.thumbnailMediaAssetId)
                .filter(v -> v != null && v > 0)
                .collect(Collectors.toSet());
        Map<Long, MediaRepository.MediaRow> thumbs = thumbIds.isEmpty()
                ? Map.of()
                : media.findByIds(new ArrayList<>(thumbIds)).stream()
                .collect(Collectors.toMap(r -> r.id, r -> r, (a, b) -> a));

        List<Map<String, Object>> out = new ArrayList<>();
        for (Long id : ids) {
            MediaRepository.MediaRow r = byId.get(id);
            if (r == null || r.s3Key == null || r.s3Key.isBlank()) continue;
            Map<String, Object> item = new HashMap<>();
            item.put("id", r.id);
            item.put("content_type", r.mimeType);
            item.put("mime_type", r.mimeType);

            String url = resolveUrl(r.s3Key);
            if (cloudfrontDomain != null && !cloudfrontDomain.isBlank()) item.put("cdn_url", url);
            else item.put("download_url", url);

            boolean isVideo = r.mimeType != null && r.mimeType.toLowerCase(Locale.ROOT).startsWith("video/");
            if (isVideo && r.thumbnailMediaAssetId != null) {
                MediaRepository.MediaRow thumb = thumbs.get(r.thumbnailMediaAssetId);
                if (thumb != null && thumb.s3Key != null && !thumb.s3Key.isBlank()) {
                    item.put("thumbnail_url", resolveUrl(thumb.s3Key));
                }
            }
            out.add(item);
        }
        return out;
    }

    private String resolveUrl(String key) {
        if (cloudfrontDomain != null && !cloudfrontDomain.isBlank()) {
            return "https://" + cloudfrontDomain + "/" + key;
        }
        return mediaService.presignedGetUrl(key, java.time.Duration.ofMinutes(5));
    }

    @GetMapping("/posts/search")
    public ResponseEntity<?> search(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(value = "query") String query,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false, defaultValue = "50") int limit,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "companyId", required = false) Long companyId,
            @RequestParam(value = "communityId", required = false) Long communityId,
            @RequestParam(value = "authorId", required = false) Long authorId,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to
    ) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.check(jwt.getSubject(), email);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        boolean canView = AdminPermissions.hasPermission(authRes.admin(), AdminPermissions.VIEW_POSTS)
                || AdminPermissions.hasPermission(authRes.admin(), AdminPermissions.REMOVE_POST);
        if (!canView) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }

        String raw = query == null ? null : query.trim();
        if (raw == null || raw.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "invalid_query"));
        }

        AdminPostSearchRepository.StatusFilter statusFilter = AdminPostSearchRepository.StatusFilter.ALL;
        String normalizedStatus = status == null ? "all" : status.trim().toLowerCase(Locale.ROOT);
        if ("active".equals(normalizedStatus)) statusFilter = AdminPostSearchRepository.StatusFilter.ACTIVE;
        else if ("removed".equals(normalizedStatus)) statusFilter = AdminPostSearchRepository.StatusFilter.REMOVED;
        else if ("all".equals(normalizedStatus) || normalizedStatus.isBlank()) statusFilter = AdminPostSearchRepository.StatusFilter.ALL;
        else return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "invalid_status"));

        int lim = Math.max(1, Math.min(limit, 200));

        RankPagination.Cursor rankedCursor = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                rankedCursor = RankPagination.decode(cursor);
            } catch (IllegalArgumentException ignored) {}
        }
        OffsetDateTime asOf = rankedCursor == null ? OffsetDateTime.now() : rankedCursor.asOf();
        Long cursorScore = rankedCursor == null ? null : rankedCursor.score();
        OffsetDateTime cursorTs = rankedCursor == null ? null : rankedCursor.timestamp();
        Long cursorId = rankedCursor == null ? null : rankedCursor.id();

        OffsetDateTime fromTs = null;
        OffsetDateTime toTs = null;
        try {
            if (from != null && !from.isBlank()) {
                fromTs = parseDateOrDateTime(from.trim(), true);
            }
            if (to != null && !to.isBlank()) {
                toTs = parseDateOrDateTime(to.trim(), false);
            }
        } catch (DateTimeParseException e) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "invalid_date"));
        }
        if (fromTs != null && toTs != null && !toTs.isAfter(fromTs)) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "invalid_date_range"));
        }

        String numericCandidate = raw.startsWith("#") ? raw.substring(1).trim() : raw;
        boolean isNumeric = !numericCandidate.isBlank() && numericCandidate.chars().allMatch(Character::isDigit);

        List<AdminPostSearchRepository.ScoredPostRow> rows;
        if (isNumeric) {
            try {
                long prefix = Long.parseLong(numericCandidate);
                boolean usePrefix = numericCandidate.length() >= 3 || cursorScore != null;
                if (usePrefix) {
                    rows = postSearch.searchByIdPrefix(
                            prefix,
                            numericCandidate.length(),
                            asOf,
                            cursorScore,
                            cursorTs,
                            cursorId,
                            lim,
                            statusFilter,
                            companyId,
                            communityId,
                            authorId,
                            fromTs,
                            toTs
                    );
                } else {
                    var postOpt = posts.findByIdIncludingRemoved(prefix);
                    if (postOpt.isEmpty() || !matches(postOpt.get(), statusFilter, companyId, communityId, authorId, fromTs, toTs)) {
                        rows = List.of();
                    } else {
                        var post = postOpt.get();
                        AdminPostSearchRepository.ScoredPostRow r = new AdminPostSearchRepository.ScoredPostRow();
                        r.id = post.id;
                        r.authorId = post.authorId;
                        r.companyId = post.companyId;
                        r.communityId = post.communityId;
                        r.createdAt = post.createdAt;
                        r.removedAt = post.removedAt;
                        r.contentSnippet = snippet(post.content);
                        r.score = Long.MAX_VALUE;
                        rows = List.of(r);
                    }
                }
            } catch (NumberFormatException e) {
                rows = List.of();
            }
        } else {
            String prefixQuery = PostSearchQuery.toPrefixTsquery(raw);
            rows = postSearch.searchFuzzy(
                    raw,
                    prefixQuery,
                    asOf,
                    cursorScore,
                    cursorTs,
                    cursorId,
                    lim,
                    statusFilter,
                    companyId,
                    communityId,
                    authorId,
                    fromTs,
                    toTs
            );
        }

        String next = null;
        if (rows.size() == lim) {
            var last = rows.get(rows.size() - 1);
            next = RankPagination.encode(asOf, last.score, last.createdAt, last.id);
        }
        List<Map<String, Object>> items = rows.stream().map(r -> {
            Map<String, Object> out = new HashMap<>();
            out.put("id", r.id);
            if (r.contentSnippet != null) out.put("content_snippet", r.contentSnippet);
            if (r.authorId != null) out.put("author_id", r.authorId);
            if (r.companyId != null) out.put("company_id", r.companyId);
            out.put("created_at", r.createdAt);
            if (r.removedAt != null) out.put("removed_at", r.removedAt);
            return out;
        }).toList();

        Map<String, Object> body = new HashMap<>();
        body.put("items", items);
        if (next != null) body.put("next_cursor", next);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/posts/{id}/remove")
    public ResponseEntity<?> remove(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") long id,
            @RequestBody(required = false) RemoveRequest body
    ) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.REMOVE_POST);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        var postOpt = posts.findByIdIncludingRemoved(id);
        if (postOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
        }
        if (postOpt.get().removedAt == null) {
            String reason = body != null ? body.reason() : null;
            boolean removed = posts.remove(id, authRes.admin().id, reason);
            if (removed) {
                reports.resolveOpenByTarget("post", id, authRes.admin().id, reason);
                audit.log(authRes.admin().id, "post.remove", "post", id, null);
            }
        }
        return ResponseEntity.ok(Map.of("status", "removed"));
    }

    @PostMapping("/posts/{id}/restore")
    public ResponseEntity<?> restore(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") long id) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.REMOVE_POST);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        var postOpt = posts.findByIdIncludingRemoved(id);
        if (postOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
        }
        posts.restore(id);
        audit.log(authRes.admin().id, "post.restore", "post", id, null);
        return ResponseEntity.ok(Map.of("status", "active"));
    }

    public record RemoveRequest(String reason) {}

    private static OffsetDateTime parseDateOrDateTime(String raw, boolean isFrom) throws DateTimeParseException {
        try {
            return OffsetDateTime.parse(raw);
        } catch (DateTimeParseException ignored) {
            LocalDate d = LocalDate.parse(raw);
            if (isFrom) return d.atStartOfDay().atOffset(ZoneOffset.UTC);
            return d.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        }
    }

    private static boolean matches(
            PostRepository.PostRow post,
            AdminPostSearchRepository.StatusFilter status,
            Long companyId,
            Long communityId,
            Long authorId,
            OffsetDateTime from,
            OffsetDateTime toExclusive
    ) {
        if (status == AdminPostSearchRepository.StatusFilter.ACTIVE && post.removedAt != null) return false;
        if (status == AdminPostSearchRepository.StatusFilter.REMOVED && post.removedAt == null) return false;
        if (companyId != null && post.companyId != companyId) return false;
        if (communityId != null && (post.communityId == null || !post.communityId.equals(communityId))) return false;
        if (authorId != null && (post.authorId == null || !post.authorId.equals(authorId))) return false;
        if (from != null && (post.createdAt == null || post.createdAt.isBefore(from))) return false;
        if (toExclusive != null && (post.createdAt == null || !post.createdAt.isBefore(toExclusive))) return false;
        return true;
    }

    private static String snippet(String content) {
        if (content == null) return "";
        String cleaned = content.replaceAll("\\s+", " ").trim();
        if (cleaned.length() > 200) return cleaned.substring(0, 200);
        return cleaned;
    }
}

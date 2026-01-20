package com.looped.admin;

import com.looped.comments.CommentsRepository;
import com.looped.media.MediaRepository;
import com.looped.moderation.ModerationQueueAdminService;
import com.looped.posts.PostRepository;
import com.looped.shared.Pagination;
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

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/v1/admin/moderation/queue")
public class AdminModerationQueueController {
    private final AdminAuthService auth;
    private final ModerationQueueAdminService queue;
    private final PostRepository posts;
    private final CommentsRepository comments;
    private final MediaRepository media;
    private final AdminAuditRepository audit;
    private final String cloudfrontDomain;

    public AdminModerationQueueController(AdminAuthService auth,
                                          ModerationQueueAdminService queue,
                                          PostRepository posts,
                                          CommentsRepository comments,
                                          MediaRepository media,
                                          AdminAuditRepository audit,
                                          @Value("${cloudfront.domain:}") String cloudfrontDomain) {
        this.auth = auth;
        this.queue = queue;
        this.posts = posts;
        this.comments = comments;
        this.media = media;
        this.audit = audit;
        this.cloudfrontDomain = cloudfrontDomain;
    }

    @GetMapping
    public ResponseEntity<?> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "targetType", required = false) String targetType,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false, defaultValue = "50") int limit
    ) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.VIEW_MODERATION_QUEUE);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        int lim = Math.max(1, Math.min(limit, 200));
        OffsetDateTime cursorTs = null;
        Long cursorId = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                var decoded = Pagination.decode(cursor);
                cursorTs = decoded.timestamp();
                cursorId = decoded.id();
            } catch (IllegalArgumentException ignored) {}
        }
        String normalizedStatus = status == null || status.isBlank() ? "open" : status.trim().toLowerCase(Locale.ROOT);
        String normalizedTargetType = targetType == null || targetType.isBlank() ? null : targetType.trim().toLowerCase(Locale.ROOT);

        var res = queue.list(normalizedStatus, normalizedTargetType, cursorTs, cursorId, lim);
        List<Map<String, Object>> items = res.items().stream().map(item -> {
            Map<String, Object> out = new HashMap<>();
            out.put("id", item.id);
            out.put("target_type", item.targetType);
            out.put("target_id", item.targetId);
            out.put("source", item.source);
            out.put("reason", item.reason);
            out.put("status", item.status);
            out.put("created_at", item.createdAt);
            out.put("updated_at", item.updatedAt);
            if (item.reviewedAt != null) out.put("reviewed_at", item.reviewedAt);
            if (item.reviewedBy != null) out.put("reviewed_by", item.reviewedBy);
            if (item.reviewNote != null) out.put("review_note", item.reviewNote);

            if ("post".equalsIgnoreCase(item.targetType)) {
                var post = posts.findByIdIncludingRemoved(item.targetId).orElse(null);
                if (post == null) {
                    out.put("target_missing", true);
                } else {
                    Map<String, Object> p = new HashMap<>();
                    p.put("id", post.id);
                    p.put("author_id", post.authorId);
                    p.put("author_principal_id", post.authorPrincipalId);
                    p.put("company_id", post.companyId);
                    p.put("community_id", post.communityId);
                    p.put("content", post.content);
                    p.put("media_asset_ids", post.mediaAssetIds);
                    p.put("created_at", post.createdAt);
                    p.put("removed_at", post.removedAt);
                    p.put("visibility", post.visibility);
                    p.put("quarantined_at", post.quarantinedAt);
                    p.put("quarantine_reason", post.quarantineReason);
                    out.put("post", p);
                }
            } else if ("comment".equalsIgnoreCase(item.targetType)) {
                var comment = comments.findById(item.targetId).orElse(null);
                if (comment == null) {
                    out.put("target_missing", true);
                } else {
                    Map<String, Object> c = new HashMap<>();
                    c.put("id", comment.id);
                    c.put("post_id", comment.postId);
                    c.put("user_id", comment.userId);
                    c.put("author_principal_id", comment.authorPrincipalId);
                    c.put("company_id", comment.companyId);
                    c.put("content", comment.content);
                    c.put("media_asset_id", comment.mediaAssetId);
                    c.put("parent_id", comment.parentId);
                    c.put("created_at", comment.createdAt);
                    c.put("deleted_at", comment.deletedAt);
                    c.put("removed_at", comment.removedAt);
                    c.put("visibility", comment.visibility);
                    c.put("quarantined_at", comment.quarantinedAt);
                    c.put("quarantine_reason", comment.quarantineReason);
                    out.put("comment", c);
                }
            } else if ("media".equalsIgnoreCase(item.targetType)) {
                var m = media.findById(item.targetId).orElse(null);
                if (m == null) {
                    out.put("target_missing", true);
                } else {
                    Map<String, Object> mm = new HashMap<>();
                    mm.put("id", m.id);
                    mm.put("owner_id", m.ownerId);
                    mm.put("key", m.s3Key);
                    mm.put("mime_type", m.mimeType);
                    mm.put("visibility", m.visibility);
                    mm.put("quarantined_at", m.quarantinedAt);
                    mm.put("quarantine_reason", m.quarantineReason);
                    mm.put("removed_at", m.removedAt);
                    mm.put("removed_by", m.removedBy);
                    mm.put("removed_reason", m.removedReason);
                    if (cloudfrontDomain != null && !cloudfrontDomain.isBlank() && m.s3Key != null && !m.s3Key.isBlank()) {
                        mm.put("cdn_url", "https://" + cloudfrontDomain + "/" + m.s3Key);
                    }
                    out.put("media", mm);
                }
            }
            return out;
        }).toList();

        Map<String, Object> body = new HashMap<>();
        body.put("items", items);
        if (res.nextCursor() != null) body.put("next_cursor", res.nextCursor());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<?> approve(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") long id,
            @RequestBody(required = false) ReviewRequest body
    ) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.RESOLVE_MODERATION_QUEUE);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        String note = body == null ? null : body.note();
        var res = queue.approve(id, authRes.admin().id, note);
        return switch (res.status()) {
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
            case TARGET_NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "target_not_found"));
            case INVALID_TARGET_TYPE -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "invalid_target_type"));
            case ALREADY_REVIEWED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "already_reviewed", "status", res.priorStatus()));
            case OK -> {
                audit.log(authRes.admin().id, "moderation_queue.approve", "moderation_queue_item", id, null);
                yield ResponseEntity.ok(Map.of("status", "approved"));
            }
        };
    }

    @PostMapping("/{id}/remove")
    public ResponseEntity<?> remove(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") long id,
            @RequestBody(required = false) RemoveRequest body
    ) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.RESOLVE_MODERATION_QUEUE);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        String reason = body == null ? null : body.reason();
        String note = body == null ? null : body.note();
        var res = queue.remove(id, authRes.admin().id, reason, note);
        return switch (res.status()) {
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
            case TARGET_NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "target_not_found"));
            case INVALID_TARGET_TYPE -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "invalid_target_type"));
            case ALREADY_REVIEWED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "already_reviewed", "status", res.priorStatus()));
            case OK -> {
                audit.log(authRes.admin().id, "moderation_queue.remove", "moderation_queue_item", id, null);
                yield ResponseEntity.ok(Map.of("status", "removed"));
            }
        };
    }

    public record ReviewRequest(String note) {}
    public record RemoveRequest(String reason, String note) {}
}

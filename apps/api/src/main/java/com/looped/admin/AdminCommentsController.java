package com.looped.admin;

import com.looped.comments.CommentsRepository;
import com.looped.media.MediaRepository;
import com.looped.media.MediaService;
import com.looped.posts.PostRepository;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/v1/admin")
public class AdminCommentsController {
    private final AdminAuthService auth;
    private final AdminCommentsRepository comments;
    private final CommentsRepository commentWrites;
    private final PostRepository posts;
    private final MediaRepository media;
    private final MediaService mediaService;
    private final AdminAuditRepository audit;
    private final String cloudfrontDomain;

    public AdminCommentsController(AdminAuthService auth,
                                   AdminCommentsRepository comments,
                                   CommentsRepository commentWrites,
                                   PostRepository posts,
                                   MediaRepository media,
                                   MediaService mediaService,
                                   AdminAuditRepository audit,
                                   @Value("${cloudfront.domain:}") String cloudfrontDomain) {
        this.auth = auth;
        this.comments = comments;
        this.commentWrites = commentWrites;
        this.posts = posts;
        this.media = media;
        this.mediaService = mediaService;
        this.audit = audit;
        this.cloudfrontDomain = cloudfrontDomain;
    }

    @GetMapping("/comments/{id}")
    public ResponseEntity<?> get(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") long id) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.VIEW_REPORTS);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        var commentOpt = comments.findById(id);
        if (commentOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
        }
        var c = commentOpt.get();
        Map<String, Object> body = new HashMap<>();
        body.put("id", c.id);
        body.put("post_id", c.postId);
        body.put("user_id", c.userId);
        body.put("author_principal_id", c.authorPrincipalId);
        body.put("author_handle", c.authorHandle);
        if (c.authorDisplayName != null) body.put("author_display_name", c.authorDisplayName);
        body.put("author_is_anonymous", c.authorIsAnonymous);
        body.put("company_id", c.companyId);
        body.put("content", c.content);
        body.put("media_asset_id", c.mediaAssetId);
        body.put("parent_id", c.parentId);
        body.put("likes_count", c.likesCount);
        body.put("reply_count", c.replyCount);
        body.put("created_at", c.createdAt);
        if (c.deletedAt != null) body.put("deleted_at", c.deletedAt);
        if (c.visibility != null) body.put("visibility", c.visibility);
        if (c.quarantinedAt != null) body.put("quarantined_at", c.quarantinedAt);
        if (c.quarantineReason != null) body.put("quarantine_reason", c.quarantineReason);
        if (c.removedAt != null) body.put("removed_at", c.removedAt);
        if (c.removedBy != null) body.put("removed_by", c.removedBy);
        if (c.removedReason != null) body.put("removed_reason", c.removedReason);

        if (c.mediaAssetId != null && c.mediaAssetId > 0) {
            var mediaOpt = media.findById(c.mediaAssetId);
            if (mediaOpt.isPresent() && mediaOpt.get().s3Key != null && !mediaOpt.get().s3Key.isBlank()) {
                var m = mediaOpt.get();
                Map<String, Object> item = new HashMap<>();
                item.put("id", m.id);
                item.put("content_type", m.mimeType);
                item.put("mime_type", m.mimeType);
                String url = resolveUrl(m.s3Key);
                if (cloudfrontDomain != null && !cloudfrontDomain.isBlank()) item.put("cdn_url", url);
                else item.put("download_url", url);
                boolean isVideo = m.mimeType != null && m.mimeType.toLowerCase(Locale.ROOT).startsWith("video/");
                if (isVideo && m.thumbnailMediaAssetId != null) {
                    var thumbOpt = media.findById(m.thumbnailMediaAssetId);
                    if (thumbOpt.isPresent() && thumbOpt.get().s3Key != null && !thumbOpt.get().s3Key.isBlank()) {
                        item.put("thumbnail_url", resolveUrl(thumbOpt.get().s3Key));
                    }
                }
                body.put("media", List.of(item));
            } else {
                body.put("media", List.of());
            }
        } else {
            body.put("media", List.of());
        }

        return ResponseEntity.ok(body);
    }

    @PostMapping("/comments/{id}/remove")
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

        var commentOpt = comments.findById(id);
        if (commentOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
        }
        var comment = commentOpt.get();
        if (comment.deletedAt != null) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "comment_deleted"));
        }

        if (comment.removedAt == null) {
            String reason = body == null ? null : body.reason();
            if (reason == null || reason.isBlank()) reason = "admin_removed";
            boolean removed = commentWrites.removeByAdmin(id, authRes.admin().id, reason);
            if (removed) {
                boolean wasPublic = comment.visibility == null || comment.visibility.equalsIgnoreCase("public");
                if (wasPublic) {
                    posts.decrementCommentsCount(comment.postId);
                    if (comment.parentId != null) {
                        commentWrites.decrementReplyCount(comment.parentId);
                    }
                }
                audit.log(authRes.admin().id, "comment.remove", "comment", id, null);
            }
        }

        return ResponseEntity.ok(Map.of("status", "removed"));
    }

    @PostMapping("/comments/{id}/restore")
    public ResponseEntity<?> restore(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") long id) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.REMOVE_POST);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }

        var commentOpt = comments.findById(id);
        if (commentOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
        }
        var comment = commentOpt.get();
        if (comment.deletedAt != null) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "comment_deleted"));
        }

        if (comment.removedAt != null) {
            boolean restored = commentWrites.restoreByAdmin(id);
            if (restored) {
                boolean wasPublic = comment.visibility == null || comment.visibility.equalsIgnoreCase("public");
                if (wasPublic) {
                    posts.incrementCommentsCount(comment.postId);
                    if (comment.parentId != null) {
                        commentWrites.incrementReplyCount(comment.parentId);
                    }
                }
                audit.log(authRes.admin().id, "comment.restore", "comment", id, null);
            }
        }

        return ResponseEntity.ok(Map.of("status", "active"));
    }

    private String resolveUrl(String key) {
        if (cloudfrontDomain != null && !cloudfrontDomain.isBlank()) {
            return "https://" + cloudfrontDomain + "/" + key;
        }
        return mediaService.presignedGetUrl(key, java.time.Duration.ofMinutes(5));
    }

    public record RemoveRequest(String reason) {}
}

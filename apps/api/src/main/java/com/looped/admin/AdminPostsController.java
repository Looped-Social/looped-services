package com.looped.admin;

import com.looped.posts.PostRepository;
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
import java.util.Map;

@RestController
@RequestMapping("/v1/admin")
public class AdminPostsController {
    private final AdminAuthService auth;
    private final PostRepository posts;
    private final AdminAuditRepository audit;

    public AdminPostsController(AdminAuthService auth, PostRepository posts, AdminAuditRepository audit) {
        this.auth = auth;
        this.posts = posts;
        this.audit = audit;
    }

    @GetMapping("/posts/{id}")
    public ResponseEntity<?> get(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") long id) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.REMOVE_POST);
        if (authRes.status() != AdminAuthService.Status.OK) {
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
        body.put("created_at", post.createdAt);
        body.put("removed_at", post.removedAt);
        body.put("removed_reason", post.removedReason);
        body.put("removed_by", post.removedBy);
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
            posts.remove(id, authRes.admin().id, reason);
            audit.log(authRes.admin().id, "post.remove", "post", id, null);
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
}

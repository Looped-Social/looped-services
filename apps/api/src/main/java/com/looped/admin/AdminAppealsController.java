package com.looped.admin;

import com.looped.moderation.AppealRepository;
import com.looped.posts.PostRepository;
import com.looped.shared.Pagination;
import com.looped.users.UserBanRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/v1/admin")
public class AdminAppealsController {
    private final AdminAuthService auth;
    private final AppealRepository appeals;
    private final UserBanRepository bans;
    private final PostRepository posts;
    private final AdminAuditRepository audit;

    public AdminAppealsController(AdminAuthService auth, AppealRepository appeals, UserBanRepository bans,
                                  PostRepository posts, AdminAuditRepository audit) {
        this.auth = auth;
        this.appeals = appeals;
        this.bans = bans;
        this.posts = posts;
        this.audit = audit;
    }

    @GetMapping("/appeals")
    public ResponseEntity<?> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "targetType", required = false) String targetType,
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "sort", required = false) String sort,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false, defaultValue = "50") int limit
    ) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.VIEW_REPORTS);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        java.time.OffsetDateTime cursorTs = null;
        Long cursorId = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                var decoded = Pagination.decode(cursor);
                cursorTs = decoded.timestamp();
                cursorId = decoded.id();
            } catch (IllegalArgumentException ignored) {}
        }
        String normalizedSort = sort != null ? sort.trim().toLowerCase(Locale.ROOT) : "created_at_desc";
        boolean ascending = "created_at_asc".equals(normalizedSort);
        if (!ascending && !"created_at_desc".equals(normalizedSort)) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "invalid_sort"));
        }
        String normalizedStatus = status != null ? status.trim().toLowerCase(Locale.ROOT) : null;
        String normalizedTarget = targetType != null ? targetType.trim().toLowerCase(Locale.ROOT) : null;
        List<AppealRepository.AppealRow> rows = appeals.listAll(
                normalizedStatus,
                normalizedTarget,
                userId,
                cursorTs,
                cursorId,
                limit,
                ascending
        );
        String next = null;
        if (rows.size() == limit) {
            var last = rows.get(rows.size() - 1);
            next = Pagination.encode(last.createdAt, last.id);
        }
        List<Map<String, Object>> items = rows.stream().map(a -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", a.id);
            map.put("user_id", a.userId);
            if (a.userHandle != null) map.put("user_handle", a.userHandle);
            map.put("target_type", a.targetType);
            map.put("target_id", a.targetId);
            map.put("reason", a.reason);
            map.put("status", a.status);
            map.put("created_at", a.createdAt);
            map.put("updated_at", a.updatedAt);
            if (a.reviewedAt != null) map.put("reviewed_at", a.reviewedAt);
            if (a.reviewedBy != null) map.put("reviewed_by", a.reviewedBy);
            if (a.reviewedReason != null) map.put("reviewed_reason", a.reviewedReason);
            return map;
        }).toList();
        Map<String, Object> body = new HashMap<>();
        body.put("items", items);
        if (next != null) body.put("next_cursor", next);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/appeals/{id}/approve")
    @Transactional
    public ResponseEntity<?> approve(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") long id,
            @RequestBody(required = false) ReviewRequest body
    ) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.RESOLVE_REPORTS);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        var appealOpt = appeals.findById(id);
        if (appealOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
        }
        var appeal = appealOpt.get();
        if (!"open".equalsIgnoreCase(appeal.status)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "appeal_already_reviewed"));
        }
        String reason = body != null ? body.reason() : null;
        boolean updated = appeals.review(id, "approved", authRes.admin().id, reason);
        if (!updated) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "update_failed"));
        }
        String action = "none";
        if ("user_ban".equalsIgnoreCase(appeal.targetType)) {
            var banOpt = bans.findActiveByUserId(appeal.userId);
            if (banOpt.isPresent() && banOpt.get().id == appeal.targetId) {
                bans.revokeActive(appeal.userId, authRes.admin().id);
                audit.log(authRes.admin().id, "user.unban", "user", appeal.userId, null);
                action = "user_unbanned";
            }
        } else if ("post_removal".equalsIgnoreCase(appeal.targetType)) {
            var postOpt = posts.findByIdIncludingRemoved(appeal.targetId);
            if (postOpt.isPresent() && postOpt.get().removedAt != null) {
                posts.restore(appeal.targetId);
                audit.log(authRes.admin().id, "post.restore", "post", appeal.targetId, null);
                action = "post_restored";
            }
        }
        audit.log(authRes.admin().id, "appeal.approve", "appeal", id, null);
        return ResponseEntity.ok(Map.of("status", "approved", "action", action));
    }

    @PostMapping("/appeals/{id}/reject")
    @Transactional
    public ResponseEntity<?> reject(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") long id,
            @RequestBody(required = false) ReviewRequest body
    ) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.RESOLVE_REPORTS);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        var appealOpt = appeals.findById(id);
        if (appealOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
        }
        var appeal = appealOpt.get();
        if (!"open".equalsIgnoreCase(appeal.status)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "appeal_already_reviewed"));
        }
        String reason = body != null ? body.reason() : null;
        boolean updated = appeals.review(id, "rejected", authRes.admin().id, reason);
        if (!updated) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "update_failed"));
        }
        audit.log(authRes.admin().id, "appeal.reject", "appeal", id, null);
        return ResponseEntity.ok(Map.of("status", "rejected"));
    }

    public record ReviewRequest(String reason) {}
}

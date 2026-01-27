package com.looped.admin;

import com.looped.communities.CommunitiesRepository;
import com.looped.communities.CommunityRequestsRepository;
import com.looped.shared.Pagination;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
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
@RequestMapping("/v1/admin")
public class AdminCommunityRequestsController {
    private final AdminAuthService auth;
    private final CommunityRequestsRepository requests;
    private final CommunitiesRepository communities;
    private final AdminAuditRepository audit;
    private final String cloudfrontDomain;

    public AdminCommunityRequestsController(AdminAuthService auth,
                                            CommunityRequestsRepository requests,
                                            CommunitiesRepository communities,
                                            AdminAuditRepository audit,
                                            @Value("${cloudfront.domain:}") String cloudfrontDomain) {
        this.auth = auth;
        this.requests = requests;
        this.communities = communities;
        this.audit = audit;
        this.cloudfrontDomain = cloudfrontDomain;
    }

    @GetMapping("/community-requests")
    public ResponseEntity<?> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false, defaultValue = "50") int limit
    ) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.CREATE_COMMUNITY);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        OffsetDateTime cursorTs = null;
        Long cursorId = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                var decoded = Pagination.decode(cursor);
                cursorTs = decoded.timestamp();
                cursorId = decoded.id();
            } catch (IllegalArgumentException ignored) {}
        }
        String normalizedStatus = status != null ? status.trim().toLowerCase(Locale.ROOT) : null;
        List<CommunityRequestsRepository.Row> rows = requests.listForAdmin(normalizedStatus, cursorTs, cursorId, limit);
        String next = null;
        if (rows.size() == limit) {
            var last = rows.get(rows.size() - 1);
            next = Pagination.encode(last.createdAt, last.id);
        }
        List<Map<String, Object>> items = rows.stream().map(r -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", r.id);
            map.put("user_id", r.userId);
            if (r.userHandle != null) map.put("user_handle", r.userHandle);
            if (r.userEmail != null) map.put("user_email", r.userEmail);
            map.put("kind", r.kind);
            map.put("name", r.name);
            if (r.description != null) map.put("description", r.description);
            if (r.imageKey != null) {
                map.put("image_key", r.imageKey);
                String cdnUrl = cdnUrl(r.imageKey);
                if (cdnUrl != null) map.put("image_url", cdnUrl);
            }
            map.put("status", r.status);
            map.put("created_at", r.createdAt);
            if (r.reviewedAt != null) map.put("reviewed_at", r.reviewedAt);
            if (r.reviewedBy != null) map.put("reviewed_by", r.reviewedBy);
            if (r.rejectReason != null) map.put("reject_reason", r.rejectReason);
            if (r.communityId != null) map.put("community_id", r.communityId);
            return map;
        }).toList();
        Map<String, Object> body = new HashMap<>();
        body.put("items", items);
        if (next != null) body.put("next_cursor", next);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/community-requests/{id}/approve")
    @Transactional
    public ResponseEntity<?> approve(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") long id,
            @Valid @RequestBody(required = false) ApproveRequest body
    ) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.CREATE_COMMUNITY);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        var reqOpt = requests.findByIdForUpdate(id);
        if (reqOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
        }
        var req = reqOpt.get();
        if (!"pending".equalsIgnoreCase(req.status)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "community_request_already_reviewed"));
        }
        String kind = normalizeKind(body != null && body.kind() != null ? body.kind() : req.kind);
        if (kind == null) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "invalid_kind"));
        }
        String name = normalizeName(body != null && body.name() != null ? body.name() : req.name);
        if (name == null) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "name_required"));
        }
        String description = normalizeDescription(body != null && body.description() != null ? body.description() : req.description);
        String imageUrl = normalizeDescription(body != null && body.imageUrl() != null ? body.imageUrl() : null);
        if (imageUrl == null && req.imageKey != null) {
            imageUrl = cdnUrl(req.imageKey);
        }
        Integer ttlDays = body != null ? body.verificationTtlDays() : null;
        if (ttlDays != null && ttlDays < 0) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "invalid_ttl_days"));
        }
        if (communities.findByKindAndName(kind, name).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "community_exists"));
        }
        long communityId;
        try {
            communityId = communities.insert(kind, name, description, imageUrl, ttlDays);
        } catch (DuplicateKeyException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "community_exists"));
        }
        boolean updated = requests.review(id, "approved", authRes.admin().id, null, communityId);
        if (!updated) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "community_request_already_reviewed"));
        }
        audit.log(authRes.admin().id, "community.create", "community", communityId, null);
        audit.log(authRes.admin().id, "community_request.approve", "community_request", id, "community_id=" + communityId);
        return ResponseEntity.ok(Map.of("status", "approved", "community_id", communityId));
    }

    @PostMapping("/community-requests/{id}/reject")
    @Transactional
    public ResponseEntity<?> reject(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") long id,
            @RequestBody(required = false) RejectRequest body
    ) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.CREATE_COMMUNITY);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        var reqOpt = requests.findByIdForUpdate(id);
        if (reqOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
        }
        if (!"pending".equalsIgnoreCase(reqOpt.get().status)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "community_request_already_reviewed"));
        }
        String reason = body != null ? normalizeDescription(body.reason()) : null;
        boolean updated = requests.review(id, "rejected", authRes.admin().id, reason, null);
        if (!updated) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "community_request_already_reviewed"));
        }
        audit.log(authRes.admin().id, "community_request.reject", "community_request", id, null);
        return ResponseEntity.ok(Map.of("status", "rejected"));
    }

    @DeleteMapping("/community-requests/{id}")
    public ResponseEntity<?> delete(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") long id
    ) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.CREATE_COMMUNITY);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        boolean deleted = requests.delete(id);
        if (!deleted) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
        }
        audit.log(authRes.admin().id, "community_request.delete", "community_request", id, null);
        return ResponseEntity.ok(Map.of("status", "deleted"));
    }

    private String normalizeKind(String raw) {
        if (raw == null) return null;
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) return null;
        if (!normalized.equals("company") && !normalized.equals("school")) {
            return null;
        }
        return normalized;
    }

    private String normalizeName(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private String normalizeDescription(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private String cdnUrl(String key) {
        if (cloudfrontDomain == null || cloudfrontDomain.isBlank()) return null;
        return "https://" + cloudfrontDomain + "/" + key;
    }

    public record ApproveRequest(String kind, String name, String description, String imageUrl, Integer verificationTtlDays) {}
    public record RejectRequest(String reason) {}
}

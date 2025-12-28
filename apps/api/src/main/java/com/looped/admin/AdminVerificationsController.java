package com.looped.admin;

import com.looped.communities.CommunitiesRepository;
import com.looped.communities.CommunityVerificationsRepository;
import com.looped.shared.Pagination;
import com.looped.verification.VerificationRepository;
import com.looped.verification.VerificationRequestsRepository;
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
@RequestMapping("/v1/admin")
public class AdminVerificationsController {
    private final AdminAuthService auth;
    private final VerificationRequestsRepository requests;
    private final VerificationRepository verifications;
    private final CommunityVerificationsRepository communityVerifications;
    private final CommunitiesRepository communities;
    private final AdminAuditRepository audit;

    public AdminVerificationsController(AdminAuthService auth, VerificationRequestsRepository requests,
                                        VerificationRepository verifications,
                                        CommunityVerificationsRepository communityVerifications,
                                        CommunitiesRepository communities,
                                        AdminAuditRepository audit) {
        this.auth = auth;
        this.requests = requests;
        this.verifications = verifications;
        this.communityVerifications = communityVerifications;
        this.communities = communities;
        this.audit = audit;
    }

    @GetMapping("/verifications")
    public ResponseEntity<?> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false, defaultValue = "50") int limit
    ) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.VERIFY_USERS);
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
        List<VerificationRequestsRepository.Row> rows = requests.listForAdmin(normalizedStatus, cursorTs, cursorId, limit);
        String next = null;
        if (rows.size() == limit) {
            var last = rows.get(rows.size() - 1);
            next = Pagination.encode(last.submittedAt, last.id);
        }
        List<Map<String, Object>> items = rows.stream().map(r -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", r.id);
            map.put("user_id", r.userId);
            map.put("email", r.email);
            map.put("method", r.method);
            map.put("status", r.status);
            map.put("submitted_at", r.submittedAt);
            map.put("company_domain", r.companyDomain);
            if (r.communityId != null) map.put("community_id", r.communityId);
            if (r.communityName != null) map.put("community_name", r.communityName);
            if (r.communityKind != null) map.put("community_kind", r.communityKind);
            if (r.mediaKey != null) map.put("media_key", r.mediaKey);
            if (r.metadata != null) map.put("metadata", r.metadata);
            if (r.reviewedAt != null) map.put("reviewed_at", r.reviewedAt);
            if (r.reviewedBy != null) map.put("reviewed_by", r.reviewedBy);
            if (r.rejectReason != null) map.put("reject_reason", r.rejectReason);
            return map;
        }).toList();
        Map<String, Object> body = new HashMap<>();
        body.put("items", items);
        if (next != null) body.put("next_cursor", next);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/verifications/{id}/approve")
    public ResponseEntity<?> approve(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") long id) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.VERIFY_USERS);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        var req = requests.findById(id);
        if (req.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
        }
        if (req.get().communityId != null && communities.findById(req.get().communityId).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "community_not_found"));
        }
        boolean updated = requests.updateStatus(id, "approved", authRes.admin().id, null);
        if (!updated) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "update_failed"));
        }
        if (req.get().communityId != null) {
            var community = communities.findById(req.get().communityId).orElseThrow();
            communityVerifications.markVerified(req.get().userId, req.get().communityId, req.get().method,
                    resolveExpiry(community));
        } else {
            verifications.markVerified(req.get().userId, req.get().method);
        }
        audit.log(authRes.admin().id, "verification.approve", "verification_request", id, null);
        return ResponseEntity.ok(Map.of("status", "approved"));
    }

    @PostMapping("/verifications/{id}/reject")
    public ResponseEntity<?> reject(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") long id,
            @RequestBody(required = false) RejectRequest body
    ) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.VERIFY_USERS);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        var req = requests.findById(id);
        if (req.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
        }
        String reason = body != null ? body.reason() : null;
        boolean updated = requests.updateStatus(id, "rejected", authRes.admin().id, reason);
        if (!updated) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "update_failed"));
        }
        if (req.get().communityId != null) {
            communityVerifications.markUnverified(req.get().userId, req.get().communityId, req.get().method);
        } else {
            verifications.markUnverified(req.get().userId, req.get().method);
        }
        audit.log(authRes.admin().id, "verification.reject", "verification_request", id, null);
        return ResponseEntity.ok(Map.of("status", "rejected"));
    }

    public record RejectRequest(String reason) {}

    private java.time.OffsetDateTime resolveExpiry(CommunitiesRepository.CommunityRow community) {
        Integer ttlDays = community.verificationTtlDays;
        if (ttlDays != null && ttlDays > 0) {
            return java.time.OffsetDateTime.now().plusDays(ttlDays);
        }
        return null;
    }
}

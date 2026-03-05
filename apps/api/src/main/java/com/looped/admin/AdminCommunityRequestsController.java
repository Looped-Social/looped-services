package com.looped.admin;

import com.looped.communities.CommunitiesRepository;
import com.looped.communities.CommunityRequestAvailabilityNotifier;
import com.looped.communities.CommunityRequestsRepository;
import com.looped.email.EmailService;
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
import java.util.regex.Pattern;

@RestController
@RequestMapping("/v1/admin")
public class AdminCommunityRequestsController {
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,63}$",
            Pattern.CASE_INSENSITIVE
    );

    private final AdminAuthService auth;
    private final CommunityRequestsRepository requests;
    private final CommunitiesRepository communities;
    private final CommunityRequestAvailabilityNotifier availabilityNotifier;
    private final EmailService emailService;
    private final AdminAuditRepository audit;
    private final String cloudfrontDomain;

    public AdminCommunityRequestsController(AdminAuthService auth,
                                            CommunityRequestsRepository requests,
                                            CommunitiesRepository communities,
                                            CommunityRequestAvailabilityNotifier availabilityNotifier,
                                            EmailService emailService,
                                            AdminAuditRepository audit,
                                            @Value("${cloudfront.domain:}") String cloudfrontDomain) {
        this.auth = auth;
        this.requests = requests;
        this.communities = communities;
        this.availabilityNotifier = availabilityNotifier;
        this.emailService = emailService;
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
            if (r.contactEmail != null) map.put("contact_email", r.contactEmail);
            map.put("notify_when_available", r.notifyWhenAvailable);
            if (r.notifiedAt != null) map.put("notified_at", r.notifiedAt);
            if (r.notifiedCommunityId != null) map.put("notified_community_id", r.notifiedCommunityId);
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
        var kindInfo = normalizeKind(body != null && body.kind() != null ? body.kind() : req.kind);
        if (kindInfo == null) {
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
        boolean exists = kindInfo.specializationType() != null
                ? communities.findByKindAndName(kindInfo.communityKind(), name, kindInfo.specializationType()).isPresent()
                : communities.findByKindAndName(kindInfo.communityKind(), name).isPresent();
        if (exists) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "community_exists"));
        }
        long communityId;
        try {
            communityId = kindInfo.specializationType() != null
                    ? communities.insert(kindInfo.communityKind(), name, description, imageUrl, ttlDays, kindInfo.specializationType())
                    : communities.insert(kindInfo.communityKind(), name, description, imageUrl, ttlDays);
        } catch (DuplicateKeyException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "community_exists"));
        }
        var notificationSummary = availabilityNotifier.notifyForCreatedCommunity(kindInfo.requestKind(), name, communityId);
        boolean updated = requests.review(id, "approved", authRes.admin().id, null, communityId);
        if (!updated) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "community_request_already_reviewed"));
        }
        audit.log(authRes.admin().id, "community.create", "community", communityId, null);
        audit.log(authRes.admin().id, "community_request.approve", "community_request", id, "community_id=" + communityId);
        return ResponseEntity.ok(Map.of(
                "status", "approved",
                "community_id", communityId,
                "matched_requests", notificationSummary.matchedRequests(),
                "notified_requests", notificationSummary.sentEmails()
        ));
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
        String recipient = resolveRequestRecipientEmail(reqOpt.get());
        boolean notifiedRequester = false;
        if (recipient != null) {
            notifiedRequester = emailService.sendCommunityRequestRejectedEmail(recipient, reqOpt.get().name, reason);
        }
        audit.log(authRes.admin().id, "community_request.reject", "community_request", id, null);
        return ResponseEntity.ok(Map.of(
                "status", "rejected",
                "notified_requester", notifiedRequester
        ));
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

    private KindInfo normalizeKind(String raw) {
        if (raw == null) return null;
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) return null;
        if (normalized.equals("workplace")) normalized = "company";
        if (!normalized.equals("company") && !normalized.equals("school") && !normalized.equals("field")) {
            return null;
        }
        if (normalized.equals("field")) {
            return new KindInfo(normalized, "specialization", normalized);
        }
        return new KindInfo(normalized, normalized, null);
    }

    private record KindInfo(String requestKind, String communityKind, String specializationType) {}

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

    private String resolveRequestRecipientEmail(CommunityRequestsRepository.Row row) {
        if (row == null) return null;
        String contact = normalizeEmailOrNull(row.contactEmail);
        if (contact != null) return contact;
        return normalizeEmailOrNull(row.userEmail);
    }

    private String normalizeEmailOrNull(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim().toLowerCase(Locale.ROOT);
        if (trimmed.isBlank() || trimmed.length() > 320) return null;
        return EMAIL_PATTERN.matcher(trimmed).matches() ? trimmed : null;
    }

    public record ApproveRequest(String kind, String name, String description, String imageUrl, Integer verificationTtlDays) {}
    public record RejectRequest(String reason) {}
}

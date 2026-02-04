package com.looped.admin;

import com.looped.communities.CommunitiesRepository;
import com.looped.communities.CommunityVerificationsRepository;
import com.looped.email.EmailService;
import com.looped.notifications.NotificationPublisher;
import com.looped.shared.Pagination;
import com.looped.verification.PhotoIdVerificationProperties;
import com.looped.verification.VerificationProperties;
import com.looped.verification.VerificationRepository;
import com.looped.verification.VerificationPrivateMediaService;
import com.looped.verification.VerificationRequestsRepository;
import org.springframework.dao.DataIntegrityViolationException;
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
import java.util.ArrayList;
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
    private final VerificationPrivateMediaService privateMedia;
    private final PhotoIdVerificationProperties photoIdProps;
    private final VerificationProperties verificationProps;
    private final NotificationPublisher notifications;
    private final EmailService emailService;

    public AdminVerificationsController(AdminAuthService auth, VerificationRequestsRepository requests,
                                        VerificationRepository verifications,
                                        CommunityVerificationsRepository communityVerifications,
                                        CommunitiesRepository communities,
                                        AdminAuditRepository audit,
                                        VerificationPrivateMediaService privateMedia,
                                        PhotoIdVerificationProperties photoIdProps,
                                        VerificationProperties verificationProps,
                                        NotificationPublisher notifications,
                                        EmailService emailService) {
        this.auth = auth;
        this.requests = requests;
        this.verifications = verifications;
        this.communityVerifications = communityVerifications;
        this.communities = communities;
        this.audit = audit;
        this.privateMedia = privateMedia;
        this.photoIdProps = photoIdProps;
        this.verificationProps = verificationProps;
        this.notifications = notifications;
        this.emailService = emailService;
    }

    @GetMapping("/verifications")
    public ResponseEntity<?> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "method", required = false) String method,
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
        String normalizedMethod = method != null ? method.trim().toLowerCase(Locale.ROOT) : null;
        List<VerificationRequestsRepository.Row> rows = requests.listForAdmin(normalizedStatus, normalizedMethod, cursorTs, cursorId, limit);
        String next = null;
        if (rows.size() == limit) {
            var last = rows.get(rows.size() - 1);
            next = Pagination.encode(last.submittedAt, last.id);
        }
        List<Map<String, Object>> items = rows.stream().map(r -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", r.id);
            map.put("user_id", r.userId);
            if (r.userHandle != null) map.put("user_handle", r.userHandle);
            if (r.userDisplayName != null) map.put("user_display_name", r.userDisplayName);
            map.put("email", r.email);
            map.put("method", r.method);
            map.put("status", r.status);
            map.put("submitted_at", r.submittedAt);
            map.put("company_domain", r.companyDomain);
            map.put("community_id", r.communityId);
            map.put("community_name", r.communityName);
            if (r.communityKind != null) map.put("community_kind", r.communityKind);
            if (r.mediaKey != null) map.put("media_key", r.mediaKey);
            if (r.selfieKey != null) map.put("selfie_key", r.selfieKey);
            if (r.idFrontKey != null) map.put("id_front_key", r.idFrontKey);
            if (r.idBackKey != null) map.put("id_back_key", r.idBackKey);
            if (r.metadata != null) map.put("metadata", r.metadata);
            if (r.reviewedAt != null) map.put("reviewed_at", r.reviewedAt);
            if (r.reviewedBy != null) map.put("reviewed_by", r.reviewedBy);
            if (r.rejectReason != null) map.put("reject_reason", r.rejectReason);
            if (r.deleteAfterAt != null) map.put("delete_after_at", r.deleteAfterAt);
            if (r.mediaDeletedAt != null) map.put("media_deleted_at", r.mediaDeletedAt);
            return map;
        }).toList();
        Map<String, Object> body = new HashMap<>();
        body.put("items", items);
        if (next != null) body.put("next_cursor", next);
        return ResponseEntity.ok(body);
    }

    @GetMapping("/verifications/{id}")
    public ResponseEntity<?> get(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") long id) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.VERIFY_USERS);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        var req = requests.findById(id);
        if (req.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
        }
        Map<String, Object> body = new HashMap<>();
        body.put("id", req.get().id);
        body.put("user_id", req.get().userId);
        if (req.get().userHandle != null) body.put("user_handle", req.get().userHandle);
        if (req.get().userDisplayName != null) body.put("user_display_name", req.get().userDisplayName);
        body.put("email", req.get().email);
        body.put("method", req.get().method);
        body.put("status", req.get().status);
        body.put("submitted_at", req.get().submittedAt);
        if (req.get().reviewedAt != null) body.put("reviewed_at", req.get().reviewedAt);
        if (req.get().reviewedBy != null) body.put("reviewed_by", req.get().reviewedBy);
        if (req.get().rejectReason != null) body.put("reject_reason", req.get().rejectReason);
        if (req.get().mediaKey != null) body.put("media_key", req.get().mediaKey);
        if (req.get().mediaDeletedAt == null && (req.get().selfieKey != null || req.get().idFrontKey != null || req.get().idBackKey != null)) {
            List<Map<String, Object>> docs = new ArrayList<>();
            addDoc(docs, "selfie", req.get().selfieKey);
            addDoc(docs, "id_front", req.get().idFrontKey);
            addDoc(docs, "id_back", req.get().idBackKey);
            body.put("documents", docs);
        }
        if (req.get().deleteAfterAt != null) body.put("delete_after_at", req.get().deleteAfterAt);
        if (req.get().mediaDeletedAt != null) body.put("media_deleted_at", req.get().mediaDeletedAt);
        if (req.get().companyDomain != null) body.put("company_domain", req.get().companyDomain);
        body.put("community_id", req.get().communityId);
        body.put("community_name", req.get().communityName);
        if (req.get().communityKind != null) body.put("community_kind", req.get().communityKind);
        if (req.get().metadata != null) body.put("metadata", req.get().metadata);
        return ResponseEntity.ok(body);
    }

    private void addDoc(List<Map<String, Object>> docs, String kind, String key) {
        if (key == null || key.isBlank()) return;
        String url = privateMedia.presignGet(key, privateMedia.adminDownloadTtl());
        Map<String, Object> m = new HashMap<>();
        m.put("kind", kind);
        m.put("key", key);
        if (url != null) {
            m.put("download_url", url);
            m.put("expires_in_seconds", (int) privateMedia.adminDownloadTtl().toSeconds());
        }
        docs.add(m);
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
        if (req.get().status == null || !"pending".equalsIgnoreCase(req.get().status)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "verification_already_reviewed",
                    "status", req.get().status
            ));
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
            String method = req.get().method;
            String requestEmail = "email".equalsIgnoreCase(method) ? req.get().email : null;
            java.time.OffsetDateTime expiresAt = resolveExpiry(community);
            try {
                communityVerifications.markVerified(
                        req.get().userId,
                        req.get().communityId,
                        method,
                        expiresAt,
                        requestEmail
                );
            } catch (DataIntegrityViolationException ex) {
                requests.updateStatus(id, "rejected", authRes.admin().id, "email_in_use");
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "email_in_use"));
            }
            notifications.notifyCommunityVerificationApproved(
                    req.get().userId,
                    req.get().communityId,
                    req.get().communityName,
                    method,
                    req.get().id,
                    expiresAt
            );
            if ("email".equalsIgnoreCase(method) && requestEmail != null) {
                emailService.sendCommunityVerifiedEmail(requestEmail, req.get().communityName);
            }
        } else {
            verifications.markVerified(req.get().userId, req.get().method);
            notifications.notifyUserVerificationApproved(req.get().userId, req.get().method, req.get().id);
            if ("email".equalsIgnoreCase(req.get().method) && req.get().email != null) {
                emailService.sendUserVerifiedEmail(req.get().email);
            }
        }
        boolean deleted = deleteVerificationMediaIfPresent(req.get());
        audit.log(authRes.admin().id, "verification.approve", "verification_request", id, null);
        return ResponseEntity.ok(Map.of("status", "approved", "media_deleted", deleted));
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
        if (req.get().status == null || !"pending".equalsIgnoreCase(req.get().status)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "verification_already_reviewed",
                    "status", req.get().status
            ));
        }
        String reason = body != null ? body.reason() : null;
        boolean updated;
        java.time.OffsetDateTime deleteAfterAt = null;
        if ("photo_id".equalsIgnoreCase(req.get().method)) {
            deleteAfterAt = java.time.OffsetDateTime.now().plusDays(photoIdProps.getRejectedDeleteAfterDays());
            updated = requests.updateStatusWithDeleteAfter(id, "rejected", authRes.admin().id, reason, deleteAfterAt);
        } else {
            updated = requests.updateStatus(id, "rejected", authRes.admin().id, reason);
        }
        if (!updated) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "update_failed"));
        }
        if (req.get().communityId != null) {
            communityVerifications.markUnverified(req.get().userId, req.get().communityId, req.get().method);
            notifications.notifyCommunityVerificationRejected(
                    req.get().userId,
                    req.get().communityId,
                    req.get().communityName,
                    req.get().method,
                    req.get().id,
                    reason
            );
            if ("email".equalsIgnoreCase(req.get().method) && req.get().email != null) {
                emailService.sendCommunityVerificationRejectedEmail(req.get().email, req.get().communityName, reason);
            }
        } else {
            verifications.markUnverified(req.get().userId, req.get().method);
            notifications.notifyUserVerificationRejected(req.get().userId, req.get().method, req.get().id, reason);
        }
        audit.log(authRes.admin().id, "verification.reject", "verification_request", id, null);
        Map<String, Object> out = new HashMap<>();
        out.put("status", "rejected");
        if (deleteAfterAt != null) out.put("delete_after_at", deleteAfterAt);
        return ResponseEntity.ok(out);
    }

    @PostMapping("/verifications/{id}/delete-media")
    public ResponseEntity<?> deleteMedia(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") long id) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.VERIFY_USERS);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        var req = requests.findById(id);
        if (req.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
        }
        if (!"photo_id".equalsIgnoreCase(req.get().method)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "unsupported_method"));
        }
        if (req.get().mediaDeletedAt != null) {
            return ResponseEntity.ok(Map.of(
                    "media_deleted", false,
                    "already_deleted", true,
                    "media_deleted_at", req.get().mediaDeletedAt
            ));
        }
        if (!privateMedia.isConfigured()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "verification_bucket_not_configured"));
        }
        boolean hasMedia = (req.get().selfieKey != null && !req.get().selfieKey.isBlank())
                || (req.get().idFrontKey != null && !req.get().idFrontKey.isBlank())
                || (req.get().idBackKey != null && !req.get().idBackKey.isBlank());
        if (!hasMedia) {
            return ResponseEntity.ok(Map.of("media_deleted", false, "no_media", true));
        }
        boolean ok = true;
        if (req.get().selfieKey != null && !req.get().selfieKey.isBlank()) ok &= privateMedia.deleteObjectQuietly(req.get().selfieKey);
        if (req.get().idFrontKey != null && !req.get().idFrontKey.isBlank()) ok &= privateMedia.deleteObjectQuietly(req.get().idFrontKey);
        if (req.get().idBackKey != null && !req.get().idBackKey.isBlank()) ok &= privateMedia.deleteObjectQuietly(req.get().idBackKey);
        if (!ok) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "delete_failed"));
        }
        requests.markMediaDeleted(req.get().id);
        audit.log(authRes.admin().id, "verification.delete_media", "verification_request", id, null);
        return ResponseEntity.ok(Map.of(
                "media_deleted", true,
                "media_deleted_at", OffsetDateTime.now()
        ));
    }

    private boolean deleteVerificationMediaIfPresent(VerificationRequestsRepository.Row req) {
        if (req.mediaDeletedAt != null) return false;
        if (!privateMedia.isConfigured()) return false;
        boolean any = false;
        if (req.selfieKey != null && !req.selfieKey.isBlank()) {
            any = privateMedia.deleteObjectQuietly(req.selfieKey) || any;
        }
        if (req.idFrontKey != null && !req.idFrontKey.isBlank()) {
            any = privateMedia.deleteObjectQuietly(req.idFrontKey) || any;
        }
        if (req.idBackKey != null && !req.idBackKey.isBlank()) {
            any = privateMedia.deleteObjectQuietly(req.idBackKey) || any;
        }
        if (any) {
            requests.markMediaDeleted(req.id);
        }
        return any;
    }

    public record RejectRequest(String reason) {}

    private java.time.OffsetDateTime resolveExpiry(CommunitiesRepository.CommunityRow community) {
        Integer ttlDays = community.verificationTtlDays;
        int effectiveTtlDays = ttlDays != null ? ttlDays : verificationProps.getDefaultCommunityTtlDays();
        if (effectiveTtlDays > 0) return java.time.OffsetDateTime.now().plusDays(effectiveTtlDays);
        return null; // 0 or negative => no expiry
    }
}

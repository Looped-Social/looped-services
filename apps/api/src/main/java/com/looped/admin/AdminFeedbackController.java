package com.looped.admin;

import com.looped.email.EmailService;
import com.looped.feedback.FeedbackRepository;
import com.looped.shared.Pagination;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/v1/admin")
@Validated
public class AdminFeedbackController {
    private final AdminAuthService auth;
    private final FeedbackRepository feedback;
    private final EmailService emailService;
    private final AdminAuditRepository audit;

    public AdminFeedbackController(AdminAuthService auth,
                                   FeedbackRepository feedback,
                                   EmailService emailService,
                                   AdminAuditRepository audit) {
        this.auth = auth;
        this.feedback = feedback;
        this.emailService = emailService;
        this.audit = audit;
    }

    @GetMapping("/feedback")
    public ResponseEntity<?> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false, defaultValue = "50") int limit
    ) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.VIEW_FEEDBACK);
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
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "invalid_date"));
        }
        String normalizedStatus = status != null ? status.trim().toLowerCase(Locale.ROOT) : null;
        int lim = Math.max(1, Math.min(limit, 200));
        List<FeedbackRepository.Row> rows = feedback.listForAdmin(normalizedStatus, fromTs, toTs, cursorTs, cursorId, lim);
        String next = null;
        if (rows.size() == lim) {
            var last = rows.get(rows.size() - 1);
            next = Pagination.encode(last.createdAt, last.id);
        }
        List<Map<String, Object>> items = rows.stream().map(r -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", r.id);
            if (r.userId != null) map.put("user_id", r.userId);
            if (r.userHandle != null) map.put("user_handle", r.userHandle);
            if (r.email != null) map.put("email", r.email);
            map.put("title", r.title);
            map.put("message", r.message);
            map.put("status", r.status);
            map.put("created_at", r.createdAt);
            if (r.reviewedAt != null) map.put("reviewed_at", r.reviewedAt);
            if (r.reviewedBy != null) map.put("reviewed_by", r.reviewedBy);
            if (r.reviewedNote != null) map.put("reviewed_note", r.reviewedNote);
            return map;
        }).toList();
        Map<String, Object> body = new HashMap<>();
        body.put("items", items);
        if (next != null) body.put("next_cursor", next);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/feedback/{id}/seen")
    public ResponseEntity<?> markSeen(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") long id,
            @RequestBody(required = false) @Valid SeenRequest body
    ) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.VIEW_FEEDBACK);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        var existing = feedback.findById(id);
        if (existing.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
        }
        String note = body == null ? null : normalizeNote(body.note());
        boolean updated = feedback.review(id, "seen", authRes.admin().id, note);
        if (!updated) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "update_failed"));
        }
        audit.log(authRes.admin().id, "feedback.seen", "feedback", id, null);
        return ResponseEntity.ok(Map.of("status", "seen"));
    }

    @PostMapping("/feedback/{id}/reply")
    public ResponseEntity<?> reply(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") long id,
            @Valid @RequestBody ReplyRequest body
    ) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.VIEW_FEEDBACK);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        var existing = feedback.findById(id);
        if (existing.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
        }
        var row = existing.get();
        if (row.email == null || row.email.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", "no_contact_email",
                    "message", "Feedback does not include an email address"
            ));
        }
        if (!emailService.isEnabled()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "error", "email_not_configured"
            ));
        }

        String resolvedSubject = normalizeSubject(body.subject());
        if (resolvedSubject == null) {
            String base = (row.title == null || row.title.isBlank()) ? "Looped feedback" : row.title.trim();
            resolvedSubject = "Re: " + base;
        }

        try {
            emailService.sendAdminOpsEmail(row.email, resolvedSubject, body.message().trim(), null);
        } catch (RuntimeException ex) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", "email_send_failed"));
        }

        String note = normalizeNote(body.note());
        boolean updated = feedback.review(id, "replied", authRes.admin().id, note);
        if (!updated) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "update_failed"));
        }
        audit.log(authRes.admin().id, "feedback.reply", "feedback", id, null);
        return ResponseEntity.ok(Map.of("status", "replied"));
    }

    private String normalizeNote(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private String normalizeSubject(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    public record SeenRequest(
            @Size(max = 2000) String note
    ) {}

    public record ReplyRequest(
            @Size(max = 200) String subject,
            @NotBlank @Size(max = 4000) String message,
            @Size(max = 2000) String note
    ) {}
}

package com.looped.admin;

import com.looped.notifications.NotificationPublisher;
import com.looped.users.UserRepository;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/v1/admin/announcements")
public class AdminAnnouncementsController {
    private final AdminAuthService auth;
    private final AdminAuditRepository audit;
    private final UserRepository users;
    private final NotificationPublisher notifications;

    public AdminAnnouncementsController(AdminAuthService auth,
                                        AdminAuditRepository audit,
                                        UserRepository users,
                                        NotificationPublisher notifications) {
        this.auth = auth;
        this.audit = audit;
        this.users = users;
        this.notifications = notifications;
    }

    @PostMapping
    public ResponseEntity<?> create(@AuthenticationPrincipal Jwt jwt,
                                    @Validated @RequestBody CreateRequest body) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.SEND_ANNOUNCEMENTS);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        var userIds = users.listActiveUserIdsByCompany(body.companyId());
        Map<String, Object> payload = new HashMap<>();
        payload.put("title", body.title());
        payload.put("body", body.body());
        if (body.deeplink() != null && !body.deeplink().isBlank()) {
            payload.put("deeplink", body.deeplink());
        }
        payload.put("company_id", body.companyId());
        notifications.notifyAnnouncement(userIds, payload);
        audit.log(authRes.admin().id, "announcement.send", "company", body.companyId(), null);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("sent", userIds.size()));
    }

    public record CreateRequest(
            @NotNull Long companyId,
            @NotBlank @Size(max = 140) String title,
            @NotBlank @Size(max = 1000) String body,
            @Size(max = 300) String deeplink
    ) {}
}

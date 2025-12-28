package com.looped.admin;

import com.looped.communities.CommunitiesRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/v1/admin/communities")
@Validated
public class AdminCommunitiesController {
    private final AdminAuthService auth;
    private final CommunitiesRepository communities;
    private final AdminAuditRepository audit;

    public AdminCommunitiesController(AdminAuthService auth, CommunitiesRepository communities, AdminAuditRepository audit) {
        this.auth = auth;
        this.communities = communities;
        this.audit = audit;
    }

    @PatchMapping("/{id}")
    public ResponseEntity<?> update(@AuthenticationPrincipal Jwt jwt,
                                    @PathVariable("id") long id,
                                    @Valid @RequestBody UpdateCommunityRequest body) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.CREATE_COMMUNITY);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        Integer ttlDays = body.verificationTtlDays();
        if (ttlDays != null && ttlDays < 1) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "invalid_ttl_days"));
        }
        boolean updated = communities.updateVerificationTtlDays(id, ttlDays);
        if (!updated) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
        }
        audit.log(authRes.admin().id, "community.update", "community", id,
                ttlDays == null ? "verification_ttl_days=null" : "verification_ttl_days=" + ttlDays);
        return ResponseEntity.ok(Map.of(
                "id", id,
                "verification_ttl_days", ttlDays
        ));
    }

    public record UpdateCommunityRequest(Integer verificationTtlDays) {}
}

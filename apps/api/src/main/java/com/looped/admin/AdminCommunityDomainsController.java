package com.looped.admin;

import com.looped.communities.CommunitiesRepository;
import com.looped.communities.CommunityDomainsRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/v1/admin/communities")
@Validated
public class AdminCommunityDomainsController {
    private final AdminAuthService auth;
    private final CommunitiesRepository communities;
    private final CommunityDomainsRepository domains;
    private final AdminAuditRepository audit;

    public AdminCommunityDomainsController(AdminAuthService auth,
                                           CommunitiesRepository communities,
                                           CommunityDomainsRepository domains,
                                           AdminAuditRepository audit) {
        this.auth = auth;
        this.communities = communities;
        this.domains = domains;
        this.audit = audit;
    }

    @GetMapping("/{id}/domains")
    public ResponseEntity<?> list(@AuthenticationPrincipal Jwt jwt,
                                  @PathVariable("id") long id,
                                  @RequestParam(value = "includeInherited", required = false, defaultValue = "false") boolean includeInherited) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.CREATE_COMMUNITY);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        var community = communities.findById(id);
        if (community.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
        }
        return ResponseEntity.ok(Map.of("items", domains.listDomains(id)));
    }

    @PostMapping("/{id}/domains")
    public ResponseEntity<?> add(@AuthenticationPrincipal Jwt jwt,
                                 @PathVariable("id") long id,
                                 @Valid @RequestBody DomainRequest body) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.CREATE_COMMUNITY);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        if (communities.findById(id).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
        }
        String normalized = domains.normalizeDomain(body.domain());
        if (normalized == null) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "invalid_domain"));
        }
        boolean inserted = domains.insert(id, normalized);
        audit.log(authRes.admin().id, "community.domain.add", "community", id, "domain=" + normalized);
        if (inserted) {
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("domain", normalized));
        }
        return ResponseEntity.ok(Map.of("domain", normalized));
    }

    @DeleteMapping("/{id}/domains")
    public ResponseEntity<?> delete(@AuthenticationPrincipal Jwt jwt,
                                    @PathVariable("id") long id,
                                    @RequestParam("domain") String domain) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.CREATE_COMMUNITY);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        if (communities.findById(id).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
        }
        String normalized = domains.normalizeDomain(domain);
        if (normalized == null) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "invalid_domain"));
        }
        boolean removed = domains.delete(id, normalized);
        if (!removed) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
        }
        audit.log(authRes.admin().id, "community.domain.delete", "community", id, "domain=" + normalized);
        return ResponseEntity.noContent().build();
    }

    public record DomainRequest(@NotBlank String domain) {}
}

package com.looped.admin;

import com.looped.communities.CommunitiesRepository;
import com.looped.communities.CommunitySectorLinksRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/admin/sectors")
@Validated
public class AdminSectorCompaniesController {
    private final AdminAuthService auth;
    private final CommunitiesRepository communities;
    private final CommunitySectorLinksRepository links;
    private final AdminAuditRepository audit;

    public AdminSectorCompaniesController(AdminAuthService auth,
                                          CommunitiesRepository communities,
                                          CommunitySectorLinksRepository links,
                                          AdminAuditRepository audit) {
        this.auth = auth;
        this.communities = communities;
        this.links = links;
        this.audit = audit;
    }

    @GetMapping("/{id}/companies")
    public ResponseEntity<?> list(@AuthenticationPrincipal Jwt jwt,
                                  @PathVariable("id") long id) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.CREATE_COMMUNITY);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        var sector = communities.findById(id);
        if (sector.isEmpty() || !"sector".equalsIgnoreCase(sector.get().kind)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
        }
        List<Map<String, Object>> items = links.listCompanies(id).stream().map(this::payload).toList();
        return ResponseEntity.ok(Map.of("items", items));
    }

    @PostMapping("/{id}/companies")
    public ResponseEntity<?> add(@AuthenticationPrincipal Jwt jwt,
                                 @PathVariable("id") long id,
                                 @Valid @RequestBody LinkRequest body) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.CREATE_COMMUNITY);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        var sector = communities.findById(id);
        if (sector.isEmpty() || !"sector".equalsIgnoreCase(sector.get().kind)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
        }
        var company = communities.findById(body.companyId());
        if (company.isEmpty() || !"company".equalsIgnoreCase(company.get().kind)) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "company_not_found"));
        }
        boolean inserted = links.insert(id, body.companyId());
        audit.log(authRes.admin().id, "sector.company.add", "community", id, "company_id=" + body.companyId());
        if (inserted) {
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("company_id", body.companyId()));
        }
        return ResponseEntity.ok(Map.of("company_id", body.companyId()));
    }

    @DeleteMapping("/{id}/companies/{companyId}")
    public ResponseEntity<?> delete(@AuthenticationPrincipal Jwt jwt,
                                    @PathVariable("id") long id,
                                    @PathVariable("companyId") long companyId) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.CREATE_COMMUNITY);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        var sector = communities.findById(id);
        if (sector.isEmpty() || !"sector".equalsIgnoreCase(sector.get().kind)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
        }
        boolean removed = links.delete(id, companyId);
        if (!removed) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
        }
        audit.log(authRes.admin().id, "sector.company.delete", "community", id, "company_id=" + companyId);
        return ResponseEntity.noContent().build();
    }

    private Map<String, Object> payload(CommunitiesRepository.CommunityRow row) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", row.id);
        map.put("kind", row.kind);
        map.put("name", row.name);
        if (row.description != null) map.put("description", row.description);
        map.put("member_count", row.memberCount);
        if (row.imageUrl != null) map.put("image_url", row.imageUrl);
        map.put("created_at", row.createdAt);
        if (row.verificationTtlDays != null) map.put("verification_ttl_days", row.verificationTtlDays);
        return map;
    }

    public record LinkRequest(@NotNull Long companyId) {}
}

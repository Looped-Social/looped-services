package com.looped.admin;

import com.looped.communities.CommunitiesRepository;
import com.looped.communities.CommunityLogoResolver;
import com.looped.communities.CommunitySectorLinksRepository;
import com.looped.communities.CommunityVerificationsRepository;
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
    private final CommunityLogoResolver logos;
    private final AdminAuditRepository audit;
    private final CommunityVerificationsRepository verifications;

    public AdminSectorCompaniesController(AdminAuthService auth,
                                          CommunitiesRepository communities,
                                          CommunitySectorLinksRepository links,
                                          CommunityLogoResolver logos,
                                          AdminAuditRepository audit,
                                          CommunityVerificationsRepository verifications) {
        this.auth = auth;
        this.communities = communities;
        this.links = links;
        this.logos = logos;
        this.audit = audit;
        this.verifications = verifications;
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
        var rows = links.listCompanies(id);
        var fallback = logos.resolveFallbacks(rows.stream()
                .map(row -> new CommunityLogoResolver.CommunityRef(row.id, row.kind, row.imageUrl))
                .toList());
        var memberCounts = verifications.countActiveVerifiedMembersByCommunityIds(rows.stream().map(r -> r.id).toList());
        List<Map<String, Object>> items = rows.stream()
                .map(row -> payload(row, fallback, memberCounts.getOrDefault(row.id, 0)))
                .toList();
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
        var member = communities.findById(body.companyId());
        if (member.isEmpty() || !isLinkableKind(member.get().kind)) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "community_not_found"));
        }
        boolean inserted = links.insert(id, body.companyId());
        audit.log(authRes.admin().id, "sector.community.add", "community", id, "community_id=" + body.companyId());
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
        audit.log(authRes.admin().id, "sector.community.delete", "community", id, "community_id=" + companyId);
        return ResponseEntity.noContent().build();
    }

    private Map<String, Object> payload(CommunitiesRepository.CommunityRow row, Map<Long, String> fallbacks, int memberCount) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", row.id);
        map.put("kind", row.kind);
        map.put("name", row.name);
        if (row.description != null) map.put("description", row.description);
        map.put("member_count", memberCount);
        String resolved = row.imageUrl;
        if ((resolved == null || resolved.isBlank()) && fallbacks != null) {
            resolved = fallbacks.get(row.id);
        } else if (resolved == null || resolved.isBlank()) {
            resolved = logos.resolve(row.id, row.kind, row.imageUrl);
        }
        if (resolved != null && !resolved.isBlank()) map.put("image_url", resolved);
        map.put("created_at", row.createdAt);
        if (row.verificationTtlDays != null) map.put("verification_ttl_days", row.verificationTtlDays);
        return map;
    }

    private boolean isLinkableKind(String kind) {
        if (kind == null) return false;
        String normalized = kind.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.equals("company") || normalized.equals("school");
    }

    public record LinkRequest(@NotNull Long companyId) {}
}

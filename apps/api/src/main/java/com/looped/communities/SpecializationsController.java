package com.looped.communities;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1")
public class SpecializationsController {
    private final CommunityFollowsService communityFollows;
    private final SpecializationMembershipService memberships;
    private final CommunitiesRepository communities;
    private final CommunityVerificationsRepository verifications;

    public SpecializationsController(CommunityFollowsService communityFollows,
                                     SpecializationMembershipService memberships,
                                     CommunitiesRepository communities,
                                     CommunityVerificationsRepository verifications) {
        this.communityFollows = communityFollows;
        this.memberships = memberships;
        this.communities = communities;
        this.verifications = verifications;
    }

    @PostMapping("/specializations/{id}/follow")
    public ResponseEntity<?> followSpecialization(@AuthenticationPrincipal Jwt jwt,
                                                  @PathVariable("id") long id) {
        var valid = validateMajorOrDepartment(id);
        if (valid != null) return valid;
        var res = communityFollows.follow(jwt.getSubject(), id);
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "user_not_provisioned"));
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "specialization_not_found"));
            case OK -> new ResponseEntity<>(Map.of(
                    "specialization_id", id,
                    "following", true
            ), res.changed() ? HttpStatus.CREATED : HttpStatus.OK);
            default -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        };
    }

    @DeleteMapping("/specializations/{id}/follow")
    public ResponseEntity<?> unfollowSpecialization(@AuthenticationPrincipal Jwt jwt,
                                                    @PathVariable("id") long id) {
        var valid = validateMajorOrDepartment(id);
        if (valid != null) return valid;
        var res = communityFollows.unfollow(jwt.getSubject(), id);
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "user_not_provisioned"));
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "specialization_not_found"));
            case OK -> ResponseEntity.ok(Map.of(
                    "specialization_id", id,
                    "following", false
            ));
            default -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        };
    }

    @PostMapping("/specializations/{id}/join")
    public ResponseEntity<?> join(@AuthenticationPrincipal Jwt jwt,
                                  @PathVariable("id") long id) {
        var res = memberships.join(jwt.getSubject(), id);
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "user_not_provisioned"));
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "specialization_not_found"));
            case INVALID_SPECIALIZATION -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", "invalid_specialization",
                    "message", "Specialization must be a major or department"
            ));
            case LIMIT_REACHED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "specialization_join_limit",
                    "message", limitMessage(res.specializationType(), res.limit()),
                    "specialization_type", res.specializationType(),
                    "limit", res.limit()
            ));
            case COOLDOWN -> {
                Map<String, Object> body = new HashMap<>();
                body.put("error", "specialization_join_cooldown");
                body.put("message", cooldownMessage(res.specializationType()));
                body.put("specialization_type", res.specializationType());
                if (res.cooldownEndsAt() != null) {
                    body.put("cooldown_ends_at", res.cooldownEndsAt());
                    long days = java.time.Duration.between(OffsetDateTime.now(), res.cooldownEndsAt()).toDays();
                    if (days > 0) body.put("cooldown_days_remaining", days);
                }
                yield ResponseEntity.status(HttpStatus.CONFLICT).body(body);
            }
            case OK -> new ResponseEntity<>(Map.of(
                    "specialization_id", id,
                    "joined", true
            ), res.changed() ? HttpStatus.CREATED : HttpStatus.OK);
        };
    }

    @DeleteMapping("/specializations/{id}/join")
    public ResponseEntity<?> unjoin(@AuthenticationPrincipal Jwt jwt,
                                    @PathVariable("id") long id) {
        var res = memberships.unjoin(jwt.getSubject(), id);
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "user_not_provisioned"));
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "specialization_not_found"));
            case INVALID_SPECIALIZATION -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", "invalid_specialization",
                    "message", "Specialization must be a major or department"
            ));
            case OK -> ResponseEntity.ok(Map.of(
                    "specialization_id", id,
                    "joined", false
            ));
            default -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        };
    }

    @GetMapping("/me/joined/specializations")
    public ResponseEntity<?> joinedSpecializations(@AuthenticationPrincipal Jwt jwt,
                                                   @RequestParam(value = "type", required = false) String type,
                                                   @RequestParam(value = "specializationType", required = false) String specializationTypeAlt,
                                                   @RequestParam(value = "specialization_type", required = false) String specializationType,
                                                   @RequestParam(value = "cursor", required = false) String cursor,
                                                   @RequestParam(value = "limit", required = false, defaultValue = "50") int limit) {
        int lim = Math.max(1, Math.min(limit, 200));
        String requested = type != null ? type : (specializationTypeAlt != null ? specializationTypeAlt : specializationType);
        String normalized = normalizeListType(requested);
        if (normalized == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "error", "invalid_specialization_type",
                    "message", "type must be major, department, or all"
            ));
        }
        var res = memberships.joined(jwt.getSubject(), requested, cursor, lim);
        if (res.status() == SpecializationMembershipService.Status.USER_NOT_PROVISIONED) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned",
                    "message", "Complete onboarding before viewing joined specializations"
            ));
        }
        if (res.status() != SpecializationMembershipService.Status.OK) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
        var memberCounts = verifications.countActiveVerifiedMembersByCommunityIds(
                res.items().stream().map(SpecializationJoinsRepository.JoinRow::specializationId).toList()
        );
        List<Map<String, Object>> items = res.items().stream().map(row -> payload(row, memberCounts)).toList();
        Map<String, Object> body = new HashMap<>();
        body.put("items", items);
        if (res.nextCursor() != null) body.put("next_cursor", res.nextCursor());
        return ResponseEntity.ok(body);
    }

    private Map<String, Object> payload(SpecializationJoinsRepository.JoinRow row, java.util.Map<Long, Integer> memberCounts) {
        Map<String, Object> out = new HashMap<>();
        out.put("id", row.specializationId());
        out.put("kind", row.kind());
        out.put("name", row.name());
        out.put("member_count", memberCounts.getOrDefault(row.specializationId(), 0));
        if (row.specializationType() != null) out.put("specialization_type", row.specializationType());
        out.put("joined_at", row.createdAt());
        return out;
    }

    private String limitMessage(String specializationType, Integer limit) {
        if (specializationType == null || limit == null) return "Specialization join limit reached";
        String label = specializationType.equals("major") ? "majors" : "departments";
        return "You can only join up to " + limit + " " + label + ".";
    }

    private String cooldownMessage(String specializationType) {
        if (specializationType == null) return "You must wait before changing specializations.";
        String label = specializationType.equals("major") ? "majors" : "departments";
        return "You must wait 6 months before changing " + label + ".";
    }

    private ResponseEntity<?> validateMajorOrDepartment(long specializationId) {
        var communityOpt = communities.findById(specializationId);
        if (communityOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "specialization_not_found"));
        }
        var community = communityOpt.get();
        if (!"specialization".equalsIgnoreCase(community.kind)) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", "invalid_specialization",
                    "message", "Specialization must be a major or department"
            ));
        }
        String t = community.specializationType == null ? "" : community.specializationType.trim().toLowerCase(java.util.Locale.ROOT);
        if (!t.equals("major") && !t.equals("department")) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", "invalid_specialization",
                    "message", "Specialization must be a major or department"
            ));
        }
        return null;
    }

    private String normalizeListType(String raw) {
        if (raw == null || raw.isBlank()) return "all";
        String n = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if ("all".equals(n)) return "all";
        if ("major".equals(n) || "department".equals(n)) return n;
        return null;
    }
}

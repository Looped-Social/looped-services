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
    private final CommunityMemberCountService memberCounts;

    public SpecializationsController(CommunityFollowsService communityFollows,
                                     SpecializationMembershipService memberships,
                                     CommunitiesRepository communities,
                                     CommunityMemberCountService memberCounts) {
        this.communityFollows = communityFollows;
        this.memberships = memberships;
        this.communities = communities;
        this.memberCounts = memberCounts;
    }

    @PostMapping("/specializations/{id}/follow")
    public ResponseEntity<?> followSpecialization(@AuthenticationPrincipal Jwt jwt,
                                                  @PathVariable("id") long id) {
        var valid = validateMajorOrField(id);
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
        var valid = validateMajorOrField(id);
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
                    "message", "Specialization must be a major or field"
            ));
            case VERIFICATION_REQUIRED -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "specialization_verification_required",
                    "message", verificationRequiredMessage(res.specializationType(), res.requiredVerificationKind()),
                    "specialization_type", res.specializationType(),
                    "required_verification_kind", res.requiredVerificationKind()
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
                body.put("message", cooldownMessage(res.specializationType(), res.cooldownMonths()));
                body.put("specialization_type", res.specializationType());
                if (res.cooldownEndsAt() != null) {
                    body.put("cooldown_ends_at", res.cooldownEndsAt());
                    long days = java.time.Duration.between(OffsetDateTime.now(), res.cooldownEndsAt()).toDays();
                    if (days > 0) body.put("cooldown_days_remaining", days);
                }
                if (res.cooldownMonths() != null) body.put("cooldown_months", res.cooldownMonths());
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
                    "message", "Specialization must be a major or field"
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
                    "message", "type must be major, field, or all"
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
        var memberCounts = this.memberCounts.memberCountsByCommunityRefs(
                res.items().stream()
                        .map(r -> new CommunityMemberCountService.Ref(r.specializationId(), r.kind()))
                        .toList()
        );
        List<Map<String, Object>> items = res.items().stream().map(row -> payload(row, memberCounts)).toList();
        Map<String, Object> body = new HashMap<>();
        body.put("items", items);
        if (res.nextCursor() != null) body.put("next_cursor", res.nextCursor());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/me/specializations/join-limits")
    public ResponseEntity<?> specializationJoinLimits(@AuthenticationPrincipal Jwt jwt,
                                                      @RequestParam(value = "type", required = false) String type,
                                                      @RequestParam(value = "specializationType", required = false) String specializationTypeAlt,
                                                      @RequestParam(value = "specialization_type", required = false) String specializationType) {
        String requested = type != null ? type : (specializationTypeAlt != null ? specializationTypeAlt : specializationType);
        String normalized = normalizeListType(requested);
        if (normalized == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "error", "invalid_specialization_type",
                    "message", "type must be major, field, or all"
            ));
        }

        SpecializationMembershipService.JoinLimitSnapshotsResult res;
        if ("all".equals(normalized)) {
            res = memberships.joinLimitSnapshots(jwt.getSubject());
        } else {
            res = memberships.joinLimitSnapshots(jwt.getSubject(), normalized);
        }

        if (res.status() == SpecializationMembershipService.Status.USER_NOT_PROVISIONED) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned",
                    "message", "Complete onboarding before viewing specialization limits"
            ));
        }
        if (res.status() != SpecializationMembershipService.Status.OK) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }

        List<Map<String, Object>> items = res.items().stream().map(this::joinLimitPayload).toList();
        return ResponseEntity.ok(Map.of("items", items));
    }

    private Map<String, Object> joinLimitPayload(SpecializationMembershipService.JoinLimitSnapshot snap) {
        Map<String, Object> out = new HashMap<>();
        if (snap.specializationType() != null) out.put("specialization_type", snap.specializationType());
        out.put("limit", snap.limit());
        out.put("joined_count", snap.joinedCount());
        out.put("remaining", snap.remaining());
        out.put("cooldown_months", snap.cooldownMonths());
        out.put("cooldown_active", snap.cooldownActive());
        if (snap.cooldownEndsAt() != null) out.put("cooldown_ends_at", snap.cooldownEndsAt());
        if (snap.cooldownDaysRemaining() != null) out.put("cooldown_days_remaining", snap.cooldownDaysRemaining());
        out.put("can_join", snap.canJoin());
        if (snap.blockedReason() != null) out.put("blocked_reason", snap.blockedReason());
        if (snap.requiredVerificationKind() != null) {
            out.put("required_verification_kind", snap.requiredVerificationKind());
            out.put("join_requires_verification_kind", snap.requiredVerificationKind());
        }
        String joinBlocked = joinBlockedReason(snap.blockedReason());
        if (joinBlocked != null) out.put("join_blocked_reason", joinBlocked);
        return out;
    }

    private String joinBlockedReason(String blockedReason) {
        if (blockedReason == null || blockedReason.isBlank()) return null;
        if (blockedReason.startsWith("verify_")) return "verification_required";
        if ("limit".equals(blockedReason)) return "limit";
        if ("cooldown".equals(blockedReason)) return "cooldown";
        return null;
    }

    private Map<String, Object> payload(SpecializationJoinsRepository.JoinRow row, java.util.Map<Long, Integer> memberCounts) {
        Map<String, Object> out = new HashMap<>();
        out.put("id", row.specializationId());
        out.put("kind", row.kind());
        out.put("name", row.name());
        if (row.shortName() != null && !row.shortName().isBlank()) {
            out.put("short_name", row.shortName());
        }
        out.put("member_count", memberCounts.getOrDefault(row.specializationId(), 0));
        if (row.specializationType() != null) out.put("specialization_type", row.specializationType());
        out.put("joined_at", row.createdAt());
        return out;
    }

    private String limitMessage(String specializationType, Integer limit) {
        if (specializationType == null || limit == null) return "Specialization join limit reached";
        String label = specializationType.equals("major") ? "majors" : "fields";
        return "You can only join up to " + limit + " " + label + ".";
    }

    private String cooldownMessage(String specializationType, Integer cooldownMonths) {
        if (specializationType == null) return "You must wait before changing specializations.";
        String label = specializationType.equals("major") ? "majors" : "fields";
        if (cooldownMonths == null || cooldownMonths <= 0) {
            return "You must wait before changing " + label + ".";
        }
        String unit = cooldownMonths == 1 ? "month" : "months";
        return "You must wait " + cooldownMonths + " " + unit + " before changing " + label + ".";
    }

    private String verificationRequiredMessage(String specializationType, String requiredKind) {
        if (specializationType == null || requiredKind == null) {
            return "You must be verified before joining this specialization.";
        }
        String label = specializationType.equals("major") ? "majors" : "fields";
        String requiredLabel = requiredKind.equals("school") ? "school" : requiredKind;
        return "Verify at least one " + requiredLabel + " before joining " + label + ".";
    }

    private ResponseEntity<?> validateMajorOrField(long specializationId) {
        var communityOpt = communities.findById(specializationId);
        if (communityOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "specialization_not_found"));
        }
        var community = communityOpt.get();
        if (!"specialization".equalsIgnoreCase(community.kind)) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", "invalid_specialization",
                    "message", "Specialization must be a major or field"
            ));
        }
        String t = community.specializationType == null ? "" : community.specializationType.trim().toLowerCase(java.util.Locale.ROOT);
        if (!t.equals("major") && !t.equals("field")) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", "invalid_specialization",
                    "message", "Specialization must be a major or field"
            ));
        }
        return null;
    }

    private String normalizeListType(String raw) {
        if (raw == null || raw.isBlank()) return "all";
        String n = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if ("all".equals(n)) return "all";
        if ("major".equals(n) || "field".equals(n)) return n;
        return null;
    }
}

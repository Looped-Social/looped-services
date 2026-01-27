package com.looped.communities;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/v1/communities")
public class CommunityJoinsController {
    private final CommunitiesRepository communities;
    private final CommunityFollowsService follows;
    private final SpecializationMembershipService specializationMemberships;
    private final CommunityVerificationsRepository verifications;

    public CommunityJoinsController(CommunitiesRepository communities,
                                    CommunityFollowsService follows,
                                    SpecializationMembershipService specializationMemberships,
                                    CommunityVerificationsRepository verifications) {
        this.communities = communities;
        this.follows = follows;
        this.specializationMemberships = specializationMemberships;
        this.verifications = verifications;
    }

    @PostMapping("/{id}/join")
    public ResponseEntity<?> join(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") long id) {
        var community = communities.findById(id);
        if (community.isEmpty()) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));

        if ("specialization".equalsIgnoreCase(community.get().kind)) {
            var res = specializationMemberships.join(jwt.getSubject(), id);
            return switch (res.status()) {
                case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "user_not_provisioned"));
                case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
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
                        "community_id", id,
                        "joined", true,
                        "member_count", verifications.countActiveVerifiedMembers(id)
                ), res.changed() ? HttpStatus.CREATED : HttpStatus.OK);
            };
        }

        var res = follows.follow(jwt.getSubject(), id);
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "user_not_provisioned"));
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
            case OK -> new ResponseEntity<>(Map.of(
                    "community_id", id,
                    "joined", true,
                    "member_count", verifications.countActiveVerifiedMembers(id)
            ), res.changed() ? HttpStatus.CREATED : HttpStatus.OK);
        };
    }

    @DeleteMapping("/{id}/join")
    public ResponseEntity<?> unjoin(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") long id) {
        var community = communities.findById(id);
        if (community.isEmpty()) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));

        if ("specialization".equalsIgnoreCase(community.get().kind)) {
            var res = specializationMemberships.unjoin(jwt.getSubject(), id);
            return switch (res.status()) {
                case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "user_not_provisioned"));
                case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
                case INVALID_SPECIALIZATION -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "invalid_specialization"));
                case OK -> ResponseEntity.ok(Map.of(
                        "community_id", id,
                        "joined", false,
                        "member_count", verifications.countActiveVerifiedMembers(id)
                ));
                default -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
            };
        }

        var res = follows.unfollow(jwt.getSubject(), id);
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "user_not_provisioned"));
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
            case OK -> ResponseEntity.ok(Map.of(
                    "community_id", id,
                    "joined", false,
                    "member_count", verifications.countActiveVerifiedMembers(id)
            ));
            default -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        };
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
}

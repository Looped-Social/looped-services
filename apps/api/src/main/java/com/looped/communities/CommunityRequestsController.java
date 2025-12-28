package com.looped.communities;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/community-requests")
@Validated
public class CommunityRequestsController {
    private final CommunityRequestsService service;
    private final String cloudfrontDomain;

    public CommunityRequestsController(CommunityRequestsService service,
                                       @Value("${cloudfront.domain:}") String cloudfrontDomain) {
        this.service = service;
        this.cloudfrontDomain = cloudfrontDomain;
    }

    @PostMapping
    public ResponseEntity<?> create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateRequest body) {
        var res = service.create(jwt.getSubject(), body.kind(), body.name(), body.description(), body.imageKey());
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned"
            ));
            case INVALID_KIND -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", "invalid_kind"
            ));
            case INVALID_NAME -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", "name_required"
            ));
            case INVALID_IMAGE -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", "invalid_image"
            ));
            case IMAGE_NOT_OWNED -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "image_not_owned"
            ));
            case COMMUNITY_EXISTS -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "community_exists"
            ));
            case DUPLICATE -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "request_already_pending"
            ));
            case OK -> new ResponseEntity<>(Map.of(
                    "id", res.id(),
                    "status", "pending"
            ), HttpStatus.CREATED);
        };
    }

    @GetMapping
    public ResponseEntity<?> list(@AuthenticationPrincipal Jwt jwt,
                                  @RequestParam(value = "status", required = false) String status) {
        var res = service.list(jwt.getSubject(), status);
        if (res.status() == CommunityRequestsService.Status.USER_NOT_PROVISIONED) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned"
            ));
        }
        List<Map<String, Object>> items = res.items().stream().map(row -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", row.id);
            map.put("kind", row.kind);
            map.put("name", row.name);
            if (row.description != null) map.put("description", row.description);
            if (row.imageKey != null) {
                map.put("image_key", row.imageKey);
                String cdnUrl = cdnUrl(row.imageKey);
                if (cdnUrl != null) map.put("image_url", cdnUrl);
            }
            map.put("status", row.status);
            map.put("created_at", row.createdAt);
            if (row.reviewedAt != null) map.put("reviewed_at", row.reviewedAt);
            if (row.rejectReason != null) map.put("reject_reason", row.rejectReason);
            if (row.communityId != null) map.put("community_id", row.communityId);
            return map;
        }).toList();
        return ResponseEntity.ok(Map.of("items", items));
    }

    private String cdnUrl(String key) {
        if (cloudfrontDomain == null || cloudfrontDomain.isBlank()) return null;
        return "https://" + cloudfrontDomain + "/" + key;
    }

    public record CreateRequest(
            @NotBlank @JsonAlias("type") String kind,
            @NotBlank String name,
            @JsonAlias("about") String description,
            @JsonAlias("image_key") String imageKey
    ) {}
}

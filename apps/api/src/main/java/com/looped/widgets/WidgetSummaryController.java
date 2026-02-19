package com.looped.widgets;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class WidgetSummaryController {
    private final WidgetSummaryService service;

    public WidgetSummaryController(WidgetSummaryService service) {
        this.service = service;
    }

    @GetMapping("/v1/widget-summary")
    public ResponseEntity<?> summary(@AuthenticationPrincipal Jwt jwt) {
        var result = service.summary(jwt.getSubject());
        if (result.status() == WidgetSummaryService.SummaryStatus.USER_NOT_PROVISIONED) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "user_not_provisioned"));
        }
        return ResponseEntity.ok(result.response());
    }

    @PostMapping("/v1/widget-state/community/{id}/seen")
    public ResponseEntity<?> markCommunitySeen(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") long communityId
    ) {
        var result = service.markCommunitySeen(jwt.getSubject(), communityId);
        if (result.status() == WidgetSummaryService.MarkCommunitySeenStatus.USER_NOT_PROVISIONED) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "user_not_provisioned"));
        }
        if (result.status() == WidgetSummaryService.MarkCommunitySeenStatus.COMMUNITY_NOT_FOUND) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "community_not_found"));
        }
        if (result.status() == WidgetSummaryService.MarkCommunitySeenStatus.COMMUNITY_NOT_VERIFIED) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "community_not_verified"));
        }
        return ResponseEntity.ok(result.response());
    }
}

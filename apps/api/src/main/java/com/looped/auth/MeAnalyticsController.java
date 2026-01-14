package com.looped.auth;

import com.looped.users.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

@RestController
public class MeAnalyticsController {
    private final UserRepository users;
    private final MeAnalyticsRepository analytics;

    public MeAnalyticsController(UserRepository users, MeAnalyticsRepository analytics) {
        this.users = users;
        this.analytics = analytics;
    }

    @GetMapping("/v1/me/analytics")
    public ResponseEntity<?> meAnalytics(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(value = "window_days", required = false, defaultValue = "7") int windowDays
    ) {
        int days = Math.max(1, Math.min(windowDays, 365));
        var actor = users.findByFirebaseUid(jwt.getSubject());
        if (actor.isEmpty() || actor.get().companyId == null) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "user_not_provisioned"));
        }
        OffsetDateTime since = OffsetDateTime.now().minusDays(days);

        Map<String, Object> out = new HashMap<>();
        out.put("window_days", days);
        out.put("total_hearts", analytics.totalHeartsReceived(actor.get().id));
        out.put("hearts_last_window", analytics.heartsReceivedSince(actor.get().id, since));
        out.put("posts_last_window", analytics.postsCreatedSince(actor.get().id, since));
        return ResponseEntity.ok(out);
    }
}


package com.looped.communities;

import com.looped.users.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/v1/communities")
public class CommunitiesController {
    private final UserRepository users;
    private final CommunitiesRepository communities;
    private final CommunityFollowsRepository follows;

    public CommunitiesController(UserRepository users,
                                 CommunitiesRepository communities,
                                 CommunityFollowsRepository follows) {
        this.users = users;
        this.communities = communities;
        this.follows = follows;
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getCommunity(@AuthenticationPrincipal Jwt jwt,
                                          @PathVariable("id") long id) {
        var actor = users.findByFirebaseUid(jwt.getSubject());
        if (actor.isEmpty() || actor.get().companyId == null) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned"
            ));
        }
        var communityOpt = communities.findById(id);
        if (communityOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "community_not_found"
            ));
        }
        var community = communityOpt.get();
        Map<String, Object> out = new HashMap<>();
        out.put("id", community.id);
        out.put("kind", community.kind);
        out.put("name", community.name);
        if (community.shortName != null) out.put("short_name", community.shortName);
        if (community.description != null) out.put("description", community.description);
        if (community.imageUrl != null) out.put("image_url", community.imageUrl);
        out.put("member_count", community.memberCount);
        if (community.specializationType != null) out.put("specialization_type", community.specializationType);
        out.put("is_following", follows.exists(actor.get().id, id));
        return ResponseEntity.ok(out);
    }
}

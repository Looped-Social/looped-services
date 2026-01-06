package com.looped.messaging;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/channels")
public class ChannelsController {
    private final ChannelService service;

    public ChannelsController(ChannelService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<?> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false, defaultValue = "20") int limit
    ) {
        int lim = Math.max(1, Math.min(limit, 100));
        var res = service.list(jwt.getSubject(), cursor, lim);
        if (res.status() == ChannelService.Status.USER_NOT_PROVISIONED) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "user_not_provisioned"));
        }
        if (res.status() == ChannelService.Status.ANONYMOUS_NOT_ALLOWED) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "anonymous_not_allowed"));
        }
        if (res.status() != ChannelService.Status.OK) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
        Map<String, Object> body = new HashMap<>();
        body.put("items", res.items());
        if (res.nextCursor() != null) body.put("next_cursor", res.nextCursor());
        return ResponseEntity.ok(body);
    }

    @PostMapping
    public ResponseEntity<?> create(
            @AuthenticationPrincipal Jwt jwt,
            @Validated @RequestBody CreateRequest body
    ) {
        var res = service.create(jwt.getSubject(), body.name(), body.memberUserIds());
        if (res.status() == ChannelService.Status.USER_NOT_PROVISIONED) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "user_not_provisioned"));
        }
        if (res.status() == ChannelService.Status.ANONYMOUS_NOT_ALLOWED) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "anonymous_not_allowed"));
        }
        if (res.status() == ChannelService.Status.NOT_FOUND) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
        }
        if (res.status() == ChannelService.Status.FORBIDDEN) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(res.channel());
    }

    @GetMapping("/{id}/members")
    public ResponseEntity<?> members(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") long id,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false, defaultValue = "50") int limit
    ) {
        int lim = Math.max(1, Math.min(limit, 200));
        var res = service.members(jwt.getSubject(), id, cursor, lim);
        if (res.status() == ChannelService.Status.USER_NOT_PROVISIONED) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "user_not_provisioned"));
        }
        if (res.status() == ChannelService.Status.ANONYMOUS_NOT_ALLOWED) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "anonymous_not_allowed"));
        }
        if (res.status() == ChannelService.Status.NOT_FOUND) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
        }
        if (res.status() == ChannelService.Status.FORBIDDEN) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        List<Map<String, Object>> items = res.members().stream().map(member -> {
            Map<String, Object> map = new HashMap<>();
            map.put("user_id", member.userId);
            map.put("handle", member.handle);
            map.put("display_name", member.displayName);
            map.put("profile_image_url", member.profileImageUrl);
            map.put("company_id", member.companyId);
            map.put("can_manage_members", member.canManageMembers);
            map.put("created_at", member.createdAt);
            map.put("is_owner", res.ownerUserId() != null && res.ownerUserId() == member.userId);
            return map;
        }).toList();
        Map<String, Object> body = new HashMap<>();
        body.put("items", items);
        if (res.nextCursor() != null) body.put("next_cursor", res.nextCursor());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/{id}/members")
    public ResponseEntity<?> addMembers(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") long id,
            @Validated @RequestBody AddMembersRequest body
    ) {
        var res = service.addMembers(jwt.getSubject(), id, body.userIds());
        if (res.status() == ChannelService.Status.USER_NOT_PROVISIONED) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "user_not_provisioned"));
        }
        if (res.status() == ChannelService.Status.ANONYMOUS_NOT_ALLOWED) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "anonymous_not_allowed"));
        }
        if (res.status() == ChannelService.Status.NOT_FOUND) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
        }
        if (res.status() == ChannelService.Status.FORBIDDEN) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        return ResponseEntity.ok(Map.of("status", "ok", "added_count", res.changedCount()));
    }

    @DeleteMapping("/{id}/members/{userId}")
    public ResponseEntity<?> removeMember(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") long id,
            @PathVariable("userId") long userId
    ) {
        var res = service.removeMember(jwt.getSubject(), id, userId);
        if (res.status() == ChannelService.Status.USER_NOT_PROVISIONED) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "user_not_provisioned"));
        }
        if (res.status() == ChannelService.Status.ANONYMOUS_NOT_ALLOWED) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "anonymous_not_allowed"));
        }
        if (res.status() == ChannelService.Status.NOT_FOUND) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
        }
        if (res.status() == ChannelService.Status.FORBIDDEN) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @PutMapping("/{id}/members/{userId}")
    public ResponseEntity<?> updateMember(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") long id,
            @PathVariable("userId") long userId,
            @Validated @RequestBody UpdateMemberRequest body
    ) {
        var res = service.updateMemberPermission(jwt.getSubject(), id, userId, body.canManageMembers());
        if (res.status() == ChannelService.Status.USER_NOT_PROVISIONED) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "user_not_provisioned"));
        }
        if (res.status() == ChannelService.Status.ANONYMOUS_NOT_ALLOWED) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "anonymous_not_allowed"));
        }
        if (res.status() == ChannelService.Status.NOT_FOUND) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
        }
        if (res.status() == ChannelService.Status.FORBIDDEN) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @GetMapping("/{id}/messages")
    public ResponseEntity<?> messages(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") long id,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false, defaultValue = "50") int limit
    ) {
        int lim = Math.max(1, Math.min(limit, 200));
        var res = service.messages(jwt.getSubject(), id, cursor, lim);
        if (res.status() == ChannelService.Status.USER_NOT_PROVISIONED) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "user_not_provisioned"));
        }
        if (res.status() == ChannelService.Status.ANONYMOUS_NOT_ALLOWED) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "anonymous_not_allowed"));
        }
        if (res.status() == ChannelService.Status.FORBIDDEN) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        if (res.status() == ChannelService.Status.NOT_FOUND) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
        }
        List<Map<String, Object>> items = res.messages().stream().map(this::toMessage).toList();
        Map<String, Object> body = new HashMap<>();
        body.put("items", items);
        if (res.nextCursor() != null) body.put("next_cursor", res.nextCursor());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/{id}/messages")
    public ResponseEntity<?> send(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") long id,
        @Validated @RequestBody SendRequest body
    ) {
        var res = service.send(jwt.getSubject(), id, body.content(), body.attachments());
        if (res.status() == ChannelService.Status.USER_NOT_PROVISIONED) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "user_not_provisioned"));
        }
        if (res.status() == ChannelService.Status.ANONYMOUS_NOT_ALLOWED) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "anonymous_not_allowed"));
        }
        if (res.status() == ChannelService.Status.FORBIDDEN) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        if (res.status() == ChannelService.Status.NOT_FOUND) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(toMessage(res.message()));
    }

    private Map<String, Object> toMessage(ChannelRepository.ChannelMessageRow row) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", row.id);
        map.put("sender_id", row.senderId);
        map.put("content", row.content);
        map.put("attachments", row.attachments);
        map.put("created_at", row.createdAt);
        return map;
    }

    public record CreateRequest(@NotBlank String name, List<Long> memberUserIds) {}
    public record AddMembersRequest(@NotEmpty List<Long> userIds) {}
    public record UpdateMemberRequest(boolean canManageMembers) {}

    public record SendRequest(@NotBlank String content, List<String> attachments) {}
}

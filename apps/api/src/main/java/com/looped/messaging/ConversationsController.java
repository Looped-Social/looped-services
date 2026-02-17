package com.looped.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
@RequestMapping("/v1/conversations")
public class ConversationsController {
    private final ConversationService service;

    public ConversationsController(ConversationService service) {
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
        if (res.status() == ConversationService.Status.USER_NOT_PROVISIONED) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "user_not_provisioned"));
        }
        if (res.status() == ConversationService.Status.ANONYMOUS_NOT_ALLOWED) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "anonymous_not_allowed"));
        }
        if (res.status() != ConversationService.Status.OK) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
        Map<String, Object> body = new HashMap<>();
        body.put("items", res.items());
        if (res.nextCursor() != null) body.put("next_cursor", res.nextCursor());
        return ResponseEntity.ok(body);
    }

    @PostMapping
    public ResponseEntity<?> start(
            @AuthenticationPrincipal Jwt jwt,
        @Validated @RequestBody StartRequest body
    ) {
        var res = service.start(jwt.getSubject(), body.participantUserId());
        if (res.status() == ConversationService.Status.USER_NOT_PROVISIONED) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "user_not_provisioned"));
        }
        if (res.status() == ConversationService.Status.ANONYMOUS_NOT_ALLOWED) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "anonymous_not_allowed"));
        }
        if (res.status() == ConversationService.Status.INVALID_PARTICIPANT) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "invalid_participant"));
        }
        if (res.status() == ConversationService.Status.NOT_FOUND) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
        }
        if (res.status() == ConversationService.Status.FORBIDDEN) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        if (res.status() == ConversationService.Status.BLOCKED_RELATIONSHIP) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "blocked_relationship"));
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(res.conversation());
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
        if (res.status() == ConversationService.Status.USER_NOT_PROVISIONED) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "user_not_provisioned"));
        }
        if (res.status() == ConversationService.Status.ANONYMOUS_NOT_ALLOWED) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "anonymous_not_allowed"));
        }
        if (res.status() == ConversationService.Status.FORBIDDEN) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        if (res.status() == ConversationService.Status.BLOCKED_RELATIONSHIP) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "blocked_relationship"));
        }
        if (res.status() == ConversationService.Status.MESSAGE_REQUEST_PENDING) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "message_request_pending"));
        }
        if (res.status() == ConversationService.Status.MESSAGE_REQUEST_REJECTED) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "message_request_rejected"));
        }
        if (res.status() == ConversationService.Status.NOT_FOUND) {
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
        List<MessageAttachment> attachments;
        try {
            attachments = MessageAttachments.parse(body.attachments());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "invalid_attachments"));
        }
        var res = service.send(jwt.getSubject(), id, body.content(), attachments);
        if (res.status() == ConversationService.Status.USER_NOT_PROVISIONED) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "user_not_provisioned"));
        }
        if (res.status() == ConversationService.Status.ANONYMOUS_NOT_ALLOWED) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "anonymous_not_allowed"));
        }
        if (res.status() == ConversationService.Status.FORBIDDEN) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        if (res.status() == ConversationService.Status.BLOCKED_RELATIONSHIP) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "blocked_relationship"));
        }
        if (res.status() == ConversationService.Status.INVALID_ATTACHMENTS) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "invalid_attachments"));
        }
        if (res.status() == ConversationService.Status.MESSAGE_REQUEST_PENDING) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "message_request_pending"));
        }
        if (res.status() == ConversationService.Status.MESSAGE_REQUEST_REJECTED) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "message_request_rejected"));
        }
        if (res.status() == ConversationService.Status.NOT_FOUND) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(toMessage(res.message()));
    }

    @PutMapping("/{id}/preferences")
    public ResponseEntity<?> preferences(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") long id,
            @Validated @RequestBody PreferencesRequest body
    ) {
        var res = service.setPreferences(jwt.getSubject(), id, Boolean.TRUE.equals(body.muted()));
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "user_not_provisioned"));
            case ANONYMOUS_NOT_ALLOWED -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "anonymous_not_allowed"));
            case FORBIDDEN -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
            case OK -> ResponseEntity.ok(Map.of("conversation_id", id, "muted", res.muted()));
        };
    }

    private Map<String, Object> toMessage(ConversationRepository.MessageRow row) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", row.id);
        map.put("sender_id", row.senderId);
        map.put("content", row.content);
        map.put("attachments", row.attachments);
        map.put("created_at", row.createdAt);
        return map;
    }

    public record StartRequest(long participantUserId) {}
    public record SendRequest(@NotBlank String content, JsonNode attachments) {}
    public record PreferencesRequest(@NotNull Boolean muted) {}
}

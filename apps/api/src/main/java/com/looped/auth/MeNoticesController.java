package com.looped.auth;

import com.fasterxml.jackson.annotation.JsonAlias;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/v1/me/notices")
public class MeNoticesController {
    private final MeNoticesService notices;

    public MeNoticesController(MeNoticesService notices) {
        this.notices = notices;
    }

    @PostMapping("/{noticeKey}/ack")
    public ResponseEntity<?> acknowledge(@AuthenticationPrincipal Jwt jwt,
                                         @PathVariable("noticeKey") String noticeKey,
                                         @RequestBody(required = false) NoticeAckRequest body) {
        if (jwt == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "error", "unauthorized",
                    "message", "Authorization is required"
            ));
        }
        String action = body == null ? null : body.action();
        var result = notices.acknowledge(jwt.getSubject(), noticeKey, action);
        return switch (result.status()) {
            case OK -> ResponseEntity.noContent().build();
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned"
            ));
            case NOTICE_NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "notice_not_found"
            ));
            case INVALID_ACTION -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", "invalid_notice_action",
                    "message", "action must be dismiss or cta"
            ));
        };
    }

    public record NoticeAckRequest(
            @JsonAlias("ack_action") String action
    ) {}
}

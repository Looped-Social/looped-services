package com.looped.polls;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/v1/polls")
public class PollsController {
    private final PollsService polls;

    public PollsController(PollsService polls) {
        this.polls = polls;
    }

    @PutMapping("/{pollId}/vote")
    public ResponseEntity<?> vote(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("pollId") long pollId,
            @Validated @RequestBody PollRequests.VoteRequest body
    ) {
        var res = polls.vote(jwt == null ? null : jwt.getSubject(), pollId, body.selectedOptionIds());
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned",
                    "message", "Complete onboarding before voting"
            ));
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "not_found",
                    "message", "Poll not found"
            ));
            case FORBIDDEN -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "forbidden",
                    "message", "You do not have access to this poll"
            ));
            case POLL_CLOSED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "poll_closed",
                    "message", "Poll is closed"
            ));
            case INVALID_SELECTION -> ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "error", "invalid_selection",
                    "message", "Invalid poll selection"
            ));
            case OK -> ResponseEntity.ok(PollPayloads.from(res.poll()));
        };
    }
}


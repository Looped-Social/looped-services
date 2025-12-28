package com.looped.feedback;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/v1/feedback")
@Validated
public class FeedbackController {
    private final FeedbackService service;

    public FeedbackController(FeedbackService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<?> create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateRequest body) {
        String email = jwt != null ? jwt.getClaimAsString("email") : null;
        String subject = jwt != null ? jwt.getSubject() : null;
        var res = service.create(subject, email, body.title(), body.message(), body.email());
        return new ResponseEntity<>(Map.of(
                "id", res.id(),
                "status", "received"
        ), HttpStatus.CREATED);
    }

    public record CreateRequest(
            @NotBlank @Size(max = 120) @JsonAlias("subject") String title,
            @NotBlank @Size(max = 4000) @JsonAlias({"description", "body"}) String message,
            @Email String email
    ) {}
}

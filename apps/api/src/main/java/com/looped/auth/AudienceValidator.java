package com.looped.auth;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

/**
 * Validates that a JWT contains the expected audience.
 */
public class AudienceValidator implements OAuth2TokenValidator<Jwt> {
    private final String expectedAudience;

    public AudienceValidator(String expectedAudience) {
        this.expectedAudience = expectedAudience;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        List<String> aud = token.getAudience();
        if (aud != null && aud.contains(expectedAudience)) {
            return OAuth2TokenValidatorResult.success();
        }
        OAuth2Error err = new OAuth2Error("invalid_token", "The required audience is missing or incorrect", null);
        return OAuth2TokenValidatorResult.failure(err);
    }
}


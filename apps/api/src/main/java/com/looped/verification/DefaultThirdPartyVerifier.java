package com.looped.verification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
public class DefaultThirdPartyVerifier implements ThirdPartyVerifier {
    private static final Logger log = LoggerFactory.getLogger(DefaultThirdPartyVerifier.class);

    @Override
    public boolean validate(String sessionId, String token) {
        // Placeholder: accept any token in MVP. Replace with real provider call later.
        log.debug("Third-party verification stub validate sessionId={} token_present={} ", sessionId, token != null && !token.isBlank());
        return token != null && !token.isBlank();
    }
}


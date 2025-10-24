package com.looped.verification;

public interface ThirdPartyVerifier {
    boolean validate(String sessionId, String token);
}


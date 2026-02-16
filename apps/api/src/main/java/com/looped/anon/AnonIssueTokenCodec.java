package com.looped.anon;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

public final class AnonIssueTokenCodec {
    private AnonIssueTokenCodec() {}

    public static String generateToken(SecureRandom random) {
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    public static byte[] hash(String token) {
        if (token == null || token.isBlank()) return null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash issue token", e);
        }
    }
}

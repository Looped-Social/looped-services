package com.looped.anon.crypto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

public final class AnonCrypto {
    private AnonCrypto() {}

    public static byte[] certMessage(byte[] personaPubkey) {
        String pub = Base64.getEncoder().encodeToString(personaPubkey);
        String canonical = "anon-cert|v1|" + pub;
        return sha256(canonical);
    }

    public static byte[] postMessage(long communityId, String content, long timestampSeconds) {
        String contentHash = sha256Hex(content);
        String canonical = "v2|" + communityId + "|" + contentHash + "|" + timestampSeconds;
        return sha256(canonical);
    }

    public static byte[] actionMessage(String action, long targetId) {
        String canonical = action + "|v1|" + targetId;
        return sha256(canonical);
    }

    public static byte[] sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static String sha256Hex(String input) {
        byte[] hash = sha256(input);
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}

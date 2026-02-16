package com.looped.anon;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

public final class AnonCertFingerprint {
    private AnonCertFingerprint() {}

    public static byte[] sha256(String anonCertKid, byte[] anonCertBytes) {
        if (anonCertKid == null || anonCertKid.isBlank() || anonCertBytes == null || anonCertBytes.length == 0) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(anonCertKid.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(anonCertBytes);
            return digest.digest();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to fingerprint anonymous cert", e);
        }
    }
}

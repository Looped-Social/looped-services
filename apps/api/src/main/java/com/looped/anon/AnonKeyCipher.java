package com.looped.anon;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

public class AnonKeyCipher {
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public AnonKeyCipher(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            this.key = null;
            return;
        }
        byte[] raw = Base64.getDecoder().decode(base64Key);
        if (raw.length != 32) {
            throw new IllegalArgumentException("anon.issuer.kek must be 32 bytes (base64)");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    public boolean enabled() {
        return key != null;
    }

    public byte[] encrypt(byte[] plaintext) {
        if (key == null) return plaintext;
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext);
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt issuer key", e);
        }
    }

    public byte[] decrypt(byte[] ciphertext) {
        if (key == null) return ciphertext;
        if (ciphertext == null || ciphertext.length <= IV_BYTES) {
            throw new IllegalArgumentException("Invalid encrypted payload");
        }
        byte[] iv = Arrays.copyOfRange(ciphertext, 0, IV_BYTES);
        byte[] ct = Arrays.copyOfRange(ciphertext, IV_BYTES, ciphertext.length);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return cipher.doFinal(ct);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decrypt issuer key", e);
        }
    }
}

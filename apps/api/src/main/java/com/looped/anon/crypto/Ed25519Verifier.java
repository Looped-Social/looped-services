package com.looped.anon.crypto;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;

public final class Ed25519Verifier {
    private static final byte[] ED25519_PREFIX = new byte[] {
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00
    };

    private Ed25519Verifier() {}

    public static boolean verify(byte[] publicKeyRaw, byte[] message, byte[] signature) {
        try {
            byte[] encoded = new byte[ED25519_PREFIX.length + publicKeyRaw.length];
            System.arraycopy(ED25519_PREFIX, 0, encoded, 0, ED25519_PREFIX.length);
            System.arraycopy(publicKeyRaw, 0, encoded, ED25519_PREFIX.length, publicKeyRaw.length);
            PublicKey publicKey = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded));
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(message);
            return verifier.verify(signature);
        } catch (Exception e) {
            return false;
        }
    }

    public static byte[] parseRawPublicKey(byte[] encodedKey) {
        if (encodedKey.length == 32) return encodedKey;
        if (encodedKey.length == ED25519_PREFIX.length + 32 && Arrays.equals(Arrays.copyOf(encodedKey, ED25519_PREFIX.length), ED25519_PREFIX)) {
            return Arrays.copyOfRange(encodedKey, ED25519_PREFIX.length, encodedKey.length);
        }
        throw new IllegalArgumentException("Unsupported Ed25519 public key format");
    }
}

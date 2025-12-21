package com.looped.anon.crypto;

import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public final class PemKeyUtils {
    private PemKeyUtils() {}

    public static RSAPrivateKey parsePrivateKey(String pem) {
        byte[] der = parsePem(pem, "PRIVATE KEY");
        try {
            KeyFactory factory = KeyFactory.getInstance("RSA");
            return (RSAPrivateKey) factory.generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalArgumentException("Invalid RSA private key", e);
        }
    }

    public static RSAPublicKey parsePublicKey(String pem) {
        byte[] der = parsePem(pem, "PUBLIC KEY");
        try {
            KeyFactory factory = KeyFactory.getInstance("RSA");
            return (RSAPublicKey) factory.generatePublic(new X509EncodedKeySpec(der));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalArgumentException("Invalid RSA public key", e);
        }
    }

    public static String encodePublicKeyPem(RSAPublicKey publicKey) {
        String base64 = Base64.getEncoder().encodeToString(publicKey.getEncoded());
        StringBuilder sb = new StringBuilder();
        sb.append("-----BEGIN PUBLIC KEY-----\n");
        for (String line : chunk(base64, 64)) {
            sb.append(line).append("\n");
        }
        sb.append("-----END PUBLIC KEY-----");
        return sb.toString();
    }

    private static byte[] parsePem(String pem, String type) {
        if (pem == null || pem.isBlank()) {
            throw new IllegalArgumentException("Missing PEM for " + type);
        }
        String normalized = pem
                .replace("-----BEGIN " + type + "-----", "")
                .replace("-----END " + type + "-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(normalized);
    }

    private static List<String> chunk(String input, int size) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < input.length(); i += size) {
            out.add(input.substring(i, Math.min(input.length(), i + size)));
        }
        return out;
    }
}

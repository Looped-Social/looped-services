package com.looped.anon.crypto;

import java.math.BigInteger;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;

public final class BlindRsaSigner {
    private final RSAPrivateKey privateKey;
    private final RSAPublicKey publicKey;

    public BlindRsaSigner(RSAPrivateKey privateKey, RSAPublicKey publicKey) {
        this.privateKey = privateKey;
        this.publicKey = publicKey;
    }

    public byte[] signBlinded(byte[] blindedMessage) {
        if (privateKey == null) {
            throw new IllegalStateException("Private key required for signing");
        }
        BigInteger m = new BigInteger(1, blindedMessage);
        BigInteger n = privateKey.getModulus();
        if (m.compareTo(n) >= 0) {
            throw new IllegalArgumentException("Blinded message too large");
        }
        BigInteger s = m.modPow(privateKey.getPrivateExponent(), n);
        return toFixedLength(s, n.bitLength());
    }

    public boolean verify(byte[] message, byte[] signature) {
        if (publicKey == null) {
            throw new IllegalStateException("Public key required for verification");
        }
        BigInteger sig = new BigInteger(1, signature);
        BigInteger n = publicKey.getModulus();
        BigInteger m = sig.modPow(publicKey.getPublicExponent(), n);
        return Arrays.equals(toFixedLength(m, n.bitLength()), toFixedLength(new BigInteger(1, message), n.bitLength()));
    }

    private static byte[] toFixedLength(BigInteger value, int bitLength) {
        int length = (bitLength + 7) / 8;
        byte[] raw = value.toByteArray();
        if (raw.length == length) return raw;
        byte[] out = new byte[length];
        if (raw.length > length) {
            System.arraycopy(raw, raw.length - length, out, 0, length);
        } else {
            System.arraycopy(raw, 0, out, length - raw.length, raw.length);
        }
        return out;
    }
}

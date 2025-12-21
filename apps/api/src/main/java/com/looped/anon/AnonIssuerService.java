package com.looped.anon;

import com.looped.anon.crypto.BlindRsaSigner;
import com.looped.anon.crypto.PemKeyUtils;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.OffsetDateTime;

@Service
public class AnonIssuerService {
    private final AnonIssuerProperties props;
    private final AnonIssuerRepository issuers;
    private final RSAPrivateKey privateKey;
    private final RSAPublicKey publicKey;
    private final BlindRsaSigner blindSigner;
    private OffsetDateTime expiresAt;

    public AnonIssuerService(AnonIssuerProperties props, AnonIssuerRepository issuers) {
        this.props = props;
        this.issuers = issuers;
        this.privateKey = PemKeyUtils.parsePrivateKey(props.getPrivateKeyPem());
        this.publicKey = resolvePublicKey(props, privateKey);
        this.blindSigner = new BlindRsaSigner(privateKey, publicKey);
    }

    @PostConstruct
    public void init() {
        expiresAt = null;
        if (props.getMaxAge() != null) {
            expiresAt = OffsetDateTime.now().plus(props.getMaxAge());
        }
        issuers.upsert(props.getKid(), "RSABSSA", publicKey.getEncoded(), "company", null, expiresAt);
    }

    public String kid() {
        return props.getKid();
    }

    public byte[] signBlinded(byte[] blindedMessage) {
        return blindSigner.signBlinded(blindedMessage);
    }

    public boolean verify(byte[] message, byte[] signature) {
        return blindSigner.verify(message, signature);
    }

    public RSAPublicKey publicKey() {
        return publicKey;
    }

    public String publicKeyPem() {
        if (props.getPublicKeyPem() != null && !props.getPublicKeyPem().isBlank()) {
            return props.getPublicKeyPem();
        }
        return PemKeyUtils.encodePublicKeyPem(publicKey);
    }

    public OffsetDateTime expiresAt() {
        return expiresAt;
    }

    private RSAPublicKey resolvePublicKey(AnonIssuerProperties props, RSAPrivateKey privateKey) {
        if (props.getPublicKeyPem() != null && !props.getPublicKeyPem().isBlank()) {
            return PemKeyUtils.parsePublicKey(props.getPublicKeyPem());
        }
        if (privateKey instanceof RSAPrivateCrtKey crtKey) {
            try {
                RSAPublicKeySpec spec = new RSAPublicKeySpec(crtKey.getModulus(), crtKey.getPublicExponent());
                return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(spec);
            } catch (Exception e) {
                throw new IllegalArgumentException("Failed to derive RSA public key", e);
            }
        }
        throw new IllegalArgumentException("Missing RSA public key PEM and private key lacks CRT params");
    }
}

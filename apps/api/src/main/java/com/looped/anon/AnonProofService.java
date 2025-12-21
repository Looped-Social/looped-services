package com.looped.anon;

import com.looped.anon.crypto.AnonCrypto;
import com.looped.anon.crypto.Ed25519Verifier;
import com.looped.principals.PrincipalRepository;
import org.springframework.stereotype.Service;

import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Optional;

@Service
public class AnonProofService {
    private final AnonymousProfilesRepository profiles;
    private final PrincipalRepository principals;
    private final AnonIssuerRepository issuers;
    private final AnonIssuerService issuerService;
    private final AnonRevocationsRepository revocations;

    public AnonProofService(AnonymousProfilesRepository profiles,
                            PrincipalRepository principals,
                            AnonIssuerRepository issuers,
                            AnonIssuerService issuerService,
                            AnonRevocationsRepository revocations) {
        this.profiles = profiles;
        this.principals = principals;
        this.issuers = issuers;
        this.issuerService = issuerService;
        this.revocations = revocations;
    }

    public VerifyResult verifyPost(AnonPostProof proof, long communityId, long companyId, String content, long timestampSeconds) {
        var profile = profiles.findById(proof.anonProfileId());
        if (profile.isEmpty()) return VerifyResult.notFound();
        if (revocations.isRevokedByPubkey(profile.get().publicKey)) return VerifyResult.revoked();
        if (!verifyCertSignature(proof.anonCert(), proof.anonCertKid(), profile.get().publicKey)) {
            return VerifyResult.invalidCert();
        }
        byte[] message = AnonCrypto.postMessage(communityId, "company", companyId, content, timestampSeconds);
        if (!verifyPersonaSignature(profile.get().publicKey, proof.anonSig(), message)) {
            return VerifyResult.invalidSignature();
        }
        var principal = principals.findByAnonProfileId(profile.get().id)
                .orElseGet(() -> principals.createForAnon(profile.get().id));
        return VerifyResult.ok(new AnonActor(principal.id, profile.get().id, profile.get().companyId, profile.get().publicKey));
    }

    public VerifyResult verifyAction(AnonActionProof proof, String action, long targetId) {
        var profile = profiles.findById(proof.anonProfileId());
        if (profile.isEmpty()) return VerifyResult.notFound();
        if (revocations.isRevokedByPubkey(profile.get().publicKey)) return VerifyResult.revoked();
        if (!verifyCertSignature(proof.anonCert(), proof.anonCertKid(), profile.get().publicKey)) {
            return VerifyResult.invalidCert();
        }
        byte[] message = AnonCrypto.actionMessage(action, targetId);
        if (!verifyPersonaSignature(profile.get().publicKey, proof.anonSig(), message)) {
            return VerifyResult.invalidSignature();
        }
        var principal = principals.findByAnonProfileId(profile.get().id)
                .orElseGet(() -> principals.createForAnon(profile.get().id));
        return VerifyResult.ok(new AnonActor(principal.id, profile.get().id, profile.get().companyId, profile.get().publicKey));
    }

    private boolean verifyCertSignature(String anonCertB64, String anonCertKid, byte[] personaPubkey) {
        if (anonCertB64 == null || anonCertB64.isBlank()) return false;
        if (anonCertKid == null || anonCertKid.isBlank()) return false;
        byte[] signature;
        try {
            signature = Base64.getDecoder().decode(anonCertB64);
        } catch (IllegalArgumentException e) {
            return false;
        }
        byte[] message = AnonCrypto.certMessage(personaPubkey);
        if (issuerService.kid().equals(anonCertKid)) {
            if (issuerService.expiresAt() != null && issuerService.expiresAt().isBefore(java.time.OffsetDateTime.now())) {
                return false;
            }
            return issuerService.verify(message, signature);
        }
        Optional<AnonIssuerRepository.IssuerRow> issuer = issuers.findByKid(anonCertKid);
        if (issuer.isEmpty()) return false;
        if (issuer.get().expiresAt != null && issuer.get().expiresAt.isBefore(java.time.OffsetDateTime.now())) {
            return false;
        }
        RSAPublicKey publicKey = parseRsaPublicKey(issuer.get().publicKey);
        return new com.looped.anon.crypto.BlindRsaSigner(null, publicKey).verify(message, signature);
    }

    private RSAPublicKey parseRsaPublicKey(byte[] encoded) {
        try {
            return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(encoded));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid RSA public key", e);
        }
    }

    private boolean verifyPersonaSignature(byte[] personaPubkey, String anonSigB64, byte[] message) {
        if (anonSigB64 == null || anonSigB64.isBlank()) return false;
        byte[] sig;
        try {
            sig = Base64.getDecoder().decode(anonSigB64);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return Ed25519Verifier.verify(personaPubkey, message, sig);
    }

    public record AnonActor(long principalId, long anonProfileId, long companyId, byte[] personaPubkey) {}

    public enum Status { OK, NOT_FOUND, INVALID_CERT, INVALID_SIGNATURE, REVOKED }

    public record VerifyResult(Status status, AnonActor actor) {
        static VerifyResult ok(AnonActor actor) { return new VerifyResult(Status.OK, actor); }
        static VerifyResult notFound() { return new VerifyResult(Status.NOT_FOUND, null); }
        static VerifyResult invalidCert() { return new VerifyResult(Status.INVALID_CERT, null); }
        static VerifyResult invalidSignature() { return new VerifyResult(Status.INVALID_SIGNATURE, null); }
        static VerifyResult revoked() { return new VerifyResult(Status.REVOKED, null); }
    }

    public record AnonPostProof(Long anonProfileId, String anonCert, String anonCertKid, String anonSig) {}
    public record AnonActionProof(Long anonProfileId, String anonCert, String anonCertKid, String anonSig) {}
}

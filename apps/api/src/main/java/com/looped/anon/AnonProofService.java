package com.looped.anon;

import com.looped.anon.crypto.AnonCrypto;
import com.looped.anon.crypto.Ed25519Verifier;
import com.looped.communities.CommunitiesRepository;
import com.looped.communities.CommunityVerificationsRepository;
import com.looped.communities.SpecializationJoinsRepository;
import com.looped.principals.PrincipalRepository;
import org.springframework.stereotype.Service;

import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Optional;

import static com.looped.communities.CommunityVisibilityRules.isUserVisible;

@Service
public class AnonProofService {
    private final AnonymousProfilesRepository profiles;
    private final PrincipalRepository principals;
    private final AnonIssuerRepository issuers;
    private final AnonRevocationsRepository revocations;
    private final AnonCertEntitlementsRepository certEntitlements;
    private final CommunitiesRepository communities;
    private final CommunityVerificationsRepository communityVerifications;
    private final SpecializationJoinsRepository specializationJoins;

    public AnonProofService(AnonymousProfilesRepository profiles,
                            PrincipalRepository principals,
                            AnonIssuerRepository issuers,
                            AnonRevocationsRepository revocations,
                            AnonCertEntitlementsRepository certEntitlements,
                            CommunitiesRepository communities,
                            CommunityVerificationsRepository communityVerifications,
                            SpecializationJoinsRepository specializationJoins) {
        this.profiles = profiles;
        this.principals = principals;
        this.issuers = issuers;
        this.revocations = revocations;
        this.certEntitlements = certEntitlements;
        this.communities = communities;
        this.communityVerifications = communityVerifications;
        this.specializationJoins = specializationJoins;
    }

    public VerifyResult verifyPost(AnonPostProof proof, long communityId, String content, long timestampSeconds) {
        var profile = profiles.findById(proof.anonProfileId());
        if (profile.isEmpty()) return VerifyResult.notFound();
        if (revocations.isRevokedByPubkey(profile.get().publicKey)) return VerifyResult.revoked();
        var cert = verifyCertSignature(proof.anonCert(), proof.anonCertKid(), profile.get().publicKey);
        if (cert.status() == Status.INVALID_CERT) return VerifyResult.invalidCert();
        if (cert.status() == Status.INVALID_SIGNATURE) return VerifyResult.invalidSignature();
        if (cert.issuer() == null || !"community".equals(cert.issuer().scopeKind) || cert.issuer().scopeId == null
                || cert.issuer().scopeId != communityId) {
            return VerifyResult.invalidCert();
        }
        var entitlement = activeCommunityEntitlement(cert.fingerprint(), communityId).orElse(null);
        if (entitlement == null) {
            return VerifyResult.invalidCert();
        }
        byte[] message = AnonCrypto.postMessage(communityId, content, timestampSeconds);
        if (!verifyPersonaSignature(profile.get().publicKey, proof.anonSig(), message)) {
            return VerifyResult.invalidSignature();
        }
        var principal = principals.findByAnonProfileId(profile.get().id)
                .orElseGet(() -> principals.createForAnon(profile.get().id));
        return VerifyResult.ok(new AnonActor(principal.id, profile.get().id, entitlement.userId(), cert.issuer().companyId, profile.get().publicKey));
    }

    public CertVerifyResult verifyCert(String anonCert, String anonCertKid, byte[] personaPubkey) {
        var cert = verifyCertSignature(anonCert, anonCertKid, personaPubkey);
        return switch (cert.status()) {
            case INVALID_CERT -> CertVerifyResult.invalidCert();
            case INVALID_SIGNATURE -> CertVerifyResult.invalidSignature();
            case OK -> CertVerifyResult.ok(cert.issuer());
            default -> CertVerifyResult.invalidCert();
        };
    }

    public VerifyResult verifyAction(AnonActionProof proof, String action, long targetId) {
        return verifyActionScoped(proof, action, targetId, null);
    }

    /**
     * Verifies an anonymous action proof against multiple possible canonical target IDs.
     * <p>
     * This is useful when an endpoint is addressed by one ID type (e.g., user_id) while the
     * underlying storage uses another (e.g., principal_id), and clients may have signed either.
     */
    public VerifyResult verifyActionAnyTarget(AnonActionProof proof, String action, long targetId, long... alternateTargetIds) {
        VerifyResult first = verifyAction(proof, action, targetId);
        if (first.status() != Status.INVALID_SIGNATURE) return first;
        if (alternateTargetIds == null || alternateTargetIds.length == 0) return first;
        for (long alt : alternateTargetIds) {
            VerifyResult res = verifyAction(proof, action, alt);
            if (res.status() == Status.OK) return res;
            if (res.status() != Status.INVALID_SIGNATURE) return res;
        }
        return first;
    }

    public VerifyResult verifyActionScoped(AnonActionProof proof, String action, long targetId, Long communityId) {
        var profile = profiles.findById(proof.anonProfileId());
        if (profile.isEmpty()) return VerifyResult.notFound();
        if (revocations.isRevokedByPubkey(profile.get().publicKey)) return VerifyResult.revoked();
        var cert = verifyCertSignature(proof.anonCert(), proof.anonCertKid(), profile.get().publicKey);
        if (cert.status() == Status.INVALID_CERT) return VerifyResult.invalidCert();
        if (cert.status() == Status.INVALID_SIGNATURE) return VerifyResult.invalidSignature();
        if (communityId != null) {
            if (cert.issuer() == null || !"community".equals(cert.issuer().scopeKind) || cert.issuer().scopeId == null
                    || !cert.issuer().scopeId.equals(communityId)) {
                return VerifyResult.invalidCert();
            }
            if (activeCommunityEntitlement(cert.fingerprint(), communityId).isEmpty()) {
                return VerifyResult.invalidCert();
            }
        }
        byte[] message = AnonCrypto.actionMessage(action, targetId);
        if (!verifyPersonaSignature(profile.get().publicKey, proof.anonSig(), message)) {
            return VerifyResult.invalidSignature();
        }
        var principal = principals.findByAnonProfileId(profile.get().id)
                .orElseGet(() -> principals.createForAnon(profile.get().id));
        return VerifyResult.ok(new AnonActor(
                principal.id,
                profile.get().id,
                null,
                cert.issuer() == null ? null : cert.issuer().companyId,
                profile.get().publicKey
        ));
    }

    private CertResult verifyCertSignature(String anonCertB64, String anonCertKid, byte[] personaPubkey) {
        if (anonCertB64 == null || anonCertB64.isBlank()) return CertResult.invalidCert();
        if (anonCertKid == null || anonCertKid.isBlank()) return CertResult.invalidCert();
        byte[] signature;
        try {
            signature = Base64.getDecoder().decode(anonCertB64);
        } catch (IllegalArgumentException e) {
            return CertResult.invalidCert();
        }
        byte[] fingerprint = AnonCertFingerprint.sha256(anonCertKid, signature);
        byte[] message = AnonCrypto.certMessage(personaPubkey);
        Optional<AnonIssuerRepository.IssuerRow> issuer = issuers.findByKid(anonCertKid);
        if (issuer.isEmpty()) return CertResult.invalidCert();
        if (issuer.get().expiresAt != null && issuer.get().expiresAt.isBefore(java.time.OffsetDateTime.now())) {
            return CertResult.invalidCert();
        }
        RSAPublicKey publicKey = parseRsaPublicKey(issuer.get().publicKey);
        boolean ok = new com.looped.anon.crypto.BlindRsaSigner(null, publicKey).verify(message, signature);
        return ok ? CertResult.ok(issuer.get(), fingerprint) : CertResult.invalidSignature();
    }

    private Optional<AnonCertEntitlementsRepository.Row> activeCommunityEntitlement(byte[] certFingerprint, long communityId) {
        if (certFingerprint == null || certFingerprint.length == 0) return Optional.empty();
        var entitlement = certEntitlements.find(certFingerprint).orElse(null);
        if (entitlement == null) return Optional.empty();
        if (entitlement.communityId() != communityId) return Optional.empty();
        if (entitlement.certExpiresAt() != null && entitlement.certExpiresAt().isBefore(java.time.OffsetDateTime.now())) {
            return Optional.empty();
        }

        var community = communities.findById(communityId).orElse(null);
        if (community == null || community.kind == null) return Optional.empty();
        if (!isUserVisible(community.kind, community.specializationType)) return Optional.empty();
        if ("specialization".equalsIgnoreCase(community.kind)) {
            if (!requiresSpecializationJoin(community.specializationType)) return Optional.of(entitlement);
            return specializationJoins.exists(entitlement.userId(), communityId) ? Optional.of(entitlement) : Optional.empty();
        }
        return communityVerifications.isVerified(entitlement.userId(), communityId) ? Optional.of(entitlement) : Optional.empty();
    }

    private boolean requiresSpecializationJoin(String specializationType) {
        if (specializationType == null) return false;
        String type = specializationType.trim().toLowerCase(java.util.Locale.ROOT);
        return "field".equals(type);
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

    public record AnonActor(long principalId, long anonProfileId, Long userId, Long companyId, byte[] personaPubkey) {}

    public enum Status { OK, NOT_FOUND, INVALID_CERT, INVALID_SIGNATURE, REVOKED }

    public record VerifyResult(Status status, AnonActor actor) {
        static VerifyResult ok(AnonActor actor) { return new VerifyResult(Status.OK, actor); }
        static VerifyResult notFound() { return new VerifyResult(Status.NOT_FOUND, null); }
        static VerifyResult invalidCert() { return new VerifyResult(Status.INVALID_CERT, null); }
        static VerifyResult invalidSignature() { return new VerifyResult(Status.INVALID_SIGNATURE, null); }
        static VerifyResult revoked() { return new VerifyResult(Status.REVOKED, null); }
    }

    public record CertVerifyResult(Status status, AnonIssuerRepository.IssuerRow issuer) {
        static CertVerifyResult ok(AnonIssuerRepository.IssuerRow issuer) { return new CertVerifyResult(Status.OK, issuer); }
        static CertVerifyResult invalidCert() { return new CertVerifyResult(Status.INVALID_CERT, null); }
        static CertVerifyResult invalidSignature() { return new CertVerifyResult(Status.INVALID_SIGNATURE, null); }
    }

    private record CertResult(Status status, AnonIssuerRepository.IssuerRow issuer, byte[] fingerprint) {
        static CertResult ok(AnonIssuerRepository.IssuerRow issuer, byte[] fingerprint) {
            return new CertResult(Status.OK, issuer, fingerprint);
        }
        static CertResult invalidCert() { return new CertResult(Status.INVALID_CERT, null, null); }
        static CertResult invalidSignature() { return new CertResult(Status.INVALID_SIGNATURE, null, null); }
    }

    public record AnonPostProof(Long anonProfileId, String anonCert, String anonCertKid, String anonSig) {}
    public record AnonActionProof(Long anonProfileId, String anonCert, String anonCertKid, String anonSig) {}
}

package com.looped.anon;

import com.looped.anon.crypto.AnonCrypto;
import com.looped.anon.crypto.BlindRsaSigner;
import com.looped.anon.crypto.Ed25519Verifier;
import com.looped.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.OffsetDateTime;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "auth.issuer=http://test-issuer",
        "auth.audience=test-app"
})
class AnonEntitlementEnforcementIntegrationTest extends PostgresTestBase {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AnonProofService proofs;

    @Test
    void anon_action_fails_after_verification_is_revoked() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('AnonEntitlementCo','anonentitlement.co') RETURNING id",
                Long.class
        );
        long userId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-anon-entitlement-verify", "anonentverify", companyId
        );
        long communityId = jdbc.queryForObject(
                "INSERT INTO communities(kind, name) VALUES ('school', 'UNC') RETURNING id",
                Long.class
        );
        jdbc.update(
                "INSERT INTO community_verifications(user_id, community_id, method, verified, verified_at, expires_at) VALUES (?,?,?, true, now(), ?)",
                userId, communityId, "manual", OffsetDateTime.now().plusDays(7)
        );

        String kid = "kid-anon-entitlement-verify";
        KeyPair rsaPair = rsaPair();
        jdbc.update(
                "INSERT INTO anon_issuers(kid, alg, public_key, company_id, scope_kind, scope_id, expires_at) VALUES (?,?,?,?,?,?,?)",
                kid, "RSABSSA", rsaPair.getPublic().getEncoded(), companyId, "community", communityId, OffsetDateTime.now().plusDays(30)
        );

        KeyPair persona = ed25519Pair();
        byte[] personaPubkey = rawEd25519PublicKey(persona.getPublic());
        long anonProfileId = jdbc.queryForObject(
                "INSERT INTO anonymous_profiles(company_id, public_key, handle) VALUES (?,?,?) RETURNING id",
                Long.class, companyId, personaPubkey, "anonentverifyprofile"
        );
        String anonCertB64 = certForPersona((RSAPrivateKey) rsaPair.getPrivate(), (RSAPublicKey) rsaPair.getPublic(), personaPubkey);
        byte[] certFingerprint = AnonCertFingerprint.sha256(kid, Base64.getDecoder().decode(anonCertB64));
        jdbc.update(
                "INSERT INTO anon_cert_entitlements(cert_fingerprint, anon_cert_kid, user_id, community_id, cert_expires_at) VALUES (?,?,?,?,?)",
                certFingerprint, kid, userId, communityId, OffsetDateTime.now().plusDays(30)
        );

        String sigB64 = signAction(persona.getPrivate(), "like", 123L);
        var proof = new AnonProofService.AnonActionProof(anonProfileId, anonCertB64, kid, sigB64);
        var before = proofs.verifyActionScoped(proof, "like", 123L, communityId);
        assertThat(before.status()).isEqualTo(AnonProofService.Status.OK);

        jdbc.update(
                "UPDATE community_verifications SET verified = false, verified_at = NULL, expires_at = NULL WHERE user_id = ? AND community_id = ?",
                userId, communityId
        );

        var after = proofs.verifyActionScoped(proof, "like", 123L, communityId);
        assertThat(after.status()).isEqualTo(AnonProofService.Status.INVALID_CERT);
    }

    @Test
    void anon_action_fails_after_specialization_unjoin() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('AnonEntitlementSpecCo','anonentitlementspec.co') RETURNING id",
                Long.class
        );
        long userId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-anon-entitlement-spec", "anonentspec", companyId
        );
        long communityId = jdbc.queryForObject(
                "INSERT INTO communities(kind, specialization_type, name) VALUES ('specialization','major','CS') RETURNING id",
                Long.class
        );
        jdbc.update("INSERT INTO specialization_joins(user_id, specialization_id) VALUES (?,?)", userId, communityId);

        String kid = "kid-anon-entitlement-spec";
        KeyPair rsaPair = rsaPair();
        jdbc.update(
                "INSERT INTO anon_issuers(kid, alg, public_key, company_id, scope_kind, scope_id, expires_at) VALUES (?,?,?,?,?,?,?)",
                kid, "RSABSSA", rsaPair.getPublic().getEncoded(), companyId, "community", communityId, OffsetDateTime.now().plusDays(30)
        );

        KeyPair persona = ed25519Pair();
        byte[] personaPubkey = rawEd25519PublicKey(persona.getPublic());
        long anonProfileId = jdbc.queryForObject(
                "INSERT INTO anonymous_profiles(company_id, public_key, handle) VALUES (?,?,?) RETURNING id",
                Long.class, companyId, personaPubkey, "anonentspecprofile"
        );
        String anonCertB64 = certForPersona((RSAPrivateKey) rsaPair.getPrivate(), (RSAPublicKey) rsaPair.getPublic(), personaPubkey);
        byte[] certFingerprint = AnonCertFingerprint.sha256(kid, Base64.getDecoder().decode(anonCertB64));
        jdbc.update(
                "INSERT INTO anon_cert_entitlements(cert_fingerprint, anon_cert_kid, user_id, community_id, cert_expires_at) VALUES (?,?,?,?,?)",
                certFingerprint, kid, userId, communityId, OffsetDateTime.now().plusDays(30)
        );

        String sigB64 = signAction(persona.getPrivate(), "like", 456L);
        var proof = new AnonProofService.AnonActionProof(anonProfileId, anonCertB64, kid, sigB64);
        var before = proofs.verifyActionScoped(proof, "like", 456L, communityId);
        assertThat(before.status()).isEqualTo(AnonProofService.Status.OK);

        jdbc.update("DELETE FROM specialization_joins WHERE user_id = ? AND specialization_id = ?", userId, communityId);

        var after = proofs.verifyActionScoped(proof, "like", 456L, communityId);
        assertThat(after.status()).isEqualTo(AnonProofService.Status.INVALID_CERT);
    }

    private static KeyPair rsaPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static KeyPair ed25519Pair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        return generator.generateKeyPair();
    }

    private static byte[] rawEd25519PublicKey(PublicKey publicKey) {
        return Ed25519Verifier.parseRawPublicKey(publicKey.getEncoded());
    }

    private static String certForPersona(RSAPrivateKey privateKey, RSAPublicKey publicKey, byte[] personaPubkey) {
        byte[] certMessage = AnonCrypto.certMessage(personaPubkey);
        byte[] certSig = new BlindRsaSigner(privateKey, publicKey).signBlinded(certMessage);
        return Base64.getEncoder().encodeToString(certSig);
    }

    private static String signAction(java.security.PrivateKey privateKey, String action, long targetId) throws Exception {
        byte[] message = AnonCrypto.actionMessage(action, targetId);
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(privateKey);
        signature.update(message);
        return Base64.getEncoder().encodeToString(signature.sign());
    }
}

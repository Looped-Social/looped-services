package com.looped.integration;

import com.looped.anon.crypto.AnonCrypto;
import com.looped.anon.crypto.BlindRsaSigner;
import com.looped.anon.crypto.Ed25519Verifier;
import com.looped.auth.TestSecurityConfig;
import com.looped.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;

import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import static org.hamcrest.Matchers.equalTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "auth.issuer=http://test-issuer",
        "auth.audience=test-app"
})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(TestSecurityConfig.class)
class AnonDisplaySpecializationIntegrationTest extends PostgresTestBase {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JwtEncoder jwtEncoder;

    @Autowired
    JdbcTemplate jdbc;

    private String token(String sub) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("http://test-issuer")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(3600))
                .subject(sub)
                .audience(List.of("test-app"))
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private static byte[] rawEd25519PublicKey(java.security.PublicKey publicKey) {
        return Ed25519Verifier.parseRawPublicKey(publicKey.getEncoded());
    }

    private static byte[] signEd25519(java.security.PrivateKey privateKey, byte[] message) throws Exception {
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(privateKey);
        signature.update(message);
        return signature.sign();
    }

    @Test
    void anon_can_set_display_specialization() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('AnonSpecCo','anon.spec.co') RETURNING id", Long.class);
        long specializationId = jdbc.queryForObject(
                "INSERT INTO communities(kind, specialization_type, name) VALUES ('specialization','major','Computer Science') RETURNING id",
                Long.class
        );
        long userId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-anon-spec-owner", "anonspecowner", companyId
        );
        jdbc.update("INSERT INTO specialization_joins(user_id, specialization_id) VALUES (?,?)", userId, specializationId);

        // Issuer (RSA) scoped to specialization community
        var rsaKpg = KeyPairGenerator.getInstance("RSA");
        rsaKpg.initialize(2048);
        var rsa = rsaKpg.generateKeyPair();
        String kid = "kid-anon-spec";
        jdbc.update(
                "INSERT INTO anon_issuers(kid, alg, public_key, company_id, scope_kind, scope_id, expires_at) " +
                        "VALUES (?,?,?,?,?,?, now() + interval '1 day')",
                kid, "RSABSSA", rsa.getPublic().getEncoded(), companyId, "community", specializationId
        );

        // Persona (Ed25519)
        var edKpg = KeyPairGenerator.getInstance("Ed25519");
        var ed = edKpg.generateKeyPair();
        byte[] personaPubkeyRaw = rawEd25519PublicKey(ed.getPublic());

        long anonProfileId = jdbc.queryForObject(
                "INSERT INTO anonymous_profiles(company_id, public_key, handle) VALUES (?,?,?) RETURNING id",
                Long.class, companyId, personaPubkeyRaw, "anonymous-spec"
        );

        byte[] certMsg = AnonCrypto.certMessage(personaPubkeyRaw);
        byte[] certSig = new BlindRsaSigner((RSAPrivateKey) rsa.getPrivate(), (RSAPublicKey) rsa.getPublic())
                .signBlinded(certMsg);
        String anonCertB64 = Base64.getEncoder().encodeToString(certSig);
        byte[] certFingerprint = com.looped.anon.AnonCertFingerprint.sha256(kid, certSig);
        jdbc.update(
                "INSERT INTO anon_cert_entitlements(cert_fingerprint, anon_cert_kid, user_id, community_id, cert_expires_at) VALUES (?,?,?,?, now() + interval '1 day')",
                certFingerprint, kid, userId, specializationId
        );

        byte[] actionMsg = AnonCrypto.actionMessage("anon_display_specialization", anonProfileId);
        byte[] actionSig = signEd25519(ed.getPrivate(), actionMsg);
        String anonSigB64 = Base64.getEncoder().encodeToString(actionSig);

        String body = "{"
                + "\"asAnon\":true,"
                + "\"anonProfileId\":" + anonProfileId + ","
                + "\"specializationId\":" + specializationId + ","
                + "\"anonCert\":\"" + anonCertB64 + "\","
                + "\"anonCertKid\":\"" + kid + "\","
                + "\"anonSig\":\"" + anonSigB64 + "\""
                + "}";

        mockMvc.perform(put("/v1/anon/" + anonProfileId + "/display-specialization")
                        .header("X-Actor", "anon")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.display_specialization.id").value((int) specializationId))
                .andExpect(jsonPath("$.display_specialization.kind").value("specialization"))
                .andExpect(jsonPath("$.display_specialization.specialization_type").value("major"));
    }

    @Test
    void anon_set_display_specialization_rejects_invalid_kind() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('AnonSpecBad','anon.spec.bad') RETURNING id", Long.class);
        long notSpecId = jdbc.queryForObject(
                "INSERT INTO communities(kind, name) VALUES ('company','NotASpec') RETURNING id",
                Long.class
        );

        // Issuer and persona are irrelevant: validation fails before proof verification
        var rsaKpg = KeyPairGenerator.getInstance("RSA");
        rsaKpg.initialize(2048);
        var rsa = rsaKpg.generateKeyPair();
        String kid = "kid-anon-spec-bad";
        jdbc.update(
                "INSERT INTO anon_issuers(kid, alg, public_key, company_id, scope_kind, scope_id, expires_at) " +
                        "VALUES (?,?,?,?,?,?, now() + interval '1 day')",
                kid, "RSABSSA", rsa.getPublic().getEncoded(), companyId, "community", notSpecId
        );

        var edKpg = KeyPairGenerator.getInstance("Ed25519");
        var ed = edKpg.generateKeyPair();
        byte[] personaPubkeyRaw = rawEd25519PublicKey(ed.getPublic());
        long anonProfileId = jdbc.queryForObject(
                "INSERT INTO anonymous_profiles(company_id, public_key, handle) VALUES (?,?,?) RETURNING id",
                Long.class, companyId, personaPubkeyRaw, "anonymous-bad"
        );

        byte[] certMsg = AnonCrypto.certMessage(personaPubkeyRaw);
        byte[] certSig = new BlindRsaSigner((RSAPrivateKey) rsa.getPrivate(), (RSAPublicKey) rsa.getPublic())
                .signBlinded(certMsg);
        String anonCertB64 = Base64.getEncoder().encodeToString(certSig);

        byte[] actionMsg = AnonCrypto.actionMessage("anon_display_specialization", anonProfileId);
        byte[] actionSig = signEd25519(ed.getPrivate(), actionMsg);
        String anonSigB64 = Base64.getEncoder().encodeToString(actionSig);

        String body = "{"
                + "\"asAnon\":true,"
                + "\"anonProfileId\":" + anonProfileId + ","
                + "\"specializationId\":" + notSpecId + ","
                + "\"anonCert\":\"" + anonCertB64 + "\","
                + "\"anonCertKid\":\"" + kid + "\","
                + "\"anonSig\":\"" + anonSigB64 + "\""
                + "}";

        mockMvc.perform(put("/v1/anon/" + anonProfileId + "/display-specialization")
                        .header("X-Actor", "anon")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value(equalTo("invalid_specialization")));
    }

    @Test
    void anon_set_display_specialization_requires_joined_cert_scope() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('AnonSpecJoin','anon.spec.join') RETURNING id", Long.class);
        long specializationA = jdbc.queryForObject(
                "INSERT INTO communities(kind, specialization_type, name) VALUES ('specialization','major','A') RETURNING id",
                Long.class
        );
        long specializationB = jdbc.queryForObject(
                "INSERT INTO communities(kind, specialization_type, name) VALUES ('specialization','major','B') RETURNING id",
                Long.class
        );

        var rsaKpg = KeyPairGenerator.getInstance("RSA");
        rsaKpg.initialize(2048);
        var rsa = rsaKpg.generateKeyPair();
        String kid = "kid-anon-spec-join";
        jdbc.update(
                "INSERT INTO anon_issuers(kid, alg, public_key, company_id, scope_kind, scope_id, expires_at) " +
                        "VALUES (?,?,?,?,?,?, now() + interval '1 day')",
                kid, "RSABSSA", rsa.getPublic().getEncoded(), companyId, "community", specializationB
        );

        var edKpg = KeyPairGenerator.getInstance("Ed25519");
        var ed = edKpg.generateKeyPair();
        byte[] personaPubkeyRaw = rawEd25519PublicKey(ed.getPublic());

        long anonProfileId = jdbc.queryForObject(
                "INSERT INTO anonymous_profiles(company_id, public_key, handle) VALUES (?,?,?) RETURNING id",
                Long.class, companyId, personaPubkeyRaw, "anonymous-join"
        );

        byte[] certMsg = AnonCrypto.certMessage(personaPubkeyRaw);
        byte[] certSig = new BlindRsaSigner((RSAPrivateKey) rsa.getPrivate(), (RSAPublicKey) rsa.getPublic())
                .signBlinded(certMsg);
        String anonCertB64 = Base64.getEncoder().encodeToString(certSig);

        byte[] actionMsg = AnonCrypto.actionMessage("anon_display_specialization", anonProfileId);
        byte[] actionSig = signEd25519(ed.getPrivate(), actionMsg);
        String anonSigB64 = Base64.getEncoder().encodeToString(actionSig);

        String body = "{"
                + "\"asAnon\":true,"
                + "\"anonProfileId\":" + anonProfileId + ","
                + "\"specializationId\":" + specializationA + ","
                + "\"anonCert\":\"" + anonCertB64 + "\","
                + "\"anonCertKid\":\"" + kid + "\","
                + "\"anonSig\":\"" + anonSigB64 + "\""
                + "}";

        mockMvc.perform(put("/v1/anon/" + anonProfileId + "/display-specialization")
                        .header("X-Actor", "anon")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(equalTo("specialization_not_joined")));
    }

    @Test
    void anon_display_specialization_appears_on_posts() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('AnonSpecPostCo','anon.spec.post.co') RETURNING id", Long.class);
        jdbc.update("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?)", "uid-viewer-anon-spec", "viewer", companyId);
        String auth = "Bearer " + token("uid-viewer-anon-spec");

        long postCommunityId = jdbc.queryForObject(
                "INSERT INTO communities(kind, name) VALUES ('company','AnonSpecPostCo') RETURNING id",
                Long.class
        );
        long specializationId = jdbc.queryForObject(
                "INSERT INTO communities(kind, specialization_type, name) VALUES ('specialization','major','Computer Science') RETURNING id",
                Long.class
        );

        var rsaKpg = KeyPairGenerator.getInstance("RSA");
        rsaKpg.initialize(2048);
        var rsa = rsaKpg.generateKeyPair();
        String kid = "kid-anon-spec-post";
        jdbc.update(
                "INSERT INTO anon_issuers(kid, alg, public_key, company_id, scope_kind, scope_id, expires_at) " +
                        "VALUES (?,?,?,?,?,?, now() + interval '1 day')",
                kid, "RSABSSA", rsa.getPublic().getEncoded(), companyId, "community", specializationId
        );

        var edKpg = KeyPairGenerator.getInstance("Ed25519");
        var ed = edKpg.generateKeyPair();
        byte[] personaPubkeyRaw = rawEd25519PublicKey(ed.getPublic());

        long anonProfileId = jdbc.queryForObject(
                "INSERT INTO anonymous_profiles(company_id, public_key, handle, display_specialization_id, display_specialization_cert_kid) " +
                        "VALUES (?,?,?,?,?) RETURNING id",
                Long.class, companyId, personaPubkeyRaw, "anonymous-post", specializationId, kid
        );

        long anonPrincipalId = jdbc.queryForObject(
                "INSERT INTO principals(kind, anon_profile_id) VALUES ('anon', ?) RETURNING id",
                Long.class, anonProfileId
        );

        long postId = jdbc.queryForObject(
                "INSERT INTO posts(author_id, author_principal_id, company_id, community_id, content, is_anon, anon_profile_id, anon_company_id) " +
                        "VALUES (NULL,?,?,?,?, true, ?, ?) RETURNING id",
                Long.class, anonPrincipalId, companyId, postCommunityId, "anon post", anonProfileId, companyId
        );

        mockMvc.perform(get("/v1/posts/" + postId).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.author_display_specialization.id").value((int) specializationId))
                .andExpect(jsonPath("$.author_display_specialization.kind").value("specialization"))
                .andExpect(jsonPath("$.author_display_specialization.specialization_type").value("major"));
    }
}

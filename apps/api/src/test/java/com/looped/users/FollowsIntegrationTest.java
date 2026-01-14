package com.looped.users;

import com.looped.anon.crypto.AnonCrypto;
import com.looped.anon.crypto.BlindRsaSigner;
import com.looped.anon.crypto.Ed25519Verifier;
import com.looped.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "auth.issuer=http://test-issuer",
        "auth.audience=test-app"
})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(com.looped.auth.TestSecurityConfig.class)
class FollowsIntegrationTest extends PostgresTestBase {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JdbcTemplate jdbc;

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
    void anonymous_can_follow_and_unfollow_user_using_proof_signed_over_user_id() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('FollowAnon','followanon.co') RETURNING id", Long.class);
        long targetUserId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id, display_name) VALUES (?,?,?,?) RETURNING id",
                Long.class, "uid-follow-target", "follow_target", companyId, "Follow Target"
        );
        long targetPrincipalId = jdbc.queryForObject(
                "INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id",
                Long.class, targetUserId
        );

        var rsaKpg = KeyPairGenerator.getInstance("RSA");
        rsaKpg.initialize(2048);
        var rsa = rsaKpg.generateKeyPair();
        String kid = "kid-follow";
        jdbc.update(
                "INSERT INTO anon_issuers(kid, alg, public_key, company_id, scope_kind, scope_id, expires_at) " +
                        "VALUES (?,?,?,?,?,?, now() + interval '1 day')",
                kid, "RSABSSA", rsa.getPublic().getEncoded(), companyId, "company", companyId
        );

        var edKpg = KeyPairGenerator.getInstance("Ed25519");
        var ed = edKpg.generateKeyPair();
        byte[] personaPubkeyRaw = rawEd25519PublicKey(ed.getPublic());

        long anonProfileId = jdbc.queryForObject(
                "INSERT INTO anonymous_profiles(company_id, public_key, handle) VALUES (?,?,?) RETURNING id",
                Long.class, companyId, personaPubkeyRaw, "anonymous-follow-test"
        );

        byte[] certMsg = AnonCrypto.certMessage(personaPubkeyRaw);
        byte[] certSig = new BlindRsaSigner((RSAPrivateKey) rsa.getPrivate(), (RSAPublicKey) rsa.getPublic())
                .signBlinded(certMsg);
        String anonCertB64 = Base64.getEncoder().encodeToString(certSig);

        byte[] followMsg = AnonCrypto.actionMessage("follow", targetUserId);
        String followSigB64 = Base64.getEncoder().encodeToString(signEd25519(ed.getPrivate(), followMsg));
        String followBody = "{"
                + "\"asAnon\":true,"
                + "\"anonProfileId\":" + anonProfileId + ","
                + "\"anonCert\":\"" + anonCertB64 + "\","
                + "\"anonCertKid\":\"" + kid + "\","
                + "\"anonSig\":\"" + followSigB64 + "\""
                + "}";

        mockMvc.perform(post("/v1/users/" + targetUserId + "/follow")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(followBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user_id").value((int) targetUserId))
                .andExpect(jsonPath("$.following").value(true));

        Long anonPrincipalId = jdbc.queryForObject(
                "SELECT id FROM principals WHERE anon_profile_id = ?",
                Long.class, anonProfileId
        );
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM principal_follows WHERE follower_principal_id=? AND followee_principal_id=?",
                Integer.class, anonPrincipalId, targetPrincipalId
        );
        org.junit.jupiter.api.Assertions.assertEquals(1, count);

        byte[] unfollowMsg = AnonCrypto.actionMessage("unfollow", targetUserId);
        String unfollowSigB64 = Base64.getEncoder().encodeToString(signEd25519(ed.getPrivate(), unfollowMsg));
        String unfollowBody = "{"
                + "\"asAnon\":true,"
                + "\"anonProfileId\":" + anonProfileId + ","
                + "\"anonCert\":\"" + anonCertB64 + "\","
                + "\"anonCertKid\":\"" + kid + "\","
                + "\"anonSig\":\"" + unfollowSigB64 + "\""
                + "}";

        mockMvc.perform(delete("/v1/users/" + targetUserId + "/follow")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unfollowBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user_id").value((int) targetUserId))
                .andExpect(jsonPath("$.following").value(false));
    }
}


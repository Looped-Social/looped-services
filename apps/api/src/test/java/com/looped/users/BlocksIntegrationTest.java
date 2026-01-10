package com.looped.users;

import com.looped.auth.TestSecurityConfig;
import com.looped.anon.crypto.AnonCrypto;
import com.looped.anon.crypto.BlindRsaSigner;
import com.looped.anon.crypto.Ed25519Verifier;
import com.looped.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "auth.issuer=http://test-issuer",
        "auth.audience=test-app"
})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(TestSecurityConfig.class)
class BlocksIntegrationTest extends PostgresTestBase {

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
    void blocked_list_orders_newest_first() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('BlockCo','block.co') RETURNING id", Long.class);
        long blocker = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id, display_name) VALUES (?,?,?,?) RETURNING id",
                Long.class, "uid-blocker", "blocker", companyId, "Blocker");
        long alice = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id, display_name) VALUES (?,?,?,?) RETURNING id",
                Long.class, "uid-alice", "alice", companyId, "Alice");
        long bob = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id, display_name) VALUES (?,?,?,?) RETURNING id",
                Long.class, "uid-bob", "bob", companyId, "Bob");

        long blockerPrincipal = jdbc.queryForObject("INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id", Long.class, blocker);
        long alicePrincipal = jdbc.queryForObject("INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id", Long.class, alice);
        long bobPrincipal = jdbc.queryForObject("INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id", Long.class, bob);

        jdbc.update("INSERT INTO principal_blocks(blocker_principal_id, blocked_principal_id, created_at) VALUES (?,?, now() - interval '5 seconds')",
                blockerPrincipal, alicePrincipal);
        jdbc.update("INSERT INTO principal_blocks(blocker_principal_id, blocked_principal_id, created_at) VALUES (?,?, now() - interval '2 seconds')",
                blockerPrincipal, bobPrincipal);

        mockMvc.perform(get("/v1/users/blocked")
                        .header("Authorization", "Bearer " + token("uid-blocker")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].handle", equalTo("bob")))
                .andExpect(jsonPath("$.items[1].handle", equalTo("alice")));
    }

    @Test
    void block_and_unblock_user() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('Block2','block2.co') RETURNING id", Long.class);
        jdbc.update("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?)", "uid-actor", "actor", companyId);
        long targetId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-target", "target", companyId);

        String auth = "Bearer " + token("uid-actor");

        mockMvc.perform(post("/v1/users/" + targetId + "/block")
                        .header("Authorization", auth))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.blocked").value(true));

        mockMvc.perform(delete("/v1/users/" + targetId + "/block")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocked").value(false));
    }

    @Test
    void anonymous_persona_can_block_and_unblock_user() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('AnonBlock','anonblock.co') RETURNING id", Long.class);

        long targetUserId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-target-user", "target_user", companyId);
        long targetPrincipalId = jdbc.queryForObject("INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id", Long.class, targetUserId);

        // Issuer (RSA)
        var rsaKpg = KeyPairGenerator.getInstance("RSA");
        rsaKpg.initialize(2048);
        var rsa = rsaKpg.generateKeyPair();
        String kid = "kid-test";
        jdbc.update(
                "INSERT INTO anon_issuers(kid, alg, public_key, company_id, scope_kind, scope_id, expires_at) " +
                        "VALUES (?,?,?,?,?,?, now() + interval '1 day')",
                kid, "RSABSSA", rsa.getPublic().getEncoded(), companyId, "company", companyId
        );

        // Persona (Ed25519)
        var edKpg = KeyPairGenerator.getInstance("Ed25519");
        var ed = edKpg.generateKeyPair();
        byte[] personaPubkeyRaw = rawEd25519PublicKey(ed.getPublic());

        long anonProfileId = jdbc.queryForObject(
                "INSERT INTO anonymous_profiles(company_id, public_key, handle) VALUES (?,?,?) RETURNING id",
                Long.class, companyId, personaPubkeyRaw, "anonymous-test"
        );

        byte[] certMsg = AnonCrypto.certMessage(personaPubkeyRaw);
        byte[] certSig = new BlindRsaSigner((RSAPrivateKey) rsa.getPrivate(), (RSAPublicKey) rsa.getPublic())
                .signBlinded(certMsg);
        String anonCertB64 = Base64.getEncoder().encodeToString(certSig);

        byte[] actionMsg = AnonCrypto.actionMessage("block", targetPrincipalId);
        byte[] actionSig = signEd25519(ed.getPrivate(), actionMsg);
        String anonSigB64 = Base64.getEncoder().encodeToString(actionSig);

        String body = "{"
                + "\"asAnon\":true,"
                + "\"anonProfileId\":" + anonProfileId + ","
                + "\"anonCert\":\"" + anonCertB64 + "\","
                + "\"anonCertKid\":\"" + kid + "\","
                + "\"anonSig\":\"" + anonSigB64 + "\""
                + "}";

        mockMvc.perform(post("/v1/users/" + targetUserId + "/block")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.blocked").value(true));

        Long anonPrincipalId = jdbc.queryForObject(
                "SELECT id FROM principals WHERE anon_profile_id = ?",
                Long.class, anonProfileId
        );
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM principal_blocks WHERE blocker_principal_id=? AND blocked_principal_id=?",
                Integer.class, anonPrincipalId, targetPrincipalId
        );
        org.junit.jupiter.api.Assertions.assertEquals(1, count);

        // Unblock (needs signature over "unblock|v1|targetPrincipalId")
        byte[] unActionMsg = AnonCrypto.actionMessage("unblock", targetPrincipalId);
        byte[] unActionSig = signEd25519(ed.getPrivate(), unActionMsg);
        String unSigB64 = Base64.getEncoder().encodeToString(unActionSig);
        String unblockBody = "{"
                + "\"asAnon\":true,"
                + "\"anonProfileId\":" + anonProfileId + ","
                + "\"anonCert\":\"" + anonCertB64 + "\","
                + "\"anonCertKid\":\"" + kid + "\","
                + "\"anonSig\":\"" + unSigB64 + "\""
                + "}";

        mockMvc.perform(delete("/v1/users/" + targetUserId + "/block")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unblockBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocked").value(false));
    }

    @Test
    void user_can_block_anonymous_persona_by_principal_id() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('BlockAnonP','blockanonp.co') RETURNING id", Long.class);
        jdbc.update("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?)", "uid-actor", "actor", companyId);

        var edKpg = KeyPairGenerator.getInstance("Ed25519");
        var ed = edKpg.generateKeyPair();
        byte[] personaPubkeyRaw = rawEd25519PublicKey(ed.getPublic());
        long anonProfileId = jdbc.queryForObject(
                "INSERT INTO anonymous_profiles(company_id, public_key, handle) VALUES (?,?,?) RETURNING id",
                Long.class, companyId, personaPubkeyRaw, "anonymous-target"
        );
        long anonPrincipalId = jdbc.queryForObject(
                "INSERT INTO principals(kind, anon_profile_id) VALUES ('anon', ?) RETURNING id",
                Long.class, anonProfileId
        );

        String auth = "Bearer " + token("uid-actor");
        mockMvc.perform(post("/v1/principals/" + anonPrincipalId + "/block")
                        .header("Authorization", auth))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.blocked").value(true))
                .andExpect(jsonPath("$.principal_id").value((int) anonPrincipalId));
    }
}

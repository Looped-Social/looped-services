package com.looped.anon;

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
import org.springframework.test.web.servlet.MockMvc;

import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.OffsetDateTime;
import java.util.Base64;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "auth.issuer=http://test-issuer",
        "auth.audience=test-app"
})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(TestSecurityConfig.class)
class AnonRegisterIntegrationTest extends PostgresTestBase {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void register_returns_matching_community_scope_when_requested() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('AnonRegCo','anonreg.co') RETURNING id",
                Long.class
        );
        long userId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-anon-register-cs", "anonregistercs", companyId
        );
        jdbc.update("INSERT INTO communities(kind, name) VALUES ('school', 'UNC')");
        long csId = jdbc.queryForObject(
                "INSERT INTO communities(kind, specialization_type, name) VALUES ('specialization','major','CS') RETURNING id",
                Long.class
        );
        jdbc.update("INSERT INTO specialization_joins(user_id, specialization_id) VALUES (?,?)", userId, csId);

        var rsaKpg = KeyPairGenerator.getInstance("RSA");
        rsaKpg.initialize(2048);
        var rsa = rsaKpg.generateKeyPair();
        String kid = "kid-anon-register-cs";
        jdbc.update(
                "INSERT INTO anon_issuers(kid, alg, public_key, company_id, scope_kind, scope_id, expires_at) " +
                        "VALUES (?,?,?,?,?,?, now() + interval '1 day')",
                kid, "RSABSSA", rsa.getPublic().getEncoded(), companyId, "community", csId
        );

        var edKpg = KeyPairGenerator.getInstance("Ed25519");
        var ed = edKpg.generateKeyPair();
        byte[] personaPubkeyRaw = rawEd25519PublicKey(ed.getPublic());
        String personaPubkeyB64 = Base64.getEncoder().encodeToString(personaPubkeyRaw);
        String anonCertB64 = issueCertB64((RSAPrivateKey) rsa.getPrivate(), (RSAPublicKey) rsa.getPublic(), personaPubkeyRaw);
        String issueToken = "issue-token-register-cs";
        jdbc.update(
                "INSERT INTO anon_issue_tokens(token_hash, user_id, community_id, expires_at) VALUES (?,?,?,?)",
                AnonIssueTokenCodec.hash(issueToken),
                userId,
                csId,
                OffsetDateTime.now().plusHours(1)
        );

        String body = "{"
                + "\"personaPubkey\":\"" + personaPubkeyB64 + "\","
                + "\"anonCert\":\"" + anonCertB64 + "\","
                + "\"anonCertKid\":\"" + kid + "\","
                + "\"communityId\":" + csId + ","
                + "\"issueToken\":\"" + issueToken + "\""
                + "}";

        mockMvc.perform(post("/anon/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.community_id").value((int) csId))
                .andExpect(jsonPath("$.communityId").value((int) csId))
                .andExpect(jsonPath("$.anon_cert_kid").value(kid))
                .andExpect(jsonPath("$.expires_at").isString())
                .andExpect(jsonPath("$.expiresAt").isString());
    }

    @Test
    void register_rejects_when_requested_community_differs_from_cert_scope() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('AnonRegMismatchCo','anonregmismatch.co') RETURNING id",
                Long.class
        );
        long userId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-anon-register-mismatch", "anonregistermismatch", companyId
        );
        long uncId = jdbc.queryForObject(
                "INSERT INTO communities(kind, name) VALUES ('school', 'UNC') RETURNING id",
                Long.class
        );
        long csId = jdbc.queryForObject(
                "INSERT INTO communities(kind, specialization_type, name) VALUES ('specialization','major','CS') RETURNING id",
                Long.class
        );

        var rsaKpg = KeyPairGenerator.getInstance("RSA");
        rsaKpg.initialize(2048);
        var rsa = rsaKpg.generateKeyPair();
        String kid = "kid-anon-register-unc";
        jdbc.update(
                "INSERT INTO anon_issuers(kid, alg, public_key, company_id, scope_kind, scope_id, expires_at) " +
                        "VALUES (?,?,?,?,?,?, now() + interval '1 day')",
                kid, "RSABSSA", rsa.getPublic().getEncoded(), companyId, "community", uncId
        );

        var edKpg = KeyPairGenerator.getInstance("Ed25519");
        var ed = edKpg.generateKeyPair();
        byte[] personaPubkeyRaw = rawEd25519PublicKey(ed.getPublic());
        String personaPubkeyB64 = Base64.getEncoder().encodeToString(personaPubkeyRaw);
        String anonCertB64 = issueCertB64((RSAPrivateKey) rsa.getPrivate(), (RSAPublicKey) rsa.getPublic(), personaPubkeyRaw);
        String issueToken = "issue-token-register-mismatch";
        jdbc.update(
                "INSERT INTO anon_issue_tokens(token_hash, user_id, community_id, expires_at) VALUES (?,?,?,?)",
                AnonIssueTokenCodec.hash(issueToken),
                userId,
                uncId,
                OffsetDateTime.now().plusHours(1)
        );

        String body = "{"
                + "\"personaPubkey\":\"" + personaPubkeyB64 + "\","
                + "\"anonCert\":\"" + anonCertB64 + "\","
                + "\"anonCertKid\":\"" + kid + "\","
                + "\"communityId\":" + csId + ","
                + "\"issueToken\":\"" + issueToken + "\""
                + "}";

        mockMvc.perform(post("/anon/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("anon_scope_mismatch"))
                .andExpect(jsonPath("$.requested_community_id").value((int) csId))
                .andExpect(jsonPath("$.cert_community_id").value((int) uncId));
    }

    private static byte[] rawEd25519PublicKey(PublicKey publicKey) {
        return Ed25519Verifier.parseRawPublicKey(publicKey.getEncoded());
    }

    private static String issueCertB64(RSAPrivateKey privateKey, RSAPublicKey publicKey, byte[] personaPubkeyRaw) {
        byte[] certMessage = AnonCrypto.certMessage(personaPubkeyRaw);
        byte[] certSig = new BlindRsaSigner(privateKey, publicKey).signBlinded(certMessage);
        return Base64.getEncoder().encodeToString(certSig);
    }
}

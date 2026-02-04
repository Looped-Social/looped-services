package com.looped.posts;

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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
class AnonRepostToggleIntegrationTest extends PostgresTestBase {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    JwtEncoder jwtEncoder;

    private static byte[] rawEd25519PublicKey(java.security.PublicKey publicKey) {
        return Ed25519Verifier.parseRawPublicKey(publicKey.getEncoded());
    }

    private static byte[] signEd25519(java.security.PrivateKey privateKey, byte[] message) throws Exception {
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(privateKey);
        signature.update(message);
        return signature.sign();
    }

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

    @Test
    void anon_can_repost_and_unrepost_using_proof() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('Acme','acme.com') RETURNING id", Long.class);
        long authorId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-author-anon-repost", "author", companyId);
        long authorPrincipalId = jdbc.queryForObject("INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id", Long.class, authorId);
        long postId = jdbc.queryForObject("INSERT INTO posts(author_id, author_principal_id, company_id, content) VALUES (?,?,?,?) RETURNING id",
                Long.class, authorId, authorPrincipalId, companyId, "hello");

        var rsaKpg = KeyPairGenerator.getInstance("RSA");
        rsaKpg.initialize(2048);
        var rsa = rsaKpg.generateKeyPair();
        String kid = "kid-anon-repost";
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
                Long.class, companyId, personaPubkeyRaw, "anonymous-repost-test"
        );

        byte[] certMsg = AnonCrypto.certMessage(personaPubkeyRaw);
        byte[] certSig = new BlindRsaSigner((RSAPrivateKey) rsa.getPrivate(), (RSAPublicKey) rsa.getPublic())
                .signBlinded(certMsg);
        String anonCertB64 = Base64.getEncoder().encodeToString(certSig);

        byte[] repostMsg = AnonCrypto.actionMessage("repost", postId);
        String repostSigB64 = Base64.getEncoder().encodeToString(signEd25519(ed.getPrivate(), repostMsg));
        String repostBody = "{"
                + "\"asAnon\":true,"
                + "\"anonProfileId\":" + anonProfileId + ","
                + "\"anonCert\":\"" + anonCertB64 + "\","
                + "\"anonCertKid\":\"" + kid + "\","
                + "\"anonSig\":\"" + repostSigB64 + "\""
                + "}";

        mockMvc.perform(put("/v1/posts/" + postId + "/repost")
                        .header("X-Actor", "anon")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(repostBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.repost_count").doesNotExist())
                .andExpect(jsonPath("$.viewer_has_reposted", equalTo(true)));
        assertEquals(1, jdbc.queryForObject("SELECT repost_count FROM posts WHERE id = ?", Integer.class, postId));

        byte[] unrepostMsg = AnonCrypto.actionMessage("unrepost", postId);
        String unrepostSigB64 = Base64.getEncoder().encodeToString(signEd25519(ed.getPrivate(), unrepostMsg));
        String unrepostBody = "{"
                + "\"asAnon\":true,"
                + "\"anonProfileId\":" + anonProfileId + ","
                + "\"anonCert\":\"" + anonCertB64 + "\","
                + "\"anonCertKid\":\"" + kid + "\","
                + "\"anonSig\":\"" + unrepostSigB64 + "\""
                + "}";

        mockMvc.perform(delete("/v1/posts/" + postId + "/repost")
                        .header("X-Actor", "anon")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unrepostBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repost_count").doesNotExist())
                .andExpect(jsonPath("$.viewer_has_reposted", equalTo(false)));
        assertEquals(0, jdbc.queryForObject("SELECT repost_count FROM posts WHERE id = ?", Integer.class, postId));
    }

    @Test
    void anon_reposts_appear_in_anon_profile_reposts_tab_for_authenticated_viewer() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('Acme','acme.com') RETURNING id", Long.class);
        long viewerId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-anon-reposts-viewer", "viewer", companyId);
        jdbc.queryForObject("INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id", Long.class, viewerId);

        long authorId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-anon-reposts-author2", "author", companyId);
        long authorPrincipalId = jdbc.queryForObject("INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id", Long.class, authorId);
        long postId = jdbc.queryForObject("INSERT INTO posts(author_id, author_principal_id, company_id, content) VALUES (?,?,?,?) RETURNING id",
                Long.class, authorId, authorPrincipalId, companyId, "hello");

        var rsaKpg = KeyPairGenerator.getInstance("RSA");
        rsaKpg.initialize(2048);
        var rsa = rsaKpg.generateKeyPair();
        String kid = "kid-anon-repost2";
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
                Long.class, companyId, personaPubkeyRaw, "anonymous-repost-test2"
        );

        byte[] certMsg = AnonCrypto.certMessage(personaPubkeyRaw);
        byte[] certSig = new BlindRsaSigner((RSAPrivateKey) rsa.getPrivate(), (RSAPublicKey) rsa.getPublic())
                .signBlinded(certMsg);
        String anonCertB64 = Base64.getEncoder().encodeToString(certSig);

        byte[] repostMsg = AnonCrypto.actionMessage("repost", postId);
        String repostSigB64 = Base64.getEncoder().encodeToString(signEd25519(ed.getPrivate(), repostMsg));
        String repostBody = "{"
                + "\"asAnon\":true,"
                + "\"anonProfileId\":" + anonProfileId + ","
                + "\"anonCert\":\"" + anonCertB64 + "\","
                + "\"anonCertKid\":\"" + kid + "\","
                + "\"anonSig\":\"" + repostSigB64 + "\""
                + "}";

        mockMvc.perform(put("/v1/posts/" + postId + "/repost")
                        .header("X-Actor", "anon")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(repostBody))
                .andExpect(status().isCreated());

        String auth = "Bearer " + token("uid-anon-reposts-viewer");
        mockMvc.perform(get("/v1/anon/" + anonProfileId + "/reposts?limit=20").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id", equalTo((int) postId)));
    }
}

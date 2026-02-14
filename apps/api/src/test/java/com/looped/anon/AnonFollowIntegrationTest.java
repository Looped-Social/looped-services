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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "auth.issuer=http://test-issuer",
        "auth.audience=test-app"
})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(TestSecurityConfig.class)
class AnonFollowIntegrationTest extends PostgresTestBase {

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
    void user_can_follow_and_unfollow_anon_profile() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('AnonFollowCo','anonfollow.co') RETURNING id",
                Long.class
        );
        jdbc.update("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?)",
                "uid-actor", "actor", companyId);
        long targetAnonProfileId = jdbc.queryForObject(
                "INSERT INTO anonymous_profiles(company_id, public_key, handle) VALUES (?,?,?) RETURNING id",
                Long.class, companyId, new byte[]{9, 9, 9, 9}, "anon_target"
        );

        String auth = "Bearer " + token("uid-actor");
        mockMvc.perform(post("/v1/anon/" + targetAnonProfileId + "/follow")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.anon_profile_id").value((int) targetAnonProfileId))
                .andExpect(jsonPath("$.following").value(true));

        mockMvc.perform(delete("/v1/anon/" + targetAnonProfileId + "/follow")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.anon_profile_id").value((int) targetAnonProfileId))
                .andExpect(jsonPath("$.following").value(false));
    }

    @Test
    void user_can_follow_unscoped_anon_profile() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('AnonFollowGlobalCo','anonfollowglobal.co') RETURNING id",
                Long.class
        );
        jdbc.update("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?)",
                "uid-actor-global", "actor_global", companyId);
        long targetAnonProfileId = jdbc.queryForObject(
                "INSERT INTO anonymous_profiles(company_id, public_key, handle) VALUES (?,?,?) RETURNING id",
                Long.class, null, new byte[]{7, 7, 7, 7}, "anon_target_global"
        );

        String auth = "Bearer " + token("uid-actor-global");
        mockMvc.perform(post("/v1/anon/" + targetAnonProfileId + "/follow")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.anon_profile_id").value((int) targetAnonProfileId))
                .andExpect(jsonPath("$.following").value(true));
    }

    @Test
    void user_follow_cross_company_anon_profile_returns_specific_error() throws Exception {
        long actorCompanyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('AnonFollowActorCo','anonfollowactor.co') RETURNING id",
                Long.class
        );
        long targetCompanyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('AnonFollowTargetCo','anonfollowtarget.co') RETURNING id",
                Long.class
        );
        jdbc.update("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?)",
                "uid-actor-cross", "actor_cross", actorCompanyId);
        long targetAnonProfileId = jdbc.queryForObject(
                "INSERT INTO anonymous_profiles(company_id, public_key, handle) VALUES (?,?,?) RETURNING id",
                Long.class, targetCompanyId, new byte[]{6, 6, 6, 6}, "anon_target_cross"
        );

        String auth = "Bearer " + token("uid-actor-cross");
        mockMvc.perform(post("/v1/anon/" + targetAnonProfileId + "/follow")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("cross_company_follow_forbidden"));
    }

    @Test
    void anon_can_follow_and_unfollow_anon_profile_using_proof() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('AnonFollowAnon','anonfollowanon.co') RETURNING id",
                Long.class
        );
        long targetAnonProfileId = jdbc.queryForObject(
                "INSERT INTO anonymous_profiles(company_id, public_key, handle) VALUES (?,?,?) RETURNING id",
                Long.class, companyId, new byte[]{1, 7, 7, 7}, "anon_target"
        );

        // Issuer (RSA)
        var rsaKpg = KeyPairGenerator.getInstance("RSA");
        rsaKpg.initialize(2048);
        var rsa = rsaKpg.generateKeyPair();
        String kid = "kid-anon-follow";
        jdbc.update(
                "INSERT INTO anon_issuers(kid, alg, public_key, company_id, scope_kind, scope_id, expires_at) " +
                        "VALUES (?,?,?,?,?,?, now() + interval '1 day')",
                kid, "RSABSSA", rsa.getPublic().getEncoded(), companyId, "company", companyId
        );

        // Actor persona (Ed25519)
        var edKpg = KeyPairGenerator.getInstance("Ed25519");
        var ed = edKpg.generateKeyPair();
        byte[] actorPubkeyRaw = rawEd25519PublicKey(ed.getPublic());
        long actorAnonProfileId = jdbc.queryForObject(
                "INSERT INTO anonymous_profiles(company_id, public_key, handle) VALUES (?,?,?) RETURNING id",
                Long.class, companyId, actorPubkeyRaw, "anon_actor"
        );

        byte[] certMsg = AnonCrypto.certMessage(actorPubkeyRaw);
        byte[] certSig = new BlindRsaSigner((RSAPrivateKey) rsa.getPrivate(), (RSAPublicKey) rsa.getPublic())
                .signBlinded(certMsg);
        String anonCertB64 = Base64.getEncoder().encodeToString(certSig);

        byte[] followMsg = AnonCrypto.actionMessage("follow_anon", targetAnonProfileId);
        String followSigB64 = Base64.getEncoder().encodeToString(signEd25519(ed.getPrivate(), followMsg));
        String followBody = "{"
                + "\"as_anon\":true,"
                + "\"anon_profile_id\":" + actorAnonProfileId + ","
                + "\"anon_cert\":\"" + anonCertB64 + "\","
                + "\"anon_cert_kid\":\"" + kid + "\","
                + "\"anon_sig\":\"" + followSigB64 + "\""
                + "}";

        mockMvc.perform(post("/v1/anon/" + targetAnonProfileId + "/follow")
                        .header("X-Actor", "anon")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(followBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.anon_profile_id").value((int) targetAnonProfileId))
                .andExpect(jsonPath("$.following").value(true));

        byte[] unfollowMsg = AnonCrypto.actionMessage("unfollow_anon", targetAnonProfileId);
        String unfollowSigB64 = Base64.getEncoder().encodeToString(signEd25519(ed.getPrivate(), unfollowMsg));
        String unfollowBody = "{"
                + "\"as_anon\":true,"
                + "\"anon_profile_id\":" + actorAnonProfileId + ","
                + "\"anon_cert\":\"" + anonCertB64 + "\","
                + "\"anon_cert_kid\":\"" + kid + "\","
                + "\"anon_sig\":\"" + unfollowSigB64 + "\""
                + "}";

        mockMvc.perform(delete("/v1/anon/" + targetAnonProfileId + "/follow")
                        .header("X-Actor", "anon")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unfollowBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.anon_profile_id").value((int) targetAnonProfileId))
                .andExpect(jsonPath("$.following").value(false));
    }
}

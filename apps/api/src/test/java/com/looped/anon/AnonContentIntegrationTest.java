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
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "auth.issuer=http://test-issuer",
        "auth.audience=test-app",
        "moderation.blocklist-terms=underreview_marker"
})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(TestSecurityConfig.class)
class AnonContentIntegrationTest extends PostgresTestBase {

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

    private void completeOnboarding(long userId) {
        jdbc.update(
                "UPDATE users SET onboarding_step = 'verification_notifications', onboarding_completed_at = now() WHERE id = ?",
                userId
        );
    }

    @Test
    void anon_profile_content_mixes_posts_and_replies_with_stable_pagination() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('Acme','acme.com') RETURNING id", Long.class);
        long actorId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-anon-content-actor", "actor", companyId);
        completeOnboarding(actorId);
        long actorPrincipalId = jdbc.queryForObject("INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id", Long.class, actorId);

        byte[] pubkey = new byte[32];
        long anonProfileId = jdbc.queryForObject(
                "INSERT INTO anonymous_profiles(company_id, public_key, handle) VALUES (?,?,?) RETURNING id",
                Long.class, companyId, pubkey, "anon-content"
        );
        long anonPrincipalId = jdbc.queryForObject(
                "INSERT INTO principals(kind, anon_profile_id) VALUES ('anon', ?) RETURNING id",
                Long.class, anonProfileId
        );

        Instant base = Instant.now();

        long anonPost1 = jdbc.queryForObject(
                "INSERT INTO posts(author_id, author_principal_id, company_id, content, is_anon, anon_profile_id, anon_company_id, created_at) " +
                        "VALUES (NULL,?,?,?,?,?,?,?) RETURNING id",
                Long.class, anonPrincipalId, companyId, "p1", true, anonProfileId, companyId, Timestamp.from(base.minusSeconds(100))
        );
        jdbc.queryForObject(
                "INSERT INTO posts(author_id, author_principal_id, company_id, content, is_anon, anon_profile_id, anon_company_id, created_at) " +
                        "VALUES (NULL,?,?,?,?,?,?,?) RETURNING id",
                Long.class, anonPrincipalId, companyId, "p2", true, anonProfileId, companyId, Timestamp.from(base.minusSeconds(60))
        );

        long hostPost = jdbc.queryForObject(
                "INSERT INTO posts(author_id, author_principal_id, company_id, content, created_at) VALUES (?,?,?,?,?) RETURNING id",
                Long.class, actorId, actorPrincipalId, companyId, "host", Timestamp.from(base.minusSeconds(200))
        );
        jdbc.update(
                "INSERT INTO comments(post_id, user_id, author_principal_id, company_id, content, created_at) VALUES (?,?,?,?,?,?)",
                hostPost, null, anonPrincipalId, companyId, "r1", Timestamp.from(base.minusSeconds(80))
        );
        jdbc.update(
                "INSERT INTO comments(post_id, user_id, author_principal_id, company_id, content, created_at) VALUES (?,?,?,?,?,?)",
                hostPost, null, anonPrincipalId, companyId, "r2", Timestamp.from(base.minusSeconds(40))
        );

        String auth = "Bearer " + token("uid-anon-content-actor");

        var r1 = mockMvc.perform(get("/v1/anon/" + anonProfileId + "/content?limit=3").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(3)))
                .andExpect(jsonPath("$.items[0].type", equalTo("reply")))
                .andExpect(jsonPath("$.items[0].reply.content", equalTo("r2")))
                .andExpect(jsonPath("$.items[1].type", equalTo("post")))
                .andExpect(jsonPath("$.items[2].type", equalTo("reply")))
                .andExpect(jsonPath("$.next_cursor", notNullValue()))
                .andReturn();

        String cursor = r1.getResponse().getContentAsString().replaceAll(".*\"next_cursor\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(get("/v1/anon/" + anonProfileId + "/content?cursor=" + cursor + "&limit=3").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].type", equalTo("post")))
                .andExpect(jsonPath("$.items[0].post.id", equalTo((int) anonPost1)))
                .andExpect(jsonPath("$.next_cursor").doesNotExist());
    }

    @Test
    void anon_profile_content_includes_poll_for_posts_and_reply_post_previews() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('AcmePoll','acmepoll.com') RETURNING id", Long.class);
        long actorId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-anon-poll-actor", "actorpoll", companyId);
        completeOnboarding(actorId);
        long actorPrincipalId = jdbc.queryForObject("INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id", Long.class, actorId);

        byte[] pubkey = new byte[32];
        long anonProfileId = jdbc.queryForObject(
                "INSERT INTO anonymous_profiles(company_id, public_key, handle) VALUES (?,?,?) RETURNING id",
                Long.class, companyId, pubkey, "anon-poll-content"
        );
        long anonPrincipalId = jdbc.queryForObject(
                "INSERT INTO principals(kind, anon_profile_id) VALUES ('anon', ?) RETURNING id",
                Long.class, anonProfileId
        );

        Instant base = Instant.now();

        long anonPollPostId = jdbc.queryForObject(
                "INSERT INTO posts(author_id, author_principal_id, company_id, content, is_anon, anon_profile_id, anon_company_id, created_at) " +
                        "VALUES (NULL,?,?,?,?,?,?,?) RETURNING id",
                Long.class, anonPrincipalId, companyId, "anon poll post", true, anonProfileId, companyId, Timestamp.from(base.minusSeconds(60))
        );
        long anonPollId = jdbc.queryForObject(
                "INSERT INTO polls(post_id, question, max_selections, closes_at) VALUES (?,?,?, now() + interval '7 days') RETURNING id",
                Long.class, anonPollPostId, "Anon poll?", 1
        );
        jdbc.update("INSERT INTO poll_options(poll_id, text, sort_order) VALUES (?,?,?)", anonPollId, "A1", 0);
        jdbc.update("INSERT INTO poll_options(poll_id, text, sort_order) VALUES (?,?,?)", anonPollId, "A2", 1);

        long hostPollPostId = jdbc.queryForObject(
                "INSERT INTO posts(author_id, author_principal_id, company_id, content, created_at) VALUES (?,?,?,?,?) RETURNING id",
                Long.class, actorId, actorPrincipalId, companyId, "host poll post", Timestamp.from(base.minusSeconds(120))
        );
        long hostPollId = jdbc.queryForObject(
                "INSERT INTO polls(post_id, question, max_selections, closes_at) VALUES (?,?,?, now() + interval '7 days') RETURNING id",
                Long.class, hostPollPostId, "Host poll?", 1
        );
        jdbc.update("INSERT INTO poll_options(poll_id, text, sort_order) VALUES (?,?,?)", hostPollId, "H1", 0);
        jdbc.update("INSERT INTO poll_options(poll_id, text, sort_order) VALUES (?,?,?)", hostPollId, "H2", 1);

        jdbc.update(
                "INSERT INTO comments(post_id, user_id, author_principal_id, company_id, content, created_at) VALUES (?,?,?,?,?,?)",
                hostPollPostId, null, anonPrincipalId, companyId, "reply on host poll", Timestamp.from(base.minusSeconds(40))
        );

        String auth = "Bearer " + token("uid-anon-poll-actor");

        mockMvc.perform(get("/v1/anon/" + anonProfileId + "/content?limit=10&include_post_preview=true").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].type", equalTo("reply")))
                .andExpect(jsonPath("$.items[0].post.id", equalTo((int) hostPollPostId)))
                .andExpect(jsonPath("$.items[0].post.poll.question", equalTo("Host poll?")))
                .andExpect(jsonPath("$.items[0].post.poll.options", hasSize(2)))
                .andExpect(jsonPath("$.items[0].post.viewer_capabilities.canVote", equalTo(true)))
                .andExpect(jsonPath("$.items[1].type", equalTo("post")))
                .andExpect(jsonPath("$.items[1].post.id", equalTo((int) anonPollPostId)))
                .andExpect(jsonPath("$.items[1].post.poll.question", equalTo("Anon poll?")))
                .andExpect(jsonPath("$.items[1].post.poll.options", hasSize(2)))
                .andExpect(jsonPath("$.items[1].post.viewer_capabilities.canVote", equalTo(true)));
    }

    @Test
    void anon_under_review_post_is_visible_immediately_in_owner_content_only() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('AnonReviewCo','anonreview.co') RETURNING id",
                Long.class
        );
        long ownerUserId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class,
                "uid-anon-review-owner",
                "anonreviewowner",
                companyId
        );
        completeOnboarding(ownerUserId);
        long otherUserId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class,
                "uid-anon-review-other",
                "anonreviewother",
                companyId
        );
        completeOnboarding(otherUserId);

        long communityId = jdbc.queryForObject(
                "INSERT INTO communities(kind, name) VALUES ('school','Anon Review School') RETURNING id",
                Long.class
        );
        jdbc.update(
                "INSERT INTO community_verifications(user_id, community_id, method, verified, verified_at, expires_at) " +
                        "VALUES (?,?,?, true, now(), ?)",
                ownerUserId,
                communityId,
                "manual",
                OffsetDateTime.now().plusDays(3)
        );

        var rsaKpg = KeyPairGenerator.getInstance("RSA");
        rsaKpg.initialize(2048);
        var rsa = rsaKpg.generateKeyPair();
        String kid = "kid-anon-under-review";
        jdbc.update(
                "INSERT INTO anon_issuers(kid, alg, public_key, company_id, scope_kind, scope_id, expires_at) " +
                        "VALUES (?,?,?,?,?,?, now() + interval '3 days')",
                kid, "RSABSSA", rsa.getPublic().getEncoded(), companyId, "community", communityId
        );

        var edKpg = KeyPairGenerator.getInstance("Ed25519");
        var ed = edKpg.generateKeyPair();
        byte[] personaPubkeyRaw = rawEd25519PublicKey(ed.getPublic());
        long anonProfileId = jdbc.queryForObject(
                "INSERT INTO anonymous_profiles(company_id, public_key, handle) VALUES (?,?,?) RETURNING id",
                Long.class, companyId, personaPubkeyRaw, "anon-under-review"
        );

        byte[] certMsg = AnonCrypto.certMessage(personaPubkeyRaw);
        byte[] certSig = new BlindRsaSigner((RSAPrivateKey) rsa.getPrivate(), (RSAPublicKey) rsa.getPublic())
                .signBlinded(certMsg);
        String anonCertB64 = Base64.getEncoder().encodeToString(certSig);
        byte[] certFingerprint = AnonCertFingerprint.sha256(kid, certSig);
        jdbc.update(
                "INSERT INTO anon_cert_entitlements(cert_fingerprint, anon_cert_kid, user_id, community_id, cert_expires_at) " +
                        "VALUES (?,?,?,?,?)",
                certFingerprint, kid, ownerUserId, communityId, OffsetDateTime.now().plusDays(3)
        );

        long timestampSeconds = Instant.now().getEpochSecond();
        String content = "post with underreview_marker";
        byte[] postMsg = AnonCrypto.postMessage(communityId, content, timestampSeconds);
        String anonSigB64 = Base64.getEncoder().encodeToString(signEd25519(ed.getPrivate(), postMsg));

        String createBody = """
                {
                  "isAnon": true,
                  "communityId": %d,
                  "content": "%s",
                  "anonProfileId": %d,
                  "anonCert": "%s",
                  "anonCertKid": "%s",
                  "anonSig": "%s",
                  "anonTimestamp": %d
                }
                """.formatted(communityId, content, anonProfileId, anonCertB64, kid, anonSigB64, timestampSeconds);

        mockMvc.perform(post("/v1/posts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error", equalTo("content_under_review")));

        Long postId = jdbc.queryForObject(
                "SELECT id FROM posts WHERE anon_profile_id = ? ORDER BY id DESC LIMIT 1",
                Long.class,
                anonProfileId
        );

        String ownerAuth = "Bearer " + token("uid-anon-review-owner");
        mockMvc.perform(get("/v1/anon/" + anonProfileId + "/content?limit=10&include_post_preview=true")
                        .header("Authorization", ownerAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].type", equalTo("post")))
                .andExpect(jsonPath("$.items[0].post.id", equalTo(postId.intValue())))
                .andExpect(jsonPath("$.items[0].post.anon_profile_id", equalTo((int) anonProfileId)))
                .andExpect(jsonPath("$.items[0].post.is_under_review", equalTo(true)));

        String otherAuth = "Bearer " + token("uid-anon-review-other");
        mockMvc.perform(get("/v1/posts/" + postId).header("Authorization", otherAuth))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/v1/public/posts/" + postId))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error", equalTo("post_unavailable")));
    }
}

package com.looped.polls;

import com.jayway.jsonpath.JsonPath;
import com.looped.anon.crypto.AnonCrypto;
import com.looped.anon.crypto.BlindRsaSigner;
import com.looped.anon.crypto.Ed25519Verifier;
import com.looped.support.PostgresTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PollsIntegrationTest extends PostgresTestBase {
    private static final String FIREBASE_UID = "firebase_test_uid";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    long userId;
    long communityId;

    private static byte[] rawEd25519PublicKey(java.security.PublicKey publicKey) {
        return Ed25519Verifier.parseRawPublicKey(publicKey.getEncoded());
    }

    private static byte[] signEd25519(java.security.PrivateKey privateKey, byte[] message) throws Exception {
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(privateKey);
        signature.update(message);
        return signature.sign();
    }

    @BeforeEach
    void seed() {
        userId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,1) RETURNING id",
                Long.class,
                FIREBASE_UID, "tester"
        );
        communityId = jdbc.queryForObject(
                "INSERT INTO communities(kind, name) VALUES ('specialization', 'Test Community') RETURNING id",
                Long.class
        );
        assertThat(userId).isPositive();
        assertThat(communityId).isPositive();
    }

    @Test
    void create_vote_change_vote_and_idempotent_revote() throws Exception {
        OffsetDateTime closesAt = OffsetDateTime.now(ZoneOffset.UTC).plusDays(7).withNano(0);
        String createBody = """
                {
                  "content": "Lunch opinions?",
                  "communityId": %d,
                  "poll": {
                    "question": "Where should we go?",
                    "options": ["Tacos", "Salad", "Sushi"],
                    "maxSelections": 1,
                    "closesAt": "%s"
                  }
                }
                """.formatted(communityId, closesAt);

        MvcResult created = mockMvc.perform(
                        post("/v1/posts")
                                .with(jwt().jwt(j -> j.subject(FIREBASE_UID)))
                                .header("Idempotency-Key", "idem-1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(createBody)
                )
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.poll").exists())
                .andExpect(jsonPath("$.poll.totalVotes").value(0))
                .andExpect(jsonPath("$.poll.options[0].voteCount").value(0))
                .andReturn();

        String postJson = created.getResponse().getContentAsString();
        long pollId = ((Number) JsonPath.read(postJson, "$.poll.id")).longValue();
        List<Number> optionIds = JsonPath.read(postJson, "$.poll.options[*].id");
        assertThat(optionIds).hasSize(3);
        long opt1 = optionIds.get(0).longValue();
        long opt2 = optionIds.get(1).longValue();
        long opt3 = optionIds.get(2).longValue();

        String voteBody = """
                { "selectedOptionIds": [%d] }
                """.formatted(opt1);
        mockMvc.perform(
                        put("/v1/polls/{pollId}/vote", pollId)
                                .with(jwt().jwt(j -> j.subject(FIREBASE_UID)))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(voteBody)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalVotes").value(1))
                .andExpect(jsonPath("$.viewer.hasVoted").value(true))
                .andExpect(jsonPath("$.viewer.selectedOptionIds").isArray())
                .andExpect(jsonPath("$.viewer.canChangeVote").value(true))
                .andExpect(jsonPath("$.options[0].voteCount").value(1));

        String changeVoteBody = """
                { "selectedOptionIds": [%d] }
                """.formatted(opt2);
        mockMvc.perform(
                        put("/v1/polls/{pollId}/vote", pollId)
                                .with(jwt().jwt(j -> j.subject(FIREBASE_UID)))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(changeVoteBody)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalVotes").value(1))
                .andExpect(jsonPath("$.options[0].voteCount").value(0))
                .andExpect(jsonPath("$.options[1].voteCount").value(1))
                .andExpect(jsonPath("$.options[2].voteCount").value(0))
                .andExpect(jsonPath("$.viewer.selectedOptionIds[0]").value((int) opt2));

        mockMvc.perform(
                        put("/v1/polls/{pollId}/vote", pollId)
                                .with(jwt().jwt(j -> j.subject(FIREBASE_UID)))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(changeVoteBody)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalVotes").value(1))
                .andExpect(jsonPath("$.options[1].voteCount").value(1));
    }

    @Test
    void create_poll_post_allows_empty_caption() throws Exception {
        OffsetDateTime closesAt = OffsetDateTime.now(ZoneOffset.UTC).plusDays(7).withNano(0);
        String createBody = """
                {
                  "communityId": %d,
                  "poll": {
                    "question": "Where should we go?",
                    "options": ["Tacos", "Salad"],
                    "maxSelections": 1,
                    "closesAt": "%s"
                  }
                }
                """.formatted(communityId, closesAt);

        mockMvc.perform(
                        post("/v1/posts")
                                .with(jwt().jwt(j -> j.subject(FIREBASE_UID)))
                                .header("Idempotency-Key", "idem-empty-caption")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(createBody)
                )
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.content").value(""))
                .andExpect(jsonPath("$.poll").exists());
    }

    @Test
    void vote_rejects_too_many_selections_and_unknown_option_ids() throws Exception {
        OffsetDateTime closesAt = OffsetDateTime.now(ZoneOffset.UTC).plusDays(7).withNano(0);
        String createBody = """
                {
                  "content": "Lunch opinions?",
                  "communityId": %d,
                  "poll": {
                    "question": "Where should we go?",
                    "options": ["Tacos", "Salad"],
                    "maxSelections": 1,
                    "closesAt": "%s"
                  }
                }
                """.formatted(communityId, closesAt);

        MvcResult created = mockMvc.perform(
                        post("/v1/posts")
                                .with(jwt().jwt(j -> j.subject(FIREBASE_UID)))
                                .header("Idempotency-Key", "idem-2")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(createBody)
                )
                .andExpect(status().isCreated())
                .andReturn();

        String postJson = created.getResponse().getContentAsString();
        long pollId = ((Number) JsonPath.read(postJson, "$.poll.id")).longValue();
        List<Number> optionIds = JsonPath.read(postJson, "$.poll.options[*].id");
        long opt1 = optionIds.get(0).longValue();
        long opt2 = optionIds.get(1).longValue();

        String tooMany = """
                { "selectedOptionIds": [%d, %d] }
                """.formatted(opt1, opt2);
        mockMvc.perform(
                        put("/v1/polls/{pollId}/vote", pollId)
                                .with(jwt().jwt(j -> j.subject(FIREBASE_UID)))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(tooMany)
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_selection"));

        String unknown = """
                { "selectedOptionIds": [%d] }
                """.formatted(999999);
        mockMvc.perform(
                        put("/v1/polls/{pollId}/vote", pollId)
                                .with(jwt().jwt(j -> j.subject(FIREBASE_UID)))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(unknown)
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_selection"));
    }

    @Test
    void vote_rejects_closed_poll() throws Exception {
        OffsetDateTime closesAt = OffsetDateTime.now(ZoneOffset.UTC).plusDays(1).withNano(0);
        String createBody = """
                {
                  "content": "Lunch opinions?",
                  "communityId": %d,
                  "poll": {
                    "question": "Where should we go?",
                    "options": ["Tacos", "Salad"],
                    "maxSelections": 1,
                    "closesAt": "%s"
                  }
                }
                """.formatted(communityId, closesAt);

        MvcResult created = mockMvc.perform(
                        post("/v1/posts")
                                .with(jwt().jwt(j -> j.subject(FIREBASE_UID)))
                                .header("Idempotency-Key", "idem-3")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(createBody)
                )
                .andExpect(status().isCreated())
                .andReturn();

        String postJson = created.getResponse().getContentAsString();
        long pollId = ((Number) JsonPath.read(postJson, "$.poll.id")).longValue();
        long opt1 = ((Number) JsonPath.read(postJson, "$.poll.options[0].id")).longValue();

        jdbc.update(
                """
                        UPDATE polls
                        SET created_at = now() - interval '2 days',
                            updated_at = now() - interval '2 days'
                        WHERE id = ?
                        """,
                pollId
        );
        jdbc.update(
                "UPDATE polls SET closes_at = now() - interval '1 day' WHERE id = ?",
                pollId
        );

        String voteBody = """
                { "selectedOptionIds": [%d] }
                """.formatted(opt1);
        mockMvc.perform(
                        put("/v1/polls/{pollId}/vote", pollId)
                                .with(jwt().jwt(j -> j.subject(FIREBASE_UID)))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(voteBody)
                )
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("poll_closed"));
    }

    @Test
    void vote_requires_join_for_field_specialization() throws Exception {
        String authorUid = "firebase_author_uid";
        String viewerUid = "firebase_viewer_uid";
        long authorId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,1) RETURNING id",
                Long.class,
                authorUid, "author"
        );
        long viewerId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,1) RETURNING id",
                Long.class,
                viewerUid, "viewer"
        );
        long fieldId = jdbc.queryForObject(
                "INSERT INTO communities(kind, name, specialization_type) VALUES ('specialization', 'Test Field', 'field') RETURNING id",
                Long.class
        );
        jdbc.update(
                "INSERT INTO specialization_joins(user_id, specialization_id) VALUES (?, ?)",
                authorId, fieldId
        );

        OffsetDateTime closesAt = OffsetDateTime.now(ZoneOffset.UTC).plusDays(7).withNano(0);
        String createBody = """
                {
                  "content": "Field poll",
                  "communityId": %d,
                  "poll": {
                    "question": "Pick one",
                    "options": ["A", "B"],
                    "maxSelections": 1,
                    "closesAt": "%s"
                  }
                }
                """.formatted(fieldId, closesAt);

        MvcResult created = mockMvc.perform(
                        post("/v1/posts")
                                .with(jwt().jwt(j -> j.subject(authorUid)))
                                .header("Idempotency-Key", "idem-field-1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(createBody)
                )
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.poll").exists())
                .andReturn();

        String postJson = created.getResponse().getContentAsString();
        long pollId = ((Number) JsonPath.read(postJson, "$.poll.id")).longValue();
        long opt1 = ((Number) JsonPath.read(postJson, "$.poll.options[0].id")).longValue();

        String voteBody = """
                { "selectedOptionIds": [%d] }
                """.formatted(opt1);
        mockMvc.perform(
                        put("/v1/polls/{pollId}/vote", pollId)
                                .with(jwt().jwt(j -> j.subject(viewerUid)))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(voteBody)
                )
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("specialization_not_joined"));

        assertThat(viewerId).isPositive();
    }

    @Test
    void anon_and_regular_votes_are_actor_isolated_for_same_poll() throws Exception {
        OffsetDateTime closesAt = OffsetDateTime.now(ZoneOffset.UTC).plusDays(7).withNano(0);
        String createBody = """
                {
                  "content": "Lunch opinions?",
                  "communityId": %d,
                  "poll": {
                    "question": "Where should we go?",
                    "options": ["Tacos", "Salad"],
                    "maxSelections": 1,
                    "closesAt": "%s"
                  }
                }
                """.formatted(communityId, closesAt);

        MvcResult created = mockMvc.perform(
                        post("/v1/posts")
                                .with(jwt().jwt(j -> j.subject(FIREBASE_UID)))
                                .header("Idempotency-Key", "idem-poll-anon-regular")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(createBody)
                )
                .andExpect(status().isCreated())
                .andReturn();

        String postJson = created.getResponse().getContentAsString();
        long postId = ((Number) JsonPath.read(postJson, "$.id")).longValue();
        long pollId = ((Number) JsonPath.read(postJson, "$.poll.id")).longValue();
        long optA = ((Number) JsonPath.read(postJson, "$.poll.options[0].id")).longValue();
        long optB = ((Number) JsonPath.read(postJson, "$.poll.options[1].id")).longValue();
        long companyId = jdbc.queryForObject("SELECT company_id FROM users WHERE id = ?", Long.class, userId);

        KeyPairGenerator rsaKpg = KeyPairGenerator.getInstance("RSA");
        rsaKpg.initialize(2048);
        KeyPair rsa = rsaKpg.generateKeyPair();
        String kid = "kid-poll-isolation";
        jdbc.update(
                "INSERT INTO anon_issuers(kid, alg, public_key, company_id, scope_kind, scope_id, expires_at) " +
                        "VALUES (?,?,?,?,?,?, now() + interval '1 day')",
                kid, "RSABSSA", rsa.getPublic().getEncoded(), companyId, "community", communityId
        );

        KeyPairGenerator edKpg = KeyPairGenerator.getInstance("Ed25519");
        KeyPair ed = edKpg.generateKeyPair();
        byte[] personaPubkeyRaw = rawEd25519PublicKey(ed.getPublic());
        long anonProfileId = jdbc.queryForObject(
                "INSERT INTO anonymous_profiles(company_id, public_key, handle) VALUES (?,?,?) RETURNING id",
                Long.class, companyId, personaPubkeyRaw, "poll-anon-voter"
        );

        byte[] certMsg = AnonCrypto.certMessage(personaPubkeyRaw);
        byte[] certSig = new BlindRsaSigner((RSAPrivateKey) rsa.getPrivate(), (RSAPublicKey) rsa.getPublic())
                .signBlinded(certMsg);
        String anonCertB64 = Base64.getEncoder().encodeToString(certSig);
        byte[] voteAction = AnonCrypto.actionMessage("poll_vote", pollId);
        String anonSigB64 = Base64.getEncoder().encodeToString(signEd25519(ed.getPrivate(), voteAction));

        String anonVoteBody = """
                {
                  "selectedOptionIds": [%d],
                  "asAnon": true,
                  "anonProfileId": %d,
                  "anonCert": "%s",
                  "anonCertKid": "%s",
                  "anonSig": "%s"
                }
                """.formatted(optA, anonProfileId, anonCertB64, kid, anonSigB64);

        mockMvc.perform(
                        put("/v1/polls/{pollId}/vote", pollId)
                                .header("X-Actor", "anon")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(anonVoteBody)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalVotes").value(1))
                .andExpect(jsonPath("$.viewer.hasVoted").value(true))
                .andExpect(jsonPath("$.viewer.selectedOptionIds[0]").value((int) optA));

        mockMvc.perform(
                        get("/v1/posts/{id}", postId)
                                .with(jwt().jwt(j -> j.subject(FIREBASE_UID)))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.poll.viewer.hasVoted").value(false))
                .andExpect(jsonPath("$.poll.viewer.selectedOptionIds").isEmpty());

        String regularVoteBody = """
                { "selectedOptionIds": [%d] }
                """.formatted(optB);
        mockMvc.perform(
                        put("/v1/polls/{pollId}/vote", pollId)
                                .with(jwt().jwt(j -> j.subject(FIREBASE_UID)))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(regularVoteBody)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalVotes").value(2))
                .andExpect(jsonPath("$.options[0].voteCount").value(1))
                .andExpect(jsonPath("$.options[1].voteCount").value(1))
                .andExpect(jsonPath("$.viewer.hasVoted").value(true))
                .andExpect(jsonPath("$.viewer.selectedOptionIds[0]").value((int) optB));

        mockMvc.perform(
                        put("/v1/polls/{pollId}/vote", pollId)
                                .header("X-Actor", "anon")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(anonVoteBody)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalVotes").value(2))
                .andExpect(jsonPath("$.options[0].voteCount").value(1))
                .andExpect(jsonPath("$.options[1].voteCount").value(1))
                .andExpect(jsonPath("$.viewer.hasVoted").value(true))
                .andExpect(jsonPath("$.viewer.selectedOptionIds[0]").value((int) optA));
    }

    @Test
    void anon_vote_rejects_invalid_proof() throws Exception {
        OffsetDateTime closesAt = OffsetDateTime.now(ZoneOffset.UTC).plusDays(7).withNano(0);
        String createBody = """
                {
                  "content": "Lunch opinions?",
                  "communityId": %d,
                  "poll": {
                    "question": "Where should we go?",
                    "options": ["Tacos", "Salad"],
                    "maxSelections": 1,
                    "closesAt": "%s"
                  }
                }
                """.formatted(communityId, closesAt);

        MvcResult created = mockMvc.perform(
                        post("/v1/posts")
                                .with(jwt().jwt(j -> j.subject(FIREBASE_UID)))
                                .header("Idempotency-Key", "idem-poll-anon-invalid")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(createBody)
                )
                .andExpect(status().isCreated())
                .andReturn();

        String postJson = created.getResponse().getContentAsString();
        long pollId = ((Number) JsonPath.read(postJson, "$.poll.id")).longValue();
        long optA = ((Number) JsonPath.read(postJson, "$.poll.options[0].id")).longValue();
        long companyId = jdbc.queryForObject("SELECT company_id FROM users WHERE id = ?", Long.class, userId);

        KeyPairGenerator rsaKpg = KeyPairGenerator.getInstance("RSA");
        rsaKpg.initialize(2048);
        KeyPair rsa = rsaKpg.generateKeyPair();
        String kid = "kid-poll-invalid";
        jdbc.update(
                "INSERT INTO anon_issuers(kid, alg, public_key, company_id, scope_kind, scope_id, expires_at) " +
                        "VALUES (?,?,?,?,?,?, now() + interval '1 day')",
                kid, "RSABSSA", rsa.getPublic().getEncoded(), companyId, "community", communityId
        );

        KeyPairGenerator edKpg = KeyPairGenerator.getInstance("Ed25519");
        KeyPair ed = edKpg.generateKeyPair();
        byte[] personaPubkeyRaw = rawEd25519PublicKey(ed.getPublic());
        long anonProfileId = jdbc.queryForObject(
                "INSERT INTO anonymous_profiles(company_id, public_key, handle) VALUES (?,?,?) RETURNING id",
                Long.class, companyId, personaPubkeyRaw, "poll-anon-invalid"
        );

        byte[] certMsg = AnonCrypto.certMessage(personaPubkeyRaw);
        byte[] certSig = new BlindRsaSigner((RSAPrivateKey) rsa.getPrivate(), (RSAPublicKey) rsa.getPublic())
                .signBlinded(certMsg);
        String anonCertB64 = Base64.getEncoder().encodeToString(certSig);

        String badProofBody = """
                {
                  "selectedOptionIds": [%d],
                  "asAnon": true,
                  "anonProfileId": %d,
                  "anonCert": "%s",
                  "anonCertKid": "%s",
                  "anonSig": "aW52YWxpZC1zaWc="
                }
                """.formatted(optA, anonProfileId, anonCertB64, kid);

        mockMvc.perform(
                        put("/v1/polls/{pollId}/vote", pollId)
                                .header("X-Actor", "anon")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(badProofBody)
                )
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("invalid_anon_proof"));
    }
}

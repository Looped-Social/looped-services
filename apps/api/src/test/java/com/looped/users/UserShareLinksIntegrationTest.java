package com.looped.users;

import com.looped.auth.TestSecurityConfig;
import com.looped.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "auth.issuer=http://test-issuer",
        "auth.audience=test-app",
        "share.base-url=https://mylooped.app"
})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(TestSecurityConfig.class)
class UserShareLinksIntegrationTest extends PostgresTestBase {

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

    @Test
    void me_share_link_defaults_to_username_slug() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('ShareMe', 'shareme.co') RETURNING id",
                Long.class
        );
        jdbc.update(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?)",
                "uid-share-me",
                "wmillen",
                companyId
        );

        mockMvc.perform(get("/v1/users/me/share-link")
                        .header("Authorization", "Bearer " + token("uid-share-me")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usernameSlug", equalTo("wmillen")))
                .andExpect(jsonPath("$.customSlug", nullValue()))
                .andExpect(jsonPath("$.activeSlug", equalTo("wmillen")))
                .andExpect(jsonPath("$.canonicalUrl", equalTo("https://mylooped.app/u/wmillen")));
    }

    @Test
    void set_custom_slug_makes_both_custom_and_username_resolve() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('ShareResolve', 'shareresolve.co') RETURNING id",
                Long.class
        );
        long userId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id, display_name) VALUES (?,?,?,?) RETURNING id",
                Long.class,
                "uid-share-resolve",
                "wmillen",
                companyId,
                "William"
        );

        mockMvc.perform(put("/v1/users/me/share-link")
                        .header("Authorization", "Bearer " + token("uid-share-resolve"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customSlug\":\"william\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usernameSlug", equalTo("wmillen")))
                .andExpect(jsonPath("$.customSlug", equalTo("william")))
                .andExpect(jsonPath("$.activeSlug", equalTo("william")))
                .andExpect(jsonPath("$.canonicalUrl", equalTo("https://mylooped.app/u/william")));

        mockMvc.perform(get("/v1/public/profiles/william"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", equalTo((int) userId)));

        mockMvc.perform(get("/v1/public/profiles/wmillen"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", equalTo((int) userId)));
    }

    @Test
    void slug_availability_reports_owned_reserved_and_taken() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('ShareAvail', 'shareavail.co') RETURNING id",
                Long.class
        );
        jdbc.update("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?)",
                "uid-share-ava-me", "mara", companyId);
        jdbc.update("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?)",
                "uid-share-ava-other", "taylor", companyId);

        mockMvc.perform(put("/v1/users/me/share-link")
                        .header("Authorization", "Bearer " + token("uid-share-ava-other"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customSlug\":\"cleanname\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/v1/users/slug/availability?slug=@Mara")
                        .header("Authorization", "Bearer " + token("uid-share-ava-me")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug", equalTo("mara")))
                .andExpect(jsonPath("$.available", equalTo(true)))
                .andExpect(jsonPath("$.ownedByMe", equalTo(true)))
                .andExpect(jsonPath("$.reserved", equalTo(false)));

        mockMvc.perform(get("/v1/users/slug/availability?slug=privacy")
                        .header("Authorization", "Bearer " + token("uid-share-ava-me")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug", equalTo("privacy")))
                .andExpect(jsonPath("$.available", equalTo(false)))
                .andExpect(jsonPath("$.ownedByMe", equalTo(false)))
                .andExpect(jsonPath("$.reserved", equalTo(true)));

        mockMvc.perform(get("/v1/users/slug/availability?slug=cleanname")
                        .header("Authorization", "Bearer " + token("uid-share-ava-me")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug", equalTo("cleanname")))
                .andExpect(jsonPath("$.available", equalTo(false)))
                .andExpect(jsonPath("$.ownedByMe", equalTo(false)))
                .andExpect(jsonPath("$.reserved", equalTo(false)));
    }

    @Test
    void clear_custom_slug_reverts_canonical_and_custom_slug_stops_resolving() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('ShareClear', 'shareclear.co') RETURNING id",
                Long.class
        );
        long userId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id, display_name) VALUES (?,?,?,?) RETURNING id",
                Long.class,
                "uid-share-clear",
                "clearname",
                companyId,
                "Clear Name"
        );
        String auth = "Bearer " + token("uid-share-clear");

        mockMvc.perform(put("/v1/users/me/share-link")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customSlug\":\"cleanname\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(put("/v1/users/me/share-link")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customSlug\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usernameSlug", equalTo("clearname")))
                .andExpect(jsonPath("$.customSlug", nullValue()))
                .andExpect(jsonPath("$.activeSlug", equalTo("clearname")))
                .andExpect(jsonPath("$.canonicalUrl", equalTo("https://mylooped.app/u/clearname")));

        mockMvc.perform(get("/v1/public/profiles/clearname"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", equalTo((int) userId)));

        mockMvc.perform(get("/v1/public/profiles/cleanname"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", equalTo("profile_not_found")));
    }

    @Test
    void identity_username_change_updates_reserved_slug_and_old_username_stops_resolving() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('ShareIdentity', 'shareidentity.co') RETURNING id",
                Long.class
        );
        long userId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id, first_name, last_name, date_of_birth, display_name) VALUES (?,?,?,?,?,?,?) RETURNING id",
                Long.class,
                "uid-share-identity",
                "oldname",
                companyId,
                "Old",
                "Name",
                java.sql.Date.valueOf("1990-01-01"),
                "Old Name"
        );
        String auth = "Bearer " + token("uid-share-identity");

        mockMvc.perform(put("/v1/users/me/share-link")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customSlug\":\"cleanname\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(put("/v1/users/me/identity")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username":"newname",
                                  "firstName":"New",
                                  "lastName":"Name",
                                  "dateOfBirth":"1990-01-01"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.handle", equalTo("newname")));

        mockMvc.perform(get("/v1/public/profiles/newname"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", equalTo((int) userId)));

        mockMvc.perform(get("/v1/public/profiles/oldname"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", equalTo("profile_not_found")));

        mockMvc.perform(get("/v1/public/profiles/cleanname"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", equalTo((int) userId)));
    }

    @Test
    void set_custom_slug_returns_expected_slug_errors() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('ShareErrors', 'shareerrors.co') RETURNING id",
                Long.class
        );
        jdbc.update("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?)",
                "uid-share-errors-me", "myname", companyId);
        jdbc.update("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?)",
                "uid-share-errors-other", "othername", companyId);

        String meAuth = "Bearer " + token("uid-share-errors-me");
        String otherAuth = "Bearer " + token("uid-share-errors-other");

        mockMvc.perform(put("/v1/users/me/share-link")
                        .header("Authorization", otherAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customSlug\":\"taken_slug\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(put("/v1/users/me/share-link")
                        .header("Authorization", meAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customSlug\":\"ab\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error", equalTo("slug_invalid")));

        mockMvc.perform(put("/v1/users/me/share-link")
                        .header("Authorization", meAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customSlug\":\"app\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error", equalTo("slug_reserved")));

        mockMvc.perform(put("/v1/users/me/share-link")
                        .header("Authorization", meAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customSlug\":\"taken_slug\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", equalTo("slug_taken")));

        mockMvc.perform(put("/v1/users/me/share-link")
                        .header("Authorization", meAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customSlug\":\"myname\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error", equalTo("slug_not_actionable")));
    }
}

package com.looped.admin;

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

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.equalTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "auth.issuer=http://test-issuer",
        "auth.audience=test-app"
})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(TestSecurityConfig.class)
class AdminCommunitiesEditIntegrationTest extends PostgresTestBase {

    @Autowired MockMvc mockMvc;
    @Autowired JwtEncoder jwtEncoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired AdminUsersRepository admins;

    private String token(String sub, String email) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("http://test-issuer")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(3600))
                .subject(sub)
                .audience(List.of("test-app"))
                .claim("email", email)
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    @Test
    void admin_can_rename_company_and_specialization() throws Exception {
        admins.insert(null, "admin-community@looped.com", "admin", "active",
                List.of(AdminPermissions.CREATE_COMMUNITY));
        String auth = "Bearer " + token("admin-community", "admin-community@looped.com");

        long companyId = jdbc.queryForObject(
                "INSERT INTO communities(kind, name) VALUES ('company', 'OldCo') RETURNING id",
                Long.class
        );
        long fieldId = jdbc.queryForObject(
                "INSERT INTO communities(kind, specialization_type, name) VALUES ('specialization', 'field', 'Old Field') RETURNING id",
                Long.class
        );

        mockMvc.perform(patch("/v1/admin/communities/" + companyId)
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"NewCo\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", equalTo((int) companyId)))
                .andExpect(jsonPath("$.name", equalTo("NewCo")));

        mockMvc.perform(patch("/v1/admin/communities/" + fieldId)
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"New Field\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", equalTo((int) fieldId)))
                .andExpect(jsonPath("$.name", equalTo("New Field")));
    }

    @Test
    void change_kind_allows_company_to_school_and_field_to_major() throws Exception {
        admins.insert(null, "admin-kind@looped.com", "admin", "active",
                List.of(AdminPermissions.CREATE_COMMUNITY));
        String auth = "Bearer " + token("admin-kind", "admin-kind@looped.com");

        long communityId = jdbc.queryForObject(
                "INSERT INTO communities(kind, name) VALUES ('company', 'Acme') RETURNING id",
                Long.class
        );
        long fieldId = jdbc.queryForObject(
                "INSERT INTO communities(kind, specialization_type, name) VALUES ('specialization', 'field', 'Retail') RETURNING id",
                Long.class
        );

        mockMvc.perform(post("/v1/admin/communities/" + communityId + "/change-kind")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"school\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", equalTo((int) communityId)))
                .andExpect(jsonPath("$.kind", equalTo("school")))
                .andExpect(jsonPath("$.specialization_type").doesNotExist());

        mockMvc.perform(post("/v1/admin/communities/" + fieldId + "/change-kind")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"major\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", equalTo((int) fieldId)))
                .andExpect(jsonPath("$.kind", equalTo("specialization")))
                .andExpect(jsonPath("$.specialization_type", equalTo("major")));
    }

    @Test
    void change_kind_rejects_specialization_to_company() throws Exception {
        admins.insert(null, "admin-kind2@looped.com", "admin", "active",
                List.of(AdminPermissions.CREATE_COMMUNITY));
        String auth = "Bearer " + token("admin-kind2", "admin-kind2@looped.com");

        long fieldId = jdbc.queryForObject(
                "INSERT INTO communities(kind, specialization_type, name) VALUES ('specialization', 'field', 'Retail') RETURNING id",
                Long.class
        );

        mockMvc.perform(post("/v1/admin/communities/" + fieldId + "/change-kind")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"company\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error", equalTo("invalid_transition")));
    }
}


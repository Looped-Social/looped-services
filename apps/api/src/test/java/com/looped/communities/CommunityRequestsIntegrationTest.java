package com.looped.communities;

import com.looped.auth.TestSecurityConfig;
import com.looped.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
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
class CommunityRequestsIntegrationTest extends PostgresTestBase {

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
    void create_and_list_request_normalizes_kind() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('Acme', 'acme.com') RETURNING id",
                Long.class);
        jdbc.update("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?)",
                "uid-community-request", "riley", companyId);

        String auth = "Bearer " + token("uid-community-request");
        String body = """
                {
                  "type": "company",
                  "name": "Design",
                  "about": "For designers"
                }
                """;

        mockMvc.perform(post("/v1/community-requests")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", equalTo("pending")));

        mockMvc.perform(get("/v1/community-requests")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].kind", equalTo("company")))
                .andExpect(jsonPath("$.items[0].name", equalTo("Design")))
                .andExpect(jsonPath("$.items[0].description", equalTo("For designers")))
                .andExpect(jsonPath("$.items[0].status", equalTo("pending")));
    }

    @Test
    void create_accepts_workplace_alias_as_company() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('Acme Two', 'acmetwo.com') RETURNING id",
                Long.class);
        jdbc.update("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?)",
                "uid-workplace-alias", "casey", companyId);

        String auth = "Bearer " + token("uid-workplace-alias");
        String body = """
                {
                  "type": "workplace",
                  "name": "Product",
                  "about": "Product folks"
                }
                """;

        mockMvc.perform(post("/v1/community-requests")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", equalTo("pending")));

        mockMvc.perform(get("/v1/community-requests")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].kind", equalTo("company")))
                .andExpect(jsonPath("$.items[0].name", equalTo("Product")));
    }

    @Test
    void create_with_contact_email_and_notify_persists_fields() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('Acme Notify', 'acmenotify.com') RETURNING id",
                Long.class);
        jdbc.update("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?)",
                "uid-community-request-notify", "drew", companyId);

        String auth = "Bearer " + token("uid-community-request-notify");
        String body = """
                {
                  "type": "company",
                  "name": "Carolina Product",
                  "about": "For product folks",
                  "contactEmail": "Drew+notify@Example.com",
                  "notifyWhenAvailable": true
                }
                """;

        mockMvc.perform(post("/v1/community-requests")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", equalTo("pending")));

        mockMvc.perform(get("/v1/community-requests")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].contact_email", equalTo("drew+notify@example.com")))
                .andExpect(jsonPath("$.items[0].notify_when_available", equalTo(true)));
    }

    @Test
    void create_parses_legacy_contact_email_from_about_when_contact_email_missing() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('Acme Legacy', 'acmelegacy.com') RETURNING id",
                Long.class);
        jdbc.update("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?)",
                "uid-community-request-legacy", "jules", companyId);

        String auth = "Bearer " + token("uid-community-request-legacy");
        String body = """
                {
                  "type": "field",
                  "name": "UNC Chapel Hill",
                  "about": "Need this for alumni\\nPreferred contact email: UNC.Requests@Example.edu",
                  "notify_when_available": true
                }
                """;

        mockMvc.perform(post("/v1/community-requests")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/v1/community-requests")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].kind", equalTo("field")))
                .andExpect(jsonPath("$.items[0].description", equalTo("Need this for alumni")))
                .andExpect(jsonPath("$.items[0].contact_email", equalTo("unc.requests@example.edu")))
                .andExpect(jsonPath("$.items[0].notify_when_available", equalTo(true)));
    }
}

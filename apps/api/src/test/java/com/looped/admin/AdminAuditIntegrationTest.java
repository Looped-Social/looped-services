package com.looped.admin;

import com.looped.auth.TestSecurityConfig;
import com.looped.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "auth.issuer=http://test-issuer",
        "auth.audience=test-app"
})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(TestSecurityConfig.class)
class AdminAuditIntegrationTest extends PostgresTestBase {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JwtEncoder jwtEncoder;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AdminUsersRepository admins;

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
    void audit_list_includes_actor_identity_fields() throws Exception {
        admins.insert(null, "viewer@looped.com", "admin", "active",
                List.of(AdminPermissions.MANAGE_ADMINS));
        String adminAuth = "Bearer " + token("audit-viewer", "viewer@looped.com");

        long actorId = admins.insert("actor-firebase", "actor@looped.com", "moderator", "active",
                List.of(AdminPermissions.VIEW_REPORTS));
        jdbc.update(
                "INSERT INTO admin_audit_log(actor_admin_id, action, target_type, target_id, meta) VALUES (?,?,?,?,?)",
                actorId, "remove_post", "post", 42L, "{\"reason\":\"spam\"}"
        );

        mockMvc.perform(get("/v1/admin/audit")
                        .header("Authorization", adminAuth)
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].actor_admin_id", equalTo((int) actorId)))
                .andExpect(jsonPath("$.items[0].actor_email", equalTo("actor@looped.com")))
                .andExpect(jsonPath("$.items[0].action", equalTo("remove_post")))
                .andExpect(jsonPath("$.items[0].target_type", equalTo("post")))
                .andExpect(jsonPath("$.items[0].target_id", equalTo(42)));
    }
}

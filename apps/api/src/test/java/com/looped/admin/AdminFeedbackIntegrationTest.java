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
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
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
class AdminFeedbackIntegrationTest extends PostgresTestBase {

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
    void list_feedback_filters_by_status() throws Exception {
        admins.insert(null, "feedback@looped.com", "admin", "active",
                List.of(AdminPermissions.VIEW_FEEDBACK));
        jdbc.update("INSERT INTO feedback(title, message, status) VALUES (?,?,?)",
                "Open feedback", "Message", "open");
        jdbc.update("INSERT INTO feedback(title, message, status) VALUES (?,?,?)",
                "Closed feedback", "Message", "closed");

        String auth = "Bearer " + token("admin-feedback", "feedback@looped.com");

        mockMvc.perform(get("/v1/admin/feedback?status=open")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].title", equalTo("Open feedback")))
                .andExpect(jsonPath("$.items[0].status", equalTo("open")));
    }

    @Test
    void mark_feedback_seen_updates_status_and_note() throws Exception {
        long adminId = admins.insert(null, "feedback-seen@looped.com", "admin", "active",
                List.of(AdminPermissions.VIEW_FEEDBACK));
        long feedbackId = jdbc.queryForObject(
                "INSERT INTO feedback(title, message, status) VALUES (?,?,?) RETURNING id",
                Long.class,
                "Need help",
                "Please contact me",
                "open"
        );

        String auth = "Bearer " + token("admin-feedback-seen", "feedback-seen@looped.com");

        mockMvc.perform(post("/v1/admin/feedback/" + feedbackId + "/seen")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "note": "triaged"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", equalTo("seen")));

        String statusValue = jdbc.queryForObject("SELECT status FROM feedback WHERE id = ?", String.class, feedbackId);
        String noteValue = jdbc.queryForObject("SELECT reviewed_note FROM feedback WHERE id = ?", String.class, feedbackId);
        Long reviewedBy = jdbc.queryForObject("SELECT reviewed_by FROM feedback WHERE id = ?", Long.class, feedbackId);

        org.junit.jupiter.api.Assertions.assertEquals("seen", statusValue);
        org.junit.jupiter.api.Assertions.assertEquals("triaged", noteValue);
        org.junit.jupiter.api.Assertions.assertEquals(adminId, reviewedBy);
    }

    @Test
    void reply_feedback_returns_service_unavailable_when_email_not_configured() throws Exception {
        admins.insert(null, "feedback-reply@looped.com", "admin", "active",
                List.of(AdminPermissions.VIEW_FEEDBACK));
        long feedbackId = jdbc.queryForObject(
                "INSERT INTO feedback(title, message, status, email) VALUES (?,?,?,?) RETURNING id",
                Long.class,
                "Question",
                "How does X work?",
                "open",
                "person@example.com"
        );

        String auth = "Bearer " + token("admin-feedback-reply", "feedback-reply@looped.com");

        mockMvc.perform(post("/v1/admin/feedback/" + feedbackId + "/reply")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "subject": "We got your feedback",
                                  "message": "Thanks for sending this.",
                                  "note": "responded via email"
                                }
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error", equalTo("email_not_configured")));
    }
}

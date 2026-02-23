package com.looped.admin;

import com.looped.auth.TestSecurityConfig;
import com.looped.communities.CommunityRequestAvailabilityNotifier;
import com.looped.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.SendEmailRequest;
import software.amazon.awssdk.services.ses.model.SendEmailResponse;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.equalTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "auth.issuer=http://test-issuer",
        "auth.audience=test-app",
        "email.enabled=true",
        "email.from=dev@mylooped.app",
        "email.community-request-from=no-reply@mylooped.app",
        "share.base-url=https://mylooped.app"
})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import({TestSecurityConfig.class, AdminCommunityRequestsIntegrationTest.SesStubConfig.class})
class AdminCommunityRequestsIntegrationTest extends PostgresTestBase {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JwtEncoder jwtEncoder;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AdminUsersRepository admins;
    @Autowired
    CommunityRequestAvailabilityNotifier availabilityNotifier;
    @Autowired
    AtomicInteger sesSendCount;

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
    void approve_request_creates_community() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('Acme', 'acme.com') RETURNING id",
                Long.class);
        long userId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-requester", "requester", companyId);
        long requestId = jdbc.queryForObject(
                "INSERT INTO community_requests(user_id, kind, name, description) VALUES (?,?,?,?) RETURNING id",
                Long.class, userId, "company", "Design", "For designers");

        admins.insert(null, "admin@looped.com", "owner", "active",
                List.of(AdminPermissions.CREATE_COMMUNITY));
        String auth = "Bearer " + token("admin-uid", "admin@looped.com");

        mockMvc.perform(post("/v1/admin/community-requests/" + requestId + "/approve")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", equalTo("approved")))
                .andExpect(jsonPath("$.community_id").isNumber());

        Integer communities = jdbc.queryForObject(
                "SELECT COUNT(*) FROM communities WHERE kind = 'company' AND name = 'Design'",
                Integer.class
        );
        String status = jdbc.queryForObject(
                "SELECT status FROM community_requests WHERE id = ?",
                String.class, requestId
        );
        org.junit.jupiter.api.Assertions.assertEquals(1, communities.intValue());
        org.junit.jupiter.api.Assertions.assertEquals("approved", status);
    }

    @Test
    void approve_request_notifies_matching_pending_requests_and_prevents_duplicate_sends() throws Exception {
        int sendsBefore = sesSendCount.get();
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('Notify Acme', 'notifyacme.com') RETURNING id",
                Long.class);
        long userA = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-req-a", "reqa", companyId);
        long userB = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-req-b", "reqb", companyId);

        long requestId = jdbc.queryForObject(
                "INSERT INTO community_requests(user_id, kind, name, description, contact_email, notify_when_available) " +
                        "VALUES (?,?,?,?,?,true) RETURNING id",
                Long.class, userA, "company", "University of North Carolina", "First", "first@example.com");
        long similarRequestId = jdbc.queryForObject(
                "INSERT INTO community_requests(user_id, kind, name, description, contact_email, notify_when_available) " +
                        "VALUES (?,?,?,?,?,true) RETURNING id",
                Long.class, userB, "company", "UNC", "Second", "second@example.com");

        admins.insert(null, "admin@looped.com", "owner", "active", List.of(AdminPermissions.CREATE_COMMUNITY));
        String auth = "Bearer " + token("admin-uid", "admin@looped.com");

        mockMvc.perform(post("/v1/admin/community-requests/" + requestId + "/approve")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", equalTo("approved")))
                .andExpect(jsonPath("$.matched_requests", equalTo(2)))
                .andExpect(jsonPath("$.notified_requests", equalTo(2)));

        Long communityId = jdbc.queryForObject(
                "SELECT community_id FROM community_requests WHERE id = ?",
                Long.class, requestId
        );
        Integer notifiedA = jdbc.queryForObject(
                "SELECT COUNT(*) FROM community_requests WHERE id = ? AND notified_at IS NOT NULL AND notified_community_id = ?",
                Integer.class, requestId, communityId
        );
        Integer notifiedB = jdbc.queryForObject(
                "SELECT COUNT(*) FROM community_requests WHERE id = ? AND notified_at IS NOT NULL AND notified_community_id = ?",
                Integer.class, similarRequestId, communityId
        );
        org.junit.jupiter.api.Assertions.assertEquals(1, notifiedA.intValue());
        org.junit.jupiter.api.Assertions.assertEquals(1, notifiedB.intValue());
        org.junit.jupiter.api.Assertions.assertEquals(sendsBefore + 2, sesSendCount.get());

        var secondRun = availabilityNotifier.notifyForCreatedCommunity("company", "University of North Carolina", communityId);
        org.junit.jupiter.api.Assertions.assertEquals(0, secondRun.matchedRequests());
        org.junit.jupiter.api.Assertions.assertEquals(0, secondRun.sentEmails());
        org.junit.jupiter.api.Assertions.assertEquals(sendsBefore + 2, sesSendCount.get());
    }

    @Test
    void reject_request_sends_rejection_email_to_request_contact() throws Exception {
        int sendsBefore = sesSendCount.get();
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('Reject Acme', 'rejectacme.com') RETURNING id",
                Long.class);
        long userId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-reject-req", "rejectreq", companyId);
        long requestId = jdbc.queryForObject(
                "INSERT INTO community_requests(user_id, kind, name, description, contact_email, notify_when_available) " +
                        "VALUES (?,?,?,?,?,true) RETURNING id",
                Long.class, userId, "company", "Rejected Co", "Needs review", "reject@example.com");

        admins.insert(null, "admin@looped.com", "owner", "active", List.of(AdminPermissions.CREATE_COMMUNITY));
        String auth = "Bearer " + token("admin-uid", "admin@looped.com");

        mockMvc.perform(post("/v1/admin/community-requests/" + requestId + "/reject")
                        .header("Authorization", auth)
                        .contentType("application/json")
                        .content("{\"reason\":\"Needs clearer request details\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", equalTo("rejected")))
                .andExpect(jsonPath("$.notified_requester", equalTo(true)));

        String status = jdbc.queryForObject(
                "SELECT status FROM community_requests WHERE id = ?",
                String.class, requestId
        );
        org.junit.jupiter.api.Assertions.assertEquals("rejected", status);
        org.junit.jupiter.api.Assertions.assertEquals(sendsBefore + 1, sesSendCount.get());
    }

    @TestConfiguration
    static class SesStubConfig {
        @Bean
        @Primary
        AtomicInteger sesSendCount() {
            return new AtomicInteger();
        }

        @Bean
        @Primary
        SesClient sesClientStub(AtomicInteger sesSendCount) {
            SesClient ses = mock(SesClient.class);
            when(ses.sendEmail(any(SendEmailRequest.class))).thenAnswer(inv -> {
                int n = sesSendCount.incrementAndGet();
                return SendEmailResponse.builder().messageId("msg-" + n).build();
            });
            return ses;
        }
    }
}

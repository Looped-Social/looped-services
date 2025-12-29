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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.equalTo;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "auth.issuer=http://test-issuer",
        "auth.audience=test-app"
})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(TestSecurityConfig.class)
class AdminCommunityImportIntegrationTest extends PostgresTestBase {

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
    void import_csv_creates_companies_sectors_and_domains() throws Exception {
        admins.insert(null, "admin@looped.com", "owner", "active",
                List.of(AdminPermissions.CREATE_COMMUNITY));
        String auth = "Bearer " + token("admin-uid", "admin@looped.com");

        String csv = """
                community_type,display_name,sector,authorized_domains,description,website,rank,source
                company,Walmart,Retailing,walmart.com,Verified community for Walmart,,,top_companies
                company,Target,Retailing,target.com,Verified community for Target,,,top_companies
                """;

        var request = MockMvcRequestBuilders.multipart("/v1/admin/communities/import-csv")
                .file("file", csv.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .header("Authorization", auth);

        mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows_total", equalTo(2)))
                .andExpect(jsonPath("$.communities_created", equalTo(2)))
                .andExpect(jsonPath("$.sectors_created", equalTo(1)))
                .andExpect(jsonPath("$.links_created", equalTo(2)))
                .andExpect(jsonPath("$.domains_added", equalTo(2)));

        Integer companies = jdbc.queryForObject(
                "SELECT COUNT(*) FROM communities WHERE kind = 'company'",
                Integer.class
        );
        Integer sectors = jdbc.queryForObject(
                "SELECT COUNT(*) FROM communities WHERE kind = 'sector'",
                Integer.class
        );
        Integer links = jdbc.queryForObject(
                "SELECT COUNT(*) FROM community_sector_links",
                Integer.class
        );
        Integer domains = jdbc.queryForObject(
                "SELECT COUNT(*) FROM community_domains",
                Integer.class
        );

        org.junit.jupiter.api.Assertions.assertEquals(2, companies.intValue());
        org.junit.jupiter.api.Assertions.assertEquals(1, sectors.intValue());
        org.junit.jupiter.api.Assertions.assertEquals(2, links.intValue());
        org.junit.jupiter.api.Assertions.assertEquals(2, domains.intValue());
    }
}

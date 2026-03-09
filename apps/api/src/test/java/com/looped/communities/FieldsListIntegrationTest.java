package com.looped.communities;

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
class FieldsListIntegrationTest extends PostgresTestBase {

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
    void fields_endpoint_includes_additive_branding_urls() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('FieldsCo', 'fields.co') RETURNING id",
                Long.class
        );
        jdbc.update("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?)",
                "uid-fields-list", "fieldlist", companyId);

        jdbc.update(
                "INSERT INTO communities(kind, specialization_type, name, short_name, icon_kind, icon_value, specialization_icon_image_url, specialization_banner_image_url) " +
                        "VALUES ('specialization','field','Computer Science','cs','emoji',?, ?, ?)",
                "\uD83D\uDCBB",
                "https://cdn.example.com/specializations/icon-cs.png",
                "https://cdn.example.com/specializations/banner-cs.png"
        );

        mockMvc.perform(get("/v1/fields")
                        .header("Authorization", "Bearer " + token("uid-fields-list")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name", equalTo("Computer Science")))
                .andExpect(jsonPath("$.items[0].icon.kind", equalTo("emoji")))
                .andExpect(jsonPath("$.items[0].icon.value", equalTo("\uD83D\uDCBB")))
                .andExpect(jsonPath("$.items[0].iconImageUrl", equalTo("https://cdn.example.com/specializations/icon-cs.png")))
                .andExpect(jsonPath("$.items[0].icon_image_url", equalTo("https://cdn.example.com/specializations/icon-cs.png")))
                .andExpect(jsonPath("$.items[0].bannerImageUrl", equalTo("https://cdn.example.com/specializations/banner-cs.png")))
                .andExpect(jsonPath("$.items[0].banner_image_url", equalTo("https://cdn.example.com/specializations/banner-cs.png")));
    }
}

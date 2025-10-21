package com.looped.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "auth.issuer=https://securetoken.google.com/demo-project",
        "auth.audience=demo-project",
        "auth.jwksUri=https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com"
})
@AutoConfigureMockMvc
class AuthSecurityTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void health_is_public() throws Exception {
        mockMvc.perform(get("/health")).andExpect(status().isOk());
    }

    @Test
    void me_requires_auth() throws Exception {
        mockMvc.perform(get("/v1/me")).andExpect(status().isUnauthorized());
    }
}


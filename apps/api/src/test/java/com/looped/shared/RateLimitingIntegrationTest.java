package com.looped.shared;

import com.looped.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "rateLimit.enabled=true",
        "rateLimit.perIp.windowSeconds=60",
        "rateLimit.perIp.maxRequests=3",
        "rateLimit.perUser.windowSeconds=60",
        "rateLimit.perUser.maxRequests=1000"
})
@AutoConfigureMockMvc
class RateLimitingIntegrationTest extends PostgresTestBase {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProps(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.url", () -> "redis://" + redis.getHost() + ":" + redis.getMappedPort(6379));
    }

    @Autowired
    MockMvc mockMvc;

    @Test
    void limits_by_ip() throws Exception {
        mockMvc.perform(get("/health")).andExpect(status().isOk());
        mockMvc.perform(get("/health")).andExpect(status().isOk());
        mockMvc.perform(get("/health")).andExpect(status().isOk());
        mockMvc.perform(get("/health")).andExpect(status().isTooManyRequests());
    }
}

package com.looped.moderation;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class OpenAiModerationClientTest {

    @Test
    void shouldQuarantine_blocks_only_configured_categories() {
        ModerationProperties props = new ModerationProperties();
        props.setOpenaiEnabled(true);
        props.setOpenaiApiKey("test");
        props.setOpenaiCategoryBlocklist("hate,sexual");
        OpenAiModerationClient client = new OpenAiModerationClient(props);

        var res = new OpenAiModerationClient.Result(true, true, Set.of("violence"), false);
        assertThat(client.shouldQuarantine(res)).isFalse();

        var res2 = new OpenAiModerationClient.Result(true, true, Set.of("hate"), false);
        assertThat(client.shouldQuarantine(res2)).isTrue();
    }

    @Test
    void shouldQuarantine_if_flagged_and_no_blocklist_configured() {
        ModerationProperties props = new ModerationProperties();
        props.setOpenaiEnabled(true);
        props.setOpenaiApiKey("test");
        props.setOpenaiCategoryBlocklist("");
        OpenAiModerationClient client = new OpenAiModerationClient(props);

        var res = new OpenAiModerationClient.Result(true, true, Set.of("anything"), false);
        assertThat(client.shouldQuarantine(res)).isTrue();
    }

    @Test
    void moderateText_skips_when_budget_lockout_is_active() {
        ModerationProperties props = new ModerationProperties();
        props.setOpenaiEnabled(true);
        props.setOpenaiApiKey("test");
        props.setOpenaiDailyRequestBudget(9000);
        props.setOpenaiBudgetRedisPrefix("test:openai");

        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        String utcDay = DateTimeFormatter.BASIC_ISO_DATE.format(LocalDate.now(ZoneOffset.UTC));
        when(redis.hasKey("test:openai:lockout:" + utcDay)).thenReturn(true);

        OpenAiModerationClient client = new OpenAiModerationClient(props, redis);
        var result = client.moderateText("hello");

        assertThat(result.disabled()).isTrue();
        verify(redis, never()).opsForValue();
    }

    @Test
    void moderateText_skips_when_daily_budget_exhausted() {
        ModerationProperties props = new ModerationProperties();
        props.setOpenaiEnabled(true);
        props.setOpenaiApiKey("test");
        props.setOpenaiDailyRequestBudget(3);
        props.setOpenaiBudgetRedisPrefix("test:openai");

        String utcDay = DateTimeFormatter.BASIC_ISO_DATE.format(LocalDate.now(ZoneOffset.UTC));
        String countKey = "test:openai:count:" + utcDay;
        String lockKey = "test:openai:lockout:" + utcDay;

        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.hasKey(lockKey)).thenReturn(false);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.increment(countKey)).thenReturn(4L);

        OpenAiModerationClient client = new OpenAiModerationClient(props, redis);
        var result = client.moderateText("hello");

        assertThat(result.disabled()).isTrue();
        verify(ops).set(eq(lockKey), eq("daily_budget_exhausted"), any(Duration.class));
    }
}

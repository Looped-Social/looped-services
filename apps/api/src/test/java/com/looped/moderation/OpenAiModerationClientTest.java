package com.looped.moderation;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

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
}


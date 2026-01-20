package com.looped.moderation;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import static org.assertj.core.api.Assertions.assertThat;

class BlocklistServiceTest {

    @Test
    void matches_token_case_insensitive() {
        ModerationProperties props = new ModerationProperties();
        props.setBlocklistResource(null);
        props.setBlocklistTerms("slur");
        BlocklistService svc = new BlocklistService(props, new DefaultResourceLoader(), null);

        assertThat(svc.match("This contains a SLUR.")).isNotNull();
        assertThat(svc.match("no match here")).isNull();
    }

    @Test
    void matches_collapsed_terms_across_separators() {
        ModerationProperties props = new ModerationProperties();
        props.setBlocklistResource(null);
        props.setBlocklistTerms("badword");
        BlocklistService svc = new BlocklistService(props, new DefaultResourceLoader(), null);

        assertThat(svc.match("b a d w o r d")).isNotNull();
        assertThat(svc.match("b-a-d_w-o-r-d")).isNotNull();
    }

    @Test
    void ignores_blank_and_comment_lines() {
        ModerationProperties props = new ModerationProperties();
        props.setBlocklistResource(null);
        props.setBlocklistTerms("  \n# comment\n\n");
        BlocklistService svc = new BlocklistService(props, new DefaultResourceLoader(), null);

        assertThat(svc.match("anything")).isNull();
    }
}

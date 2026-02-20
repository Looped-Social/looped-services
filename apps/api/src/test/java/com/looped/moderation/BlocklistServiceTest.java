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

        var match = svc.match("This contains a SLUR.");
        assertThat(match).isNotNull();
        assertThat(match.method()).isEqualTo("word-boundary");
        assertThat(match.matchedTerm()).isEqualTo("slur");
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

    @Test
    void does_not_match_substring_inside_larger_word() {
        ModerationProperties props = new ModerationProperties();
        props.setBlocklistResource(null);
        props.setBlocklistTerms("ass");
        BlocklistService svc = new BlocklistService(props, new DefaultResourceLoader(), null);

        assertThat(svc.match("fifth class presentation this semester")).isNull();
    }

    @Test
    void does_not_match_standalone_token_from_multi_word_term() {
        ModerationProperties props = new ModerationProperties();
        props.setBlocklistResource(null);
        props.setBlocklistTerms("hand job");
        BlocklistService svc = new BlocklistService(props, new DefaultResourceLoader(), null);

        assertThat(svc.match("great job on the presentation")).isNull();
        assertThat(svc.match("that was a hand job")).isNotNull();
    }

    @Test
    void does_not_match_split_symbol_term_as_single_char_token() {
        ModerationProperties props = new ModerationProperties();
        props.setBlocklistResource(null);
        props.setBlocklistTerms("x$$a");
        BlocklistService svc = new BlocklistService(props, new DefaultResourceLoader(), null);

        assertThat(svc.match("this has a lot of words")).isNull();
        assertThat(svc.match("x$$a")).isNotNull();
    }
}

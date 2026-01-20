package com.looped.moderation;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import static org.assertj.core.api.Assertions.assertThat;

class ContentModerationServiceTest {

    @Test
    void quarantines_on_blocklist_match() {
        ModerationProperties props = new ModerationProperties();
        props.setEnabled(true);
        props.setOpenaiEnabled(false);
        props.setBlocklistResource(null);
        props.setBlocklistTerms("badword");

        BlocklistService blocklist = new BlocklistService(props, new DefaultResourceLoader(), null);
        OpenAiModerationClient openai = new OpenAiModerationClient(props);
        ContentModerationService svc = new ContentModerationService(props, blocklist, openai);

        var d = svc.evaluateText("this has BADWORD in it");
        assertThat(d.action()).isEqualTo(ContentModerationService.Action.QUARANTINE);
        assertThat(d.source()).isEqualTo("blocklist");
    }

    @Test
    void disabled_allows() {
        ModerationProperties props = new ModerationProperties();
        props.setEnabled(false);
        props.setBlocklistResource(null);
        props.setBlocklistTerms("badword");

        BlocklistService blocklist = new BlocklistService(props, new DefaultResourceLoader(), null);
        OpenAiModerationClient openai = new OpenAiModerationClient(props);
        ContentModerationService svc = new ContentModerationService(props, blocklist, openai);

        var d = svc.evaluateText("badword");
        assertThat(d.action()).isEqualTo(ContentModerationService.Action.ALLOW);
    }
}

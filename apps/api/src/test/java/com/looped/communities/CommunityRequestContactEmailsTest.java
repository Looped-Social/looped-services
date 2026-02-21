package com.looped.communities;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CommunityRequestContactEmailsTest {

    @Test
    void parses_legacy_contact_email_line_and_strips_it_from_description() {
        String about = """
                Looking for this community
                Preferred contact email: UNC.Requests@Example.edu
                """;
        var parsed = CommunityRequestContactEmails.parseLegacyContactEmailLine(about);
        assertThat(parsed.description()).isEqualTo("Looking for this community");
        assertThat(parsed.extractedEmail()).isEqualTo("UNC.Requests@Example.edu");
        assertThat(CommunityRequestContactEmails.normalizeValidEmailOrNull(parsed.extractedEmail()))
                .isEqualTo("unc.requests@example.edu");
    }
}


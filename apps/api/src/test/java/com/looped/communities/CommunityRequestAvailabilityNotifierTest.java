package com.looped.communities;

import com.looped.email.EmailService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;

class CommunityRequestAvailabilityNotifierTest {

    @Test
    void matches_similar_names_notifies_once_and_uses_idempotent_marking() {
        CommunityRequestsRepository repo = Mockito.mock(CommunityRequestsRepository.class);
        EmailService email = Mockito.mock(EmailService.class);

        CommunityRequestsRepository.Row r1 = new CommunityRequestsRepository.Row();
        r1.id = 10L;
        r1.kind = "company";
        r1.name = "University of North Carolina";
        r1.contactEmail = "first@example.com";
        r1.notifyWhenAvailable = true;

        CommunityRequestsRepository.Row r2 = new CommunityRequestsRepository.Row();
        r2.id = 11L;
        r2.kind = "company";
        r2.name = "UNC";
        r2.description = "Preferred contact email: second@example.com";
        r2.notifyWhenAvailable = true;

        Mockito.when(repo.listPendingNotifiableByKindForUpdate("company"))
                .thenReturn(List.of(r1, r2))
                .thenReturn(List.of());
        Mockito.when(email.sendCommunityRequestAvailableEmail(eq("first@example.com"), eq("University of North Carolina"), Mockito.anyString(), Mockito.anyString()))
                .thenReturn(true);
        Mockito.when(email.sendCommunityRequestAvailableEmail(eq("second@example.com"), eq("University of North Carolina"), Mockito.anyString(), Mockito.anyString()))
                .thenReturn(true);
        Mockito.when(repo.markNotified(anyLong(), eq(200L))).thenReturn(true);

        var notifier = new CommunityRequestAvailabilityNotifier(repo, email, new SimpleMeterRegistry(), "https://mylooped.app");
        var first = notifier.notifyForCreatedCommunity("company", "University of North Carolina", 200L);
        var second = notifier.notifyForCreatedCommunity("company", "University of North Carolina", 200L);

        assertThat(first.matchedRequests()).isEqualTo(2);
        assertThat(first.sentEmails()).isEqualTo(2);
        assertThat(second.matchedRequests()).isEqualTo(0);
        assertThat(second.sentEmails()).isEqualTo(0);
        Mockito.verify(repo, Mockito.times(2)).listPendingNotifiableByKindForUpdate("company");
        Mockito.verify(repo, Mockito.times(2)).markNotified(anyLong(), eq(200L));
    }
}


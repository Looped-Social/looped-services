package com.looped.notifications;

import com.looped.messaging.ChannelPreferencesRepository;
import com.looped.users.UserRepository;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class NotificationServiceTest {

    @Test
    void mark_read_is_idempotent_when_notification_id_is_missing() {
        NotificationRepository repo = mock(NotificationRepository.class);
        UserRepository users = mock(UserRepository.class);
        NotificationPreferencesService preferences = mock(NotificationPreferencesService.class);
        ChannelPreferencesRepository channelPrefs = mock(ChannelPreferencesRepository.class);
        NotificationService service = new NotificationService(repo, users, preferences, channelPrefs);

        when(users.findByFirebaseUid("uid-read")).thenReturn(Optional.of(activeUser(10L, "uid-read")));
        when(repo.markRead(eq(999L), eq(10L), any(OffsetDateTime.class))).thenReturn(false);
        when(repo.findById(999L)).thenReturn(Optional.empty());

        var res = service.markRead("uid-read", 999L);

        assertThat(res.status()).isEqualTo(NotificationService.Status.OK);
    }

    @Test
    void mark_read_returns_forbidden_when_notification_belongs_to_different_user() {
        NotificationRepository repo = mock(NotificationRepository.class);
        UserRepository users = mock(UserRepository.class);
        NotificationPreferencesService preferences = mock(NotificationPreferencesService.class);
        ChannelPreferencesRepository channelPrefs = mock(ChannelPreferencesRepository.class);
        NotificationService service = new NotificationService(repo, users, preferences, channelPrefs);

        when(users.findByFirebaseUid("uid-read")).thenReturn(Optional.of(activeUser(10L, "uid-read")));
        when(repo.markRead(eq(500L), eq(10L), any(OffsetDateTime.class))).thenReturn(false);
        var otherUsersNotification = new NotificationRepository.NotificationRow();
        otherUsersNotification.id = 500L;
        otherUsersNotification.userId = 22L;
        when(repo.findById(500L)).thenReturn(Optional.of(otherUsersNotification));

        var res = service.markRead("uid-read", 500L);

        assertThat(res.status()).isEqualTo(NotificationService.Status.FORBIDDEN);
    }

    @Test
    void list_tombstones_deleted_actor_payload() {
        NotificationRepository repo = mock(NotificationRepository.class);
        UserRepository users = mock(UserRepository.class);
        NotificationPreferencesService preferences = mock(NotificationPreferencesService.class);
        ChannelPreferencesRepository channelPrefs = mock(ChannelPreferencesRepository.class);
        NotificationService service = new NotificationService(repo, users, preferences, channelPrefs);

        when(users.findByFirebaseUid("uid-list")).thenReturn(Optional.of(activeUser(10L, "uid-list")));
        when(preferences.preferencesForUserId(10L)).thenReturn(NotificationPreferences.defaults());
        when(users.listActiveUserIdsByIds(Set.of(99L))).thenReturn(Set.of());

        var row = new NotificationRepository.NotificationRow();
        row.id = 123L;
        row.userId = 10L;
        row.type = "follow";
        row.createdAt = OffsetDateTime.now();
        row.payload = new HashMap<>(Map.of(
                "actor_user_id", 99L,
                "actor_display_name", "Old Name",
                "actor_profile_image_url", "https://cdn.example.com/profile.jpg",
                "deeplink", "looped://user/99",
                "action_deeplink", "looped://user/99"
        ));
        when(repo.findByUser(10L, null, null, 20)).thenReturn(List.of(row));

        var res = service.list("uid-list", null, 20);

        assertThat(res.status()).isEqualTo(NotificationService.Status.OK);
        assertThat(res.notifications()).hasSize(1);
        Map<String, Object> payload = res.notifications().get(0).payload;
        assertThat(payload.get("actor_deleted")).isEqualTo(true);
        assertThat(payload.get("actor_display_name")).isEqualTo("Deleted user");
        assertThat(payload).doesNotContainKey("actor_user_id");
        assertThat(payload).doesNotContainKey("actor_profile_image_url");
        assertThat(payload.get("deeplink")).isEqualTo("looped://notifications");
        assertThat(payload.get("action_deeplink")).isEqualTo("looped://notifications");
    }

    private UserRepository.UserRow activeUser(long id, String firebaseUid) {
        var row = new UserRepository.UserRow();
        row.id = id;
        row.firebaseUid = firebaseUid;
        row.companyId = 77L;
        return row;
    }
}

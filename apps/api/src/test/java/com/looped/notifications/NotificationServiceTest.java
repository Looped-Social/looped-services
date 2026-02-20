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

    @Test
    void dismiss_returns_not_found_for_missing_or_not_owned_notification() {
        NotificationRepository repo = mock(NotificationRepository.class);
        UserRepository users = mock(UserRepository.class);
        NotificationPreferencesService preferences = mock(NotificationPreferencesService.class);
        ChannelPreferencesRepository channelPrefs = mock(ChannelPreferencesRepository.class);
        NotificationService service = new NotificationService(repo, users, preferences, channelPrefs);

        when(users.findByFirebaseUid("uid-dismiss")).thenReturn(Optional.of(activeUser(10L, "uid-dismiss")));
        when(repo.markDismissed(eq(404L), eq(10L), any(OffsetDateTime.class))).thenReturn(false);

        var res = service.dismiss("uid-dismiss", 404L);

        assertThat(res.status()).isEqualTo(NotificationService.Status.NOT_FOUND);
    }

    @Test
    void dismiss_is_idempotent_for_owned_notification() {
        NotificationRepository repo = mock(NotificationRepository.class);
        UserRepository users = mock(UserRepository.class);
        NotificationPreferencesService preferences = mock(NotificationPreferencesService.class);
        ChannelPreferencesRepository channelPrefs = mock(ChannelPreferencesRepository.class);
        NotificationService service = new NotificationService(repo, users, preferences, channelPrefs);

        when(users.findByFirebaseUid("uid-dismiss")).thenReturn(Optional.of(activeUser(10L, "uid-dismiss")));
        when(repo.markDismissed(eq(123L), eq(10L), any(OffsetDateTime.class))).thenReturn(true);

        var res = service.dismiss("uid-dismiss", 123L);

        assertThat(res.status()).isEqualTo(NotificationService.Status.OK);
    }

    @Test
    void dismiss_all_only_marks_currently_visible_notifications() {
        NotificationRepository repo = mock(NotificationRepository.class);
        UserRepository users = mock(UserRepository.class);
        NotificationPreferencesService preferences = mock(NotificationPreferencesService.class);
        ChannelPreferencesRepository channelPrefs = mock(ChannelPreferencesRepository.class);
        NotificationService service = new NotificationService(repo, users, preferences, channelPrefs);

        when(users.findByFirebaseUid("uid-dismiss-all")).thenReturn(Optional.of(activeUser(10L, "uid-dismiss-all")));
        when(preferences.preferencesForUserId(10L)).thenReturn(NotificationPreferences.defaults());

        var visible = notificationRow(1L, 10L, "mention", OffsetDateTime.now(), Map.of());
        var mutedChannel = notificationRow(2L, 10L, "channel.mention", OffsetDateTime.now(), Map.of("channel_id", 55L));
        var custom = notificationRow(3L, 10L, "custom.unknown", OffsetDateTime.now(), Map.of());
        when(repo.findByUser(10L, null, null, 200, false)).thenReturn(List.of(visible, mutedChannel, custom));
        when(channelPrefs.mutedByChannelIds(10L, List.of(55L))).thenReturn(Map.of(55L, true));
        when(repo.markDismissedByIds(eq(10L), eq(List.of(1L, 3L)), any(OffsetDateTime.class))).thenReturn(2);

        var res = service.dismissAll("uid-dismiss-all");

        assertThat(res.status()).isEqualTo(NotificationService.Status.OK);
        assertThat(res.dismissedCount()).isEqualTo(2);
    }

    private UserRepository.UserRow activeUser(long id, String firebaseUid) {
        var row = new UserRepository.UserRow();
        row.id = id;
        row.firebaseUid = firebaseUid;
        row.companyId = 77L;
        return row;
    }

    private NotificationRepository.NotificationRow notificationRow(long id,
                                                                   long userId,
                                                                   String type,
                                                                   OffsetDateTime createdAt,
                                                                   Map<String, Object> payload) {
        var row = new NotificationRepository.NotificationRow();
        row.id = id;
        row.userId = userId;
        row.type = type;
        row.createdAt = createdAt;
        row.payload = payload;
        return row;
    }
}

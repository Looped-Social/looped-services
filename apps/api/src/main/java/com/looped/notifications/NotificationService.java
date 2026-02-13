package com.looped.notifications;

import com.looped.shared.Pagination;
import com.looped.messaging.ChannelPreferencesRepository;
import com.looped.users.UserRepository;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class NotificationService {
    private final NotificationRepository repo;
    private final UserRepository users;
    private final NotificationPreferencesService preferences;
    private final ChannelPreferencesRepository channelPreferences;

    public NotificationService(NotificationRepository repo,
                               UserRepository users,
                               NotificationPreferencesService preferences,
                               ChannelPreferencesRepository channelPreferences) {
        this.repo = repo;
        this.users = users;
        this.preferences = preferences;
        this.channelPreferences = channelPreferences;
    }

    public ListResult list(String firebaseUid, String cursor, int limit) {
        var actor = requireProvisionedUser(firebaseUid);
        if (actor.isEmpty()) return ListResult.userNotProvisioned();
        OffsetDateTime cTs = null; Long cId = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                var decoded = Pagination.decode(cursor);
                cTs = decoded.timestamp();
                cId = decoded.id();
            } catch (IllegalArgumentException ignored) {}
        }
        NotificationPreferences prefs = preferences.preferencesForUserId(actor.get().id);
        List<NotificationRepository.NotificationRow> filtered = new java.util.ArrayList<>();
        NotificationRepository.NotificationRow lastIncluded = null;
        int lastBatchSize = 0;
        while (filtered.size() < limit) {
            var rows = repo.findByUser(actor.get().id, cTs, cId, limit);
            lastBatchSize = rows.size();
            if (rows.isEmpty()) break;
            var activeActorUserIds = activeActorUserIds(rows);
            var mutedByChannelId = mutedChannelsForUser(actor.get().id, rows);
            for (var row : rows) {
                tombstoneDeletedActor(row, activeActorUserIds);
                var type = NotificationType.fromValue(row.type).orElse(null);
                if (isMutedChannelNotification(row, mutedByChannelId)) {
                    continue;
                }
                if (type == null || prefs.allows(NotificationChannel.IN_APP, type)) {
                    filtered.add(row);
                    lastIncluded = row;
                    if (filtered.size() == limit) break;
                }
            }
            if (filtered.size() == limit) break;
            if (rows.size() < limit) break;
            var last = rows.get(rows.size() - 1);
            cTs = last.createdAt;
            cId = last.id;
        }
        String next = null;
        if (filtered.size() == limit && lastBatchSize == limit && lastIncluded != null) {
            next = Pagination.encode(lastIncluded.createdAt, lastIncluded.id);
        }
        return ListResult.ok(filtered, next);
    }

    public MarkReadResult markRead(String firebaseUid, long notificationId) {
        var actor = requireProvisionedUser(firebaseUid);
        if (actor.isEmpty()) return MarkReadResult.userNotProvisioned();
        if (repo.markRead(notificationId, actor.get().id, OffsetDateTime.now())) {
            return MarkReadResult.ok();
        }
        var notif = repo.findById(notificationId);
        if (notif.isPresent() && notif.get().userId != actor.get().id) {
            return MarkReadResult.forbidden();
        }
        // Idempotent no-op for missing/stale notification ids.
        return MarkReadResult.ok();
    }

    private Optional<UserRepository.UserRow> requireProvisionedUser(String firebaseUid) {
        var user = users.findByFirebaseUid(firebaseUid);
        if (user.isEmpty() || user.get().companyId == null) return Optional.empty();
        return user;
    }

    public enum Status { OK, USER_NOT_PROVISIONED, FORBIDDEN }

    public record ListResult(Status status, List<NotificationRepository.NotificationRow> notifications, String nextCursor) {
        static ListResult ok(List<NotificationRepository.NotificationRow> notifications, String next) { return new ListResult(Status.OK, notifications, next); }
        static ListResult userNotProvisioned() { return new ListResult(Status.USER_NOT_PROVISIONED, List.of(), null); }
    }

    public record MarkReadResult(Status status) {
        static MarkReadResult ok() { return new MarkReadResult(Status.OK); }
        static MarkReadResult userNotProvisioned() { return new MarkReadResult(Status.USER_NOT_PROVISIONED); }
        static MarkReadResult forbidden() { return new MarkReadResult(Status.FORBIDDEN); }
    }

    private java.util.Map<Long, Boolean> mutedChannelsForUser(long userId, List<NotificationRepository.NotificationRow> rows) {
        if (rows == null || rows.isEmpty()) return java.util.Map.of();
        List<Long> channelIds = rows.stream()
                .map(this::channelIdFromPayload)
                .filter(id -> id != null && id > 0)
                .distinct()
                .limit(200)
                .toList();
        if (channelIds.isEmpty()) return java.util.Map.of();
        return channelPreferences.mutedByChannelIds(userId, channelIds);
    }

    private boolean isMutedChannelNotification(NotificationRepository.NotificationRow row, java.util.Map<Long, Boolean> mutedByChannelId) {
        if (row == null || row.type == null) return false;
        if (!row.type.startsWith("channel.")) return false;
        Long channelId = channelIdFromPayload(row);
        if (channelId == null || channelId <= 0) return false;
        return mutedByChannelId.getOrDefault(channelId, false);
    }

    private Long channelIdFromPayload(NotificationRepository.NotificationRow row) {
        if (row == null || row.payload == null) return null;
        Object val = row.payload.get("channel_id");
        if (val == null) val = row.payload.get("channelId");
        if (val instanceof Number n) return n.longValue();
        return null;
    }

    private java.util.Set<Long> activeActorUserIds(List<NotificationRepository.NotificationRow> rows) {
        if (rows == null || rows.isEmpty()) return java.util.Set.of();
        java.util.Set<Long> actorUserIds = new java.util.HashSet<>();
        for (var row : rows) {
            Long actorUserId = actorUserIdFromPayload(row);
            if (actorUserId != null && actorUserId > 0) actorUserIds.add(actorUserId);
        }
        if (actorUserIds.isEmpty()) return java.util.Set.of();
        return users.listActiveUserIdsByIds(actorUserIds);
    }

    private Long actorUserIdFromPayload(NotificationRepository.NotificationRow row) {
        if (row == null || row.payload == null) return null;
        Object actor = row.payload.get("actor_user_id");
        if (actor instanceof Number n) return n.longValue();
        if (actor instanceof String s) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private void tombstoneDeletedActor(NotificationRepository.NotificationRow row, java.util.Set<Long> activeActorUserIds) {
        Long actorUserId = actorUserIdFromPayload(row);
        if (actorUserId == null || actorUserId <= 0) return;
        if (activeActorUserIds.contains(actorUserId)) return;
        java.util.Map<String, Object> payload = row.payload == null ? new java.util.HashMap<>() : new java.util.HashMap<>(row.payload);
        payload.put("actor_deleted", true);
        payload.put("actor_display_name", "Deleted user");
        payload.remove("actor_user_id");
        payload.remove("actor_profile_image_url");
        rewriteDeadActorDeeplink(payload, "deeplink");
        rewriteDeadActorDeeplink(payload, "action_deeplink");
        row.payload = payload;
    }

    private void rewriteDeadActorDeeplink(java.util.Map<String, Object> payload, String key) {
        if (payload == null || key == null) return;
        Object raw = payload.get(key);
        if (!(raw instanceof String deeplink)) return;
        if (deeplink.startsWith("looped://user/")) {
            payload.put(key, "looped://notifications");
        }
    }
}

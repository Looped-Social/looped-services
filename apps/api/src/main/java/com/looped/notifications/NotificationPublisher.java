package com.looped.notifications;

import com.looped.devices.DeviceRepository;
import com.looped.principals.PrincipalRepository;
import com.looped.posts.PostRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class NotificationPublisher {
    private final NotificationRepository notifications;
    private final NotificationPreferencesService preferences;
    private final PrincipalRepository principals;
    private final DeviceRepository devices;
    private final PushQueuePublisher pushQueue;

    public NotificationPublisher(NotificationRepository notifications,
                                 NotificationPreferencesService preferences,
                                 PrincipalRepository principals,
                                 DeviceRepository devices,
                                 PushQueuePublisher pushQueue) {
        this.notifications = notifications;
        this.preferences = preferences;
        this.principals = principals;
        this.devices = devices;
        this.pushQueue = pushQueue;
    }

    public void notifyFollow(long targetUserId, long actorPrincipalId) {
        var payload = actorPayload(actorPrincipalId);
        emitToUser(targetUserId, NotificationType.FOLLOW, payload);
    }

    public void notifyPostLike(PostRepository.PostRow post, long actorPrincipalId) {
        if (post.authorId == null) return;
        if (post.authorPrincipalId == actorPrincipalId) return;
        Map<String, Object> payload = new HashMap<>(actorPayload(actorPrincipalId));
        payload.put("post_id", post.id);
        emitToUser(post.authorId, NotificationType.LIKE, payload);
    }

    public void notifyComment(PostRepository.PostRow post, long commentId, long actorPrincipalId) {
        if (post.authorId == null) return;
        if (post.authorPrincipalId == actorPrincipalId) return;
        Map<String, Object> payload = new HashMap<>(actorPayload(actorPrincipalId));
        payload.put("post_id", post.id);
        payload.put("comment_id", commentId);
        emitToUser(post.authorId, NotificationType.COMMENT, payload);
    }

    public void notifyMentions(long actorPrincipalId, List<Long> mentionedUserIds, Long postId, Long commentId) {
        if (mentionedUserIds == null || mentionedUserIds.isEmpty()) return;
        Map<String, Object> payload = new HashMap<>(actorPayload(actorPrincipalId));
        if (postId != null) payload.put("post_id", postId);
        if (commentId != null) payload.put("comment_id", commentId);
        payload.put("context", commentId != null ? "comment" : "post");
        emitToUsers(mentionedUserIds, NotificationType.MENTION, payload);
    }

    public void notifyPostFromFollowed(long authorPrincipalId, long postId, List<Long> followerUserIds) {
        if (followerUserIds == null || followerUserIds.isEmpty()) return;
        Map<String, Object> payload = new HashMap<>(actorPayload(authorPrincipalId));
        payload.put("post_id", postId);
        emitToUsers(followerUserIds, NotificationType.POST_FROM_FOLLOWED, payload);
    }

    public void notifyAnnouncement(List<Long> userIds, Map<String, Object> payload) {
        emitToUsers(userIds, NotificationType.ANNOUNCEMENT, payload);
        emitPushAnnouncements(userIds, payload);
    }

    private void emitToUser(long userId, NotificationType type, Map<String, Object> payload) {
        if (!allowsInApp(userId, type)) return;
        notifications.insert(userId, type.value(), payload);
    }

    private void emitToUsers(List<Long> userIds, NotificationType type, Map<String, Object> payload) {
        List<NotificationRepository.NotificationInsert> inserts = new ArrayList<>();
        for (Long userId : userIds) {
            if (userId == null) continue;
            if (!allowsInApp(userId, type)) continue;
            inserts.add(new NotificationRepository.NotificationInsert(userId, type.value(), payload));
        }
        notifications.insertBatch(inserts);
    }

    private boolean allowsInApp(long userId, NotificationType type) {
        NotificationPreferences prefs = preferences.preferencesForUserId(userId);
        return prefs.allows(NotificationChannel.IN_APP, type);
    }

    private boolean allowsPush(long userId, NotificationType type) {
        NotificationPreferences prefs = preferences.preferencesForUserId(userId);
        return prefs.allows(NotificationChannel.PUSH, type);
    }

    private void emitPushAnnouncements(List<Long> userIds, Map<String, Object> payload) {
        if (userIds == null || userIds.isEmpty()) return;
        if (!pushQueue.enabled()) return;
        String title = payload.get("title") instanceof String s ? s : null;
        String body = payload.get("body") instanceof String s ? s : null;
        if (title == null || body == null) return;
        String deeplink = payload.get("deeplink") instanceof String s ? s : null;
        String collapseId = payload.get("company_id") instanceof Number n ? "announcement-" + n.longValue() : "announcement";
        String traceId = UUID.randomUUID().toString();

        var tokenRows = devices.listApnsTokensByUserIds(userIds);
        Map<Long, List<String>> tokensByUser = new HashMap<>();
        for (var row : tokenRows) {
            if (row.apnsToken == null || row.apnsToken.isBlank()) continue;
            tokensByUser.computeIfAbsent(row.userId, ignored -> new ArrayList<>()).add(row.apnsToken);
        }

        for (Long userId : userIds) {
            if (userId == null) continue;
            if (!allowsPush(userId, NotificationType.ANNOUNCEMENT)) continue;
            List<String> tokens = tokensByUser.get(userId);
            if (tokens == null || tokens.isEmpty()) continue;
            for (String token : tokens) {
                pushQueue.enqueueAnnouncement(userId, token, title, body, deeplink, collapseId, traceId);
            }
        }
    }

    private Map<String, Object> actorPayload(long actorPrincipalId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("actor_principal_id", actorPrincipalId);
        var principal = principals.findById(actorPrincipalId).orElse(null);
        if (principal != null) {
            payload.put("actor_is_anonymous", "anon".equals(principal.kind));
            if (principal.userId != null) {
                payload.put("actor_user_id", principal.userId);
            }
        }
        return payload;
    }
}

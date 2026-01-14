package com.looped.notifications;

import com.looped.comments.CommentsRepository;
import com.looped.devices.DeviceRepository;
import com.looped.principals.PrincipalRepository;
import com.looped.posts.PostRepository;
import com.looped.users.UserRepository;
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
    private final UserRepository users;

    public NotificationPublisher(NotificationRepository notifications,
                                 NotificationPreferencesService preferences,
                                 PrincipalRepository principals,
                                 DeviceRepository devices,
                                 PushQueuePublisher pushQueue,
                                 UserRepository users) {
        this.notifications = notifications;
        this.preferences = preferences;
        this.principals = principals;
        this.devices = devices;
        this.pushQueue = pushQueue;
        this.users = users;
    }

    public void notifyFollow(long targetUserId, long actorPrincipalId) {
        var payload = actorPayload(actorPrincipalId);
        publishToUser(targetUserId, NotificationType.FOLLOW, payload);
    }

    public void notifyPostLike(PostRepository.PostRow post, long actorPrincipalId) {
        if (post.authorId == null) return;
        if (post.authorPrincipalId == actorPrincipalId) return;
        Map<String, Object> payload = new HashMap<>(actorPayload(actorPrincipalId));
        payload.put("post_id", post.id);
        publishToUser(post.authorId, NotificationType.LIKE, payload);
    }

    public void notifyComment(PostRepository.PostRow post, long commentId, long actorPrincipalId) {
        if (post.authorId == null) return;
        if (post.authorPrincipalId == actorPrincipalId) return;
        Map<String, Object> payload = new HashMap<>(actorPayload(actorPrincipalId));
        payload.put("post_id", post.id);
        payload.put("comment_id", commentId);
        publishToUser(post.authorId, NotificationType.COMMENT, payload);
    }

    public void notifyReply(CommentsRepository.CommentRow parent, long replyCommentId, long actorPrincipalId) {
        if (parent == null || parent.userId == null) return;
        if (parent.authorPrincipalId == actorPrincipalId) return;
        Map<String, Object> payload = new HashMap<>(actorPayload(actorPrincipalId));
        payload.put("post_id", parent.postId);
        payload.put("comment_id", replyCommentId);
        publishToUser(parent.userId, NotificationType.REPLY, payload);
    }

    public void notifyRepost(PostRepository.PostRow post, long actorPrincipalId) {
        if (post.authorId == null) return;
        if (post.authorPrincipalId == actorPrincipalId) return;
        Map<String, Object> payload = new HashMap<>(actorPayload(actorPrincipalId));
        payload.put("post_id", post.id);
        publishToUser(post.authorId, NotificationType.REPOST, payload);
    }

    public void notifyMentions(long actorPrincipalId, List<Long> mentionedUserIds, Long postId, Long commentId) {
        if (mentionedUserIds == null || mentionedUserIds.isEmpty()) return;
        Map<String, Object> payload = new HashMap<>(actorPayload(actorPrincipalId));
        if (postId != null) payload.put("post_id", postId);
        if (commentId != null) payload.put("comment_id", commentId);
        payload.put("context", commentId != null ? "comment" : "post");
        publishToUsers(mentionedUserIds, NotificationType.MENTION, payload);
    }

    public void notifyPostFromFollowed(long authorPrincipalId, long postId, List<Long> followerUserIds) {
        if (followerUserIds == null || followerUserIds.isEmpty()) return;
        Map<String, Object> payload = new HashMap<>(actorPayload(authorPrincipalId));
        payload.put("post_id", postId);
        publishToUsers(followerUserIds, NotificationType.POST_FROM_FOLLOWED, payload);
    }

    public void notifyAnnouncement(List<Long> userIds, Map<String, Object> payload) {
        if (userIds == null || userIds.isEmpty() || payload == null) return;
        Map<String, Object> normalized = new HashMap<>(payload);
        Object rawDeeplink = normalized.remove("deeplink");
        if (rawDeeplink instanceof String s && !s.isBlank()) {
            normalized.put("action_deeplink", s);
        }
        publishToUsers(userIds, NotificationType.ANNOUNCEMENT, normalized);
    }

    private void publishToUser(long userId, NotificationType type, Map<String, Object> payload) {
        if (userId <= 0 || payload == null) return;
        NotificationPreferences prefs = preferences.preferencesForUserId(userId);
        boolean allowInApp = prefs.allows(NotificationChannel.IN_APP, type);
        boolean allowPush = prefs.allows(NotificationChannel.PUSH, type);
        boolean pushEnabled = allowPush && pushQueue.enabled();
        if (!allowInApp && !pushEnabled) return;

        Map<String, Object> enriched = new HashMap<>(payload);
        applyDeeplink(enriched, type, null);
        long notificationId = notifications.insert(userId, type.value(), enriched);
        if (notificationId <= 0) return;
        ensureDeeplink(notificationId, type, enriched);

        if (pushEnabled) {
            List<String> tokens = tokensForUser(userId);
            enqueuePush(userId, notificationId, type, enriched, tokens);
        }
    }

    private void publishToUsers(List<Long> userIds, NotificationType type, Map<String, Object> payload) {
        if (userIds == null || userIds.isEmpty() || payload == null) return;
        Map<Long, List<String>> tokensByUser = pushQueue.enabled() ? tokensByUser(userIds) : Map.of();
        for (Long userId : userIds) {
            if (userId == null || userId <= 0) continue;
            NotificationPreferences prefs = preferences.preferencesForUserId(userId);
            boolean allowInApp = prefs.allows(NotificationChannel.IN_APP, type);
            boolean allowPush = prefs.allows(NotificationChannel.PUSH, type);
            boolean pushEnabled = allowPush && pushQueue.enabled();
            if (!allowInApp && !pushEnabled) continue;

            Map<String, Object> enriched = new HashMap<>(payload);
            applyDeeplink(enriched, type, null);
            long notificationId = notifications.insert(userId, type.value(), enriched);
            if (notificationId <= 0) continue;
            ensureDeeplink(notificationId, type, enriched);

            if (pushEnabled) {
                List<String> tokens = tokensByUser.get(userId);
                enqueuePush(userId, notificationId, type, enriched, tokens);
            }
        }
    }

    private void enqueuePush(long userId,
                             long notificationId,
                             NotificationType type,
                             Map<String, Object> payload,
                             List<String> tokens) {
        if (!pushQueue.enabled()) return;
        if (tokens == null || tokens.isEmpty()) return;
        PushContent content = buildPushContent(type, payload);
        if (content == null) return;
        String deeplink = payload.get("deeplink") instanceof String s ? s : null;
        String collapseId = buildCollapseId(type, payload);
        String traceId = UUID.randomUUID().toString();
        for (String token : tokens) {
            if (token == null || token.isBlank()) continue;
            pushQueue.enqueueNotification(userId, token, type.value(), notificationId,
                    content.title(), content.body(), deeplink, collapseId, traceId);
        }
    }

    private Map<Long, List<String>> tokensByUser(List<Long> userIds) {
        var tokenRows = devices.listApnsTokensByUserIds(userIds);
        Map<Long, List<String>> tokensByUser = new HashMap<>();
        for (var row : tokenRows) {
            if (row.apnsToken == null || row.apnsToken.isBlank()) continue;
            tokensByUser.computeIfAbsent(row.userId, ignored -> new ArrayList<>()).add(row.apnsToken);
        }
        return tokensByUser;
    }

    private List<String> tokensForUser(long userId) {
        var rows = devices.listApnsTokensByUserIds(List.of(userId));
        List<String> tokens = new ArrayList<>();
        for (var row : rows) {
            if (row.apnsToken == null || row.apnsToken.isBlank()) continue;
            tokens.add(row.apnsToken);
        }
        return tokens;
    }

    private void applyDeeplink(Map<String, Object> payload, NotificationType type, Long notificationId) {
        String existingDeeplink = payload.get("deeplink") instanceof String s ? s : null;
        String existingAction = payload.get("action_deeplink") instanceof String s ? s : null;
        if (existingDeeplink != null && !existingDeeplink.isBlank()) {
            if (existingAction == null || existingAction.isBlank()) payload.put("action_deeplink", existingDeeplink);
            return;
        }
        if (existingAction != null && !existingAction.isBlank()) {
            payload.put("deeplink", existingAction);
            return;
        }
        String deeplink = buildDeeplink(type, payload, notificationId);
        if (deeplink != null && !deeplink.isBlank()) {
            payload.put("deeplink", deeplink);
            payload.put("action_deeplink", deeplink);
        }
    }

    private void ensureDeeplink(long notificationId, NotificationType type, Map<String, Object> payload) {
        String existingDeeplink = payload.get("deeplink") instanceof String s ? s : null;
        String existingAction = payload.get("action_deeplink") instanceof String s ? s : null;
        if ((existingDeeplink != null && !existingDeeplink.isBlank()) ||
                (existingAction != null && !existingAction.isBlank())) {
            String resolved = existingDeeplink != null && !existingDeeplink.isBlank() ? existingDeeplink : existingAction;
            if (existingDeeplink == null || existingDeeplink.isBlank()) payload.put("deeplink", resolved);
            if (existingAction == null || existingAction.isBlank()) payload.put("action_deeplink", resolved);
            notifications.updatePayload(notificationId, payload);
            return;
        }
        String deeplink = buildDeeplink(type, payload, notificationId);
        if (deeplink == null || deeplink.isBlank()) return;
        payload.put("deeplink", deeplink);
        payload.put("action_deeplink", deeplink);
        notifications.updatePayload(notificationId, payload);
    }

    private String buildDeeplink(NotificationType type, Map<String, Object> payload, Long notificationId) {
        Long postId;
        Long commentId;
        switch (type) {
            case FOLLOW -> {
                Long userId = asLong(payload.get("actor_user_id"));
                if (userId == null) return null;
                Object rawAnon = payload.get("actor_is_anonymous");
                if (rawAnon instanceof Boolean anon) {
                    return "looped://user/" + userId + "?anon=" + anon;
                }
                return "looped://user/" + userId;
            }
            case LIKE, POST_FROM_FOLLOWED, REPOST -> {
                postId = asLong(payload.get("post_id"));
                return postId == null ? null : "looped://post/" + postId;
            }
            case COMMENT -> {
                commentId = asLong(payload.get("comment_id"));
                postId = asLong(payload.get("post_id"));
                if (commentId == null) return postId == null ? null : "looped://post/" + postId;
                if (postId == null) return "looped://comment/" + commentId;
                return "looped://comment/" + commentId + "?post_id=" + postId;
            }
            case REPLY -> {
                commentId = asLong(payload.get("comment_id"));
                if (commentId == null) return null;
                postId = asLong(payload.get("post_id"));
                if (postId == null) return "looped://comment/" + commentId;
                return "looped://comment/" + commentId + "?post_id=" + postId;
            }
            case MENTION -> {
                String context = payload.get("context") instanceof String s ? s : null;
                if ("comment".equals(context)) {
                    commentId = asLong(payload.get("comment_id"));
                    if (commentId != null) {
                        postId = asLong(payload.get("post_id"));
                        if (postId == null) return "looped://comment/" + commentId;
                        return "looped://comment/" + commentId + "?post_id=" + postId;
                    }
                }
                postId = asLong(payload.get("post_id"));
                return postId == null ? null : "looped://post/" + postId;
            }
            case ANNOUNCEMENT, SYSTEM -> {
                return notificationId == null ? null : "looped://announcement/" + notificationId;
            }
            default -> {
                return null;
            }
        }
    }

    private Long asLong(Object value) {
        if (value instanceof Number n) return n.longValue();
        return null;
    }

    private String buildCollapseId(NotificationType type, Map<String, Object> payload) {
        if (type != NotificationType.ANNOUNCEMENT) return null;
        Object companyId = payload.get("company_id");
        if (companyId instanceof Number n) {
            return "announcement-" + n.longValue();
        }
        return "announcement";
    }

    private PushContent buildPushContent(NotificationType type, Map<String, Object> payload) {
        if (type == NotificationType.ANNOUNCEMENT || type == NotificationType.SYSTEM) {
            String title = payload.get("title") instanceof String s ? s : null;
            String body = payload.get("body") instanceof String s ? s : null;
            if (title == null || body == null) return null;
            return new PushContent(title, body);
        }

        String actor = actorDisplayName(payload);
        if (actor == null || actor.isBlank()) actor = "Someone";

        return switch (type) {
            case FOLLOW -> new PushContent("New follower", actor + " followed you");
            case LIKE -> new PushContent("New like", actor + " liked your post");
            case COMMENT -> new PushContent("New comment", actor + " commented on your post");
            case REPLY -> new PushContent("New reply", actor + " replied to your comment");
            case MENTION -> {
                String context = payload.get("context") instanceof String s ? s : null;
                String body = "comment".equals(context)
                        ? actor + " mentioned you in a comment"
                        : actor + " mentioned you in a post";
                yield new PushContent("Mention", body);
            }
            case POST_FROM_FOLLOWED -> new PushContent("New post", actor + " posted");
            case REPOST -> new PushContent("Repost", actor + " reposted your post");
            default -> null;
        };
    }

    private String actorDisplayName(Map<String, Object> payload) {
        if (payload == null) return null;
        if (payload.get("actor_display_name") instanceof String name && !name.isBlank()) {
            return name;
        }
        if (payload.get("actor_is_anonymous") instanceof Boolean anon && anon) {
            return "Anonymous";
        }
        return null;
    }

    private Map<String, Object> actorPayload(long actorPrincipalId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("actor_principal_id", actorPrincipalId);
        var principal = principals.findById(actorPrincipalId).orElse(null);
        if (principal != null) {
            boolean isAnon = "anon".equals(principal.kind);
            payload.put("actor_is_anonymous", isAnon);
            if (principal.anonProfileId != null) {
                payload.put("actor_anon_profile_id", principal.anonProfileId);
            }
            if (principal.userId != null) {
                payload.put("actor_user_id", principal.userId);
                if (!isAnon) {
                    users.findById(principal.userId).ifPresent(user -> {
                        String displayName = user.displayName;
                        if (displayName == null || displayName.isBlank()) {
                            displayName = user.handle;
                        }
                        if (displayName != null && !displayName.isBlank()) {
                            payload.put("actor_display_name", displayName);
                        }
                        if (user.profileImageUrl != null && !user.profileImageUrl.isBlank()) {
                            payload.put("actor_profile_image_url", user.profileImageUrl);
                        }
                    });
                }
            }
        }
        return payload;
    }

    private record PushContent(String title, String body) {}
}

package com.looped.messaging;

import com.looped.devices.DeviceRepository;
import com.looped.notifications.NotificationChannel;
import com.looped.notifications.NotificationPreferencesService;
import com.looped.notifications.NotificationType;
import com.looped.notifications.PushQueuePublisher;
import com.looped.principals.PrincipalRepository;
import com.looped.users.BlocksRepository;
import com.looped.users.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class MessagingPushService {
    private static final int DEFAULT_MAX_CHANNEL_PUSH_RECIPIENTS = 500;

    private final PushQueuePublisher pushQueue;
    private final NotificationPreferencesService notificationPreferences;
    private final DeviceRepository devices;
    private final ConversationRepository conversations;
    private final MessageRequestRepository messageRequests;
    private final ConversationPreferencesRepository conversationPreferences;
    private final ChannelRepository channels;
    private final ChannelPreferencesRepository channelPreferences;
    private final PrincipalRepository principals;
    private final BlocksRepository blocks;
    private final UserRepository users;
    private final int maxChannelPushRecipients;

    public MessagingPushService(PushQueuePublisher pushQueue,
                               NotificationPreferencesService notificationPreferences,
                               DeviceRepository devices,
                               ConversationRepository conversations,
                               MessageRequestRepository messageRequests,
                               ConversationPreferencesRepository conversationPreferences,
                               ChannelRepository channels,
                               ChannelPreferencesRepository channelPreferences,
                               PrincipalRepository principals,
                               BlocksRepository blocks,
                               UserRepository users,
                               @Value("${messaging.push.maxChannelRecipients:" + DEFAULT_MAX_CHANNEL_PUSH_RECIPIENTS + "}") int maxChannelPushRecipients) {
        this.pushQueue = pushQueue;
        this.notificationPreferences = notificationPreferences;
        this.devices = devices;
        this.conversations = conversations;
        this.messageRequests = messageRequests;
        this.conversationPreferences = conversationPreferences;
        this.channels = channels;
        this.channelPreferences = channelPreferences;
        this.principals = principals;
        this.blocks = blocks;
        this.users = users;
        this.maxChannelPushRecipients = Math.max(0, maxChannelPushRecipients);
    }

    public void onConversationMessageCreated(long conversationId, ConversationRepository.MessageRow message) {
        if (message == null || message.id <= 0) return;
        if (!pushQueue.enabled()) return;

        long senderId = message.senderId;
        List<Long> recipients = conversations.listOtherParticipantIds(conversationId, senderId);
        if (recipients == null || recipients.isEmpty()) return;

        Set<Long> mutedUserIds = conversationPreferences.mutedUserIdsForConversation(conversationId);

        var sender = users.findById(senderId).orElse(null);
        String senderName = null;
        if (sender != null) {
            if (sender.displayName != null && !sender.displayName.isBlank()) {
                senderName = sender.displayName;
            } else if (sender.handle != null && !sender.handle.isBlank()) {
                senderName = sender.handle;
            }
        }

        String preview = preview(message.content);
        String title = "New message";
        String body = preview.isBlank()
                ? ((senderName != null && !senderName.isBlank()) ? senderName + " sent a message" : "Tap to view")
                : ((senderName != null && !senderName.isBlank()) ? senderName + ": " + preview : preview);
        String deeplink = "looped://conversations/" + conversationId + "?messageId=" + message.id;
        String traceId = UUID.randomUUID().toString();

        var tokensByUser = tokensByUserIds(recipients);
        for (Long recipientId : recipients) {
            if (recipientId == null || recipientId <= 0) continue;
            if (mutedUserIds.contains(recipientId)) continue;
            if (!canReceiveDm(conversationId, recipientId)) continue;
            if (isBlockedBetweenUsers(senderId, recipientId)) continue;
            if (!notificationPreferences.preferencesForUserId(recipientId).allows(NotificationChannel.PUSH, NotificationType.DM_MESSAGE)) {
                continue;
            }
            List<String> tokens = tokensByUser.get(recipientId);
            if (tokens == null || tokens.isEmpty()) continue;

            Map<String, Object> userInfo = new HashMap<>();
            userInfo.put("conversation_id", conversationId);
            userInfo.put("message_id", message.id);
            userInfo.put("sender_id", senderId);
            if (senderName != null && !senderName.isBlank()) userInfo.put("sender_name", senderName);
            if (!preview.isBlank()) userInfo.put("preview", preview);

            for (String token : tokens) {
                if (token == null || token.isBlank()) continue;
                try {
                    pushQueue.enqueueNotification(recipientId, token, NotificationType.DM_MESSAGE.value(), 0L,
                            title, body, deeplink, "dm-" + conversationId, traceId, null, userInfo);
                } catch (Exception ignored) {
                }
            }
        }
    }

    public void onChannelMessageCreated(long channelId, ChannelRepository.ChannelMessageRow message) {
        if (message == null || message.id <= 0) return;
        if (!pushQueue.enabled()) return;
        if (maxChannelPushRecipients <= 0) return;

        var channel = channels.findById(channelId).orElse(null);
        if (channel == null) return;

        List<Long> memberIds = channels.listMemberUserIds(channelId, maxChannelPushRecipients + 1);
        if (memberIds == null || memberIds.isEmpty()) return;

        if (memberIds.size() > maxChannelPushRecipients) {
            return;
        }

        Set<Long> muted = channelPreferences.mutedUserIdsForChannel(channelId);
        long senderId = message.senderId;
        Set<Long> recipients = new HashSet<>();
        for (Long id : memberIds) {
            if (id == null || id <= 0) continue;
            if (id == senderId) continue;
            if (muted.contains(id)) continue;
            recipients.add(id);
        }
        if (recipients.isEmpty()) return;

        var sender = users.findById(senderId).orElse(null);
        String senderName = null;
        if (sender != null) {
            if (sender.displayName != null && !sender.displayName.isBlank()) {
                senderName = sender.displayName;
            } else if (sender.handle != null && !sender.handle.isBlank()) {
                senderName = sender.handle;
            }
        }

        String preview = preview(message.content);
        String title = channel.name != null && !channel.name.isBlank() ? channel.name : "New message";
        String body = senderName != null && !senderName.isBlank()
                ? senderName + (preview.isBlank() ? ": (message)" : ": " + preview)
                : (preview.isBlank() ? "New message" : preview);
        String deeplink = "looped://channels/" + channelId + "?messageId=" + message.id;
        String traceId = UUID.randomUUID().toString();

        var recipientIds = recipients.stream().toList();
        var tokensByUser = tokensByUserIds(recipientIds);
        var blockedRecipientIds = blockedRecipientUserIds(senderId, recipientIds);
        for (Long recipientId : recipients) {
            if (blockedRecipientIds.contains(recipientId)) continue;
            if (!notificationPreferences.preferencesForUserId(recipientId).allows(NotificationChannel.PUSH, NotificationType.CHANNEL_MESSAGE)) {
                continue;
            }
            List<String> tokens = tokensByUser.get(recipientId);
            if (tokens == null || tokens.isEmpty()) continue;

            Map<String, Object> userInfo = new HashMap<>();
            userInfo.put("channel_id", channelId);
            userInfo.put("message_id", message.id);
            userInfo.put("sender_id", senderId);
            if (senderName != null && !senderName.isBlank()) userInfo.put("sender_name", senderName);
            if (!preview.isBlank()) userInfo.put("preview", preview);

            for (String token : tokens) {
                if (token == null || token.isBlank()) continue;
                try {
                    pushQueue.enqueueNotification(recipientId, token, NotificationType.CHANNEL_MESSAGE.value(), 0L,
                            title, body, deeplink, "channel-" + channelId, traceId, null, userInfo);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private boolean canReceiveDm(long conversationId, long recipientId) {
        var req = messageRequests.findByConversationRecipient(conversationId, recipientId);
        if (req.isEmpty()) return true;
        if ("approved".equals(req.get().status)) return true;
        return messageRequests.hasApprovedForConversation(conversationId);
    }

    private Map<Long, List<String>> tokensByUserIds(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) return Map.of();
        var rows = devices.listApnsTokensByUserIds(userIds);
        Map<Long, List<String>> out = new HashMap<>();
        for (var row : rows) {
            if (row.userId <= 0) continue;
            if (row.apnsToken == null || row.apnsToken.isBlank()) continue;
            out.computeIfAbsent(row.userId, ignored -> new java.util.ArrayList<>()).add(row.apnsToken);
        }
        return out;
    }

    private boolean isBlockedBetweenUsers(long userA, long userB) {
        if (userA <= 0 || userB <= 0) return false;
        long principalA = principals.createForUser(userA).id;
        long principalB = principals.createForUser(userB).id;
        return blocks.existsEitherDirection(principalA, principalB);
    }

    private Set<Long> blockedRecipientUserIds(long senderUserId, List<Long> recipientUserIds) {
        if (senderUserId <= 0 || recipientUserIds == null || recipientUserIds.isEmpty()) return Set.of();
        long senderPrincipalId = principals.createForUser(senderUserId).id;
        Map<Long, Long> principalByUser = principals.principalIdsByUserIds(recipientUserIds);
        if (principalByUser.isEmpty()) return Set.of();
        var otherPrincipalIds = principalByUser.values().stream().distinct().toList();
        Set<Long> blockedPrincipalIds = blocks.otherPrincipalsBlockedEitherDirection(senderPrincipalId, otherPrincipalIds);
        if (blockedPrincipalIds.isEmpty()) return Set.of();
        Set<Long> blockedUserIds = new HashSet<>();
        for (var entry : principalByUser.entrySet()) {
            if (blockedPrincipalIds.contains(entry.getValue())) {
                blockedUserIds.add(entry.getKey());
            }
        }
        return blockedUserIds;
    }

    private static String preview(String content) {
        if (content == null) return "";
        String s = content.trim().replaceAll("\\s+", " ");
        if (s.length() <= 120) return s;
        return s.substring(0, 117) + "...";
    }
}

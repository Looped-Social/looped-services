package com.looped.messaging;

import com.looped.shared.Pagination;
import com.looped.notifications.NotificationPublisher;
import com.looped.principals.PrincipalRepository;
import com.looped.settings.AppConfigService;
import com.looped.users.FollowsRepository;
import com.looped.users.ProfileImageUrls;
import com.looped.users.UserPayloads;
import com.looped.users.UserRepository;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class ConversationService {
    private static final String REQUEST_STATUS_PENDING = "pending";
    private static final String REQUEST_STATUS_APPROVED = "approved";
    private static final String REQUEST_STATUS_REJECTED = "rejected";
    private static final String MESSAGE_PERMISSION_COMPANY = "company";
    private static final String MESSAGE_PERMISSION_FOLLOWING = "following";
    private static final String MESSAGE_PERMISSION_NO_ONE = "no_one";
    private static final String MESSAGE_PERMISSION_ALL = "all";

    private final ConversationRepository conversations;
    private final UserRepository users;
    private final FollowsRepository follows;
    private final PrincipalRepository principals;
    private final MessageRequestRepository messageRequests;
    private final ConversationPreferencesRepository conversationPreferences;
    private final MessagingPushService push;
    private final NotificationPublisher notifications;
    private final AppConfigService appConfig;

    public ConversationService(ConversationRepository conversations,
                               UserRepository users,
                               FollowsRepository follows,
                               PrincipalRepository principals,
                               MessageRequestRepository messageRequests,
                               ConversationPreferencesRepository conversationPreferences,
                               MessagingPushService push,
                               NotificationPublisher notifications,
                               AppConfigService appConfig) {
        this.conversations = conversations;
        this.users = users;
        this.follows = follows;
        this.principals = principals;
        this.messageRequests = messageRequests;
        this.conversationPreferences = conversationPreferences;
        this.push = push;
        this.notifications = notifications;
        this.appConfig = appConfig;
    }

    public ConversationListResult list(String firebaseUid, String cursor, int limit) {
        var actor = requireProvisionedUser(firebaseUid);
        if (actor.isEmpty()) return ConversationListResult.userNotProvisioned();
        if (actor.get().isAnonymous) return ConversationListResult.anonymousNotAllowed();
        OffsetDateTime cTs = null; Long cId = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                var decoded = Pagination.decode(cursor);
                cTs = decoded.timestamp();
                cId = decoded.id();
            } catch (IllegalArgumentException ignored) {}
        }
        var rows = conversations.listForUser(actor.get().id, cTs, cId, limit);
        String next = null;
        if (rows.size() == limit) {
            var last = rows.get(rows.size() - 1);
            next = Pagination.encode(last.activityAt, last.id);
        }

        List<Long> conversationIds = rows.stream().mapToLong(r -> r.id).boxed().toList();
        var mutedByConversationId = conversationPreferences.mutedByConversationIds(actor.get().id, conversationIds);

        String defaultProfileImageUrl = appConfig.defaultProfileImageUrl();
        List<Map<String, Object>> items = rows.stream().map(row -> {
            Map<String, Object> out = new HashMap<>();
            out.put("id", row.id);
            out.put("other_user_id", row.otherUserId);
            out.put("muted", mutedByConversationId.getOrDefault(row.id, false));
            Map<String, Object> profile = new HashMap<>();
            profile.put("id", row.otherUserId);
            profile.put("handle", row.otherUserHandle);
            profile.put("username", row.otherUserHandle);
            profile.put("display_name", row.otherUserDisplayName);
            profile.put("bio", row.otherUserBio);
            profile.put("company_id", row.otherUserCompanyId);
            profile.put("profile_image_url", ProfileImageUrls.resolve(row.otherUserProfileImageUrl, defaultProfileImageUrl));
            out.put("other_user_profile", profile);
            out.put("last_message", row.lastMessage);
            out.put("last_message_timestamp", row.lastMessageAt);
            out.put("unread_count", row.unreadCount);
            return out;
        }).toList();
        return ConversationListResult.ok(items, next);
    }

    public PreferencesResult setPreferences(String firebaseUid, long conversationId, boolean muted) {
        var actor = requireProvisionedUser(firebaseUid);
        if (actor.isEmpty()) return PreferencesResult.userNotProvisioned();
        if (actor.get().isAnonymous) return PreferencesResult.anonymousNotAllowed();
        if (conversations.conversationCompany(conversationId).isEmpty()) return PreferencesResult.notFound();
        if (!conversations.isParticipant(conversationId, actor.get().id)) return PreferencesResult.forbidden();
        conversationPreferences.upsertMuted(conversationId, actor.get().id, muted);
        return PreferencesResult.ok(muted);
    }

    public StartResult start(String firebaseUid, long participantUserId) {
        var actor = requireProvisionedUser(firebaseUid);
        if (actor.isEmpty()) return StartResult.userNotProvisioned();
        if (actor.get().isAnonymous) return StartResult.anonymousNotAllowed();
        if (participantUserId == actor.get().id) return StartResult.invalidParticipant();
        var participant = users.findById(participantUserId);
        if (participant.isEmpty()) return StartResult.notFound();

        Long existing = conversations.findExistingDirect(actor.get().id, participantUserId);
        if (existing == null && !canStartConversation(actor.get(), participant.get())) {
            return StartResult.forbidden();
        }
        long conversationId = existing != null ? existing : conversations.insertConversation(actor.get().companyId);
        OffsetDateTime now = OffsetDateTime.now();
        conversations.addParticipant(conversationId, actor.get().id, now);
        conversations.addParticipant(conversationId, participantUserId, now);
        conversations.markRead(conversationId, actor.get().id, now);

        var summary = conversations.findSummary(conversationId, actor.get().id).orElseGet(() -> {
            ConversationRepository.ConversationSummary s = new ConversationRepository.ConversationSummary();
            s.id = conversationId;
            s.companyId = actor.get().companyId;
            s.otherUserId = participantUserId;
            s.lastMessage = null;
            s.lastMessageAt = null;
            s.activityAt = now;
            s.unreadCount = 0;
            return s;
        });
        Map<String, Object> payload = new HashMap<>();
        payload.put("id", summary.id);
        payload.put("other_user_id", summary.otherUserId);
        payload.put("other_user_profile", UserPayloads.directory(participant.get(), appConfig.defaultProfileImageUrl()));
        payload.put("last_message", summary.lastMessage);
        payload.put("last_message_timestamp", summary.lastMessageAt);
        payload.put("unread_count", summary.unreadCount);
        payload.put("muted", conversationPreferences.isMuted(conversationId, actor.get().id));
        return StartResult.ok(payload);
    }

    public MessagesResult messages(String firebaseUid, long conversationId, String cursor, int limit) {
        var actor = requireProvisionedUser(firebaseUid);
        if (actor.isEmpty()) return MessagesResult.userNotProvisioned();
        if (actor.get().isAnonymous) return MessagesResult.anonymousNotAllowed();

        var company = conversations.conversationCompany(conversationId);
        if (company.isEmpty()) return MessagesResult.notFound();
        if (!conversations.isParticipant(conversationId, actor.get().id)) return MessagesResult.forbidden();
        String blockingStatus = blockingRequestStatus(conversationId, actor.get().id);
        if (blockingStatus != null) {
            return REQUEST_STATUS_REJECTED.equals(blockingStatus)
                    ? MessagesResult.messageRequestRejected()
                    : MessagesResult.messageRequestPending();
        }

        OffsetDateTime cTs = null; Long cId = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                var decoded = Pagination.decode(cursor);
                cTs = decoded.timestamp();
                cId = decoded.id();
            } catch (IllegalArgumentException ignored) {}
        }
        var rows = conversations.listMessages(conversationId, cTs, cId, limit);
        String next = null;
        if (!rows.isEmpty()) {
            var last = rows.get(rows.size() - 1);
            next = Pagination.encode(last.createdAt, last.id);
        }
        if (!rows.isEmpty()) {
            conversations.markRead(conversationId, actor.get().id, OffsetDateTime.now());
        }
        return MessagesResult.ok(rows, next);
    }

    public SendResult send(String firebaseUid, long conversationId, String content, List<MessageAttachment> attachments) {
        var actor = requireProvisionedUser(firebaseUid);
        if (actor.isEmpty()) return SendResult.userNotProvisioned();
        if (actor.get().isAnonymous) return SendResult.anonymousNotAllowed();
        var company = conversations.conversationCompany(conversationId);
        if (company.isEmpty()) return SendResult.notFound();
        if (!conversations.isParticipant(conversationId, actor.get().id)) return SendResult.forbidden();
        if (!attachmentsValid(attachments)) return SendResult.invalidAttachments();
        String blockingStatus = blockingRequestStatus(conversationId, actor.get().id);
        if (blockingStatus != null) {
            return REQUEST_STATUS_REJECTED.equals(blockingStatus)
                    ? SendResult.messageRequestRejected()
                    : SendResult.messageRequestPending();
        }

        var row = conversations.insertMessage(conversationId, actor.get().id, content, attachments);
        if (row != null) {
            conversations.markRead(conversationId, actor.get().id, row.createdAt);
            maybeCreateMessageRequest(actor.get().id, conversationId, row.id);
            try {
                push.onConversationMessageCreated(conversationId, row);
            } catch (Exception ignored) {
            }
        }
        return SendResult.ok(row);
    }

    private boolean attachmentsValid(List<MessageAttachment> attachments) {
        return MessageAttachments.validDmKeys(attachments);
    }

    private Optional<UserRepository.UserRow> requireProvisionedUser(String firebaseUid) {
        var user = users.findByFirebaseUid(firebaseUid);
        if (user.isEmpty() || user.get().companyId == null) return Optional.empty();
        return user;
    }

    public enum Status { OK, USER_NOT_PROVISIONED, NOT_FOUND, FORBIDDEN, ANONYMOUS_NOT_ALLOWED, MESSAGE_REQUEST_PENDING, MESSAGE_REQUEST_REJECTED, INVALID_PARTICIPANT, INVALID_ATTACHMENTS }

    public record ConversationListResult(Status status, List<Map<String, Object>> items, String nextCursor) {
        static ConversationListResult ok(List<Map<String, Object>> items, String next) { return new ConversationListResult(Status.OK, items, next); }
        static ConversationListResult userNotProvisioned() { return new ConversationListResult(Status.USER_NOT_PROVISIONED, List.of(), null); }
        static ConversationListResult anonymousNotAllowed() { return new ConversationListResult(Status.ANONYMOUS_NOT_ALLOWED, List.of(), null); }
    }

    public enum PreferencesStatus { OK, USER_NOT_PROVISIONED, FORBIDDEN, NOT_FOUND, ANONYMOUS_NOT_ALLOWED }

    public record PreferencesResult(PreferencesStatus status, Boolean muted) {
        static PreferencesResult ok(boolean muted) { return new PreferencesResult(PreferencesStatus.OK, muted); }
        static PreferencesResult userNotProvisioned() { return new PreferencesResult(PreferencesStatus.USER_NOT_PROVISIONED, null); }
        static PreferencesResult forbidden() { return new PreferencesResult(PreferencesStatus.FORBIDDEN, null); }
        static PreferencesResult notFound() { return new PreferencesResult(PreferencesStatus.NOT_FOUND, null); }
        static PreferencesResult anonymousNotAllowed() { return new PreferencesResult(PreferencesStatus.ANONYMOUS_NOT_ALLOWED, null); }
    }

    public record StartResult(Status status, Map<String, Object> conversation) {
        static StartResult ok(Map<String, Object> conversation) { return new StartResult(Status.OK, conversation); }
        static StartResult userNotProvisioned() { return new StartResult(Status.USER_NOT_PROVISIONED, null); }
        static StartResult forbidden() { return new StartResult(Status.FORBIDDEN, null); }
        static StartResult notFound() { return new StartResult(Status.NOT_FOUND, null); }
        static StartResult anonymousNotAllowed() { return new StartResult(Status.ANONYMOUS_NOT_ALLOWED, null); }
        static StartResult invalidParticipant() { return new StartResult(Status.INVALID_PARTICIPANT, null); }
    }

    public record MessagesResult(Status status, List<ConversationRepository.MessageRow> messages, String nextCursor) {
        static MessagesResult ok(List<ConversationRepository.MessageRow> messages, String next) { return new MessagesResult(Status.OK, messages, next); }
        static MessagesResult userNotProvisioned() { return new MessagesResult(Status.USER_NOT_PROVISIONED, List.of(), null); }
        static MessagesResult forbidden() { return new MessagesResult(Status.FORBIDDEN, List.of(), null); }
        static MessagesResult notFound() { return new MessagesResult(Status.NOT_FOUND, List.of(), null); }
        static MessagesResult messageRequestPending() { return new MessagesResult(Status.MESSAGE_REQUEST_PENDING, List.of(), null); }
        static MessagesResult messageRequestRejected() { return new MessagesResult(Status.MESSAGE_REQUEST_REJECTED, List.of(), null); }
        static MessagesResult anonymousNotAllowed() { return new MessagesResult(Status.ANONYMOUS_NOT_ALLOWED, List.of(), null); }
    }

    public record SendResult(Status status, ConversationRepository.MessageRow message) {
        static SendResult ok(ConversationRepository.MessageRow message) { return new SendResult(Status.OK, message); }
        static SendResult userNotProvisioned() { return new SendResult(Status.USER_NOT_PROVISIONED, null); }
        static SendResult forbidden() { return new SendResult(Status.FORBIDDEN, null); }
        static SendResult notFound() { return new SendResult(Status.NOT_FOUND, null); }
        static SendResult messageRequestPending() { return new SendResult(Status.MESSAGE_REQUEST_PENDING, null); }
        static SendResult messageRequestRejected() { return new SendResult(Status.MESSAGE_REQUEST_REJECTED, null); }
        static SendResult anonymousNotAllowed() { return new SendResult(Status.ANONYMOUS_NOT_ALLOWED, null); }
        static SendResult invalidAttachments() { return new SendResult(Status.INVALID_ATTACHMENTS, null); }
    }

    private void maybeCreateMessageRequest(long senderId, long conversationId, long messageId) {
        if (messageRequests.hasApprovedForConversation(conversationId)) return;
        List<Long> recipients = conversations.listOtherParticipantIds(conversationId, senderId);
        if (recipients.isEmpty()) return;
        long recipientId = recipients.get(0);
        var senderPrincipal = principals.createForUser(senderId);
        var recipientPrincipal = principals.createForUser(recipientId);
        boolean followsSender = follows.exists(recipientPrincipal.id, senderPrincipal.id);
        if (followsSender) return;

        var existing = messageRequests.findByConversationRecipient(conversationId, recipientId);
        if (existing.isEmpty()) {
            boolean created = messageRequests.insertPending(conversationId, senderId, recipientId, messageId);
            if (created) {
                try {
                    notifications.notifyMessageRequest(recipientId, senderPrincipal.id, conversationId, messageId);
                } catch (RuntimeException ignored) {}
            }
            return;
        }
        if (REQUEST_STATUS_PENDING.equals(existing.get().status)) {
            messageRequests.updatePendingMessage(conversationId, recipientId, messageId);
        }
    }

    private String blockingRequestStatus(long conversationId, long actorId) {
        var request = messageRequests.findByConversationRecipient(conversationId, actorId);
        if (request.isEmpty()) return null;
        if (REQUEST_STATUS_APPROVED.equals(request.get().status)) return null;
        if (messageRequests.hasApprovedForConversation(conversationId)) return null;
        return request.get().status;
    }

    private boolean canStartConversation(UserRepository.UserRow sender, UserRepository.UserRow recipient) {
        String permission = normalizeMessagePermission(recipient.messagePermission);
        if (permission == null || MESSAGE_PERMISSION_COMPANY.equals(permission)) {
            return sender.companyId.equals(recipient.companyId);
        }
        if (MESSAGE_PERMISSION_NO_ONE.equals(permission)) {
            return false;
        }
        if (MESSAGE_PERMISSION_ALL.equals(permission)) {
            return true;
        }
        if (MESSAGE_PERMISSION_FOLLOWING.equals(permission)) {
            if (!sender.companyId.equals(recipient.companyId)) {
                return false;
            }
            var senderPrincipal = principals.createForUser(sender.id);
            var recipientPrincipal = principals.createForUser(recipient.id);
            return follows.exists(recipientPrincipal.id, senderPrincipal.id);
        }
        return sender.companyId.equals(recipient.companyId);
    }

    private String normalizeMessagePermission(String raw) {
        if (raw == null) return null;
        String normalized = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.isBlank()) return null;
        return normalized;
    }
}

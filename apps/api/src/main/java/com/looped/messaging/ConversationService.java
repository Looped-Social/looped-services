package com.looped.messaging;

import com.looped.shared.Pagination;
import com.looped.principals.PrincipalRepository;
import com.looped.users.FollowsRepository;
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

    private final ConversationRepository conversations;
    private final UserRepository users;
    private final FollowsRepository follows;
    private final PrincipalRepository principals;
    private final MessageRequestRepository messageRequests;

    public ConversationService(ConversationRepository conversations,
                               UserRepository users,
                               FollowsRepository follows,
                               PrincipalRepository principals,
                               MessageRequestRepository messageRequests) {
        this.conversations = conversations;
        this.users = users;
        this.follows = follows;
        this.principals = principals;
        this.messageRequests = messageRequests;
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

        List<Map<String, Object>> items = rows.stream().map(row -> {
            Map<String, Object> out = new HashMap<>();
            out.put("id", row.id);
            out.put("other_user_id", row.otherUserId);
            if (row.otherUserId != null) {
                users.findById(row.otherUserId).ifPresent(u -> out.put("other_user_profile", UserPayloads.directory(u)));
            }
            out.put("last_message", row.lastMessage);
            out.put("last_message_timestamp", row.lastMessageAt);
            out.put("unread_count", row.unreadCount);
            return out;
        }).toList();
        return ConversationListResult.ok(items, next);
    }

    public StartResult start(String firebaseUid, long participantUserId) {
        var actor = requireProvisionedUser(firebaseUid);
        if (actor.isEmpty()) return StartResult.userNotProvisioned();
        if (actor.get().isAnonymous) return StartResult.anonymousNotAllowed();
        var participant = users.findById(participantUserId);
        if (participant.isEmpty()) return StartResult.notFound();
        if (!actor.get().companyId.equals(participant.get().companyId)) return StartResult.forbidden();

        Long existing = conversations.findExistingDirect(actor.get().id, participantUserId);
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
        users.findById(participantUserId).ifPresent(u -> payload.put("other_user_profile", UserPayloads.directory(u)));
        payload.put("last_message", summary.lastMessage);
        payload.put("last_message_timestamp", summary.lastMessageAt);
        payload.put("unread_count", summary.unreadCount);
        return StartResult.ok(payload);
    }

    public MessagesResult messages(String firebaseUid, long conversationId, String cursor, int limit) {
        var actor = requireProvisionedUser(firebaseUid);
        if (actor.isEmpty()) return MessagesResult.userNotProvisioned();
        if (actor.get().isAnonymous) return MessagesResult.anonymousNotAllowed();

        var company = conversations.conversationCompany(conversationId);
        if (company.isEmpty()) return MessagesResult.notFound();
        if (!company.get().equals(actor.get().companyId)) return MessagesResult.forbidden();
        if (!conversations.isParticipant(conversationId, actor.get().id)) return MessagesResult.forbidden();
        var request = messageRequests.findByConversationRecipient(conversationId, actor.get().id);
        if (request.isPresent() && !REQUEST_STATUS_APPROVED.equals(request.get().status)) {
            return REQUEST_STATUS_REJECTED.equals(request.get().status)
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
        if (rows.size() == limit) {
            var last = rows.get(rows.size() - 1);
            next = Pagination.encode(last.createdAt, last.id);
        }
        if (!rows.isEmpty()) {
            conversations.markRead(conversationId, actor.get().id, OffsetDateTime.now());
        }
        return MessagesResult.ok(rows, next);
    }

    public SendResult send(String firebaseUid, long conversationId, String content, List<String> attachments) {
        var actor = requireProvisionedUser(firebaseUid);
        if (actor.isEmpty()) return SendResult.userNotProvisioned();
        if (actor.get().isAnonymous) return SendResult.anonymousNotAllowed();
        var company = conversations.conversationCompany(conversationId);
        if (company.isEmpty()) return SendResult.notFound();
        if (!company.get().equals(actor.get().companyId)) return SendResult.forbidden();
        if (!conversations.isParticipant(conversationId, actor.get().id)) return SendResult.forbidden();
        var request = messageRequests.findByConversationRecipient(conversationId, actor.get().id);
        if (request.isPresent() && !REQUEST_STATUS_APPROVED.equals(request.get().status)) {
            return REQUEST_STATUS_REJECTED.equals(request.get().status)
                    ? SendResult.messageRequestRejected()
                    : SendResult.messageRequestPending();
        }

        var row = conversations.insertMessage(conversationId, actor.get().id, content, attachments);
        if (row != null) {
            conversations.markRead(conversationId, actor.get().id, row.createdAt);
            maybeCreateMessageRequest(actor.get().id, conversationId, row.id);
        }
        return SendResult.ok(row);
    }

    private Optional<UserRepository.UserRow> requireProvisionedUser(String firebaseUid) {
        var user = users.findByFirebaseUid(firebaseUid);
        if (user.isEmpty() || user.get().companyId == null) return Optional.empty();
        return user;
    }

    public enum Status { OK, USER_NOT_PROVISIONED, NOT_FOUND, FORBIDDEN, ANONYMOUS_NOT_ALLOWED, MESSAGE_REQUEST_PENDING, MESSAGE_REQUEST_REJECTED }

    public record ConversationListResult(Status status, List<Map<String, Object>> items, String nextCursor) {
        static ConversationListResult ok(List<Map<String, Object>> items, String next) { return new ConversationListResult(Status.OK, items, next); }
        static ConversationListResult userNotProvisioned() { return new ConversationListResult(Status.USER_NOT_PROVISIONED, List.of(), null); }
        static ConversationListResult anonymousNotAllowed() { return new ConversationListResult(Status.ANONYMOUS_NOT_ALLOWED, List.of(), null); }
    }

    public record StartResult(Status status, Map<String, Object> conversation) {
        static StartResult ok(Map<String, Object> conversation) { return new StartResult(Status.OK, conversation); }
        static StartResult userNotProvisioned() { return new StartResult(Status.USER_NOT_PROVISIONED, null); }
        static StartResult forbidden() { return new StartResult(Status.FORBIDDEN, null); }
        static StartResult notFound() { return new StartResult(Status.NOT_FOUND, null); }
        static StartResult anonymousNotAllowed() { return new StartResult(Status.ANONYMOUS_NOT_ALLOWED, null); }
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
    }

    private void maybeCreateMessageRequest(long senderId, long conversationId, long messageId) {
        List<Long> recipients = conversations.listOtherParticipantIds(conversationId, senderId);
        if (recipients.isEmpty()) return;
        long recipientId = recipients.get(0);
        var senderPrincipal = principals.createForUser(senderId);
        var recipientPrincipal = principals.createForUser(recipientId);
        boolean followsSender = follows.exists(recipientPrincipal.id, senderPrincipal.id);
        if (followsSender) return;

        var existing = messageRequests.findByConversationRecipient(conversationId, recipientId);
        if (existing.isEmpty()) {
            messageRequests.insertPending(conversationId, senderId, recipientId, messageId);
            return;
        }
        if (REQUEST_STATUS_PENDING.equals(existing.get().status)) {
            messageRequests.updatePendingMessage(conversationId, recipientId, messageId);
        }
    }
}

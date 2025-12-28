package com.looped.messaging;

import com.looped.shared.Pagination;
import com.looped.users.UserPayloads;
import com.looped.users.UserRepository;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class MessageRequestService {
    private static final String STATUS_PENDING = "pending";
    private static final String STATUS_APPROVED = "approved";
    private static final String STATUS_REJECTED = "rejected";

    private final MessageRequestRepository requests;
    private final UserRepository users;

    public MessageRequestService(MessageRequestRepository requests, UserRepository users) {
        this.requests = requests;
        this.users = users;
    }

    public ListResult list(String firebaseUid, String cursor, int limit) {
        var actor = requireProvisionedUser(firebaseUid);
        if (actor.isEmpty()) return ListResult.userNotProvisioned();
        if (actor.get().isAnonymous) return ListResult.anonymousNotAllowed();

        OffsetDateTime cTs = null; Long cId = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                var decoded = Pagination.decode(cursor);
                cTs = decoded.timestamp();
                cId = decoded.id();
            } catch (IllegalArgumentException ignored) {}
        }
        var rows = requests.listPending(actor.get().id, cTs, cId, limit);
        String next = null;
        if (rows.size() == limit) {
            var last = rows.get(rows.size() - 1);
            next = Pagination.encode(last.updatedAt, last.id);
        }
        List<Map<String, Object>> items = rows.stream().map(row -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", row.id);
            map.put("conversation_id", row.conversationId);
            map.put("requester_id", row.requesterId);
            map.put("status", row.status);
            map.put("created_at", row.createdAt);
            Map<String, Object> message = new HashMap<>();
            message.put("id", row.messageId);
            message.put("sender_id", row.requesterId);
            message.put("content", row.messageContent);
            message.put("attachments", row.messageAttachments);
            message.put("created_at", row.messageCreatedAt);
            map.put("message", message);
            users.findById(row.requesterId).ifPresent(u -> map.put("requester_profile", UserPayloads.directory(u)));
            return map;
        }).toList();
        return ListResult.ok(items, next);
    }

    public ResolveResult approve(String firebaseUid, long requestId) {
        return resolve(firebaseUid, requestId, STATUS_APPROVED);
    }

    public ResolveResult reject(String firebaseUid, long requestId) {
        return resolve(firebaseUid, requestId, STATUS_REJECTED);
    }

    private ResolveResult resolve(String firebaseUid, long requestId, String targetStatus) {
        var actor = requireProvisionedUser(firebaseUid);
        if (actor.isEmpty()) return ResolveResult.userNotProvisioned();
        if (actor.get().isAnonymous) return ResolveResult.anonymousNotAllowed();

        var request = requests.findById(requestId);
        if (request.isEmpty()) return ResolveResult.notFound();
        if (request.get().recipientId != actor.get().id) return ResolveResult.forbidden();

        String status = request.get().status;
        if (STATUS_PENDING.equals(status)) {
            requests.updateStatus(requestId, actor.get().id, targetStatus);
            status = targetStatus;
        }
        return ResolveResult.ok(status);
    }

    private Optional<UserRepository.UserRow> requireProvisionedUser(String firebaseUid) {
        var user = users.findByFirebaseUid(firebaseUid);
        if (user.isEmpty() || user.get().companyId == null) return Optional.empty();
        return user;
    }

    public enum Status { OK, USER_NOT_PROVISIONED, NOT_FOUND, FORBIDDEN, ANONYMOUS_NOT_ALLOWED }

    public record ListResult(Status status, List<Map<String, Object>> items, String nextCursor) {
        static ListResult ok(List<Map<String, Object>> items, String next) { return new ListResult(Status.OK, items, next); }
        static ListResult userNotProvisioned() { return new ListResult(Status.USER_NOT_PROVISIONED, List.of(), null); }
        static ListResult anonymousNotAllowed() { return new ListResult(Status.ANONYMOUS_NOT_ALLOWED, List.of(), null); }
    }

    public record ResolveResult(Status status, String requestStatus) {
        static ResolveResult ok(String requestStatus) { return new ResolveResult(Status.OK, requestStatus); }
        static ResolveResult userNotProvisioned() { return new ResolveResult(Status.USER_NOT_PROVISIONED, null); }
        static ResolveResult notFound() { return new ResolveResult(Status.NOT_FOUND, null); }
        static ResolveResult forbidden() { return new ResolveResult(Status.FORBIDDEN, null); }
        static ResolveResult anonymousNotAllowed() { return new ResolveResult(Status.ANONYMOUS_NOT_ALLOWED, null); }
    }
}

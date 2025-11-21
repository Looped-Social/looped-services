package com.looped.notifications;

import com.looped.shared.Pagination;
import com.looped.users.UserRepository;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class NotificationService {
    private final NotificationRepository repo;
    private final UserRepository users;

    public NotificationService(NotificationRepository repo, UserRepository users) {
        this.repo = repo;
        this.users = users;
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
        var rows = repo.findByUser(actor.get().id, cTs, cId, limit);
        String next = null;
        if (rows.size() == limit) {
            var last = rows.get(rows.size() - 1);
            next = Pagination.encode(last.createdAt, last.id);
        }
        return ListResult.ok(rows, next);
    }

    public MarkReadResult markRead(String firebaseUid, long notificationId) {
        var actor = requireProvisionedUser(firebaseUid);
        if (actor.isEmpty()) return MarkReadResult.userNotProvisioned();
        var notif = repo.findById(notificationId);
        if (notif.isEmpty()) return MarkReadResult.notFound();
        if (notif.get().userId != actor.get().id) return MarkReadResult.forbidden();
        repo.markRead(notificationId, actor.get().id, OffsetDateTime.now());
        return MarkReadResult.ok();
    }

    private Optional<UserRepository.UserRow> requireProvisionedUser(String firebaseUid) {
        var user = users.findByFirebaseUid(firebaseUid);
        if (user.isEmpty() || user.get().companyId == null) return Optional.empty();
        return user;
    }

    public enum Status { OK, USER_NOT_PROVISIONED, FORBIDDEN, NOT_FOUND }

    public record ListResult(Status status, List<NotificationRepository.NotificationRow> notifications, String nextCursor) {
        static ListResult ok(List<NotificationRepository.NotificationRow> notifications, String next) { return new ListResult(Status.OK, notifications, next); }
        static ListResult userNotProvisioned() { return new ListResult(Status.USER_NOT_PROVISIONED, List.of(), null); }
    }

    public record MarkReadResult(Status status) {
        static MarkReadResult ok() { return new MarkReadResult(Status.OK); }
        static MarkReadResult userNotProvisioned() { return new MarkReadResult(Status.USER_NOT_PROVISIONED); }
        static MarkReadResult forbidden() { return new MarkReadResult(Status.FORBIDDEN); }
        static MarkReadResult notFound() { return new MarkReadResult(Status.NOT_FOUND); }
    }
}

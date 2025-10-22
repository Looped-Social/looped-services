package com.looped.moderation;

import com.looped.users.UserRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class ModerationService {
    private final ReportRepository reports;
    private final UserRepository users;

    public ModerationService(ReportRepository reports, UserRepository users) {
        this.reports = reports;
        this.users = users;
    }

    public CreateResult create(String firebaseUid, String targetType, long targetId, String reason) {
        var u = users.findByFirebaseUid(firebaseUid);
        if (u.isEmpty()) return CreateResult.userNotProvisioned();
        long id = reports.insert(targetType, targetId, u.get().id, reason);
        return CreateResult.ok(id);
    }

    public ListResult list(String firebaseUid, String status) {
        var u = users.findByFirebaseUid(firebaseUid);
        if (u.isEmpty()) return ListResult.userNotProvisioned();
        var list = reports.listByReporter(u.get().id, status);
        return ListResult.ok(list);
    }

    public UpdateResult resolve(String firebaseUid, long id) {
        var u = users.findByFirebaseUid(firebaseUid);
        if (u.isEmpty()) return UpdateResult.userNotProvisioned();
        var existing = reports.findById(id);
        if (existing.isEmpty()) return UpdateResult.notFound();
        if (existing.get().reporterId != u.get().id) return UpdateResult.forbidden();
        boolean updated = reports.updateStatus(id, "resolved");
        return updated ? UpdateResult.ok("resolved") : UpdateResult.notFound();
    }

    public enum Status { OK, USER_NOT_PROVISIONED, FORBIDDEN, NOT_FOUND }
    public record CreateResult(Status status, Long id) {
        static CreateResult ok(long id) { return new CreateResult(Status.OK, id); }
        static CreateResult userNotProvisioned() { return new CreateResult(Status.USER_NOT_PROVISIONED, null); }
    }
    public record ListResult(Status status, List<ReportRepository.ReportRow> items) {
        static ListResult ok(List<ReportRepository.ReportRow> list) { return new ListResult(Status.OK, list); }
        static ListResult userNotProvisioned() { return new ListResult(Status.USER_NOT_PROVISIONED, List.of()); }
    }
    public record UpdateResult(Status status, String newStatus) {
        static UpdateResult ok(String s) { return new UpdateResult(Status.OK, s); }
        static UpdateResult userNotProvisioned() { return new UpdateResult(Status.USER_NOT_PROVISIONED, null); }
        static UpdateResult forbidden() { return new UpdateResult(Status.FORBIDDEN, null); }
        static UpdateResult notFound() { return new UpdateResult(Status.NOT_FOUND, null); }
    }
}


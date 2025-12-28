package com.looped.moderation;

import com.looped.principals.PrincipalRepository;
import com.looped.shared.Pagination;
import com.looped.users.UserRepository;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

@Service
public class ViolationsService {
    private final ViolationsRepository violations;
    private final UserRepository users;
    private final PrincipalRepository principals;

    public ViolationsService(ViolationsRepository violations, UserRepository users, PrincipalRepository principals) {
        this.violations = violations;
        this.users = users;
        this.principals = principals;
    }

    public ListResult list(String firebaseUid, String cursor, int limit) {
        var userOpt = users.findByFirebaseUid(firebaseUid);
        if (userOpt.isEmpty()) return ListResult.userNotProvisioned();
        var principal = principals.createForUser(userOpt.get().id);

        OffsetDateTime cTs = null; Long cId = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                var decoded = Pagination.decode(cursor);
                cTs = decoded.timestamp();
                cId = decoded.id();
            } catch (IllegalArgumentException ignored) {}
        }
        var rows = violations.list(userOpt.get().id, principal.id, cTs, cId, limit);
        String next = null;
        if (rows.size() == limit) {
            var last = rows.get(rows.size() - 1);
            next = Pagination.encode(last.createdAt, last.targetId);
        }
        return ListResult.ok(rows, next);
    }

    public enum Status { OK, USER_NOT_PROVISIONED }

    public record ListResult(Status status, List<ViolationsRepository.ViolationRow> items, String nextCursor) {
        static ListResult ok(List<ViolationsRepository.ViolationRow> items, String next) { return new ListResult(Status.OK, items, next); }
        static ListResult userNotProvisioned() { return new ListResult(Status.USER_NOT_PROVISIONED, List.of(), null); }
    }
}

package com.looped.moderation;

import com.looped.posts.PostRepository;
import com.looped.principals.PrincipalRepository;
import com.looped.users.UserBanRepository;
import com.looped.users.UserRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

@Service
public class AppealService {
    private final AppealRepository appeals;
    private final UserRepository users;
    private final UserBanRepository bans;
    private final PostRepository posts;
    private final PrincipalRepository principals;

    public AppealService(AppealRepository appeals, UserRepository users, UserBanRepository bans,
                         PostRepository posts, PrincipalRepository principals) {
        this.appeals = appeals;
        this.users = users;
        this.bans = bans;
        this.posts = posts;
        this.principals = principals;
    }

    public CreateResult create(String firebaseUid, String targetType, Long targetId, String reason) {
        var userOpt = users.findByFirebaseUid(firebaseUid);
        if (userOpt.isEmpty()) return CreateResult.userNotProvisioned();
        var user = userOpt.get();
        String normalized = normalizeTargetType(targetType);
        if (normalized == null) return CreateResult.invalidTargetType();
        try {
            return switch (normalized) {
                case "user_ban" -> createUserBanAppeal(user.id, reason);
                case "post_removal" -> createPostRemovalAppeal(user.id, targetId, reason);
                default -> CreateResult.invalidTargetType();
            };
        } catch (DuplicateKeyException e) {
            return CreateResult.duplicate();
        }
    }

    public ListResult list(String firebaseUid, String status) {
        var userOpt = users.findByFirebaseUid(firebaseUid);
        if (userOpt.isEmpty()) return ListResult.userNotProvisioned();
        var items = appeals.listByUser(userOpt.get().id, normalizeStatus(status));
        if (items.isEmpty()) return ListResult.ok(items);
        var filtered = items.stream().filter(row -> {
            if (!"post_removal".equalsIgnoreCase(row.targetType)) return true;
            var postOpt = posts.findByIdIncludingRemoved(row.targetId);
            if (postOpt.isEmpty()) return true;
            var post = postOpt.get();
            return post.removedReason == null || !"user_deleted".equalsIgnoreCase(post.removedReason);
        }).toList();
        return ListResult.ok(filtered);
    }

    private CreateResult createUserBanAppeal(long userId, String reason) {
        var banOpt = bans.findActiveByUserId(userId);
        if (banOpt.isEmpty()) return CreateResult.noActiveBan();
        long appealId = appeals.insert(userId, "user_ban", banOpt.get().id, reason);
        return CreateResult.ok(appealId);
    }

    private CreateResult createPostRemovalAppeal(long userId, Long targetId, String reason) {
        if (targetId == null) return CreateResult.invalidTarget();
        var postOpt = posts.findByIdIncludingRemoved(targetId);
        if (postOpt.isEmpty()) return CreateResult.notFound();
        var post = postOpt.get();
        if (post.removedAt == null) return CreateResult.notRemoved();
        var principal = principals.createForUser(userId);
        if (post.authorPrincipalId != principal.id) return CreateResult.forbidden();
        if ("user_deleted".equalsIgnoreCase(post.removedReason)) return CreateResult.selfDeleted();
        long appealId = appeals.insert(userId, "post_removal", targetId, reason);
        return CreateResult.ok(appealId);
    }

    private String normalizeTargetType(String raw) {
        if (raw == null) return null;
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) return null;
        if (!normalized.equals("user_ban") && !normalized.equals("post_removal")) return null;
        return normalized;
    }

    private String normalizeStatus(String raw) {
        if (raw == null) return null;
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return normalized.isBlank() ? null : normalized;
    }

    public enum Status {
        OK,
        USER_NOT_PROVISIONED,
        INVALID_TARGET_TYPE,
        INVALID_TARGET,
        NOT_FOUND,
        NOT_REMOVED,
        SELF_DELETED,
        FORBIDDEN,
        DUPLICATE,
        NO_ACTIVE_BAN
    }

    public record CreateResult(Status status, Long id) {
        static CreateResult ok(long id) { return new CreateResult(Status.OK, id); }
        static CreateResult userNotProvisioned() { return new CreateResult(Status.USER_NOT_PROVISIONED, null); }
        static CreateResult invalidTargetType() { return new CreateResult(Status.INVALID_TARGET_TYPE, null); }
        static CreateResult invalidTarget() { return new CreateResult(Status.INVALID_TARGET, null); }
        static CreateResult notFound() { return new CreateResult(Status.NOT_FOUND, null); }
        static CreateResult notRemoved() { return new CreateResult(Status.NOT_REMOVED, null); }
        static CreateResult selfDeleted() { return new CreateResult(Status.SELF_DELETED, null); }
        static CreateResult forbidden() { return new CreateResult(Status.FORBIDDEN, null); }
        static CreateResult duplicate() { return new CreateResult(Status.DUPLICATE, null); }
        static CreateResult noActiveBan() { return new CreateResult(Status.NO_ACTIVE_BAN, null); }
    }

    public record ListResult(Status status, List<AppealRepository.AppealRow> items) {
        static ListResult ok(List<AppealRepository.AppealRow> items) { return new ListResult(Status.OK, items); }
        static ListResult userNotProvisioned() { return new ListResult(Status.USER_NOT_PROVISIONED, List.of()); }
    }
}

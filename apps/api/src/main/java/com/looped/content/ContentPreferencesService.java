package com.looped.content;

import com.looped.users.UserRepository;
import org.springframework.stereotype.Service;

@Service
public class ContentPreferencesService {
    private final UserRepository users;

    public ContentPreferencesService(UserRepository users) {
        this.users = users;
    }

    public Result get(String firebaseUid) {
        var actor = users.findByFirebaseUid(firebaseUid);
        if (actor.isEmpty() || actor.get().companyId == null) return Result.userNotProvisioned();
        return Result.ok(actor.get().hideAnonymousPosts);
    }

    public Result update(String firebaseUid, boolean hideAnonymousPosts) {
        var actor = users.findByFirebaseUid(firebaseUid);
        if (actor.isEmpty() || actor.get().companyId == null) return Result.userNotProvisioned();
        users.updateHideAnonymousPosts(actor.get().id, hideAnonymousPosts);
        return Result.ok(hideAnonymousPosts);
    }

    public enum Status { OK, USER_NOT_PROVISIONED }

    public record Result(Status status, boolean hideAnonymousPosts) {
        static Result ok(boolean hideAnonymousPosts) { return new Result(Status.OK, hideAnonymousPosts); }
        static Result userNotProvisioned() { return new Result(Status.USER_NOT_PROVISIONED, false); }
    }
}


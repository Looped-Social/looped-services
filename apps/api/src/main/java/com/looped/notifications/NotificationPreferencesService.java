package com.looped.notifications;

import com.looped.principals.PrincipalRepository;
import com.looped.users.UserRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class NotificationPreferencesService {
    private final NotificationPreferencesRepository repo;
    private final UserRepository users;
    private final PrincipalRepository principals;

    public NotificationPreferencesService(NotificationPreferencesRepository repo,
                                          UserRepository users,
                                          PrincipalRepository principals) {
        this.repo = repo;
        this.users = users;
        this.principals = principals;
    }

    public PreferencesResult get(String firebaseUid) {
        var principal = requirePrincipal(firebaseUid);
        if (principal.isEmpty()) return PreferencesResult.userNotProvisioned();
        NotificationPreferences prefs = loadForPrincipal(principal.get());
        return PreferencesResult.ok(prefs);
    }

    public PreferencesResult update(String firebaseUid, NotificationPreferencesUpdate update) {
        var principal = requirePrincipal(firebaseUid);
        if (principal.isEmpty()) return PreferencesResult.userNotProvisioned();
        NotificationPreferences current = loadForPrincipal(principal.get());
        NotificationPreferences merged = current.applyUpdate(update);
        repo.upsert(principal.get(), merged.toMap());
        return PreferencesResult.ok(merged);
    }

    public NotificationPreferences preferencesForUserId(long userId) {
        var principal = principals.createForUser(userId);
        return loadForPrincipal(principal.id);
    }

    private NotificationPreferences loadForPrincipal(long principalId) {
        var stored = repo.findByPrincipalId(principalId).orElse(null);
        return NotificationPreferences.from(stored);
    }

    private Optional<Long> requirePrincipal(String firebaseUid) {
        var user = users.findByFirebaseUid(firebaseUid);
        if (user.isEmpty() || user.get().companyId == null) return Optional.empty();
        var principal = principals.createForUser(user.get().id);
        return Optional.of(principal.id);
    }

    public enum Status { OK, USER_NOT_PROVISIONED }

    public record PreferencesResult(Status status, NotificationPreferences preferences) {
        static PreferencesResult ok(NotificationPreferences prefs) { return new PreferencesResult(Status.OK, prefs); }
        static PreferencesResult userNotProvisioned() { return new PreferencesResult(Status.USER_NOT_PROVISIONED, null); }
    }
}

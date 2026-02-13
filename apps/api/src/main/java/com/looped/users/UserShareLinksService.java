package com.looped.users;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@Service
public class UserShareLinksService {
    private static final Set<String> RESERVED_SLUGS = Set.of(
            "app", "admin", "privacy", "terms", "support", "help", "about", "contact",
            "login", "signup", "register", "settings", "feed", "home", "explore", "search",
            "notifications", "messages", "u", "v1", "api", "docs", "careers", "jobs", "blog"
    );

    private final UserRepository users;
    private final UserShareSlugRepository shareSlugs;
    private final String publicBaseUrl;

    public UserShareLinksService(UserRepository users,
                                 UserShareSlugRepository shareSlugs,
                                 @Value("${share.base-url:https://mylooped.app}") String publicBaseUrl) {
        this.users = users;
        this.shareSlugs = shareSlugs;
        this.publicBaseUrl = trimTrailingSlash(publicBaseUrl);
    }

    public static String normalizeSlug(String raw) {
        if (raw == null) return null;
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("@")) normalized = normalized.substring(1);
        if (normalized.isBlank()) return null;
        if (!normalized.matches("^[a-z0-9_]{3,30}$")) return null;
        return normalized;
    }

    public static boolean isReservedSlug(String normalizedSlug) {
        if (normalizedSlug == null || normalizedSlug.isBlank()) return false;
        return RESERVED_SLUGS.contains(normalizedSlug.toLowerCase(Locale.ROOT));
    }

    public ResolveResult resolveBySlug(String rawSlug) {
        String slug = normalizeSlug(rawSlug);
        if (slug == null) return ResolveResult.notFound();

        var owner = shareSlugs.findActiveBySlug(slug);
        if (owner.isEmpty()) return ResolveResult.notFound();

        var user = users.findByIdIncludingDeleted(owner.get().userId);
        if (user.isEmpty()) return ResolveResult.notFound();
        if (user.get().deletedAt != null || user.get().disabledAt != null) return ResolveResult.unavailable();
        if (user.get().companyId == null) return ResolveResult.notFound();
        return ResolveResult.ok(user.get());
    }

    public AvailabilityResult availability(String firebaseUid, String rawSlug) {
        String slug = normalizeSlug(rawSlug);
        if (slug == null) return AvailabilityResult.invalid();
        if (isReservedSlug(slug)) return AvailabilityResult.reserved(slug);

        Long me = users.findByFirebaseUid(firebaseUid).map(u -> u.id).orElse(null);
        var owner = shareSlugs.findActiveBySlug(slug);
        if (owner.isEmpty()) return AvailabilityResult.ok(slug, true, false, false);

        boolean ownedByMe = me != null && owner.get().userId == me.longValue();
        return AvailabilityResult.ok(slug, ownedByMe, ownedByMe, false);
    }

    public SettingsResult mySettings(String firebaseUid) {
        var me = users.findByFirebaseUid(firebaseUid);
        if (me.isEmpty() || me.get().companyId == null) return SettingsResult.userNotProvisioned();
        String usernameSlug = normalizeSlug(me.get().handle);
        if (usernameSlug == null) return SettingsResult.userNotProvisioned();
        if (isReservedSlug(usernameSlug)) return SettingsResult.userNotProvisioned();

        var usernameRow = shareSlugs.findActiveUsernameSlug(me.get().id);
        if (usernameRow.isEmpty()) {
            return SettingsResult.userNotProvisioned();
        }
        var custom = shareSlugs.findActiveCustomSlug(me.get().id).map(s -> s.slug).orElse(null);
        return SettingsResult.ok(toSettings(usernameSlug, custom));
    }

    public UpdateCustomSlugResult updateCustomSlug(String firebaseUid, String rawCustomSlug) {
        var me = users.findByFirebaseUid(firebaseUid);
        if (me.isEmpty() || me.get().companyId == null) return UpdateCustomSlugResult.userNotProvisioned();

        String usernameSlug = normalizeSlug(me.get().handle);
        if (usernameSlug == null) return UpdateCustomSlugResult.userNotProvisioned();
        if (isReservedSlug(usernameSlug)) return UpdateCustomSlugResult.slugReserved();

        var usernameRow = shareSlugs.findActiveUsernameSlug(me.get().id);
        if (usernameRow.isEmpty()) return UpdateCustomSlugResult.userNotProvisioned();

        if (rawCustomSlug == null) {
            shareSlugs.clearActiveCustomSlug(me.get().id);
            return UpdateCustomSlugResult.ok(toSettings(usernameSlug, null));
        }

        String slug = normalizeSlug(rawCustomSlug);
        if (slug == null) return UpdateCustomSlugResult.slugInvalid();
        if (isReservedSlug(slug)) return UpdateCustomSlugResult.slugReserved();
        if (slug.equals(usernameSlug)) return UpdateCustomSlugResult.slugNotActionable();

        var owner = shareSlugs.findActiveBySlug(slug);
        if (owner.isPresent() && owner.get().userId != me.get().id) {
            return UpdateCustomSlugResult.slugTaken();
        }
        if (owner.isPresent() && owner.get().userId == me.get().id && "username_reserved".equals(owner.get().type)) {
            return UpdateCustomSlugResult.slugNotActionable();
        }
        if (owner.isPresent() && owner.get().userId == me.get().id && "custom".equals(owner.get().type)) {
            return UpdateCustomSlugResult.ok(toSettings(usernameSlug, owner.get().slug));
        }

        try {
            int updated = shareSlugs.updateActiveCustomSlug(me.get().id, slug);
            if (updated == 0) {
                shareSlugs.insertActiveCustomSlug(me.get().id, slug);
            }
        } catch (DataAccessException e) {
            return UpdateCustomSlugResult.slugTaken();
        }
        return UpdateCustomSlugResult.ok(toSettings(usernameSlug, slug));
    }

    private Map<String, Object> toSettings(String usernameSlug, String customSlug) {
        String activeSlug = customSlug != null && !customSlug.isBlank() ? customSlug : usernameSlug;
        String canonicalUrl = publicBaseUrl + "/u/" + activeSlug;
        Map<String, Object> out = new HashMap<>();
        out.put("usernameSlug", usernameSlug);
        out.put("customSlug", customSlug);
        out.put("activeSlug", activeSlug);
        out.put("canonicalUrl", canonicalUrl);
        return out;
    }

    private String trimTrailingSlash(String raw) {
        if (raw == null) return "";
        String value = raw.trim();
        while (value.endsWith("/") && value.length() > 1) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    public enum Status {
        OK,
        NOT_FOUND,
        UNAVAILABLE,
        USER_NOT_PROVISIONED,
        SLUG_INVALID,
        SLUG_RESERVED,
        SLUG_TAKEN,
        SLUG_NOT_ACTIONABLE
    }

    public record ResolveResult(Status status, UserRepository.UserRow user) {
        static ResolveResult ok(UserRepository.UserRow user) { return new ResolveResult(Status.OK, user); }
        static ResolveResult notFound() { return new ResolveResult(Status.NOT_FOUND, null); }
        static ResolveResult unavailable() { return new ResolveResult(Status.UNAVAILABLE, null); }
    }

    public record AvailabilityResult(Status status, String slug, boolean available, boolean ownedByMe, boolean reserved) {
        static AvailabilityResult ok(String slug, boolean available, boolean ownedByMe, boolean reserved) {
            return new AvailabilityResult(Status.OK, slug, available, ownedByMe, reserved);
        }
        static AvailabilityResult invalid() {
            return new AvailabilityResult(Status.SLUG_INVALID, null, false, false, false);
        }
        static AvailabilityResult reserved(String slug) {
            return new AvailabilityResult(Status.OK, slug, false, false, true);
        }
    }

    public record SettingsResult(Status status, Map<String, Object> settings) {
        static SettingsResult ok(Map<String, Object> settings) { return new SettingsResult(Status.OK, settings); }
        static SettingsResult userNotProvisioned() { return new SettingsResult(Status.USER_NOT_PROVISIONED, null); }
    }

    public record UpdateCustomSlugResult(Status status, Map<String, Object> settings) {
        static UpdateCustomSlugResult ok(Map<String, Object> settings) { return new UpdateCustomSlugResult(Status.OK, settings); }
        static UpdateCustomSlugResult userNotProvisioned() { return new UpdateCustomSlugResult(Status.USER_NOT_PROVISIONED, null); }
        static UpdateCustomSlugResult slugInvalid() { return new UpdateCustomSlugResult(Status.SLUG_INVALID, null); }
        static UpdateCustomSlugResult slugReserved() { return new UpdateCustomSlugResult(Status.SLUG_RESERVED, null); }
        static UpdateCustomSlugResult slugTaken() { return new UpdateCustomSlugResult(Status.SLUG_TAKEN, null); }
        static UpdateCustomSlugResult slugNotActionable() { return new UpdateCustomSlugResult(Status.SLUG_NOT_ACTIONABLE, null); }
    }
}

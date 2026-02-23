package com.looped.communities;

import java.util.Map;

public final class CommunityImageSlots {
    private CommunityImageSlots() {}

    public static Resolved resolve(String bannerImageUrl, String profileImageUrl, String fallbackImageUrl) {
        String banner = normalize(bannerImageUrl);
        String profile = normalize(profileImageUrl);
        String fallback = normalize(fallbackImageUrl);

        if (banner == null && profile != null) banner = profile;
        if (profile == null && banner != null) profile = banner;

        if (banner == null && fallback != null) banner = fallback;
        if (profile == null && fallback != null) profile = fallback;

        String legacy = banner != null ? banner : profile;
        return new Resolved(banner, profile, legacy);
    }

    public static void putPayload(Map<String, Object> payload,
                                  String bannerImageUrl,
                                  String profileImageUrl,
                                  String fallbackImageUrl) {
        if (payload == null) return;
        Resolved resolved = resolve(bannerImageUrl, profileImageUrl, fallbackImageUrl);
        if (resolved.bannerImageUrl() != null) payload.put("banner_image_url", resolved.bannerImageUrl());
        if (resolved.profileImageUrl() != null) payload.put("profile_image_url", resolved.profileImageUrl());
        if (resolved.legacyImageUrl() != null) payload.put("image_url", resolved.legacyImageUrl());
    }

    private static String normalize(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    public record Resolved(String bannerImageUrl, String profileImageUrl, String legacyImageUrl) {}
}

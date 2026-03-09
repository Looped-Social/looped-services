package com.looped.communities;

import java.util.Map;

public final class SpecializationBrandingPayloads {
    private SpecializationBrandingPayloads() {}

    public static void putPayload(Map<String, Object> payload,
                                  String iconImageUrl,
                                  String bannerImageUrl) {
        if (payload == null) return;
        String icon = normalize(iconImageUrl);
        String banner = normalize(bannerImageUrl);
        if (icon != null) {
            payload.put("icon_image_url", icon);
            payload.put("iconImageUrl", icon);
        }
        if (banner != null) {
            payload.put("banner_image_url", banner);
            payload.put("bannerImageUrl", banner);
        }
    }

    private static String normalize(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        return trimmed.isBlank() ? null : trimmed;
    }
}

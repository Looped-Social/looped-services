package com.looped.notifications;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashMap;
import java.util.Map;

public record NotificationPreferencesUpdate(@JsonProperty("channels") Map<String, ChannelUpdate> channels,
                                            @JsonProperty("privacy_mode") String privacyMode) {
    public NotificationPreferencesUpdate {
        channels = channels == null ? Map.of() : Map.copyOf(channels);
        privacyMode = normalizePrivacyMode(privacyMode);
    }

    public static NotificationPreferencesUpdate empty() {
        return new NotificationPreferencesUpdate(Map.of(), null);
    }

    public static NotificationPreferencesUpdate from(Map<?, ?> rootRaw) {
        if (rootRaw == null || rootRaw.isEmpty()) return empty();
        String privacyMode = rootRaw.get("privacy_mode") instanceof String s ? normalizePrivacyMode(s) : null;
        Object channelsRaw = rootRaw.get("channels");
        if (!(channelsRaw instanceof Map<?, ?> channelsMapRaw) || channelsMapRaw.isEmpty()) {
            return new NotificationPreferencesUpdate(Map.of(), privacyMode);
        }
        Map<String, ChannelUpdate> channels = new LinkedHashMap<>();
        for (var entry : channelsMapRaw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) continue;
            if (!(entry.getValue() instanceof Map<?, ?> channelRaw)) continue;
            Boolean enabled = coerceBoolean(channelRaw.get("enabled"));
            Map<String, Boolean> types = new LinkedHashMap<>();
            Object typesRaw = channelRaw.get("types");
            if (typesRaw instanceof Map<?, ?> typesMap) {
                for (var typeEntry : typesMap.entrySet()) {
                    if (!(typeEntry.getKey() instanceof String typeKey)) continue;
                    Boolean val = coerceBoolean(typeEntry.getValue());
                    if (val != null) {
                        types.put(typeKey, val);
                    }
                }
            }
            channels.put(key, new ChannelUpdate(enabled, types.isEmpty() ? null : types));
        }
        return new NotificationPreferencesUpdate(channels, privacyMode);
    }

    private static Boolean coerceBoolean(Object value) {
        if (value instanceof Boolean b) return b;
        return null;
    }

    private static String normalizePrivacyMode(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        if ("generic".equals(normalized) || "detailed".equals(normalized)) {
            return normalized;
        }
        return null;
    }

    public record ChannelUpdate(Boolean enabled, Map<String, Boolean> types) {}
}

package com.looped.notifications;

import java.util.LinkedHashMap;
import java.util.Map;

public record NotificationPreferencesUpdate(Map<String, ChannelUpdate> channels) {
    public NotificationPreferencesUpdate {
        channels = channels == null ? Map.of() : Map.copyOf(channels);
    }

    public static NotificationPreferencesUpdate empty() {
        return new NotificationPreferencesUpdate(Map.of());
    }

    public static NotificationPreferencesUpdate from(Map<?, ?> channelsRaw) {
        if (channelsRaw == null || channelsRaw.isEmpty()) return empty();
        Map<String, ChannelUpdate> channels = new LinkedHashMap<>();
        for (var entry : channelsRaw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) continue;
            if (!(entry.getValue() instanceof Map<?, ?> channelMap)) continue;
            Boolean enabled = coerceBoolean(channelMap.get("enabled"));
            Map<String, Boolean> types = new LinkedHashMap<>();
            Object typesRaw = channelMap.get("types");
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
        return new NotificationPreferencesUpdate(channels);
    }

    private static Boolean coerceBoolean(Object value) {
        if (value instanceof Boolean b) return b;
        return null;
    }

    public record ChannelUpdate(Boolean enabled, Map<String, Boolean> types) {}
}

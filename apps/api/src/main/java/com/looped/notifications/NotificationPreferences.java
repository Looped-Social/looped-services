package com.looped.notifications;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class NotificationPreferences {
    private final Map<String, ChannelPreferences> channels;
    private final PrivacyMode privacyMode;

    public NotificationPreferences(Map<String, ChannelPreferences> channels, PrivacyMode privacyMode) {
        this.channels = Map.copyOf(channels);
        this.privacyMode = privacyMode == null ? PrivacyMode.GENERIC : privacyMode;
    }

    public static NotificationPreferences defaults() {
        Map<String, ChannelPreferences> channels = new LinkedHashMap<>();
        channels.put(NotificationChannel.IN_APP.value(), new ChannelPreferences(true, defaultTypes(true)));
        channels.put(NotificationChannel.PUSH.value(), new ChannelPreferences(true, defaultTypes(true)));
        channels.put(NotificationChannel.EMAIL.value(), new ChannelPreferences(false, defaultTypes(false)));
        return new NotificationPreferences(channels, PrivacyMode.GENERIC);
    }

    public static NotificationPreferences from(Map<String, Object> raw) {
        NotificationPreferences base = defaults();
        if (raw == null || raw.isEmpty()) return base;
        NotificationPreferencesUpdate update = NotificationPreferencesUpdate.from(raw);
        return base.applyUpdate(update);
    }

    public NotificationPreferences applyUpdate(NotificationPreferencesUpdate update) {
        if (update == null) return this;
        PrivacyMode mergedPrivacyMode = privacyMode;
        if (update.privacyMode() != null) {
            mergedPrivacyMode = PrivacyMode.fromValue(update.privacyMode()).orElse(privacyMode);
        }
        if (update.channels().isEmpty()) {
            if (mergedPrivacyMode == privacyMode) return this;
            return new NotificationPreferences(channels, mergedPrivacyMode);
        }
        Map<String, ChannelPreferences> merged = new LinkedHashMap<>();
        for (var entry : channels.entrySet()) {
            merged.put(entry.getKey(), entry.getValue());
        }
        for (var entry : update.channels().entrySet()) {
            String channel = entry.getKey();
            if (!channels.containsKey(channel)) continue;
            NotificationPreferencesUpdate.ChannelUpdate updateChannel = entry.getValue();
            if (updateChannel == null) continue;
            ChannelPreferences existing = merged.get(channel);
            boolean enabled = updateChannel.enabled() != null ? updateChannel.enabled() : existing.enabled();
            Map<String, Boolean> types = new LinkedHashMap<>(existing.types());
            if (updateChannel.types() != null) {
                for (var typeEntry : updateChannel.types().entrySet()) {
                    if (!types.containsKey(typeEntry.getKey())) continue;
                    Boolean val = typeEntry.getValue();
                    if (val != null) types.put(typeEntry.getKey(), val);
                }
            }
            merged.put(channel, new ChannelPreferences(enabled, types));
        }
        return new NotificationPreferences(merged, mergedPrivacyMode);
    }

    public boolean allows(NotificationChannel channel, NotificationType type) {
        ChannelPreferences prefs = channels.get(channel.value());
        if (prefs == null) return false;
        if (type == NotificationType.SYSTEM) {
            return prefs.enabled();
        }
        Boolean enabledType = prefs.types().get(type.value());
        return prefs.enabled() && Boolean.TRUE.equals(enabledType);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> channelsOut = new LinkedHashMap<>();
        for (var entry : channels.entrySet()) {
            ChannelPreferences prefs = entry.getValue();
            Map<String, Object> channelMap = new LinkedHashMap<>();
            channelMap.put("enabled", prefs.enabled());
            channelMap.put("types", new LinkedHashMap<>(prefs.types()));
            channelsOut.put(entry.getKey(), channelMap);
        }
        out.put("channels", channelsOut);
        out.put("privacy_mode", privacyMode.value());
        return out;
    }

    public PrivacyMode privacyMode() {
        return privacyMode;
    }

    private static Map<String, Boolean> defaultTypes(boolean enabled) {
        Map<String, Boolean> types = new LinkedHashMap<>();
        for (NotificationType type : NotificationType.values()) {
            boolean val = type == NotificationType.SYSTEM || enabled;
            types.put(type.value(), val);
        }
        return types;
    }

    public record ChannelPreferences(boolean enabled, Map<String, Boolean> types) {
        public ChannelPreferences {
            Objects.requireNonNull(types, "types");
        }
    }

    public enum PrivacyMode {
        GENERIC("generic"),
        DETAILED("detailed");

        private final String value;

        PrivacyMode(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }

        public static java.util.Optional<PrivacyMode> fromValue(String value) {
            if (value == null || value.isBlank()) return java.util.Optional.empty();
            for (PrivacyMode mode : values()) {
                if (mode.value.equalsIgnoreCase(value.trim())) return java.util.Optional.of(mode);
            }
            return java.util.Optional.empty();
        }
    }
}

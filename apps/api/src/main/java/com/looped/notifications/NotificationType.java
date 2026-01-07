package com.looped.notifications;

public enum NotificationType {
    FOLLOW("follow"),
    LIKE("like"),
    COMMENT("comment"),
    REPLY("reply"),
    MENTION("mention"),
    POST_FROM_FOLLOWED("post_from_followed"),
    REPOST("repost"),
    ANNOUNCEMENT("announcement"),
    SYSTEM("system");

    private final String value;

    NotificationType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static java.util.Optional<NotificationType> fromValue(String value) {
        if (value == null || value.isBlank()) return java.util.Optional.empty();
        for (NotificationType type : values()) {
            if (type.value.equals(value)) return java.util.Optional.of(type);
        }
        return java.util.Optional.empty();
    }
}

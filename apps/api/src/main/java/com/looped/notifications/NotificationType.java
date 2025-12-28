package com.looped.notifications;

public enum NotificationType {
    FOLLOW("follow"),
    LIKE("like"),
    COMMENT("comment"),
    MENTION("mention"),
    POST_FROM_FOLLOWED("post_from_followed"),
    ANNOUNCEMENT("announcement"),
    SYSTEM("system");

    private final String value;

    NotificationType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}

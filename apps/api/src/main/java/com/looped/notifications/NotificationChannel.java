package com.looped.notifications;

public enum NotificationChannel {
    IN_APP("in_app"),
    PUSH("push"),
    EMAIL("email");

    private final String value;

    NotificationChannel(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}

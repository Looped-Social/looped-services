package com.looped.users;

public final class ProfileImageUrls {
    private ProfileImageUrls() {}

    public static String resolve(String profileImageUrl, String defaultProfileImageUrl) {
        if (profileImageUrl != null && !profileImageUrl.isBlank()) return profileImageUrl;
        if (defaultProfileImageUrl != null && !defaultProfileImageUrl.isBlank()) return defaultProfileImageUrl;
        return null;
    }
}

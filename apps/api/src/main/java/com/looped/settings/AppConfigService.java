package com.looped.settings;

import com.looped.users.UsersProperties;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class AppConfigService {
    private final AppSettingsRepository settings;
    private final UsersProperties usersProperties;

    public AppConfigService(AppSettingsRepository settings, UsersProperties usersProperties) {
        this.settings = settings;
        this.usersProperties = usersProperties;
    }

    public String defaultProfileImageUrl() {
        return settings.findString(AppSettingsKeys.USERS_DEFAULT_PROFILE_IMAGE_URL)
                .orElseGet(() -> {
                    String fallback = usersProperties.getDefaultProfileImageUrl();
                    if (fallback == null) return null;
                    String trimmed = fallback.trim();
                    return trimmed.isBlank() ? null : trimmed;
                });
    }

    public Map<String, Object> publicConfig() {
        return Map.of(
                "default_profile_image_url", defaultProfileImageUrl()
        );
    }

    public void setDefaultProfileImageUrl(String url, Long updatedByAdminId) {
        settings.upsertString(AppSettingsKeys.USERS_DEFAULT_PROFILE_IMAGE_URL, url, updatedByAdminId);
    }
}

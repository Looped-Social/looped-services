package com.looped.settings;

import com.looped.users.UsersProperties;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class AppConfigService {
    private final AppSettingsRepository settings;
    private final UsersProperties usersProperties;
    private final AppConfigProperties appConfigProperties;

    public AppConfigService(AppSettingsRepository settings,
                            UsersProperties usersProperties,
                            AppConfigProperties appConfigProperties) {
        this.settings = settings;
        this.usersProperties = usersProperties;
        this.appConfigProperties = appConfigProperties;
    }

    public String defaultProfileImageUrl() {
        return settings.findString(AppSettingsKeys.USERS_DEFAULT_PROFILE_IMAGE_URL)
                .orElseGet(() -> normalizeOptionalText(usersProperties.getDefaultProfileImageUrl()));
    }

    public String minimumSupportedVersion() {
        return settings.findString(AppSettingsKeys.APP_MINIMUM_SUPPORTED_VERSION)
                .orElseGet(() -> normalizeOptionalText(appConfigProperties.getMinimumSupportedVersion()));
    }

    public String minimumSupportedVersionMessage() {
        return settings.findString(AppSettingsKeys.APP_MINIMUM_SUPPORTED_VERSION_MESSAGE)
                .orElseGet(() -> normalizeOptionalText(appConfigProperties.getMinimumSupportedVersionMessage()));
    }

    public String minimumSupportedVersionUpdateUrl() {
        return settings.findString(AppSettingsKeys.APP_MINIMUM_SUPPORTED_VERSION_UPDATE_URL)
                .orElseGet(() -> normalizeOptionalText(appConfigProperties.getMinimumSupportedVersionUpdateUrl()));
    }

    public Map<String, Object> publicConfig() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("default_profile_image_url", defaultProfileImageUrl());
        out.put("minimum_supported_version", minimumSupportedVersion());
        out.put("minimum_supported_version_message", minimumSupportedVersionMessage());
        out.put("minimum_supported_version_update_url", minimumSupportedVersionUpdateUrl());
        return out;
    }

    public void setDefaultProfileImageUrl(String url, Long updatedByAdminId) {
        settings.upsertString(AppSettingsKeys.USERS_DEFAULT_PROFILE_IMAGE_URL, normalizeOptionalText(url), updatedByAdminId);
    }

    public void setMinimumSupportedVersion(String version, Long updatedByAdminId) {
        settings.upsertString(AppSettingsKeys.APP_MINIMUM_SUPPORTED_VERSION, normalizeOptionalText(version), updatedByAdminId);
    }

    public void setMinimumSupportedVersionMessage(String message, Long updatedByAdminId) {
        settings.upsertString(AppSettingsKeys.APP_MINIMUM_SUPPORTED_VERSION_MESSAGE, normalizeOptionalText(message), updatedByAdminId);
    }

    public void setMinimumSupportedVersionUpdateUrl(String updateUrl, Long updatedByAdminId) {
        settings.upsertString(AppSettingsKeys.APP_MINIMUM_SUPPORTED_VERSION_UPDATE_URL, normalizeOptionalText(updateUrl), updatedByAdminId);
    }

    private String normalizeOptionalText(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }
}

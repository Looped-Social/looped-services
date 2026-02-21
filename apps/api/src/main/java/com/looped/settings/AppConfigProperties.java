package com.looped.settings;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "app-config")
public class AppConfigProperties {
    private String minimumSupportedVersion;
    private String minimumSupportedVersionMessage;
    private String minimumSupportedVersionUpdateUrl;

    public String getMinimumSupportedVersion() {
        return minimumSupportedVersion;
    }

    public void setMinimumSupportedVersion(String minimumSupportedVersion) {
        this.minimumSupportedVersion = minimumSupportedVersion;
    }

    public String getMinimumSupportedVersionMessage() {
        return minimumSupportedVersionMessage;
    }

    public void setMinimumSupportedVersionMessage(String minimumSupportedVersionMessage) {
        this.minimumSupportedVersionMessage = minimumSupportedVersionMessage;
    }

    public String getMinimumSupportedVersionUpdateUrl() {
        return minimumSupportedVersionUpdateUrl;
    }

    public void setMinimumSupportedVersionUpdateUrl(String minimumSupportedVersionUpdateUrl) {
        this.minimumSupportedVersionUpdateUrl = minimumSupportedVersionUpdateUrl;
    }
}

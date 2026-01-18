package com.looped.users;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "users")
public class UsersProperties {
    private String defaultProfileImageUrl;

    public String getDefaultProfileImageUrl() {
        return defaultProfileImageUrl;
    }

    public void setDefaultProfileImageUrl(String defaultProfileImageUrl) {
        this.defaultProfileImageUrl = defaultProfileImageUrl;
    }
}

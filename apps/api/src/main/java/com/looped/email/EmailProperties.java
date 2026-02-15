package com.looped.email;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "email")
public class EmailProperties {
    private boolean enabled = true;
    private String from;
    private String adminFrom;
    private String replyTo;
    private String verifyBaseUrl;
    private String configurationSet;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getFrom() {
        return from;
    }

    public void setFrom(String from) {
        this.from = from;
    }

    public String getAdminFrom() {
        return adminFrom;
    }

    public void setAdminFrom(String adminFrom) {
        this.adminFrom = adminFrom;
    }

    public String getReplyTo() {
        return replyTo;
    }

    public void setReplyTo(String replyTo) {
        this.replyTo = replyTo;
    }

    public String getVerifyBaseUrl() {
        return verifyBaseUrl;
    }

    public void setVerifyBaseUrl(String verifyBaseUrl) {
        this.verifyBaseUrl = verifyBaseUrl;
    }

    public String getConfigurationSet() {
        return configurationSet;
    }

    public void setConfigurationSet(String configurationSet) {
        this.configurationSet = configurationSet;
    }
}

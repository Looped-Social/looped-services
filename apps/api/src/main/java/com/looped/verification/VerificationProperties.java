package com.looped.verification;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "verification")
public class VerificationProperties {
    private boolean echoCode = false; // dev convenience only
    private int codeTtlSeconds = 600; // 10 minutes
    private int defaultCommunityTtlDays = 365; // 1 year; set to 0 for no expiry

    public boolean isEchoCode() {
        return echoCode;
    }

    public void setEchoCode(boolean echoCode) {
        this.echoCode = echoCode;
    }

    public int getCodeTtlSeconds() {
        return codeTtlSeconds;
    }

    public void setCodeTtlSeconds(int codeTtlSeconds) {
        this.codeTtlSeconds = codeTtlSeconds;
    }

    public int getDefaultCommunityTtlDays() {
        return defaultCommunityTtlDays;
    }

    public void setDefaultCommunityTtlDays(int defaultCommunityTtlDays) {
        this.defaultCommunityTtlDays = defaultCommunityTtlDays;
    }
}

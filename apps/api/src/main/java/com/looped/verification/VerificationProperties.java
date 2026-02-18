package com.looped.verification;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "verification")
public class VerificationProperties {
    private boolean echoCode = false; // dev convenience only
    private int codeTtlSeconds = 600; // 10 minutes
    private int emailCodeMaxAttempts = 5;
    private int emailResendCooldownSeconds = 60;
    private int emailMaxStartsPerHour = 10;
    private int emailMaxStartsPerDay = 25;
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

    public int getEmailCodeMaxAttempts() {
        return emailCodeMaxAttempts;
    }

    public void setEmailCodeMaxAttempts(int emailCodeMaxAttempts) {
        this.emailCodeMaxAttempts = emailCodeMaxAttempts;
    }

    public int getEmailResendCooldownSeconds() {
        return emailResendCooldownSeconds;
    }

    public void setEmailResendCooldownSeconds(int emailResendCooldownSeconds) {
        this.emailResendCooldownSeconds = emailResendCooldownSeconds;
    }

    public int getEmailMaxStartsPerHour() {
        return emailMaxStartsPerHour;
    }

    public void setEmailMaxStartsPerHour(int emailMaxStartsPerHour) {
        this.emailMaxStartsPerHour = emailMaxStartsPerHour;
    }

    public int getEmailMaxStartsPerDay() {
        return emailMaxStartsPerDay;
    }

    public void setEmailMaxStartsPerDay(int emailMaxStartsPerDay) {
        this.emailMaxStartsPerDay = emailMaxStartsPerDay;
    }

    public int getDefaultCommunityTtlDays() {
        return defaultCommunityTtlDays;
    }

    public void setDefaultCommunityTtlDays(int defaultCommunityTtlDays) {
        this.defaultCommunityTtlDays = defaultCommunityTtlDays;
    }
}

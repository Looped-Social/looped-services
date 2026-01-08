package com.looped.verification;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
@ConfigurationProperties(prefix = "verification.photo-id")
public class PhotoIdVerificationProperties {
    private long maxImageBytes = 10 * 1024 * 1024; // 10MB
    private Duration sessionTtl = Duration.ofMinutes(30);
    private Duration presignTtl = Duration.ofMinutes(15);
    private Duration adminDownloadTtl = Duration.ofMinutes(5);
    private int rejectedDeleteAfterDays = 7;

    public long getMaxImageBytes() {
        return maxImageBytes;
    }

    public void setMaxImageBytes(long maxImageBytes) {
        this.maxImageBytes = maxImageBytes;
    }

    public Duration getSessionTtl() {
        return sessionTtl;
    }

    public void setSessionTtl(Duration sessionTtl) {
        this.sessionTtl = sessionTtl;
    }

    public Duration getPresignTtl() {
        return presignTtl;
    }

    public void setPresignTtl(Duration presignTtl) {
        this.presignTtl = presignTtl;
    }

    public Duration getAdminDownloadTtl() {
        return adminDownloadTtl;
    }

    public void setAdminDownloadTtl(Duration adminDownloadTtl) {
        this.adminDownloadTtl = adminDownloadTtl;
    }

    public int getRejectedDeleteAfterDays() {
        return rejectedDeleteAfterDays;
    }

    public void setRejectedDeleteAfterDays(int rejectedDeleteAfterDays) {
        this.rejectedDeleteAfterDays = rejectedDeleteAfterDays;
    }
}


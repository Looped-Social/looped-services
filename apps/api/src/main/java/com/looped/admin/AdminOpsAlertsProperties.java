package com.looped.admin;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
@ConfigurationProperties(prefix = "admin.ops-alerts")
public class AdminOpsAlertsProperties {
    private boolean enabled = false;
    private String environment;
    private String requiredEnvironment = "prod";
    private String timeZone = "America/New_York";
    private String dailyCron = "0 0 17 * * *";
    private String hourlyCron = "0 0 * * * *";
    private int verificationPendingThreshold = 100;
    private int reportsOpenThreshold = 50;
    private int moderationQueueOpenThreshold = 75;
    private double spikeFractionThreshold = 0.30d;
    private int spikeAbsoluteThreshold = 10;
    private Duration hourlyCooldown = Duration.ofHours(2);
    private String adminBaseUrl;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    public String getRequiredEnvironment() {
        return requiredEnvironment;
    }

    public void setRequiredEnvironment(String requiredEnvironment) {
        this.requiredEnvironment = requiredEnvironment;
    }

    public String getTimeZone() {
        return timeZone;
    }

    public void setTimeZone(String timeZone) {
        this.timeZone = timeZone;
    }

    public String getDailyCron() {
        return dailyCron;
    }

    public void setDailyCron(String dailyCron) {
        this.dailyCron = dailyCron;
    }

    public String getHourlyCron() {
        return hourlyCron;
    }

    public void setHourlyCron(String hourlyCron) {
        this.hourlyCron = hourlyCron;
    }

    public int getVerificationPendingThreshold() {
        return verificationPendingThreshold;
    }

    public void setVerificationPendingThreshold(int verificationPendingThreshold) {
        this.verificationPendingThreshold = verificationPendingThreshold;
    }

    public int getReportsOpenThreshold() {
        return reportsOpenThreshold;
    }

    public void setReportsOpenThreshold(int reportsOpenThreshold) {
        this.reportsOpenThreshold = reportsOpenThreshold;
    }

    public int getModerationQueueOpenThreshold() {
        return moderationQueueOpenThreshold;
    }

    public void setModerationQueueOpenThreshold(int moderationQueueOpenThreshold) {
        this.moderationQueueOpenThreshold = moderationQueueOpenThreshold;
    }

    public double getSpikeFractionThreshold() {
        return spikeFractionThreshold;
    }

    public void setSpikeFractionThreshold(double spikeFractionThreshold) {
        this.spikeFractionThreshold = spikeFractionThreshold;
    }

    public int getSpikeAbsoluteThreshold() {
        return spikeAbsoluteThreshold;
    }

    public void setSpikeAbsoluteThreshold(int spikeAbsoluteThreshold) {
        this.spikeAbsoluteThreshold = spikeAbsoluteThreshold;
    }

    public Duration getHourlyCooldown() {
        return hourlyCooldown;
    }

    public void setHourlyCooldown(Duration hourlyCooldown) {
        this.hourlyCooldown = hourlyCooldown;
    }

    public String getAdminBaseUrl() {
        return adminBaseUrl;
    }

    public void setAdminBaseUrl(String adminBaseUrl) {
        this.adminBaseUrl = adminBaseUrl;
    }
}

package com.looped.admin;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
@ConfigurationProperties(prefix = "admin.fyp-alerts")
public class AdminFypScaleAlertsProperties {
    private boolean enabled = false;
    private String environment;
    private String requiredEnvironment = "prod";
    private String timeZone = "America/New_York";
    private String cron = "0 30 * * * *"; // hourly, minute 30
    private Duration cooldown = Duration.ofHours(24);

    // Redis-sampled global FYP latency/QPS window.
    private int perfWindowMinutes = 60;
    private int perfMinSampledRequests = 200;
    private double globalFypEstimatedRpsThreshold = 50.0d;
    private int globalFypP95UpperBoundMsThreshold = 750;
    private double globalFypSampled5xxRateThreshold = 0.005d; // 0.5%

    // Postgres volume thresholds (24h lookback).
    private long telemetryEvents24hThreshold = 5_000_000L;
    private long feedImpressions24hThreshold = 2_500_000L;
    private long telemetryTableBytesThreshold = 10L * 1024L * 1024L * 1024L; // 10GB
    private long postsCreated24hThreshold = 50_000L;

    // Optional quality tripwire (not strictly infra scaling).
    private double minInteractableImpressionShare = 0.50d;

    private String runbookUrl;
    private String dashboardUrl;

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

    public String getCron() {
        return cron;
    }

    public void setCron(String cron) {
        this.cron = cron;
    }

    public Duration getCooldown() {
        return cooldown;
    }

    public void setCooldown(Duration cooldown) {
        this.cooldown = cooldown;
    }

    public int getPerfWindowMinutes() {
        return perfWindowMinutes;
    }

    public void setPerfWindowMinutes(int perfWindowMinutes) {
        this.perfWindowMinutes = perfWindowMinutes;
    }

    public int getPerfMinSampledRequests() {
        return perfMinSampledRequests;
    }

    public void setPerfMinSampledRequests(int perfMinSampledRequests) {
        this.perfMinSampledRequests = perfMinSampledRequests;
    }

    public double getGlobalFypEstimatedRpsThreshold() {
        return globalFypEstimatedRpsThreshold;
    }

    public void setGlobalFypEstimatedRpsThreshold(double globalFypEstimatedRpsThreshold) {
        this.globalFypEstimatedRpsThreshold = globalFypEstimatedRpsThreshold;
    }

    public int getGlobalFypP95UpperBoundMsThreshold() {
        return globalFypP95UpperBoundMsThreshold;
    }

    public void setGlobalFypP95UpperBoundMsThreshold(int globalFypP95UpperBoundMsThreshold) {
        this.globalFypP95UpperBoundMsThreshold = globalFypP95UpperBoundMsThreshold;
    }

    public double getGlobalFypSampled5xxRateThreshold() {
        return globalFypSampled5xxRateThreshold;
    }

    public void setGlobalFypSampled5xxRateThreshold(double globalFypSampled5xxRateThreshold) {
        this.globalFypSampled5xxRateThreshold = globalFypSampled5xxRateThreshold;
    }

    public long getTelemetryEvents24hThreshold() {
        return telemetryEvents24hThreshold;
    }

    public void setTelemetryEvents24hThreshold(long telemetryEvents24hThreshold) {
        this.telemetryEvents24hThreshold = telemetryEvents24hThreshold;
    }

    public long getFeedImpressions24hThreshold() {
        return feedImpressions24hThreshold;
    }

    public void setFeedImpressions24hThreshold(long feedImpressions24hThreshold) {
        this.feedImpressions24hThreshold = feedImpressions24hThreshold;
    }

    public long getTelemetryTableBytesThreshold() {
        return telemetryTableBytesThreshold;
    }

    public void setTelemetryTableBytesThreshold(long telemetryTableBytesThreshold) {
        this.telemetryTableBytesThreshold = telemetryTableBytesThreshold;
    }

    public long getPostsCreated24hThreshold() {
        return postsCreated24hThreshold;
    }

    public void setPostsCreated24hThreshold(long postsCreated24hThreshold) {
        this.postsCreated24hThreshold = postsCreated24hThreshold;
    }

    public double getMinInteractableImpressionShare() {
        return minInteractableImpressionShare;
    }

    public void setMinInteractableImpressionShare(double minInteractableImpressionShare) {
        this.minInteractableImpressionShare = minInteractableImpressionShare;
    }

    public String getRunbookUrl() {
        return runbookUrl;
    }

    public void setRunbookUrl(String runbookUrl) {
        this.runbookUrl = runbookUrl;
    }

    public String getDashboardUrl() {
        return dashboardUrl;
    }

    public void setDashboardUrl(String dashboardUrl) {
        this.dashboardUrl = dashboardUrl;
    }
}


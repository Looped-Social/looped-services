package com.looped.recommendations.people;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
@ConfigurationProperties(prefix = "recommendations.people")
public class PeopleRecommendationProperties {
    private boolean activeCommunityRailEnabled = false;
    private int candidateFetchMultiplier = 6;
    private int maxFetchPerRail = 200;
    private int openReportExclusionThreshold = 3;
    private int activeWindowDays = 14;

    private int maxPerCommunityPerPage = 4;
    private int maxPerSpecializationPerPage = 4;
    private int maxViewerExposurePerCandidate24h = 3;

    private Duration hideCooldown = Duration.ofDays(30);
    private Duration lessLikeCooldown = Duration.ofDays(14);

    private String modelVersion = "people-v1-heuristic";
    private String experimentKey = "people_reco_v1";
    private int experimentBucketBPercent = 50;

    private int maxFeedbackEventsPerRequest = 200;
    private boolean retentionEnabled = true;
    private int auditRetentionDays = 60;
    private int feedbackRetentionDays = 90;
    private int retentionBatchSize = 5000;
    private int retentionMaxBatchesPerRun = 20;

    public boolean isActiveCommunityRailEnabled() {
        return activeCommunityRailEnabled;
    }

    public void setActiveCommunityRailEnabled(boolean activeCommunityRailEnabled) {
        this.activeCommunityRailEnabled = activeCommunityRailEnabled;
    }

    public int getCandidateFetchMultiplier() {
        return candidateFetchMultiplier;
    }

    public void setCandidateFetchMultiplier(int candidateFetchMultiplier) {
        this.candidateFetchMultiplier = candidateFetchMultiplier;
    }

    public int getMaxFetchPerRail() {
        return maxFetchPerRail;
    }

    public void setMaxFetchPerRail(int maxFetchPerRail) {
        this.maxFetchPerRail = maxFetchPerRail;
    }

    public int getOpenReportExclusionThreshold() {
        return openReportExclusionThreshold;
    }

    public void setOpenReportExclusionThreshold(int openReportExclusionThreshold) {
        this.openReportExclusionThreshold = openReportExclusionThreshold;
    }

    public int getActiveWindowDays() {
        return activeWindowDays;
    }

    public void setActiveWindowDays(int activeWindowDays) {
        this.activeWindowDays = activeWindowDays;
    }

    public int getMaxPerCommunityPerPage() {
        return maxPerCommunityPerPage;
    }

    public void setMaxPerCommunityPerPage(int maxPerCommunityPerPage) {
        this.maxPerCommunityPerPage = maxPerCommunityPerPage;
    }

    public int getMaxPerSpecializationPerPage() {
        return maxPerSpecializationPerPage;
    }

    public void setMaxPerSpecializationPerPage(int maxPerSpecializationPerPage) {
        this.maxPerSpecializationPerPage = maxPerSpecializationPerPage;
    }

    public int getMaxViewerExposurePerCandidate24h() {
        return maxViewerExposurePerCandidate24h;
    }

    public void setMaxViewerExposurePerCandidate24h(int maxViewerExposurePerCandidate24h) {
        this.maxViewerExposurePerCandidate24h = maxViewerExposurePerCandidate24h;
    }

    public Duration getHideCooldown() {
        return hideCooldown;
    }

    public void setHideCooldown(Duration hideCooldown) {
        this.hideCooldown = hideCooldown;
    }

    public Duration getLessLikeCooldown() {
        return lessLikeCooldown;
    }

    public void setLessLikeCooldown(Duration lessLikeCooldown) {
        this.lessLikeCooldown = lessLikeCooldown;
    }

    public String getModelVersion() {
        return modelVersion;
    }

    public void setModelVersion(String modelVersion) {
        this.modelVersion = modelVersion;
    }

    public String getExperimentKey() {
        return experimentKey;
    }

    public void setExperimentKey(String experimentKey) {
        this.experimentKey = experimentKey;
    }

    public int getExperimentBucketBPercent() {
        return experimentBucketBPercent;
    }

    public void setExperimentBucketBPercent(int experimentBucketBPercent) {
        this.experimentBucketBPercent = experimentBucketBPercent;
    }

    public int getMaxFeedbackEventsPerRequest() {
        return maxFeedbackEventsPerRequest;
    }

    public void setMaxFeedbackEventsPerRequest(int maxFeedbackEventsPerRequest) {
        this.maxFeedbackEventsPerRequest = maxFeedbackEventsPerRequest;
    }

    public boolean isRetentionEnabled() {
        return retentionEnabled;
    }

    public void setRetentionEnabled(boolean retentionEnabled) {
        this.retentionEnabled = retentionEnabled;
    }

    public int getAuditRetentionDays() {
        return auditRetentionDays;
    }

    public void setAuditRetentionDays(int auditRetentionDays) {
        this.auditRetentionDays = auditRetentionDays;
    }

    public int getFeedbackRetentionDays() {
        return feedbackRetentionDays;
    }

    public void setFeedbackRetentionDays(int feedbackRetentionDays) {
        this.feedbackRetentionDays = feedbackRetentionDays;
    }

    public int getRetentionBatchSize() {
        return retentionBatchSize;
    }

    public void setRetentionBatchSize(int retentionBatchSize) {
        this.retentionBatchSize = retentionBatchSize;
    }

    public int getRetentionMaxBatchesPerRun() {
        return retentionMaxBatchesPerRun;
    }

    public void setRetentionMaxBatchesPerRun(int retentionMaxBatchesPerRun) {
        this.retentionMaxBatchesPerRun = retentionMaxBatchesPerRun;
    }
}

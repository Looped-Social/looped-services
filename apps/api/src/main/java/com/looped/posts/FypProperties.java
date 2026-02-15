package com.looped.posts;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "feed.fyp")
public class FypProperties {
    private int eligibleWindowDays = 90;
    private int discoveryWindowDays = 7;

    private double eligibleHalfLifeHours = 48.0;
    private double discoveryHalfLifeHours = 12.0;

    // Small baseline so new posts with zero engagement still get surfaced (especially in tiny communities).
    private double eligibleBaselineEngagement = 0.25;
    private double discoveryBaselineEngagement = 0.10;

    // Mixing pattern: N eligible then M discovery, repeated (e.g., 3 + 1 = 75% eligible).
    private int patternEligible = 3;
    private int patternDiscovery = 1;

    private double minEligibleFraction = 0.50;

    private int candidatesMultiplier = 10;
    private int maxCandidatesPerPool = 250;

    private int maxPerAuthor = 2;
    private int maxPerCommunity = 5;

    public int getEligibleWindowDays() {
        return eligibleWindowDays;
    }

    public void setEligibleWindowDays(int eligibleWindowDays) {
        this.eligibleWindowDays = eligibleWindowDays;
    }

    public int getDiscoveryWindowDays() {
        return discoveryWindowDays;
    }

    public void setDiscoveryWindowDays(int discoveryWindowDays) {
        this.discoveryWindowDays = discoveryWindowDays;
    }

    public double getEligibleHalfLifeHours() {
        return eligibleHalfLifeHours;
    }

    public void setEligibleHalfLifeHours(double eligibleHalfLifeHours) {
        this.eligibleHalfLifeHours = eligibleHalfLifeHours;
    }

    public double getDiscoveryHalfLifeHours() {
        return discoveryHalfLifeHours;
    }

    public void setDiscoveryHalfLifeHours(double discoveryHalfLifeHours) {
        this.discoveryHalfLifeHours = discoveryHalfLifeHours;
    }

    public double getEligibleBaselineEngagement() {
        return eligibleBaselineEngagement;
    }

    public void setEligibleBaselineEngagement(double eligibleBaselineEngagement) {
        this.eligibleBaselineEngagement = eligibleBaselineEngagement;
    }

    public double getDiscoveryBaselineEngagement() {
        return discoveryBaselineEngagement;
    }

    public void setDiscoveryBaselineEngagement(double discoveryBaselineEngagement) {
        this.discoveryBaselineEngagement = discoveryBaselineEngagement;
    }

    public int getPatternEligible() {
        return patternEligible;
    }

    public void setPatternEligible(int patternEligible) {
        this.patternEligible = patternEligible;
    }

    public int getPatternDiscovery() {
        return patternDiscovery;
    }

    public void setPatternDiscovery(int patternDiscovery) {
        this.patternDiscovery = patternDiscovery;
    }

    public double getMinEligibleFraction() {
        return minEligibleFraction;
    }

    public void setMinEligibleFraction(double minEligibleFraction) {
        this.minEligibleFraction = minEligibleFraction;
    }

    public int getCandidatesMultiplier() {
        return candidatesMultiplier;
    }

    public void setCandidatesMultiplier(int candidatesMultiplier) {
        this.candidatesMultiplier = candidatesMultiplier;
    }

    public int getMaxCandidatesPerPool() {
        return maxCandidatesPerPool;
    }

    public void setMaxCandidatesPerPool(int maxCandidatesPerPool) {
        this.maxCandidatesPerPool = maxCandidatesPerPool;
    }

    public int getMaxPerAuthor() {
        return maxPerAuthor;
    }

    public void setMaxPerAuthor(int maxPerAuthor) {
        this.maxPerAuthor = maxPerAuthor;
    }

    public int getMaxPerCommunity() {
        return maxPerCommunity;
    }

    public void setMaxPerCommunity(int maxPerCommunity) {
        this.maxPerCommunity = maxPerCommunity;
    }
}


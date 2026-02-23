package com.looped.posts;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "feed.trending")
public class TrendingProperties {
    private int windowDays = 3;
    private double halfLifeHours = 16.0;
    private double baselineEngagement = 0.10;

    // Personalization boosts are additive prior to scaling to integer score.
    private double communityBoost = 0.20;
    private double followingBoost = 0.30;

    // Protect query length for IN (...) filters.
    private int maxFollowedPrincipalBoosts = 400;

    public int getWindowDays() {
        return windowDays;
    }

    public void setWindowDays(int windowDays) {
        this.windowDays = windowDays;
    }

    public double getHalfLifeHours() {
        return halfLifeHours;
    }

    public void setHalfLifeHours(double halfLifeHours) {
        this.halfLifeHours = halfLifeHours;
    }

    public double getBaselineEngagement() {
        return baselineEngagement;
    }

    public void setBaselineEngagement(double baselineEngagement) {
        this.baselineEngagement = baselineEngagement;
    }

    public double getCommunityBoost() {
        return communityBoost;
    }

    public void setCommunityBoost(double communityBoost) {
        this.communityBoost = communityBoost;
    }

    public double getFollowingBoost() {
        return followingBoost;
    }

    public void setFollowingBoost(double followingBoost) {
        this.followingBoost = followingBoost;
    }

    public int getMaxFollowedPrincipalBoosts() {
        return maxFollowedPrincipalBoosts;
    }

    public void setMaxFollowedPrincipalBoosts(int maxFollowedPrincipalBoosts) {
        this.maxFollowedPrincipalBoosts = maxFollowedPrincipalBoosts;
    }
}

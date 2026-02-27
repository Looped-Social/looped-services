package com.looped.posts;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "posts.share-nudge")
public class PostShareNudgeProperties {
    private int delayMinutes = 45;
    private int maxServedPerDay = 2;
    private int minMinutesBetweenServes = 60;
    private int maxCombinedEngagement = 0;
    private boolean enabledByDefault = true;
    private String defaultVariant = "v1";
    private String messageKey = "share_nudge.low_traction";
    private String ctaKey = "share_nudge.cta_share";
    private String shareDeeplinkFormat = "looped://posts/%d";

    public int getDelayMinutes() {
        return delayMinutes;
    }

    public void setDelayMinutes(int delayMinutes) {
        this.delayMinutes = delayMinutes;
    }

    public int getMaxServedPerDay() {
        return maxServedPerDay;
    }

    public void setMaxServedPerDay(int maxServedPerDay) {
        this.maxServedPerDay = maxServedPerDay;
    }

    public int getMinMinutesBetweenServes() {
        return minMinutesBetweenServes;
    }

    public void setMinMinutesBetweenServes(int minMinutesBetweenServes) {
        this.minMinutesBetweenServes = minMinutesBetweenServes;
    }

    public int getMaxCombinedEngagement() {
        return maxCombinedEngagement;
    }

    public void setMaxCombinedEngagement(int maxCombinedEngagement) {
        this.maxCombinedEngagement = maxCombinedEngagement;
    }

    public boolean isEnabledByDefault() {
        return enabledByDefault;
    }

    public void setEnabledByDefault(boolean enabledByDefault) {
        this.enabledByDefault = enabledByDefault;
    }

    public String getDefaultVariant() {
        return defaultVariant;
    }

    public void setDefaultVariant(String defaultVariant) {
        this.defaultVariant = defaultVariant;
    }

    public String getMessageKey() {
        return messageKey;
    }

    public void setMessageKey(String messageKey) {
        this.messageKey = messageKey;
    }

    public String getCtaKey() {
        return ctaKey;
    }

    public void setCtaKey(String ctaKey) {
        this.ctaKey = ctaKey;
    }

    public String getShareDeeplinkFormat() {
        return shareDeeplinkFormat;
    }

    public void setShareDeeplinkFormat(String shareDeeplinkFormat) {
        this.shareDeeplinkFormat = shareDeeplinkFormat;
    }
}

package com.looped.communities;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "specializations")
public class SpecializationProperties {
    private int defaultJoinCooldownMonths = 6;
    private int defaultMaxJoinsMajor = 2;
    private int defaultMaxJoinsField = 2;

    public int getDefaultJoinCooldownMonths() {
        return defaultJoinCooldownMonths;
    }

    public void setDefaultJoinCooldownMonths(int defaultJoinCooldownMonths) {
        this.defaultJoinCooldownMonths = defaultJoinCooldownMonths;
    }

    public int getDefaultMaxJoinsMajor() {
        return defaultMaxJoinsMajor;
    }

    public void setDefaultMaxJoinsMajor(int defaultMaxJoinsMajor) {
        this.defaultMaxJoinsMajor = defaultMaxJoinsMajor;
    }

    public int getDefaultMaxJoinsField() {
        return defaultMaxJoinsField;
    }

    public void setDefaultMaxJoinsField(int defaultMaxJoinsField) {
        this.defaultMaxJoinsField = defaultMaxJoinsField;
    }
}

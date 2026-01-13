package com.looped.communities;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "specializations")
public class SpecializationProperties {
    private int defaultJoinCooldownMonths = 6;

    public int getDefaultJoinCooldownMonths() {
        return defaultJoinCooldownMonths;
    }

    public void setDefaultJoinCooldownMonths(int defaultJoinCooldownMonths) {
        this.defaultJoinCooldownMonths = defaultJoinCooldownMonths;
    }
}


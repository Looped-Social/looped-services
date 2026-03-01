package com.looped.devices;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Locale;

@Configuration
@ConfigurationProperties(prefix = "devices.app-attest")
public class AppAttestProperties {
    private String mode = "disabled";
    private Duration challengeTtl = Duration.ofMinutes(5);
    private Duration trustTtl = Duration.ofDays(30);
    private boolean allowInsecureObservedTrust = false;

    public Mode mode() {
        String normalized = mode == null ? "" : mode.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "observe" -> Mode.OBSERVE;
            case "enforce" -> Mode.ENFORCE;
            default -> Mode.DISABLED;
        };
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public Duration getChallengeTtl() {
        return challengeTtl;
    }

    public void setChallengeTtl(Duration challengeTtl) {
        this.challengeTtl = challengeTtl;
    }

    public Duration getTrustTtl() {
        return trustTtl;
    }

    public void setTrustTtl(Duration trustTtl) {
        this.trustTtl = trustTtl;
    }

    public boolean isAllowInsecureObservedTrust() {
        return allowInsecureObservedTrust;
    }

    public void setAllowInsecureObservedTrust(boolean allowInsecureObservedTrust) {
        this.allowInsecureObservedTrust = allowInsecureObservedTrust;
    }

    public enum Mode {
        DISABLED,
        OBSERVE,
        ENFORCE
    }
}

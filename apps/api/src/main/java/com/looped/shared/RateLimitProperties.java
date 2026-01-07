package com.looped.shared;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "rate-limit")
public class RateLimitProperties {
    private boolean enabled = true;
    private Window perIp = new Window();
    private Window perUser = new Window();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Window getPerIp() { return perIp; }
    public void setPerIp(Window perIp) { this.perIp = perIp; }
    public Window getPerUser() { return perUser; }
    public void setPerUser(Window perUser) { this.perUser = perUser; }

    public static class Window {
        private int windowSeconds = 60;
        private int maxRequests = 120;
        public int getWindowSeconds() { return windowSeconds; }
        public void setWindowSeconds(int windowSeconds) { this.windowSeconds = windowSeconds; }
        public int getMaxRequests() { return maxRequests; }
        public void setMaxRequests(int maxRequests) { this.maxRequests = maxRequests; }
    }
}

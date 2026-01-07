package com.looped.shared;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

@Component
public class RateLimiter {
    private final StringRedisTemplate redis;
    private final RateLimitProperties props;

    public RateLimiter(StringRedisTemplate redis, RateLimitProperties props) {
        this.redis = redis;
        this.props = props;
    }

    public boolean allowIp(String ip) {
        if (!props.isEnabled()) return true;
        return allow("ip", ip, props.getPerIp().getWindowSeconds(), props.getPerIp().getMaxRequests());
    }

    public boolean allowUser(String user) {
        if (!props.isEnabled()) return true;
        return allow("user", user, props.getPerUser().getWindowSeconds(), props.getPerUser().getMaxRequests());
    }

    private boolean allow(String kind, String id, int windowSeconds, int max) {
        long now = Instant.now().toEpochMilli();
        String key = String.format("rl:%s:%s", kind, id);
        long windowAgo = now - windowSeconds * 1000L;
        // Use ZSET as sliding window: add now, remove older than window, count
        String nowStr = Long.toString(now);
        try {
            redis.opsForZSet().add(key, nowStr, now);
            redis.opsForZSet().removeRangeByScore(key, 0, windowAgo);
            Long count = redis.opsForZSet().zCard(key);
            redis.expire(key, windowSeconds, TimeUnit.SECONDS);
            return count != null && count <= max;
        } catch (RuntimeException e) {
            // Redis unavailable: degrade gracefully (allow request)
            return true;
        }
    }
}

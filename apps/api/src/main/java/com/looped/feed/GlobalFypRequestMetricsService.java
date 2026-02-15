package com.looped.feed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class GlobalFypRequestMetricsService {
    private static final Logger log = LoggerFactory.getLogger(GlobalFypRequestMetricsService.class);

    // Histogram buckets are upper-bounds in milliseconds. The last bucket is "overflow".
    private static final int[] LATENCY_BUCKETS_MS = new int[] {
            50, 100, 200, 350, 500, 750, 1000, 1500, 2000, 3500, 5000, 8000, 12000
    };

    private final GlobalFypRequestMetricsProperties props;
    private final StringRedisTemplate redis;

    public GlobalFypRequestMetricsService(GlobalFypRequestMetricsProperties props, StringRedisTemplate redis) {
        this.props = props;
        this.redis = redis;
    }

    public void record(int statusCode, long durationMs) {
        if (!props.isEnabled()) return;
        if (durationMs < 0) durationMs = 0;

        double sampleRate = clamp01(props.getSampleRate());
        if (sampleRate <= 0.0d) return;
        if (sampleRate < 1.0d) {
            if (ThreadLocalRandom.current().nextDouble() > sampleRate) return;
        }

        long minute = Instant.now().getEpochSecond() / 60L;
        String key = "metrics:fyp:global:" + minute;
        Duration ttl = safeTtl(props.getRetention());
        String bucketField = "lat_b" + bucketIndex(durationMs);

        try {
            redis.opsForHash().increment(key, "reqs", 1L);
            if (statusCode >= 500) {
                redis.opsForHash().increment(key, "errs5xx", 1L);
            }
            if (statusCode >= 400) {
                redis.opsForHash().increment(key, "errs4xx", 1L);
            }
            redis.opsForHash().increment(key, "lat_sum_ms", durationMs);
            redis.opsForHash().increment(key, bucketField, 1L);
            redis.expire(key, ttl);
        } catch (Exception e) {
            // Fail-open: metrics must never impact the request path.
            log.debug("Global FYP metrics write failed: {}", e.getMessage());
        }
    }

    public Snapshot snapshotLastMinutes(int minutes) {
        int windowMinutes = Math.max(1, Math.min(minutes, 24 * 60));
        long nowMinute = Instant.now().getEpochSecond() / 60L;

        long reqs = 0L;
        long errs5xx = 0L;
        long errs4xx = 0L;
        long latSumMs = 0L;
        long[] buckets = new long[LATENCY_BUCKETS_MS.length + 1];

        for (int i = 0; i < windowMinutes; i++) {
            String key = "metrics:fyp:global:" + (nowMinute - i);
            Map<Object, Object> fields;
            try {
                fields = redis.opsForHash().entries(key);
            } catch (Exception e) {
                log.debug("Global FYP metrics read failed key={} err={}", key, e.getMessage());
                continue;
            }
            if (fields == null || fields.isEmpty()) continue;

            reqs += longFrom(fields.get("reqs"));
            errs5xx += longFrom(fields.get("errs5xx"));
            errs4xx += longFrom(fields.get("errs4xx"));
            latSumMs += longFrom(fields.get("lat_sum_ms"));

            for (int b = 0; b < buckets.length; b++) {
                buckets[b] += longFrom(fields.get("lat_b" + b));
            }
        }

        double sampleRate = clamp01(props.getSampleRate());
        long estTotalReqs = sampleRate > 0.0d ? (long) Math.round(reqs / sampleRate) : reqs;

        double avgMs = reqs <= 0 ? 0.0d : (double) latSumMs / (double) reqs;
        long p50 = percentileUpperBoundMs(buckets, 0.50d);
        long p95 = percentileUpperBoundMs(buckets, 0.95d);
        long p99 = percentileUpperBoundMs(buckets, 0.99d);
        double err5xxRate = reqs <= 0 ? 0.0d : (double) errs5xx / (double) reqs;

        return new Snapshot(
                windowMinutes,
                sampleRate,
                reqs,
                estTotalReqs,
                errs4xx,
                errs5xx,
                err5xxRate,
                avgMs,
                p50,
                p95,
                p99,
                buckets
        );
    }

    public int bucketCount() {
        return LATENCY_BUCKETS_MS.length + 1;
    }

    public String bucketLabel(int bucket) {
        if (bucket < 0) bucket = 0;
        if (bucket < LATENCY_BUCKETS_MS.length) return "<=" + LATENCY_BUCKETS_MS[bucket] + "ms";
        return ">" + LATENCY_BUCKETS_MS[LATENCY_BUCKETS_MS.length - 1] + "ms";
    }

    private int bucketIndex(long durationMs) {
        for (int i = 0; i < LATENCY_BUCKETS_MS.length; i++) {
            if (durationMs <= LATENCY_BUCKETS_MS[i]) return i;
        }
        return LATENCY_BUCKETS_MS.length; // overflow
    }

    private long percentileUpperBoundMs(long[] bucketCounts, double p) {
        if (bucketCounts == null || bucketCounts.length == 0) return 0L;
        long total = 0L;
        for (long c : bucketCounts) total += Math.max(0L, c);
        if (total <= 0) return 0L;

        long target = (long) Math.ceil(p * (double) total);
        long cumulative = 0L;
        for (int b = 0; b < bucketCounts.length; b++) {
            cumulative += Math.max(0L, bucketCounts[b]);
            if (cumulative >= target) {
                if (b < LATENCY_BUCKETS_MS.length) return LATENCY_BUCKETS_MS[b];
                return LATENCY_BUCKETS_MS[LATENCY_BUCKETS_MS.length - 1] + 1L;
            }
        }
        return LATENCY_BUCKETS_MS[LATENCY_BUCKETS_MS.length - 1] + 1L;
    }

    private long longFrom(Object raw) {
        if (raw == null) return 0L;
        if (raw instanceof Number n) return n.longValue();
        if (raw instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    private double clamp01(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0.0d;
        if (v < 0.0d) return 0.0d;
        if (v > 1.0d) return 1.0d;
        return v;
    }

    private Duration safeTtl(Duration raw) {
        if (raw == null || raw.isNegative() || raw.isZero()) return Duration.ofHours(72);
        // Cap TTL so accidental config doesn't create unbounded key growth.
        Duration max = Duration.ofDays(14);
        return raw.compareTo(max) > 0 ? max : raw;
    }

    public record Snapshot(
            int windowMinutes,
            double sampleRate,
            long sampledRequests,
            long estimatedRequests,
            long sampled4xx,
            long sampled5xx,
            double sampled5xxRate,
            double avgMs,
            long p50UpperBoundMs,
            long p95UpperBoundMs,
            long p99UpperBoundMs,
            long[] latencyBuckets
    ) {
        public Map<String, Object> asMap() {
            Map<String, Object> out = new HashMap<>();
            out.put("window_minutes", windowMinutes);
            out.put("sample_rate", sampleRate);
            out.put("sampled_requests", sampledRequests);
            out.put("estimated_requests", estimatedRequests);
            out.put("sampled_4xx", sampled4xx);
            out.put("sampled_5xx", sampled5xx);
            out.put("sampled_5xx_rate", sampled5xxRate);
            out.put("avg_ms", avgMs);
            out.put("p50_upper_bound_ms", p50UpperBoundMs);
            out.put("p95_upper_bound_ms", p95UpperBoundMs);
            out.put("p99_upper_bound_ms", p99UpperBoundMs);
            out.put("latency_buckets", Arrays.stream(Objects.requireNonNullElse(latencyBuckets, new long[0])).boxed().toList());
            return out;
        }
    }
}


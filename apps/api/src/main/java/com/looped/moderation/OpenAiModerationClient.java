package com.looped.moderation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class OpenAiModerationClient {
    private static final Logger log = LoggerFactory.getLogger(OpenAiModerationClient.class);
    private static final DateTimeFormatter UTC_DAY_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;
    private static final Duration BUDGET_REDIS_KEY_TTL = Duration.ofDays(3);

    private final ModerationProperties props;
    private final StringRedisTemplate redis;

    @Autowired
    public OpenAiModerationClient(ModerationProperties props, StringRedisTemplate redis) {
        this.props = props;
        this.redis = redis;
    }

    // Package-private constructor for non-Spring tests.
    OpenAiModerationClient(ModerationProperties props) {
        this(props, null);
    }

    public Result moderateText(String input) {
        if (!props.isOpenaiEnabled()) return Result.disabledResult();
        String apiKey = props.getOpenaiApiKey();
        if (apiKey == null || apiKey.isBlank()) return Result.disabledResult();
        if (input == null) return Result.success(false, Set.of());
        if (!reserveDailyBudget()) return Result.disabledResult();

        ModerationRequest body = new ModerationRequest(props.getOpenaiModel(), input);
        try {
            ModerationResponse resp = client(apiKey).post()
                    .uri("/moderations")
                    .body(body)
                    .retrieve()
                    .body(ModerationResponse.class);
            return toResult(resp);
        } catch (RestClientResponseException ex) {
            handleHttpError(ex);
            return Result.errorResult();
        } catch (RuntimeException ex) {
            return Result.errorResult();
        }
    }

    public Result moderateImageUrl(String imageUrl) {
        if (!props.isOpenaiEnabled()) return Result.disabledResult();
        String apiKey = props.getOpenaiApiKey();
        if (apiKey == null || apiKey.isBlank()) return Result.disabledResult();
        if (imageUrl == null || imageUrl.isBlank()) return Result.success(false, Set.of());
        if (!reserveDailyBudget()) return Result.disabledResult();

        Object input = java.util.List.of(java.util.Map.of(
                "type", "input_image",
                "image_url", java.util.Map.of("url", imageUrl)
        ));
        ModerationRequest body = new ModerationRequest(props.getOpenaiModel(), input);
        try {
            ModerationResponse resp = client(apiKey).post()
                    .uri("/moderations")
                    .body(body)
                    .retrieve()
                    .body(ModerationResponse.class);
            return toResult(resp);
        } catch (RestClientResponseException ex) {
            handleHttpError(ex);
            return Result.errorResult();
        } catch (RuntimeException ex) {
            return Result.errorResult();
        }
    }

    private RestClient client(String apiKey) {
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(props.getOpenaiTimeoutMillis());
        rf.setReadTimeout(props.getOpenaiTimeoutMillis());
        return RestClient.builder()
                .baseUrl(props.getOpenaiBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .requestFactory(rf)
                .build();
    }

    private Result toResult(ModerationResponse resp) {
        if (resp == null || resp.results == null || resp.results.length == 0 || resp.results[0] == null) {
            return Result.success(false, Set.of());
        }
        ModerationResult r = resp.results[0];
        Set<String> trueCats = Set.of();
        if (r.categories != null && !r.categories.isEmpty()) {
            trueCats = r.categories.entrySet().stream()
                    .filter(e -> Boolean.TRUE.equals(e.getValue()))
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toUnmodifiableSet());
        }
        boolean flagged = Boolean.TRUE.equals(r.flagged);
        return Result.success(flagged, trueCats);
    }

    private void handleHttpError(RestClientResponseException ex) {
        int status = ex.getStatusCode().value();
        if (status == 429) {
            LocalDate utcDay = LocalDate.now(ZoneOffset.UTC);
            lockoutForUtcDay(utcDay, "openai_429");
            log.warn("OpenAI moderation rate-limited (429); lockout enabled for UTC day={}", formatUtcDay(utcDay));
            return;
        }
        log.warn("OpenAI moderation HTTP error status={}", status);
    }

    private boolean reserveDailyBudget() {
        int budget = props.getOpenaiDailyRequestBudget();
        if (budget <= 0) return true;
        if (redis == null) return true;

        LocalDate utcDay = LocalDate.now(ZoneOffset.UTC);
        if (isLockedOutForUtcDay(utcDay)) return false;

        String key = budgetCountKey(utcDay);
        try {
            Long used = redis.opsForValue().increment(key);
            if (used == null) return true;
            if (used == 1L) {
                redis.expire(key, BUDGET_REDIS_KEY_TTL);
            }
            if (used > budget) {
                lockoutForUtcDay(utcDay, "daily_budget_exhausted");
                log.warn("OpenAI moderation daily budget exhausted for UTC day={} budget={} used={}",
                        formatUtcDay(utcDay), budget, used);
                return false;
            }
            return true;
        } catch (RuntimeException ex) {
            // Preserve quota when budget tracking is unavailable.
            log.warn("OpenAI moderation budget reservation failed; skipping OpenAI moderation call");
            return false;
        }
    }

    private boolean isLockedOutForUtcDay(LocalDate utcDay) {
        if (redis == null) return false;
        try {
            Boolean exists = redis.hasKey(lockoutKey(utcDay));
            return Boolean.TRUE.equals(exists);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private void lockoutForUtcDay(LocalDate utcDay, String reason) {
        if (redis == null) return;
        try {
            redis.opsForValue().set(lockoutKey(utcDay), reason == null ? "lockout" : reason, BUDGET_REDIS_KEY_TTL);
        } catch (RuntimeException ex) {
            log.warn("Failed to persist OpenAI moderation lockout key");
        }
    }

    private String budgetCountKey(LocalDate utcDay) {
        return normalizedBudgetPrefix() + ":count:" + formatUtcDay(utcDay);
    }

    private String lockoutKey(LocalDate utcDay) {
        return normalizedBudgetPrefix() + ":lockout:" + formatUtcDay(utcDay);
    }

    private String normalizedBudgetPrefix() {
        String raw = props.getOpenaiBudgetRedisPrefix();
        if (raw == null || raw.isBlank()) {
            return "moderation:openai:requests";
        }
        String trimmed = raw.trim();
        while (trimmed.endsWith(":")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.isBlank() ? "moderation:openai:requests" : trimmed;
    }

    private static String formatUtcDay(LocalDate utcDay) {
        return UTC_DAY_FORMAT.format(utcDay);
    }

    public boolean shouldQuarantine(Result result) {
        if (result == null || !result.ok) return false;
        if (!result.flagged) return false;
        Set<String> blocked = parseCategoryBlocklist(props.getOpenaiCategoryBlocklist());
        if (blocked.isEmpty()) return true;
        for (String cat : result.trueCategories) {
            String norm = cat == null ? "" : cat.trim().toLowerCase(Locale.ROOT);
            if (blocked.contains(norm)) return true;
        }
        return false;
    }

    private static Set<String> parseCategoryBlocklist(String raw) {
        if (raw == null || raw.isBlank()) return Set.of();
        return java.util.Arrays.stream(raw.split("[,\\n\\r]+"))
                .map(String::trim)
                .map(s -> s.toLowerCase(Locale.ROOT))
                .filter(s -> !s.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    public record Result(boolean ok, boolean flagged, Set<String> trueCategories, boolean disabled) {
        static Result success(boolean flagged, Set<String> cats) { return new Result(true, flagged, cats == null ? Set.of() : cats, false); }
        static Result disabledResult() { return new Result(true, false, Set.of(), true); }
        static Result errorResult() { return new Result(false, false, Set.of(), false); }
    }

    public record ModerationRequest(String model, Object input) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ModerationResponse {
        public ModerationResult[] results;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ModerationResult {
        public Boolean flagged;
        public Map<String, Boolean> categories;
    }
}

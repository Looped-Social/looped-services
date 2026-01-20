package com.looped.moderation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class OpenAiModerationClient {
    private final ModerationProperties props;

    public OpenAiModerationClient(ModerationProperties props) {
        this.props = props;
    }

    public Result moderateText(String input) {
        if (!props.isOpenaiEnabled()) return Result.disabledResult();
        String apiKey = props.getOpenaiApiKey();
        if (apiKey == null || apiKey.isBlank()) return Result.disabledResult();
        if (input == null) return Result.success(false, Set.of());

        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(props.getOpenaiTimeoutMillis());
        rf.setReadTimeout(props.getOpenaiTimeoutMillis());
        RestClient client = RestClient.builder()
                .baseUrl(props.getOpenaiBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .requestFactory(rf)
                .build();

        ModerationRequest body = new ModerationRequest(props.getOpenaiModel(), input);
        try {
            ModerationResponse resp = client.post()
                    .uri("/moderations")
                    .body(body)
                    .retrieve()
                    .body(ModerationResponse.class);
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
        } catch (RuntimeException ex) {
            return Result.errorResult();
        }
    }

    public Result moderateImageUrl(String imageUrl) {
        if (!props.isOpenaiEnabled()) return Result.disabledResult();
        String apiKey = props.getOpenaiApiKey();
        if (apiKey == null || apiKey.isBlank()) return Result.disabledResult();
        if (imageUrl == null || imageUrl.isBlank()) return Result.success(false, Set.of());

        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(props.getOpenaiTimeoutMillis());
        rf.setReadTimeout(props.getOpenaiTimeoutMillis());
        RestClient client = RestClient.builder()
                .baseUrl(props.getOpenaiBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .requestFactory(rf)
                .build();

        Object input = java.util.List.of(java.util.Map.of(
                "type", "input_image",
                "image_url", java.util.Map.of("url", imageUrl)
        ));
        ModerationRequest body = new ModerationRequest(props.getOpenaiModel(), input);
        try {
            ModerationResponse resp = client.post()
                    .uri("/moderations")
                    .body(body)
                    .retrieve()
                    .body(ModerationResponse.class);
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
        } catch (RuntimeException ex) {
            return Result.errorResult();
        }
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

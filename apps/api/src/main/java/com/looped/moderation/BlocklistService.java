package com.looped.moderation;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class BlocklistService {
    private final ModerationProperties props;
    private final ResourceLoader resources;
    private final ModerationBlocklistRepository blocklistRepo;

    private volatile Compiled compiled;
    private volatile java.time.Instant nextCheck = java.time.Instant.EPOCH;
    private volatile java.time.OffsetDateTime lastDbUpdatedAt;

    public BlocklistService(ModerationProperties props, ResourceLoader resources, ModerationBlocklistRepository blocklistRepo) {
        this.props = props;
        this.resources = resources;
        this.blocklistRepo = blocklistRepo;
    }

    public Match match(String text) {
        if (text == null || text.isBlank()) return null;
        Compiled c = compiled();
        if (c.singleTokenTerms().isEmpty() && c.regexTerms().isEmpty()) return null;

        String normalized = normalize(text);
        if (normalized.isBlank()) return null;

        for (String token : tokenize(normalized)) {
            TermEntry entry = c.singleTokenTerms().get(token);
            if (entry != null) {
                return entry.match("word-boundary", normalized);
            }
        }

        for (TermEntry entry : c.regexTerms()) {
            if (entry.pattern().matcher(normalized).find()) {
                return entry.match("regex", normalized);
            }
        }
        return null;
    }

    private Compiled compiled() {
        java.time.Instant now = java.time.Instant.now();
        if (now.isAfter(nextCheck)) {
            refreshIfStale();
            nextCheck = now.plusSeconds(15);
        }
        Compiled existing = compiled;
        if (existing != null) return existing;
        synchronized (this) {
            if (compiled != null) return compiled;
            compiled = compile();
            return compiled;
        }
    }

    private void refreshIfStale() {
        try {
            java.time.OffsetDateTime dbUpdatedAt = blocklistRepo == null ? null : blocklistRepo.maxUpdatedAt();
            if (dbUpdatedAt == null) return;
            java.time.OffsetDateTime prev = lastDbUpdatedAt;
            if (prev != null && !dbUpdatedAt.isAfter(prev)) return;
            synchronized (this) {
                lastDbUpdatedAt = dbUpdatedAt;
                compiled = compile();
            }
        } catch (RuntimeException ignored) {
            // keep existing compiled in case DB is unavailable
        }
    }

    private Compiled compile() {
        List<TermInput> raw = new ArrayList<>();
        for (String term : loadLines(props.getBlocklistResource())) {
            raw.add(new TermInput(null, term));
        }
        for (String term : splitInlineTerms(props.getBlocklistTerms())) {
            raw.add(new TermInput(null, term));
        }
        if (blocklistRepo != null) {
            for (ModerationBlocklistRepository.EnabledTermRow row : blocklistRepo.listEnabled()) {
                raw.add(new TermInput(row.id(), row.term()));
            }
        }

        Map<String, TermEntry> byCollapsed = new LinkedHashMap<>();
        for (TermInput input : raw) {
            if (input.term() == null) continue;
            String trimmed = input.term().trim();
            if (trimmed.isBlank()) continue;
            if (trimmed.startsWith("#")) continue;
            String normalized = normalize(trimmed);
            if (normalized.isBlank()) continue;
            String collapsed = collapse(normalized);
            if (collapsed.isBlank()) continue;

            List<String> tokens = tokenize(normalized);
            TermEntry next = new TermEntry(trimmed, collapsed, tokens.size(), input.blocklistTermId(), compileBoundaryPattern(collapsed));
            TermEntry existing = byCollapsed.get(collapsed);
            if (existing == null || (existing.blocklistTermId() == null && next.blocklistTermId() != null)) {
                byCollapsed.put(collapsed, next);
            }
        }

        Map<String, TermEntry> singleTokenTerms = new LinkedHashMap<>();
        List<TermEntry> regexTerms = new ArrayList<>();
        for (TermEntry entry : byCollapsed.values()) {
            regexTerms.add(entry);
            if (entry.tokenCount() == 1) {
                TermEntry existing = singleTokenTerms.get(entry.collapsedTerm());
                if (existing == null || (existing.blocklistTermId() == null && entry.blocklistTermId() != null)) {
                    singleTokenTerms.put(entry.collapsedTerm(), entry);
                }
            }
        }
        return new Compiled(Map.copyOf(singleTokenTerms), List.copyOf(regexTerms));
    }

    private List<String> loadLines(String location) {
        if (location == null || location.isBlank()) return List.of();
        try {
            Resource resource = resources.getResource(location);
            if (!resource.exists()) return List.of();
            List<String> lines = new ArrayList<>();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    lines.add(line);
                }
            }
            return lines;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static List<String> splitInlineTerms(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        String[] parts = raw.split("[,\\n\\r]+");
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            if (p == null) continue;
            String t = p.trim();
            if (!t.isBlank()) out.add(t);
        }
        return out;
    }

    static String normalize(String input) {
        if (input == null) return "";
        String s = Normalizer.normalize(input, Normalizer.Form.NFKC);
        s = s.toLowerCase(Locale.ROOT);
        return s;
    }

    static List<String> tokenize(String normalized) {
        if (normalized == null || normalized.isBlank()) return List.of();
        String[] parts = normalized.split("[^\\p{IsAlphabetic}\\p{IsDigit}]+");
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            if (p == null) continue;
            String t = p.trim();
            if (!t.isBlank()) out.add(t);
        }
        return out;
    }

    static String collapse(String normalized) {
        if (normalized == null || normalized.isBlank()) return "";
        return normalized.replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}]+", "");
    }

    private static Pattern compileBoundaryPattern(String collapsedTerm) {
        StringBuilder sb = new StringBuilder();
        sb.append("(?<![\\p{IsAlphabetic}\\p{IsDigit}])");
        for (int i = 0; i < collapsedTerm.length(); i++) {
            sb.append(Pattern.quote(String.valueOf(collapsedTerm.charAt(i))));
            if (i < collapsedTerm.length() - 1) {
                sb.append("[^\\p{IsAlphabetic}\\p{IsDigit}]*");
            }
        }
        sb.append("(?![\\p{IsAlphabetic}\\p{IsDigit}])");
        return Pattern.compile(sb.toString());
    }

    private static String truncateNormalized(String normalizedText) {
        if (normalizedText == null) return null;
        int maxLen = 2048;
        if (normalizedText.length() <= maxLen) return normalizedText;
        return normalizedText.substring(0, maxLen);
    }

    private record Compiled(Map<String, TermEntry> singleTokenTerms, List<TermEntry> regexTerms) {}
    private record TermInput(Long blocklistTermId, String term) {}
    private record TermEntry(String term, String collapsedTerm, int tokenCount, Long blocklistTermId, Pattern pattern) {
        Match match(String method, String normalizedText) {
            return new Match(method, term, blocklistTermId, truncateNormalized(normalizedText));
        }
    }

    public record Match(String method, String matchedTerm, Long blocklistTermId, String normalizedText) {}
}

package com.looped.moderation;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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
        if (c.tokens.isEmpty() && c.collapsedTerms.isEmpty()) return null;

        String normalized = normalize(text);
        if (normalized.isBlank()) return null;

        for (String token : tokenize(normalized)) {
            if (c.tokens.contains(token)) {
                return new Match("token");
            }
        }

        String collapsed = collapse(normalized);
        if (!collapsed.isBlank()) {
            for (String term : c.collapsedTerms) {
                if (!term.isBlank() && collapsed.contains(term)) {
                    return new Match("collapsed");
                }
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
        List<String> raw = new ArrayList<>();
        raw.addAll(loadLines(props.getBlocklistResource()));
        raw.addAll(splitInlineTerms(props.getBlocklistTerms()));
        if (blocklistRepo != null) {
            raw.addAll(blocklistRepo.listEnabledTerms());
        }

        Set<String> tokens = new HashSet<>();
        Set<String> collapsedTerms = new HashSet<>();
        for (String line : raw) {
            if (line == null) continue;
            String trimmed = line.trim();
            if (trimmed.isBlank()) continue;
            if (trimmed.startsWith("#")) continue;
            String normalized = normalize(trimmed);
            if (normalized.isBlank()) continue;
            for (String token : tokenize(normalized)) {
                if (!token.isBlank()) tokens.add(token);
            }
            String collapsed = collapse(normalized);
            if (!collapsed.isBlank()) collapsedTerms.add(collapsed);
        }
        return new Compiled(Set.copyOf(tokens), Set.copyOf(collapsedTerms));
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

    private record Compiled(Set<String> tokens, Set<String> collapsedTerms) {}

    public record Match(String method) {}
}

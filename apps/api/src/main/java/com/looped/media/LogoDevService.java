package com.looped.media;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.net.URI;

@Service
public class LogoDevService {
    private final String token;
    private final boolean retina;
    private final int size;

    public LogoDevService(
            @Value("${logoDev.token:}") String token,
            @Value("${logoDev.retina:true}") boolean retina,
            @Value("${logoDev.size:256}") int size
    ) {
        this.token = token;
        this.retina = retina;
        this.size = Math.max(64, Math.min(1024, size));
    }

    public String urlForDomain(String domain) {
        String normalized = normalizeDomain(domain);
        if (normalized == null) return null;
        if (token == null || token.isBlank()) return null;
        String url = "https://img.logo.dev/" + normalized + "?token=" + token + "&size=" + size + "&format=png";
        if (retina) url += "&retina=true";
        return url;
    }

    private String normalizeDomain(String domain) {
        if (domain == null) return null;
        String trimmed = domain.trim();
        if (trimmed.isBlank()) return null;

        String normalized = trimmed.toLowerCase(Locale.ROOT);

        // Accept URLs like https://www.unc.edu/path -> unc.edu
        if (normalized.contains("://")) {
            try {
                URI uri = URI.create(normalized);
                String host = uri.getHost();
                if (host != null && !host.isBlank()) normalized = host.toLowerCase(Locale.ROOT);
            } catch (IllegalArgumentException ignored) {
                // Fall through to best-effort parsing below.
            }
        }

        // Accept emails like hello@unc.edu -> unc.edu
        int at = normalized.lastIndexOf('@');
        if (at >= 0 && at + 1 < normalized.length()) {
            normalized = normalized.substring(at + 1);
        }

        if (normalized.startsWith("@")) normalized = normalized.substring(1);
        if (normalized.startsWith("www.")) normalized = normalized.substring(4);
        int slash = normalized.indexOf('/');
        if (slash >= 0) normalized = normalized.substring(0, slash);
        int q = normalized.indexOf('?');
        if (q >= 0) normalized = normalized.substring(0, q);
        int hash = normalized.indexOf('#');
        if (hash >= 0) normalized = normalized.substring(0, hash);
        int colon = normalized.indexOf(':');
        if (colon >= 0) {
            String maybePort = normalized.substring(colon + 1);
            if (!maybePort.isBlank() && maybePort.chars().allMatch(Character::isDigit)) {
                normalized = normalized.substring(0, colon);
            }
        }

        normalized = normalized.trim();
        if (normalized.isBlank()) return null;
        if (!normalized.matches("^[a-z0-9.-]+$")) return null;
        return normalized;
    }
}

package com.looped.media;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public class LogoDevService {
    private final String token;
    private final boolean retina;

    public LogoDevService(
            @Value("${logoDev.token:}") String token,
            @Value("${logoDev.retina:true}") boolean retina
    ) {
        this.token = token;
        this.retina = retina;
    }

    public String urlForDomain(String domain) {
        String normalized = normalizeDomain(domain);
        if (normalized == null) return null;
        if (token == null || token.isBlank()) return null;
        String url = "https://img.logo.dev/" + normalized + "?token=" + token;
        if (retina) url += "&retina=true";
        return url;
    }

    private String normalizeDomain(String domain) {
        if (domain == null) return null;
        String trimmed = domain.trim().toLowerCase(Locale.ROOT);
        if (trimmed.startsWith("@")) trimmed = trimmed.substring(1);
        if (trimmed.isBlank()) return null;
        if (!trimmed.matches("^[a-z0-9.-]+$")) return null;
        return trimmed;
    }
}

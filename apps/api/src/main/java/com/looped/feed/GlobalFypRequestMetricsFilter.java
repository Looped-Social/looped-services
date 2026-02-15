package com.looped.feed;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)
public class GlobalFypRequestMetricsFilter extends OncePerRequestFilter {
    private final GlobalFypRequestMetricsService metrics;

    public GlobalFypRequestMetricsFilter(GlobalFypRequestMetricsService metrics) {
        this.metrics = metrics;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (request == null) return true;
        if (!"GET".equalsIgnoreCase(request.getMethod())) return true;
        String path = request.getRequestURI();
        if (path == null) return true;
        if (!path.equals("/v1/feed")) return true;

        String mode = request.getParameter("mode");
        if (mode == null || mode.isBlank()) mode = "for_you";
        if (!"for_you".equalsIgnoreCase(mode.trim())) return true;

        Long communityId = firstLongParam(request,
                "communityId", "community_id",
                "loopId", "loop_id" // legacy aliases
        );
        // Only track "global" FYP (no community filter).
        return communityId != null && communityId > 0;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        long startNs = System.nanoTime();
        int status = 500;
        try {
            filterChain.doFilter(request, response);
            status = response == null ? 500 : response.getStatus();
        } finally {
            long durationMs = (System.nanoTime() - startNs) / 1_000_000L;
            metrics.record(status, durationMs);
        }
    }

    private Long firstLongParam(HttpServletRequest request, String... names) {
        if (request == null || names == null) return null;
        for (String name : names) {
            if (name == null || name.isBlank()) continue;
            String raw = request.getParameter(name);
            Long v = parseLong(raw);
            if (v != null) return v;
        }
        return null;
    }

    private Long parseLong(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isBlank()) return null;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}


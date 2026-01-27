package com.looped.admin;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class AdminEdgeSecretFilter extends OncePerRequestFilter {
    private static final String HEADER_NAME = "X-Admin-Edge-Secret";

    private final String expectedSecret;

    public AdminEdgeSecretFilter(@Value("${admin.edge-secret:}") String expectedSecret) {
        this.expectedSecret = expectedSecret == null ? "" : expectedSecret;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!isEnabled() || !isAdminPath(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        String actualSecret = request.getHeader(HEADER_NAME);
        if (!secretsMatch(actualSecret, expectedSecret)) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean isEnabled() {
        return !expectedSecret.isBlank();
    }

    private boolean isAdminPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri != null && (uri.equals("/v1/admin") || uri.startsWith("/v1/admin/"));
    }

    private boolean secretsMatch(String actual, String expected) {
        if (actual == null || actual.isBlank()) return false;
        byte[] a = actual.getBytes(StandardCharsets.UTF_8);
        byte[] b = expected.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
    }
}

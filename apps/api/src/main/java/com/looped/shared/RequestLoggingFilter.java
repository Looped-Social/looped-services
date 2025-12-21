package com.looped.shared;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {
    public static Map<String, String> sanitizeHeaders(HttpServletRequest request) {
        Map<String, String> headers = Collections.list(request.getHeaderNames()).stream()
                .collect(Collectors.toMap(h -> h, request::getHeader));
        if (headers.containsKey("Authorization")) {
            headers.put("Authorization", "REDACTED");
        }
        return headers;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        String requestId = UUID.randomUUID().toString();
        MDC.put("request_id", requestId);
        response.setHeader("X-Request-Id", requestId);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String actorHeader = request.getHeader("X-Actor");
        boolean suppressPrincipal = request.getRequestURI().startsWith("/anon")
                || (actorHeader != null && actorHeader.equalsIgnoreCase("anon"));
        if (!suppressPrincipal && auth != null && auth.getPrincipal() != null) {
            MDC.put("principal", auth.getName());
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }
}

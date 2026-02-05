package com.looped.shared;

import com.looped.users.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Locale;

@Component
@Order(Ordered.LOWEST_PRECEDENCE - 11)
public class UserAccountStatusFilter extends OncePerRequestFilter {
    private final UserRepository users;

    public UserAccountStatusFilter(UserRepository users) {
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!path.startsWith("/v1/") || path.startsWith("/v1/admin") || path.startsWith("/v1/appeals") || path.startsWith("/v1/violations")) {
            filterChain.doFilter(request, response);
            return;
        }
        if (AnonRequestDetector.isAnonRequest(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            filterChain.doFilter(request, response);
            return;
        }
        var statusOpt = users.accessStatusByFirebaseUid(auth.getName());
        if (statusOpt.isEmpty()) {
            filterChain.doFilter(request, response);
            return;
        }
        var status = statusOpt.get();
        if (status.disabledAt != null) {
            respond(response, 403, "account_disabled", "Account is disabled");
            return;
        }
        if (status.deletedAt != null && "admin".equalsIgnoreCase(status.deletedSource)) {
            respond(response, 403, "account_deleted", "Account is deleted");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private void respond(HttpServletResponse response, int status, String error, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        String e = error == null ? "" : error.trim().toLowerCase(Locale.ROOT);
        String m = message == null ? "" : message;
        response.getWriter().write("{\"error\":\"" + escapeJson(e) + "\",\"message\":\"" + escapeJson(m) + "\"}");
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}


package com.looped.shared;

import com.looped.users.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
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
        if (!path.startsWith("/v1/") || path.startsWith("/v1/admin")) {
            filterChain.doFilter(request, response);
            return;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!isAuthenticatedPrincipal(auth)) {
            filterChain.doFilter(request, response);
            return;
        }

        var statusOpt = users.accessStatusByFirebaseUid(auth.getName());
        if (statusOpt.isEmpty()) {
            if (isOnboardingBootstrapRoute(request)) {
                filterChain.doFilter(request, response);
                return;
            }
            if (isMeRoute(path)) {
                filterChain.doFilter(request, response);
                return;
            }
            respond(response, 409, "user_not_provisioned", "Complete onboarding before using this endpoint");
            return;
        }
        var status = statusOpt.get();
        if (status.companyId == null) {
            if (isOnboardingBootstrapRoute(request)) {
                filterChain.doFilter(request, response);
                return;
            }
            if (isMeRoute(path)) {
                filterChain.doFilter(request, response);
                return;
            }
            respond(response, 409, "user_not_provisioned", "Complete onboarding before using this endpoint");
            return;
        }
        if (status.disabledAt != null) {
            respond(response, 403, "account_disabled", "Account is disabled");
            return;
        }
        if (status.deletedAt != null && "admin".equalsIgnoreCase(status.deletedSource)) {
            respond(response, 403, "account_deleted", "Account is deleted");
            return;
        }
        if (status.deletedAt != null && status.deletedSource != null && !"admin".equalsIgnoreCase(status.deletedSource)) {
            filterChain.doFilter(request, response);
            return;
        }
        if (status.onboardingCompletedAt == null && !isOnboardingBootstrapRoute(request) && !isMeRoute(path)) {
            respondOnboardingIncomplete(response, status.onboardingStep);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean isAuthenticatedPrincipal(Authentication auth) {
        return auth != null
                && auth.isAuthenticated()
                && !(auth instanceof AnonymousAuthenticationToken)
                && auth.getName() != null
                && !auth.getName().isBlank()
                && !"anonymousUser".equalsIgnoreCase(auth.getName());
    }

    private boolean isMeRoute(String path) {
        return "/v1/me".equals(path);
    }

    private boolean isOnboardingBootstrapRoute(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();
        return (HttpMethod.POST.matches(method) && "/v1/users/onboard".equals(path))
                || (HttpMethod.GET.matches(method) && "/v1/users/username/availability".equals(path))
                || (HttpMethod.PUT.matches(method) && "/v1/users/me/onboarding".equals(path))
                || (HttpMethod.GET.matches(method) && "/v1/communities/search".equals(path))
                || (HttpMethod.GET.matches(method) && "/v1/communities/recommended".equals(path))
                || (HttpMethod.POST.matches(method) && "/v1/verification/start".equals(path))
                || (HttpMethod.POST.matches(method) && "/v1/verification/finish".equals(path));
    }

    private void respondOnboardingIncomplete(HttpServletResponse response, String onboardingStep) throws IOException {
        response.setStatus(409);
        response.setContentType("application/json");
        String step = normalizeOnboardingStep(onboardingStep);
        response.getWriter().write(
                "{\"error\":\"onboarding_incomplete\",\"message\":\"Complete onboarding before using this endpoint\",\"onboarding_step\":\""
                        + escapeJson(step)
                        + "\"}"
        );
    }

    private String normalizeOnboardingStep(String onboardingStep) {
        if (onboardingStep == null || onboardingStep.isBlank()) {
            return "verification";
        }
        return onboardingStep.trim().toLowerCase(Locale.ROOT);
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

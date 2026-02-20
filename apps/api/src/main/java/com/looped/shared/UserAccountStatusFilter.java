package com.looped.shared;

import com.looped.users.OnboardingV2Service;
import com.looped.users.OnboardingV2Stages;
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
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;

@Component
@Order(Ordered.LOWEST_PRECEDENCE - 11)
public class UserAccountStatusFilter extends OncePerRequestFilter {
    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();
    private final UserRepository users;
    private final OnboardingV2Service onboardingV2;

    public UserAccountStatusFilter(UserRepository users, OnboardingV2Service onboardingV2) {
        this.users = users;
        this.onboardingV2 = onboardingV2;
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
            if (isSelfLifecycleRoute(request)) {
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
            if (isSelfLifecycleRoute(request)) {
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
        if (status.onboardingCompletedAt == null
                && isOnboardingRestrictedRoute(request)
                && !isOnboardingBootstrapRoute(request)
                && !isMeRoute(path)) {
            respondOnboardingIncomplete(response, status.id, status.onboardingStep, status.onboardingCompletedAt);
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
        return allowRoute(method, path, HttpMethod.POST, "/v1/users/onboard")
                || allowRoute(method, path, HttpMethod.GET, "/v1/users/username/availability")
                || allowRoute(method, path, HttpMethod.PUT, "/v1/users/me/onboarding")
                || allowRoute(method, path, HttpMethod.POST, "/v1/users/me/onboarding-v2/**")
                || allowRoute(method, path, HttpMethod.PUT, "/v1/users/me/onboarding-v2/**")
                || allowRoute(method, path, HttpMethod.PUT, "/v1/users/me/identity")
                || allowRoute(method, path, HttpMethod.PUT, "/v1/users/me/display-community")
                || allowRoute(method, path, HttpMethod.PUT, "/v1/users/me/display-specialization")
                || allowRoute(method, path, HttpMethod.GET, "/v1/communities/search")
                || allowRoute(method, path, HttpMethod.GET, "/v1/communities/recommended")
                || allowRoute(method, path, HttpMethod.GET, "/v1/specializations/recommended")
                || allowRoute(method, path, HttpMethod.GET, "/v1/specializations/browse")
                || allowRoute(method, path, HttpMethod.GET, "/v1/fields")
                || allowRoute(method, path, HttpMethod.GET, "/v1/majors")
                || allowRoute(method, path, HttpMethod.POST, "/v1/communities/*/join")
                || allowRoute(method, path, HttpMethod.DELETE, "/v1/communities/*/join")
                || allowRoute(method, path, HttpMethod.POST, "/v1/communities/*/follow")
                || allowRoute(method, path, HttpMethod.DELETE, "/v1/communities/*/follow")
                || allowRoute(method, path, HttpMethod.POST, "/v1/specializations/*/join")
                || allowRoute(method, path, HttpMethod.DELETE, "/v1/specializations/*/join")
                || allowRoute(method, path, HttpMethod.POST, "/v1/specializations/*/follow")
                || allowRoute(method, path, HttpMethod.DELETE, "/v1/specializations/*/follow")
                || allowRoute(method, path, HttpMethod.GET, "/v1/communities/*/permissions")
                || allowRoute(method, path, HttpMethod.GET, "/v1/communities/*/domains")
                || allowRoute(method, path, HttpMethod.POST, "/v1/communities/*/verification/start")
                || allowRoute(method, path, HttpMethod.POST, "/v1/communities/*/verification/finish")
                || allowRoute(method, path, HttpMethod.POST, "/v1/communities/*/verification/photo-id/start")
                || allowRoute(method, path, HttpMethod.POST, "/v1/communities/*/verification/photo-id/presign")
                || allowRoute(method, path, HttpMethod.POST, "/v1/communities/*/verification/photo-id/submit")
                || allowRoute(method, path, HttpMethod.GET, "/v1/communities/*/verification/photo-id/status")
                || allowRoute(method, path, HttpMethod.POST, "/v1/verification/start")
                || allowRoute(method, path, HttpMethod.POST, "/v1/verification/finish");
    }

    private boolean isSelfLifecycleRoute(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();
        return allowRoute(method, path, HttpMethod.DELETE, "/v1/users/me")
                || allowRoute(method, path, HttpMethod.POST, "/v1/users/me/delete")
                || allowRoute(method, path, HttpMethod.POST, "/v1/users/me/deactivate");
    }

    private boolean isOnboardingRestrictedRoute(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();
        return allowRoute(method, path, HttpMethod.GET, "/v1/feed")
                || allowRoute(method, path, HttpMethod.GET, "/v1/feed/**");
    }

    private boolean allowRoute(String method, String path, HttpMethod expectedMethod, String pattern) {
        return expectedMethod.matches(method) && PATH_MATCHER.match(pattern, path);
    }

    private void respondOnboardingIncomplete(HttpServletResponse response,
                                             long userId,
                                             String onboardingStep,
                                             OffsetDateTime onboardingCompletedAt) throws IOException {
        response.setStatus(409);
        response.setContentType("application/json");
        String step = normalizeOnboardingStep(onboardingStep);
        String stage = onboardingV2.stageForUser(userId, step, onboardingCompletedAt);
        if (stage == null || stage.isBlank()) {
            stage = OnboardingV2Stages.stageForLegacy(step, onboardingCompletedAt);
        }
        List<String> allowedNextStagesV2 = OnboardingV2Stages.allowedNextStages(stage);
        List<String> allowedNextSteps = OnboardingV2Stages.toLegacySteps(allowedNextStagesV2);
        response.getWriter().write(
                "{\"error\":\"onboarding_incomplete\",\"message\":\"Complete onboarding before using this endpoint\",\"onboarding_step\":\""
                        + escapeJson(step)
                        + "\",\"onboardingStep\":\""
                        + escapeJson(step)
                        + "\",\"current_step\":\""
                        + escapeJson(step)
                        + "\",\"currentStep\":\""
                        + escapeJson(step)
                        + "\",\"allowed_next_steps\":"
                        + jsonArray(allowedNextSteps)
                        + ",\"allowedNextSteps\":"
                        + jsonArray(allowedNextSteps)
                        + ",\"current_stage_v2\":\""
                        + escapeJson(stage)
                        + "\",\"allowed_next_stages_v2\":"
                        + jsonArray(allowedNextStagesV2)
                        + "}"
        );
    }

    private String normalizeOnboardingStep(String onboardingStep) {
        if (onboardingStep == null || onboardingStep.isBlank()) {
            return OnboardingV2Stages.LEGACY_VERIFICATION;
        }
        String normalized = onboardingStep.trim().toLowerCase(Locale.ROOT);
        String valid = OnboardingV2Stages.normalizeLegacyStep(normalized);
        return valid != null ? valid : OnboardingV2Stages.LEGACY_VERIFICATION;
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

    private String jsonArray(List<String> values) {
        if (values == null || values.isEmpty()) return "[]";
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) out.append(',');
            out.append('"').append(escapeJson(values.get(i))).append('"');
        }
        out.append(']');
        return out.toString();
    }
}

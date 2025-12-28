package com.looped.shared;

import com.looped.users.UserBanRepository;
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

@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)
public class UserBanFilter extends OncePerRequestFilter {
    private final UserBanRepository bans;

    public UserBanFilter(UserBanRepository bans) {
        this.bans = bans;
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
        if (bans.findActiveByFirebaseUid(auth.getName()).isPresent()) {
            response.setStatus(403);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"user_banned\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }
}

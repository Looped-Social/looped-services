package com.looped.shared;

import jakarta.servlet.http.HttpServletRequest;

public final class AnonRequestDetector {
    private AnonRequestDetector() {}

    public static boolean isAnonRequest(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path != null && path.startsWith("/anon")) {
            return true;
        }
        String actor = request.getHeader("X-Actor");
        if (actor != null && actor.equalsIgnoreCase("anon")) {
            return true;
        }
        String asAnon = request.getParameter("asAnon");
        if (asAnon == null) {
            asAnon = request.getParameter("isAnon");
        }
        return asAnon != null && asAnon.equalsIgnoreCase("true");
    }
}

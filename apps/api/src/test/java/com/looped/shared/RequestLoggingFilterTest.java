package com.looped.shared;

import org.junit.jupiter.api.Test;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RequestLoggingFilterTest {
    @Test
    void sanitizeHeaders_redacts_authorization() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeaderNames()).thenReturn(java.util.Collections.enumeration(java.util.List.of("Authorization","X-Test")));
        when(req.getHeader("Authorization")).thenReturn("Bearer abc");
        when(req.getHeader("X-Test")).thenReturn("ok");
        Map<String,String> out = RequestLoggingFilter.sanitizeHeaders(req);
        assertThat(out.get("Authorization")).isEqualTo("REDACTED");
        assertThat(out.get("X-Test")).isEqualTo("ok");
    }
}


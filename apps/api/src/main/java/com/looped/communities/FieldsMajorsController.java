package com.looped.communities;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1")
public class FieldsMajorsController {
    private static final CacheControl CACHE_CONTROL = CacheControl.maxAge(Duration.ZERO).cachePrivate().mustRevalidate();
    private static final DateTimeFormatter RFC_1123 = DateTimeFormatter.RFC_1123_DATE_TIME;

    private final CommunitiesRepository communities;

    public FieldsMajorsController(CommunitiesRepository communities) {
        this.communities = communities;
    }

    @GetMapping("/fields")
    public ResponseEntity<?> fields(@AuthenticationPrincipal Jwt jwt,
                                    jakarta.servlet.http.HttpServletRequest request) {
        return listSpecializations("field", request);
    }

    @GetMapping("/majors")
    public ResponseEntity<?> majors(@AuthenticationPrincipal Jwt jwt,
                                    jakarta.servlet.http.HttpServletRequest request) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok().cacheControl(CACHE_CONTROL);
        builder.header(HttpHeaders.VARY, HttpHeaders.AUTHORIZATION);
        return builder.body(Map.of("items", List.of()));
    }

    private ResponseEntity<?> listSpecializations(String specializationType,
                                                  jakarta.servlet.http.HttpServletRequest request) {
        CommunitiesRepository.SpecializationsCacheInfo cache = communities.specializationsCacheInfo(specializationType);
        String etag = cache.etagMd5() == null ? null : "\"" + cache.etagMd5() + "\"";
        long lastModifiedMs = cache.lastModified() == null ? 0L : cache.lastModified().toInstant().toEpochMilli();

        if (etag != null && ifNoneMatchMatches(request.getHeader(HttpHeaders.IF_NONE_MATCH), etag)) {
            return cachedNotModified(etag, lastModifiedMs);
        }
        if (lastModifiedMs > 0 && ifModifiedSinceNotChanged(request.getHeader(HttpHeaders.IF_MODIFIED_SINCE), lastModifiedMs)) {
            return cachedNotModified(etag, lastModifiedMs);
        }

        List<CommunitiesRepository.SpecializationFilterRow> rows = communities.listSpecializationsForFilters(specializationType);
        List<Map<String, Object>> items = rows.stream().map(r -> {
            Map<String, Object> out = new HashMap<>();
            out.put("id", r.id());
            out.put("name", r.name());
            if (r.shortName() != null && !r.shortName().isBlank()) out.put("short_name", r.shortName());
            Map<String, Object> icon = SpecializationIcons.payloadOrNull(r.iconKind(), r.iconValue());
            if (icon != null) out.put("icon", icon);
            return out;
        }).toList();

        ResponseEntity.BodyBuilder builder = ResponseEntity.ok().cacheControl(CACHE_CONTROL);
        if (etag != null) builder = builder.eTag(etag);
        if (lastModifiedMs > 0) builder = builder.lastModified(lastModifiedMs);
        builder.header(HttpHeaders.VARY, HttpHeaders.AUTHORIZATION);
        return builder.body(Map.of("items", items));
    }

    private ResponseEntity<?> cachedNotModified(String etag, long lastModifiedMs) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.NOT_MODIFIED).cacheControl(CACHE_CONTROL);
        if (etag != null) builder = builder.eTag(etag);
        if (lastModifiedMs > 0) builder = builder.lastModified(lastModifiedMs);
        builder.header(HttpHeaders.VARY, HttpHeaders.AUTHORIZATION);
        return builder.build();
    }

    private boolean ifNoneMatchMatches(String ifNoneMatch, String etag) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank() || etag == null || etag.isBlank()) return false;
        if ("*".equals(ifNoneMatch.trim())) return true;
        for (String part : ifNoneMatch.split(",")) {
            String token = part.trim();
            if (token.equals(etag)) return true;
            if (token.startsWith("W/") && token.substring(2).trim().equals(etag)) return true;
        }
        return false;
    }

    private boolean ifModifiedSinceNotChanged(String ifModifiedSince, long lastModifiedMs) {
        if (ifModifiedSince == null || ifModifiedSince.isBlank() || lastModifiedMs <= 0) return false;
        try {
            ZonedDateTime ims = ZonedDateTime.parse(ifModifiedSince, RFC_1123);
            long imsMs = ims.toInstant().toEpochMilli();
            long serverMs = (lastModifiedMs / 1000L) * 1000L;
            return imsMs >= serverMs;
        } catch (DateTimeParseException ignored) {
            return false;
        }
    }
}

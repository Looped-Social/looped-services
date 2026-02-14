package com.looped.links;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/.well-known")
public class UniversalLinksController {
    private final UniversalLinksProperties properties;

    public UniversalLinksController(UniversalLinksProperties properties) {
        this.properties = properties;
    }

    @GetMapping(value = "/apple-app-site-association", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> appleAppSiteAssociation() {
        Map<String, Object> applinks = new LinkedHashMap<>();
        applinks.put("apps", List.of());
        applinks.put("details", List.of(Map.of(
                "appID", appId(),
                "paths", sanitizedIosPaths()
        )));
        Map<String, Object> body = Map.of("applinks", applinks);

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .cacheControl(cacheControl())
                .eTag(etag())
                .body(body);
    }

    @GetMapping(value = "/assetlinks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<Map<String, Object>>> assetLinks() {
        List<Map<String, Object>> body;
        if (isBlank(properties.getAndroidPackageName()) || isBlank(properties.getAndroidSha256CertFingerprint())) {
            body = List.of();
        } else {
            body = List.of(Map.of(
                    "relation", List.of("delegate_permission/common.handle_all_urls"),
                    "target", Map.of(
                            "namespace", "android_app",
                            "package_name", properties.getAndroidPackageName().trim(),
                            "sha256_cert_fingerprints", List.of(properties.getAndroidSha256CertFingerprint().trim())
                    )
            ));
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .cacheControl(cacheControl())
                .eTag(etag())
                .body(body);
    }

    private CacheControl cacheControl() {
        int maxAge = Math.max(0, properties.getCacheMaxAgeSeconds());
        return CacheControl.maxAge(maxAge, TimeUnit.SECONDS).cachePublic().mustRevalidate();
    }

    private String etag() {
        String version = properties.getAasaVersion();
        if (version == null || version.isBlank()) return "\"v1\"";
        return "\"" + version.trim().replace("\"", "") + "\"";
    }

    private String appId() {
        return trimmedOrPlaceholder(properties.getAppleTeamId(), "REPLACE_TEAM_ID")
                + "."
                + trimmedOrPlaceholder(properties.getIosBundleId(), "REPLACE_BUNDLE_ID");
    }

    private List<String> sanitizedIosPaths() {
        if (properties.getIosPaths() == null || properties.getIosPaths().isEmpty()) {
            return List.of("/p/*", "/u/*");
        }
        return properties.getIosPaths().stream()
                .filter(s -> s != null && !s.isBlank())
                .map(String::trim)
                .toList();
    }

    private String trimmedOrPlaceholder(String value, String placeholder) {
        if (value == null || value.isBlank()) return placeholder;
        return value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

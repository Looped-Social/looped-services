package com.looped.admin;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.looped.media.MediaRepository;
import com.looped.settings.AppConfigService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Map;

@RestController
@RequestMapping("/v1/admin/settings/profile")
public class AdminProfileSettingsController {
    private final AdminAuthService auth;
    private final AppConfigService appConfig;
    private final MediaRepository media;
    private final AdminAuditRepository audit;
    private final String cloudfrontDomain;

    public AdminProfileSettingsController(AdminAuthService auth,
                                         AppConfigService appConfig,
                                         MediaRepository media,
                                         AdminAuditRepository audit,
                                         @Value("${cloudfront.domain:}") String cloudfrontDomain) {
        this.auth = auth;
        this.appConfig = appConfig;
        this.media = media;
        this.audit = audit;
        this.cloudfrontDomain = cloudfrontDomain == null ? "" : cloudfrontDomain.trim();
    }

    @GetMapping
    public ResponseEntity<?> get(@AuthenticationPrincipal Jwt jwt) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.CREATE_COMMUNITY);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        return ResponseEntity.ok(Map.of(
                "default_profile_image_url", appConfig.defaultProfileImageUrl()
        ));
    }

    @PatchMapping
    public ResponseEntity<?> update(@AuthenticationPrincipal Jwt jwt, @RequestBody UpdateProfileSettingsRequest body) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.CREATE_COMMUNITY);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        if (body == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "invalid_body"));
        }

        String resolvedUrl = null;
        if (Boolean.TRUE.equals(body.clearDefaultProfileImage())) {
            resolvedUrl = null;
        } else if (body.profileMediaAssetId() != null) {
            if (cloudfrontDomain.isBlank()) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                        "error", "cdn_not_configured",
                        "message", "cloudfront.domain is unset"
                ));
            }
            var assetOpt = media.findById(body.profileMediaAssetId());
            if (assetOpt.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "media_asset_not_found"));
            }
            var asset = assetOpt.get();
            if (asset.mimeType == null || !asset.mimeType.startsWith("image/")) {
                return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                        "error", "invalid_profile_image",
                        "message", "Media asset must be an image"
                ));
            }
            resolvedUrl = "https://" + cloudfrontDomain + "/" + asset.s3Key;
        } else if (body.defaultProfileImageUrl() != null) {
            var normalized = normalizeDefaultProfileImageUrl(body.defaultProfileImageUrl());
            if (normalized == null) {
                return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                        "error", "invalid_default_profile_image_url",
                        "message", "defaultProfileImageUrl must be a valid https URL (http allowed only for localhost)"
                ));
            }
            resolvedUrl = normalized;
        } else {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "no_changes"));
        }

        appConfig.setDefaultProfileImageUrl(resolvedUrl, authRes.admin().id);
        audit.log(authRes.admin().id, "settings.profile.update", "settings", null,
                "default_profile_image_url=" + (resolvedUrl == null ? "null" : "set"));

        return ResponseEntity.ok(Map.of(
                "default_profile_image_url", resolvedUrl
        ));
    }

    private String normalizeDefaultProfileImageUrl(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isBlank() || trimmed.length() > 2048) return null;
        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (uri.getScheme() == null || uri.getHost() == null) return null;
        String scheme = uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        if ("https".equals(scheme)) return trimmed;
        if (!"http".equals(scheme)) return null;
        String host = uri.getHost().toLowerCase(java.util.Locale.ROOT);
        if ("localhost".equals(host) || "127.0.0.1".equals(host) || "::1".equals(host)) return trimmed;
        return null;
    }

    public record UpdateProfileSettingsRequest(
            @JsonProperty("defaultProfileImageUrl") String defaultProfileImageUrl,
            @JsonProperty("profileMediaAssetId") Long profileMediaAssetId,
            @JsonProperty("clearDefaultProfileImage") Boolean clearDefaultProfileImage
    ) {}
}

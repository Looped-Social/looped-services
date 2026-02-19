package com.looped.admin;

import com.looped.communities.CommunitiesRepository;
import com.looped.communities.CommunityLogoAssetsRepository;
import com.looped.communities.CommunityLogoResolver;
import com.looped.media.MediaImageSafetyService;
import com.looped.media.MediaRepository;
import com.looped.media.MediaService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.DeleteMapping;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/v1/admin/communities")
@Validated
public class AdminCommunityLogosController {
    private static final String LOGO_PREFIX = "media/communities/logos/";

    private final AdminAuthService auth;
    private final CommunitiesRepository communities;
    private final CommunityLogoAssetsRepository logoAssets;
    private final MediaService mediaService;
    private final MediaImageSafetyService imageSafety;
    private final MediaRepository mediaRepository;
    private final CommunityLogoResolver logoResolver;
    private final AdminAuditRepository audit;
    private final String cloudfrontDomain;
    private final String callbackSecret;
    private final boolean allowExternalCustomLogoUrl;

    public AdminCommunityLogosController(AdminAuthService auth,
                                         CommunitiesRepository communities,
                                         CommunityLogoAssetsRepository logoAssets,
                                         MediaService mediaService,
                                         MediaImageSafetyService imageSafety,
                                         MediaRepository mediaRepository,
                                         CommunityLogoResolver logoResolver,
                                         AdminAuditRepository audit,
                                         @Value("${cloudfront.domain:}") String cloudfrontDomain,
                                         @Value("${media.callbackSecret:}") String callbackSecret,
                                         @Value("${community.logos.allowExternalCustomUrl:false}") boolean allowExternalCustomLogoUrl) {
        this.auth = auth;
        this.communities = communities;
        this.logoAssets = logoAssets;
        this.mediaService = mediaService;
        this.imageSafety = imageSafety;
        this.mediaRepository = mediaRepository;
        this.logoResolver = logoResolver;
        this.audit = audit;
        this.cloudfrontDomain = cloudfrontDomain;
        this.callbackSecret = callbackSecret;
        this.allowExternalCustomLogoUrl = allowExternalCustomLogoUrl;
    }

    @GetMapping("/{id}/logos")
    public ResponseEntity<?> list(@AuthenticationPrincipal Jwt jwt,
                                  @PathVariable("id") long id) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.CREATE_COMMUNITY);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        var communityOpt = communities.findById(id);
        if (communityOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
        }
        var community = communityOpt.get();
        var uploads = logoAssets.listByCommunity(id);
        List<Map<String, Object>> items = uploads.stream().map(row -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", row.id);
            map.put("media_asset_id", row.mediaAssetId);
            map.put("key", row.s3Key);
            map.put("mime_type", row.mimeType);
            if (row.createdAt != null) map.put("created_at", row.createdAt);
            String cdnUrl = cdnUrl(row.s3Key);
            if (cdnUrl != null) map.put("cdn_url", cdnUrl);
            return map;
        }).toList();

        String logoDevUrl = logoResolver.resolve(id, community.kind, null);
        String selectedUrl = community.imageUrl;
        String selectedSource;
        Long selectedUploadId = null;
        if (selectedUrl == null || selectedUrl.isBlank()) {
            if (logoDevUrl != null) {
                selectedSource = "logo_dev";
                selectedUrl = logoDevUrl;
            } else {
                selectedSource = "none";
                selectedUrl = null;
            }
        } else {
            selectedSource = "custom";
            for (var item : items) {
                Object cdn = item.get("cdn_url");
                if (cdn != null && cdn.equals(selectedUrl)) {
                    selectedSource = "upload";
                    selectedUploadId = (Long) item.get("id");
                    break;
                }
            }
        }

        Map<String, Object> out = new HashMap<>();
        out.put("community_id", id);
        out.put("kind", community.kind);
        out.put("uploads", items);
        if (logoDevUrl != null) out.put("logo_dev_url", logoDevUrl);
        out.put("selected_source", selectedSource);
        if (selectedUrl != null) out.put("selected_image_url", selectedUrl);
        if (selectedUploadId != null) out.put("selected_upload_id", selectedUploadId);
        return ResponseEntity.ok(out);
    }

    @PostMapping("/{id}/logos/presign")
    public ResponseEntity<?> presign(@AuthenticationPrincipal Jwt jwt,
                                     @PathVariable("id") long id,
                                     @Valid @RequestBody PresignRequest body) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.CREATE_COMMUNITY);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        if (communities.findById(id).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
        }
        var res = mediaService.presignImage(body.contentType(), body.sizeBytes(), LOGO_PREFIX);
        if (res.status() == MediaService.Status.BAD_REQUEST) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", res.error()));
        }
        Map<String,Object> out = new HashMap<>();
        out.put("key", res.key());
        out.put("uploadUrl", res.uploadUrl());
        out.put("headers", res.headers());
        if (res.callbackSignature() != null) out.put("callbackSignature", res.callbackSignature());
        return ResponseEntity.ok(out);
    }

    @PostMapping("/{id}/logos/callback")
    public ResponseEntity<?> callback(@AuthenticationPrincipal Jwt jwt,
                                      @PathVariable("id") long id,
                                      @RequestHeader(value = "X-Media-Signature", required = false) String signature,
                                      @Valid @RequestBody CallbackRequest body) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.CREATE_COMMUNITY);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        if (callbackSecret != null && !callbackSecret.isBlank()) {
            String expected = MediaService.hmacSha256Base64(callbackSecret, body.key());
            if (signature == null || !signature.equals(expected)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "invalid_signature"));
            }
        }
        if (communities.findById(id).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
        }
        String key = body.key();
        if (key == null || !key.startsWith(LOGO_PREFIX)) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "invalid_key"));
        }
        String normalizedMimeType = MediaService.normalizeMimeType(body.mimeType());
        if (normalizedMimeType == null || !normalizedMimeType.startsWith("image/")) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "invalid_image"));
        }

        Long mediaAssetId;
        String resolvedMimeType = normalizedMimeType;
        var existingMedia = mediaRepository.findByKey(key);
        if (existingMedia.isPresent()) {
            if (imageSafety.isUnsafeForClient(existingMedia.get().mimeType, existingMedia.get().width, existingMedia.get().height)) {
                return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                        "error", "invalid_image",
                        "message", "Image dimensions exceed limits"
                ));
            }
            if (existingMedia.get().mimeType == null || !existingMedia.get().mimeType.toLowerCase().startsWith("image/")) {
                return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "invalid_image"));
            }
            mediaAssetId = existingMedia.get().id;
            resolvedMimeType = existingMedia.get().mimeType;
        } else {
            Integer persistedWidth = body.width();
            Integer persistedHeight = body.height();
            String persistedMimeType = normalizedMimeType;
            try {
                var normalized = imageSafety.validateAndNormalizeUploadedImage(key, normalizedMimeType, body.width(), body.height());
                persistedMimeType = normalized.mimeType();
                persistedWidth = normalized.width();
                persistedHeight = normalized.height();
            } catch (MediaImageSafetyService.InvalidImageException e) {
                return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                        "error", "invalid_image",
                        "message", e.getMessage()
                ));
            } catch (MediaImageSafetyService.MediaUnavailableException e) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                        "error", "media_unavailable"
                ));
            }
            mediaAssetId = mediaRepository.insert(null, key, persistedMimeType, persistedWidth, persistedHeight, body.durationSeconds(), null);
            resolvedMimeType = persistedMimeType;
        }

        boolean linked = logoAssets.insert(id, mediaAssetId);
        if (!linked) {
            var existingLink = logoAssets.findByCommunityAndKey(id, key);
            if (existingLink.isEmpty()) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "logo_already_linked"));
            }
            return ResponseEntity.ok(Map.of("status", "exists"));
        }

        audit.log(authRes.admin().id, "community.logo.upload", "community", id, "media_asset_id=" + mediaAssetId);

        Map<String,Object> out = new HashMap<>();
        out.put("status", "created");
        out.put("media_asset_id", mediaAssetId);
        out.put("key", key);
        out.put("mime_type", resolvedMimeType);
        String cdnUrl = cdnUrl(key);
        if (cdnUrl != null) out.put("cdn_url", cdnUrl);
        return new ResponseEntity<>(out, HttpStatus.CREATED);
    }

    @PatchMapping("/{id}/logo")
    public ResponseEntity<?> updateLogo(@AuthenticationPrincipal Jwt jwt,
                                        @PathVariable("id") long id,
                                        @Valid @RequestBody UpdateLogoRequest body) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.CREATE_COMMUNITY);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        var communityOpt = communities.findById(id);
        if (communityOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
        }

        boolean useLogoDev = body.useLogoDev() != null && body.useLogoDev();
        boolean hasImageKey = body.imageKey() != null && !body.imageKey().isBlank();
        boolean hasImageUrl = body.imageUrl() != null && !body.imageUrl().isBlank();
        int choices = (useLogoDev ? 1 : 0) + (hasImageKey ? 1 : 0) + (hasImageUrl ? 1 : 0);
        if (choices != 1) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "invalid_logo_selection"));
        }

        String selectedUrl = null;
        String selectedSource;
        Long selectedUploadId = null;
        if (useLogoDev) {
            if (logoResolver.resolve(id, communityOpt.get().kind, null) == null) {
                return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "logo_dev_unavailable"));
            }
            selectedSource = "logo_dev";
        } else if (hasImageKey) {
            String key = body.imageKey().trim();
            if (!key.startsWith(LOGO_PREFIX)) {
                return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "invalid_key"));
            }
            var logoAsset = logoAssets.findByCommunityAndKey(id, key);
            if (logoAsset.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "logo_not_found"));
            }
            String cdn = cdnUrl(key);
            if (cdn == null) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "cdn_not_configured"));
            }
            selectedUrl = cdn;
            selectedSource = "upload";
            selectedUploadId = logoAsset.get().id;
        } else {
            selectedUrl = normalizeCustomLogoUrl(body.imageUrl());
            if (selectedUrl == null) {
                return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                        "error", "invalid_logo_url",
                        "message", "Use an uploaded logo or Logo.dev fallback"
                ));
            }
            selectedSource = "custom";
        }

        boolean updated = communities.updateImageUrl(id, selectedUrl);
        if (!updated) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
        }
        audit.log(authRes.admin().id, "community.logo.set", "community", id, "source=" + selectedSource);

        String resolved = selectedUrl;
        if (resolved == null || resolved.isBlank()) {
            resolved = logoResolver.resolve(id, communityOpt.get().kind, null);
        }
        Map<String, Object> out = new HashMap<>();
        out.put("community_id", id);
        out.put("selected_source", selectedSource);
        if (resolved != null) out.put("image_url", resolved);
        if (selectedUploadId != null) out.put("selected_upload_id", selectedUploadId);
        return ResponseEntity.ok(out);
    }

    @DeleteMapping("/{id}/logos/{uploadId}")
    public ResponseEntity<?> deleteLogoUpload(@AuthenticationPrincipal Jwt jwt,
                                              @PathVariable("id") long id,
                                              @PathVariable("uploadId") long uploadId) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.CREATE_COMMUNITY);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        if (uploadId <= 0) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "logo_not_found"));
        }

        var communityOpt = communities.findById(id);
        if (communityOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
        }
        var logoAsset = logoAssets.findByIdAndCommunity(uploadId, id);
        if (logoAsset.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "logo_not_found"));
        }

        boolean deleted = logoAssets.deleteByIdAndCommunity(uploadId, id);
        if (!deleted) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "logo_not_found"));
        }

        boolean clearedSelectedLogo = false;
        String selectedImageUrl = communityOpt.get().imageUrl;
        String deletedUploadCdn = cdnUrl(logoAsset.get().s3Key);
        if (selectedImageUrl != null && deletedUploadCdn != null && selectedImageUrl.equals(deletedUploadCdn)) {
            communities.updateImageUrl(id, null);
            clearedSelectedLogo = true;
        }

        audit.log(authRes.admin().id, "community.logo.delete", "community", id, "upload_id=" + uploadId);

        String resolved = communities.findById(id)
                .map(row -> row.imageUrl)
                .orElse(null);
        String selectedSource = "none";
        Long selectedUploadId = null;
        if (resolved == null || resolved.isBlank()) {
            resolved = logoResolver.resolve(id, communityOpt.get().kind, null);
            if (resolved != null && !resolved.isBlank()) {
                selectedSource = "logo_dev";
            }
        } else {
            selectedSource = "custom";
            for (var upload : logoAssets.listByCommunity(id)) {
                String uploadCdn = cdnUrl(upload.s3Key);
                if (uploadCdn != null && uploadCdn.equals(resolved)) {
                    selectedSource = "upload";
                    selectedUploadId = upload.id;
                    break;
                }
            }
        }

        Map<String, Object> out = new HashMap<>();
        out.put("status", "deleted");
        out.put("upload_id", uploadId);
        out.put("cleared_selected_logo", clearedSelectedLogo);
        out.put("selected_source", selectedSource);
        if (selectedUploadId != null) {
            out.put("selected_upload_id", selectedUploadId);
        }
        if (resolved != null && !resolved.isBlank()) {
            out.put("image_url", resolved);
        }
        return ResponseEntity.ok(out);
    }

    private String cdnUrl(String key) {
        if (cloudfrontDomain == null || cloudfrontDomain.isBlank()) return null;
        return "https://" + cloudfrontDomain + "/" + key;
    }

    private String normalizeCustomLogoUrl(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isBlank()) return null;
        java.net.URI uri;
        try {
            uri = java.net.URI.create(trimmed);
        } catch (IllegalArgumentException e) {
            return null;
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null) return null;
        if (!scheme.equalsIgnoreCase("https")) return null;
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        String normalizedPath = uri.getPath() == null ? "" : uri.getPath();
        if (allowExternalCustomLogoUrl) {
            return trimmed;
        }
        if (cloudfrontDomain != null && !cloudfrontDomain.isBlank()) {
            String cfHost = cloudfrontDomain.trim().toLowerCase(Locale.ROOT);
            if (normalizedHost.equals(cfHost) && normalizedPath.startsWith("/media/")) {
                return trimmed;
            }
        }
        if (normalizedHost.equals("img.logo.dev")) {
            return trimmed;
        }
        return null;
    }

    public record PresignRequest(@NotBlank String contentType, @NotNull Long sizeBytes) {}

    public record CallbackRequest(@NotBlank String key, @NotBlank String mimeType,
                                  Integer width, Integer height, Integer durationSeconds) {}

    public record UpdateLogoRequest(String imageUrl, String imageKey, Boolean useLogoDev) {}
}

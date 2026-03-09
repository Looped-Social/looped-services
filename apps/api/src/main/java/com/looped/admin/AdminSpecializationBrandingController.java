package com.looped.admin;

import com.looped.communities.CommunitiesRepository;
import com.looped.communities.SpecializationBrandingAssetsRepository;
import com.looped.communities.SpecializationBrandingMediaService;
import com.looped.communities.SpecializationBrandingPayloads;
import com.looped.communities.SpecializationIcons;
import com.looped.media.MediaService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/v1/admin/communities")
@Validated
public class AdminSpecializationBrandingController {
    private final AdminAuthService auth;
    private final CommunitiesRepository communities;
    private final SpecializationBrandingAssetsRepository brandingAssets;
    private final SpecializationBrandingMediaService brandingMedia;
    private final MediaService mediaService;
    private final AdminAuditRepository audit;
    private final String cloudfrontDomain;
    private final String callbackSecret;

    public AdminSpecializationBrandingController(AdminAuthService auth,
                                                 CommunitiesRepository communities,
                                                 SpecializationBrandingAssetsRepository brandingAssets,
                                                 SpecializationBrandingMediaService brandingMedia,
                                                 MediaService mediaService,
                                                 AdminAuditRepository audit,
                                                 @Value("${cloudfront.domain:}") String cloudfrontDomain,
                                                 @Value("${media.callbackSecret:}") String callbackSecret) {
        this.auth = auth;
        this.communities = communities;
        this.brandingAssets = brandingAssets;
        this.brandingMedia = brandingMedia;
        this.mediaService = mediaService;
        this.audit = audit;
        this.cloudfrontDomain = cloudfrontDomain;
        this.callbackSecret = callbackSecret;
    }

    @GetMapping("/{id}/specialization-branding")
    public ResponseEntity<?> get(@AuthenticationPrincipal Jwt jwt,
                                 @PathVariable("id") long id) {
        var authRes = requireAdmin(jwt);
        if (authRes.error() != null) return authRes.error();

        var community = loadSpecialization(id);
        if (community.error() != null) return community.error();
        return ResponseEntity.ok(payload(community.row(), brandingAssets.listByCommunity(id)));
    }

    @PostMapping("/{id}/specialization-branding/presign")
    public ResponseEntity<?> presign(@AuthenticationPrincipal Jwt jwt,
                                     @PathVariable("id") long id,
                                     @Valid @RequestBody PresignRequest body) {
        var authRes = requireAdmin(jwt);
        if (authRes.error() != null) return authRes.error();

        var community = loadSpecialization(id);
        if (community.error() != null) return community.error();

        SpecializationBrandingMediaService.PresignSpec spec;
        try {
            spec = brandingMedia.validatePresign(body.slot(), body.contentType(), body.sizeBytes());
        } catch (SpecializationBrandingMediaService.InvalidAssetException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "error", e.error(),
                    "message", e.getMessage()
            ));
        }

        var res = mediaService.presignCustom(
                spec.mimeType(),
                body.sizeBytes(),
                "media/specializations/source/" + spec.slot().pathSegment(),
                Set.of(spec.mimeType()),
                Long.MAX_VALUE
        );
        if (res.status() != MediaService.Status.OK) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", res.error()));
        }

        Map<String, Object> out = new HashMap<>();
        out.put("slot", spec.slot().pathSegment());
        out.put("key", res.key());
        out.put("uploadUrl", res.uploadUrl());
        out.put("headers", res.headers());
        if (res.callbackSignature() != null) out.put("callbackSignature", res.callbackSignature());
        return ResponseEntity.ok(out);
    }

    @PostMapping("/{id}/specialization-branding/callback")
    public ResponseEntity<?> callback(@AuthenticationPrincipal Jwt jwt,
                                      @PathVariable("id") long id,
                                      @RequestHeader(value = "X-Media-Signature", required = false) String signature,
                                      @Valid @RequestBody CallbackRequest body) {
        var authRes = requireAdmin(jwt);
        if (authRes.error() != null) return authRes.error();

        if (callbackSecret != null && !callbackSecret.isBlank()) {
            String expected = MediaService.hmacSha256Base64(callbackSecret, body.key());
            if (signature == null || !signature.equals(expected)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "invalid_signature"));
            }
        }
        if (cloudfrontDomain == null || cloudfrontDomain.isBlank()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "cdn_not_configured",
                    "message", "cloudfront.domain is unset"
            ));
        }

        var community = loadSpecialization(id);
        if (community.error() != null) return community.error();

        SpecializationBrandingMediaService.ProcessedAsset asset;
        try {
            asset = brandingMedia.process(body.slot(), body.key(), body.mimeType());
        } catch (SpecializationBrandingMediaService.InvalidAssetException e) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", e.error(),
                    "message", e.getMessage()
            ));
        } catch (SpecializationBrandingMediaService.MediaUnavailableException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "error", "media_unavailable"
            ));
        }

        brandingAssets.insert(id, asset.mediaAssetId(), asset.slot().pathSegment());
        String imageUrl = cdnUrl(asset.processedKey());
        boolean updated = communities.updateSpecializationBranding(id, asset.slot().pathSegment(), imageUrl, asset.mediaAssetId());
        if (!updated) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "invalid_specialization"));
        }

        var updatedCommunity = communities.findById(id);
        if (updatedCommunity.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
        }
        List<SpecializationBrandingAssetsRepository.BrandingAssetRow> uploads = brandingAssets.listByCommunity(id);
        Long uploadId = findUploadId(asset.slot().pathSegment(), asset.mediaAssetId(), uploads);

        audit.log(authRes.admin().id, "specialization.branding.upload", "community", id,
                "slot=" + asset.slot().pathSegment() + ",media_asset_id=" + asset.mediaAssetId());

        Map<String, Object> out = payload(updatedCommunity.get(), uploads);
        out.put("status", "created");
        out.put("slot", asset.slot().pathSegment());
        out.put("media_asset_id", asset.mediaAssetId());
        if (uploadId != null) out.put("upload_id", uploadId);
        if (uploadId != null) out.put("selected_upload_id", uploadId);
        out.put("processed_key", asset.processedKey());
        return ResponseEntity.status(HttpStatus.CREATED).body(out);
    }

    @PatchMapping("/{id}/specialization-branding")
    public ResponseEntity<?> select(@AuthenticationPrincipal Jwt jwt,
                                    @PathVariable("id") long id,
                                    @Valid @RequestBody UpdateSelectionRequest body) {
        var authRes = requireAdmin(jwt);
        if (authRes.error() != null) return authRes.error();

        var community = loadSpecialization(id);
        if (community.error() != null) return community.error();

        String slot = normalizeSlot(body.slot());
        if (slot == null) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", "invalid_slot",
                    "message", "slot must be icon or banner"
            ));
        }
        boolean hasUploadId = body.uploadId() != null;
        boolean hasMediaAssetId = body.mediaAssetId() != null;
        if (hasUploadId == hasMediaAssetId) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", "invalid_selection",
                    "message", "Provide exactly one of uploadId or mediaAssetId"
            ));
        }

        SpecializationBrandingAssetsRepository.BrandingAssetRow upload;
        if (hasUploadId) {
            upload = brandingAssets.findByIdAndCommunity(body.uploadId(), id).orElse(null);
            if (upload == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "upload_not_found"));
            }
            if (!slot.equals(upload.slot)) {
                return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                        "error", "invalid_selection",
                        "message", "Upload does not belong to the requested slot"
                ));
            }
        } else {
            upload = brandingAssets.findByCommunitySlotAndMediaAssetId(id, slot, body.mediaAssetId()).orElse(null);
            if (upload == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "upload_not_found"));
            }
        }

        boolean updated = communities.updateSpecializationBranding(id, slot, cdnUrl(upload.s3Key), upload.mediaAssetId);
        if (!updated) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "invalid_specialization"));
        }

        var updatedCommunity = communities.findById(id);
        if (updatedCommunity.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
        }
        List<SpecializationBrandingAssetsRepository.BrandingAssetRow> uploads = brandingAssets.listByCommunity(id);

        audit.log(authRes.admin().id, "specialization.branding.select", "community", id,
                "slot=" + slot + ",upload_id=" + upload.id + ",media_asset_id=" + upload.mediaAssetId);

        Map<String, Object> out = payload(updatedCommunity.get(), uploads);
        out.put("status", "updated");
        out.put("slot", slot);
        out.put("upload_id", upload.id);
        out.put("selected_upload_id", upload.id);
        out.put("media_asset_id", upload.mediaAssetId);
        return ResponseEntity.ok(out);
    }

    @DeleteMapping("/{id}/specialization-branding/{slot}")
    public ResponseEntity<?> clearSlot(@AuthenticationPrincipal Jwt jwt,
                                       @PathVariable("id") long id,
                                       @PathVariable("slot") String slot) {
        var authRes = requireAdmin(jwt);
        if (authRes.error() != null) return authRes.error();

        String normalizedSlot = normalizeSlot(slot);
        if (normalizedSlot == null) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", "invalid_slot",
                    "message", "slot must be icon or banner"
            ));
        }

        var community = loadSpecialization(id);
        if (community.error() != null) return community.error();

        boolean hadAsset = "icon".equals(normalizedSlot)
                ? community.row().specializationIconMediaAssetId != null || community.row().specializationIconImageUrl != null
                : community.row().specializationBannerMediaAssetId != null || community.row().specializationBannerImageUrl != null;
        communities.updateSpecializationBranding(id, normalizedSlot, null, null);

        var updatedCommunity = communities.findById(id);
        if (updatedCommunity.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
        }
        List<SpecializationBrandingAssetsRepository.BrandingAssetRow> uploads = brandingAssets.listByCommunity(id);

        audit.log(authRes.admin().id, "specialization.branding.clear", "community", id,
                "slot=" + normalizedSlot);

        Map<String, Object> out = payload(updatedCommunity.get(), uploads);
        out.put("status", "deleted");
        out.put("slot", normalizedSlot);
        out.put("cleared", hadAsset);
        return ResponseEntity.ok(out);
    }

    @DeleteMapping("/{id}/specialization-branding/uploads/{uploadId}")
    public ResponseEntity<?> deleteUpload(@AuthenticationPrincipal Jwt jwt,
                                          @PathVariable("id") long id,
                                          @PathVariable("uploadId") long uploadId) {
        var authRes = requireAdmin(jwt);
        if (authRes.error() != null) return authRes.error();

        var community = loadSpecialization(id);
        if (community.error() != null) return community.error();

        var upload = brandingAssets.findByIdAndCommunity(uploadId, id).orElse(null);
        if (upload == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "upload_not_found"));
        }

        boolean clearedSelectedSlot = isCurrentlySelected(community.row(), upload);
        if (clearedSelectedSlot) {
            communities.updateSpecializationBranding(id, upload.slot, null, null);
        }
        boolean deleted = brandingAssets.deleteByIdAndCommunity(uploadId, id);
        if (!deleted) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "upload_not_found"));
        }

        var updatedCommunity = communities.findById(id);
        if (updatedCommunity.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
        }
        List<SpecializationBrandingAssetsRepository.BrandingAssetRow> uploads = brandingAssets.listByCommunity(id);

        audit.log(authRes.admin().id, "specialization.branding.delete_upload", "community", id,
                "slot=" + upload.slot + ",upload_id=" + uploadId + ",media_asset_id=" + upload.mediaAssetId);

        Map<String, Object> out = payload(updatedCommunity.get(), uploads);
        out.put("status", "deleted");
        out.put("upload_id", uploadId);
        out.put("slot", upload.slot);
        out.put("cleared_selected_slot", clearedSelectedSlot);
        return ResponseEntity.ok(out);
    }

    private AuthResult requireAdmin(Jwt jwt) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.CREATE_COMMUNITY);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return new AuthResult(null, ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden")));
        }
        return new AuthResult(authRes, null);
    }

    private LoadedSpecialization loadSpecialization(long id) {
        var communityOpt = communities.findById(id);
        if (communityOpt.isEmpty()) {
            return new LoadedSpecialization(null, ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found")));
        }
        var community = communityOpt.get();
        String kind = community.kind == null ? "" : community.kind.trim().toLowerCase(java.util.Locale.ROOT);
        String specializationType = community.specializationType == null ? "" : community.specializationType.trim().toLowerCase(java.util.Locale.ROOT);
        if (!"specialization".equals(kind) || (!"field".equals(specializationType) && !"major".equals(specializationType))) {
            return new LoadedSpecialization(null, ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", "invalid_specialization",
                    "message", "id must be a field or major specialization"
            )));
        }
        return new LoadedSpecialization(community, null);
    }

    private Map<String, Object> payload(CommunitiesRepository.CommunityRow row,
                                        List<SpecializationBrandingAssetsRepository.BrandingAssetRow> uploads) {
        Map<String, Object> out = new HashMap<>();
        out.put("community_id", row.id);
        out.put("kind", row.kind);
        out.put("specialization_type", row.specializationType);
        out.put("name", row.name);
        Map<String, Object> icon = SpecializationIcons.payloadOrNull(row.iconKind, row.iconValue);
        if (icon != null) out.put("icon", icon);
        SpecializationBrandingPayloads.putPayload(out, row.specializationIconImageUrl, row.specializationBannerImageUrl);
        if (row.specializationIconMediaAssetId != null) out.put("icon_media_asset_id", row.specializationIconMediaAssetId);
        if (row.specializationBannerMediaAssetId != null) out.put("banner_media_asset_id", row.specializationBannerMediaAssetId);

        Long selectedIconUploadId = findUploadId("icon", row.specializationIconMediaAssetId, uploads);
        Long selectedBannerUploadId = findUploadId("banner", row.specializationBannerMediaAssetId, uploads);
        if (selectedIconUploadId != null) out.put("selected_icon_upload_id", selectedIconUploadId);
        if (selectedBannerUploadId != null) out.put("selected_banner_upload_id", selectedBannerUploadId);

        List<Map<String, Object>> uploadItems = uploads.stream().map(upload -> {
            Map<String, Object> item = new HashMap<>();
            item.put("id", upload.id);
            item.put("upload_id", upload.id);
            item.put("media_asset_id", upload.mediaAssetId);
            item.put("slot", upload.slot);
            item.put("key", upload.s3Key);
            item.put("mime_type", upload.mimeType);
            if (upload.width != null) item.put("width", upload.width);
            if (upload.height != null) item.put("height", upload.height);
            if (upload.createdAt != null) item.put("created_at", upload.createdAt);
            String cdnUrl = cdnUrl(upload.s3Key);
            if (cdnUrl != null) item.put("cdn_url", cdnUrl);
            item.put("selected_for_icon", row.specializationIconMediaAssetId != null && row.specializationIconMediaAssetId.equals(upload.mediaAssetId));
            item.put("selected_for_banner", row.specializationBannerMediaAssetId != null && row.specializationBannerMediaAssetId.equals(upload.mediaAssetId));
            return item;
        }).toList();
        out.put("uploads", uploadItems);
        return out;
    }

    private boolean isCurrentlySelected(CommunitiesRepository.CommunityRow row,
                                        SpecializationBrandingAssetsRepository.BrandingAssetRow upload) {
        if ("icon".equals(upload.slot)) {
            return row.specializationIconMediaAssetId != null && row.specializationIconMediaAssetId.equals(upload.mediaAssetId);
        }
        return row.specializationBannerMediaAssetId != null && row.specializationBannerMediaAssetId.equals(upload.mediaAssetId);
    }

    private Long findUploadId(String slot,
                              Long mediaAssetId,
                              List<SpecializationBrandingAssetsRepository.BrandingAssetRow> uploads) {
        if (slot == null || mediaAssetId == null || uploads == null) return null;
        for (var upload : uploads) {
            if (slot.equals(upload.slot) && mediaAssetId.equals(upload.mediaAssetId)) {
                return upload.id;
            }
        }
        return null;
    }

    private String normalizeSlot(String raw) {
        if (raw == null) return null;
        String normalized = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if ("icon".equals(normalized) || "banner".equals(normalized)) return normalized;
        return null;
    }

    private String cdnUrl(String key) {
        if (cloudfrontDomain == null || cloudfrontDomain.isBlank() || key == null || key.isBlank()) return null;
        return "https://" + cloudfrontDomain + "/" + key;
    }

    private record AuthResult(AdminAuthService.AuthResult auth, ResponseEntity<?> error) {}

    private record LoadedSpecialization(CommunitiesRepository.CommunityRow row, ResponseEntity<?> error) {}

    public record PresignRequest(
            @NotBlank String slot,
            @NotBlank String contentType,
            @Positive long sizeBytes
    ) {}

    public record CallbackRequest(
            @NotBlank String slot,
            @NotBlank String key,
            @NotBlank String mimeType
    ) {}

    public record UpdateSelectionRequest(
            @NotBlank String slot,
            Long uploadId,
            Long mediaAssetId
    ) {}
}

package com.looped.admin;

import com.looped.communities.CommunitiesRepository;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/v1/admin/communities")
@Validated
public class AdminSpecializationBrandingController {
    private final AdminAuthService auth;
    private final CommunitiesRepository communities;
    private final SpecializationBrandingMediaService brandingMedia;
    private final MediaService mediaService;
    private final AdminAuditRepository audit;
    private final String cloudfrontDomain;
    private final String callbackSecret;

    public AdminSpecializationBrandingController(AdminAuthService auth,
                                                 CommunitiesRepository communities,
                                                 SpecializationBrandingMediaService brandingMedia,
                                                 MediaService mediaService,
                                                 AdminAuditRepository audit,
                                                 @Value("${cloudfront.domain:}") String cloudfrontDomain,
                                                 @Value("${media.callbackSecret:}") String callbackSecret) {
        this.auth = auth;
        this.communities = communities;
        this.brandingMedia = brandingMedia;
        this.mediaService = mediaService;
        this.audit = audit;
        this.cloudfrontDomain = cloudfrontDomain;
        this.callbackSecret = callbackSecret;
    }

    @GetMapping("/{id}/specialization-branding")
    public ResponseEntity<?> get(@AuthenticationPrincipal Jwt jwt,
                                 @PathVariable("id") long id) {
        var authorized = requireAdmin(jwt);
        if (authorized != null) return authorized;

        var community = loadSpecialization(id);
        if (community.error() != null) return community.error();
        return ResponseEntity.ok(payload(community.row()));
    }

    @PostMapping("/{id}/specialization-branding/presign")
    public ResponseEntity<?> presign(@AuthenticationPrincipal Jwt jwt,
                                     @PathVariable("id") long id,
                                     @Valid @RequestBody PresignRequest body) {
        var authorized = requireAdmin(jwt);
        if (authorized != null) return authorized;

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
        var authorized = requireAdmin(jwt);
        if (authorized != null) return authorized;

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

        String imageUrl = cdnUrl(asset.processedKey());
        boolean updated = communities.updateSpecializationBranding(id, asset.slot().pathSegment(), imageUrl, asset.mediaAssetId());
        if (!updated) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "invalid_specialization"));
        }

        var updatedCommunity = communities.findById(id);
        if (updatedCommunity.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
        }
        audit.log(adminId(jwt), "specialization.branding.update", "community", id,
                "slot=" + asset.slot().pathSegment() + ",media_asset_id=" + asset.mediaAssetId());

        Map<String, Object> out = payload(updatedCommunity.get());
        out.put("status", "updated");
        out.put("slot", asset.slot().pathSegment());
        out.put("media_asset_id", asset.mediaAssetId());
        out.put("processed_key", asset.processedKey());
        return ResponseEntity.status(HttpStatus.CREATED).body(out);
    }

    @DeleteMapping("/{id}/specialization-branding/{slot}")
    public ResponseEntity<?> delete(@AuthenticationPrincipal Jwt jwt,
                                    @PathVariable("id") long id,
                                    @PathVariable("slot") String slot) {
        var authorized = requireAdmin(jwt);
        if (authorized != null) return authorized;

        SpecializationBrandingMediaService.AssetSlot assetSlot = SpecializationBrandingMediaService.AssetSlot.from(slot);
        if (assetSlot == null) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", "invalid_slot",
                    "message", "slot must be icon or banner"
            ));
        }

        var community = loadSpecialization(id);
        if (community.error() != null) return community.error();

        boolean hadAsset = switch (assetSlot) {
            case ICON -> community.row().specializationIconMediaAssetId != null
                    || community.row().specializationIconImageUrl != null;
            case BANNER -> community.row().specializationBannerMediaAssetId != null
                    || community.row().specializationBannerImageUrl != null;
        };
        communities.updateSpecializationBranding(id, assetSlot.pathSegment(), null, null);

        var updatedCommunity = communities.findById(id);
        if (updatedCommunity.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
        }
        audit.log(adminId(jwt), "specialization.branding.delete", "community", id,
                "slot=" + assetSlot.pathSegment());

        Map<String, Object> out = payload(updatedCommunity.get());
        out.put("status", "deleted");
        out.put("slot", assetSlot.pathSegment());
        out.put("cleared", hadAsset);
        return ResponseEntity.ok(out);
    }

    private ResponseEntity<?> requireAdmin(Jwt jwt) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.CREATE_COMMUNITY);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        return null;
    }

    private long adminId(Jwt jwt) {
        String email = jwt.getClaimAsString("email");
        return auth.requirePermission(jwt.getSubject(), email, AdminPermissions.CREATE_COMMUNITY).admin().id;
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

    private Map<String, Object> payload(CommunitiesRepository.CommunityRow row) {
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
        return out;
    }

    private String cdnUrl(String key) {
        return "https://" + cloudfrontDomain + "/" + key;
    }

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
}

package com.looped.posts;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.looped.polls.PollPayloads;
import com.looped.polls.PollRequests;
import com.looped.polls.PollsService;
import com.looped.settings.AppConfigService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/posts")
public class PostsController {
    private final PostsService postsService;
    private final PostSearchService postSearchService;
    private final PollsService pollsService;
    private final AppConfigService appConfig;
    private final PostViewerCapabilitiesService viewerCapabilities;

    public PostsController(PostsService postsService,
                           PostSearchService postSearchService,
                           PollsService pollsService,
                           AppConfigService appConfig,
                           PostViewerCapabilitiesService viewerCapabilities) {
        this.postsService = postsService;
        this.postSearchService = postSearchService;
        this.pollsService = pollsService;
        this.appConfig = appConfig;
        this.viewerCapabilities = viewerCapabilities;
    }

    @GetMapping("/search")
    public ResponseEntity<?> search(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam("query") String query,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false, defaultValue = "20") int limit
    ) {
        if (query == null || query.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", "query_required",
                    "message", "query must be provided"
            ));
        }
        int lim = Math.max(1, Math.min(limit, 100));
        var res = postSearchService.search(jwt.getSubject(), query, cursor, lim);
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned"
            ));
            case OK -> {
                Long viewerPrincipalId = pollsService.viewerPrincipalId(jwt.getSubject());
                String defaultProfileImageUrl = appConfig.defaultProfileImageUrl();
                List<Long> postIds = res.posts().stream().map(p -> p.id).toList();
                var pollsByPostId = pollsService.viewsByPostId(viewerPrincipalId, postIds);
                var capabilitiesByPostId = viewerCapabilities.byPostId(jwt.getSubject(), res.posts(), pollsByPostId);
                List<Map<String, Object>> items = res.posts().stream().map(row -> {
                    Map<String, Object> payload = PostPayloads.search(row, defaultProfileImageUrl);
                    var poll = pollsByPostId.get(row.id);
                    if (poll != null) payload.put("poll", PollPayloads.from(poll));
                    PostPayloads.putViewerCapabilities(payload, capabilitiesByPostId.get(row.id));
                    return payload;
                }).toList();
                Map<String, Object> body = new HashMap<>();
                body.put("items", items);
                if (res.nextCursor() != null) {
                    body.put("next_cursor", res.nextCursor());
                    body.put("nextCursor", res.nextCursor());
                }
                yield ResponseEntity.ok(body);
            }
            default -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        };
    }

    @PostMapping
    public ResponseEntity<?> create(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Validated @RequestBody CreateRequest body
    ) {
        Long communityId = body.communityId() != null ? body.communityId() : body.loopId();
        boolean isAnon = body.isAnon() != null && body.isAnon();
        if (isAnon && body.content() == null) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", "content_required",
                    "message", "content is required (use empty string if no caption)"
            ));
        }
        boolean hasText = body.content() != null && !body.content().isBlank();
        boolean hasMedia = (body.mediaAssetId() != null && body.mediaAssetId() > 0)
                || (body.mediaAssetIds() != null && !body.mediaAssetIds().isEmpty());
        boolean hasPoll = body.poll() != null;
        if (!hasText && !hasMedia && !hasPoll) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", "content_required",
                    "message", "Provide content, media, or poll"
            ));
        }
        String content = body.content() == null ? "" : body.content();
        if (isAnon && jwt != null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "error", "anon_jwt_not_allowed",
                    "message", "Do not send Authorization for anonymous actions"
            ));
        }
        if (!isAnon && jwt == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "error", "unauthorized",
                    "message", "Authorization is required"
            ));
        }
        if (!isAnon && (idempotencyKey == null || idempotencyKey.isBlank())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "error", "idempotency_required",
                    "message", "Idempotency-Key is required"
            ));
        }
        var res = postsService.create(
                jwt == null ? null : jwt.getSubject(),
                idempotencyKey,
                content,
                body.mediaAssetId(),
                body.mediaAssetIds(),
                communityId,
                isAnon,
                body.poll(),
                body.anonProfileId(),
                body.anonCert(),
                body.anonCertKid(),
                body.anonSig(),
                body.anonTimestamp()
        );
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned",
                    "message", "Complete onboarding before creating posts"
            ));
            case COMMUNITY_REQUIRED -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", "community_required",
                    "message", "communityId is required"
            ));
            case COMMUNITY_NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "community_not_found",
                    "message", "Community not found"
            ));
            case COMMUNITY_BANNED -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    lockDeniedBody("community_banned", "You are banned from this community", res.lock())
            );
            case NOT_VERIFIED -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    lockDeniedBody("community_not_verified", "You must be verified to post to this community", res.lock())
            );
            case VERIFICATION_EXPIRED -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    lockDeniedBody("verification_expired", "Your verification for this community has expired", res.lock())
            );
            case SPECIALIZATION_NOT_JOINED -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    lockDeniedBody("specialization_not_joined", "You must join this specialization to post", res.lock())
            );
            case SPECIALIZATION_VERIFICATION_REQUIRED -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    lockDeniedBody("specialization_verification_required", "Verify the parent community before joining this specialization", res.lock())
            );
            case IDEMPOTENCY_IN_FLIGHT -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "idempotency_in_flight",
                    "message", "A request with this Idempotency-Key is in flight"
            ));
            case IDEMPOTENCY_REQUIRED -> ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "error", "idempotency_required",
                    "message", "Idempotency-Key is required"
            ));
            case INVALID_POLL -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", "invalid_poll",
                    "message", "Invalid poll payload"
            ));
            case CONTENT_REQUIRED -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", "content_required",
                    "message", "Provide content, media, or poll"
            ));
            case INVALID_ANON_PROOF -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "invalid_anon_proof",
                    "message", "Invalid anonymous proof"
            ));
            case CONTENT_UNDER_REVIEW -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "content_under_review",
                    "message", "This content is under review"
            ));
            case MEDIA_TOO_MANY -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", "media_too_many",
                    "message", "Attach up to 4 photos or 1 video"
            ));
            case MEDIA_NOT_FOUND -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", "media_not_found",
                    "message", "One or more media assets do not exist"
            ));
            case MEDIA_INVALID -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", "media_invalid",
                    "message", "Attach up to 4 photos or 1 video"
            ));
            case MEDIA_NOT_OWNED -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "media_forbidden",
                    "message", "You may only attach media you uploaded"
            ));
            case ANON_MEDIA_NOT_ALLOWED -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", "anon_media_invalid",
                    "message", "Anonymous media assets must not be user-owned"
            ));
            case OK -> {
                String defaultProfileImageUrl = appConfig.defaultProfileImageUrl();
                var payload = PostPayloads.from(res.post(), defaultProfileImageUrl);
                Long viewerPrincipalId = jwt == null ? null : pollsService.viewerPrincipalId(jwt.getSubject());
                var pollsByPostId = pollsService.viewsByPostId(viewerPrincipalId, List.of(res.post().id));
                var poll = pollsByPostId.get(res.post().id);
                if (poll != null) payload.put("poll", PollPayloads.from(poll));
                var capabilitiesByPostId = viewerCapabilities.byPostId(jwt == null ? null : jwt.getSubject(), List.of(res.post()), pollsByPostId);
                PostPayloads.putViewerCapabilities(payload, capabilitiesByPostId.get(res.post().id));
                yield new ResponseEntity<>(payload, res.created() ? HttpStatus.CREATED : HttpStatus.OK);
            }
            default -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "error", "unexpected_status",
                    "message", "Unexpected status for create"
            ));
        };
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") long id) {
        var res = postsService.getScoped(jwt.getSubject(), id);
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "user_not_provisioned"));
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            case FORBIDDEN -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
            case COMMUNITY_BANNED -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "community_banned"));
            case OK -> {
                String defaultProfileImageUrl = appConfig.defaultProfileImageUrl();
                var payload = PostPayloads.from(res.post(), defaultProfileImageUrl);
                Long viewerPrincipalId = pollsService.viewerPrincipalId(jwt.getSubject());
                var pollsByPostId = pollsService.viewsByPostId(viewerPrincipalId, List.of(res.post().id));
                var poll = pollsByPostId.get(res.post().id);
                if (poll != null) payload.put("poll", PollPayloads.from(poll));
                var capabilitiesByPostId = viewerCapabilities.byPostId(jwt.getSubject(), List.of(res.post()), pollsByPostId);
                PostPayloads.putViewerCapabilities(payload, capabilitiesByPostId.get(res.post().id));
                yield ResponseEntity.ok(payload);
            }
            default -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "error", "unexpected_status",
                    "message", "Unexpected status for get"
            ));
        };
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> edit(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") long id,
            @Validated @RequestBody EditRequest body
    ) {
        boolean isAnon = body.asAnon() != null && body.asAnon();
        if (isAnon && !body.hasAnonProof()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "invalid_anon_proof",
                    "message", "Invalid anonymous proof"
            ));
        }
        if (isAnon && jwt != null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "error", "anon_jwt_not_allowed",
                    "message", "Do not send Authorization for anonymous actions"
            ));
        }
        if (!isAnon && jwt == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "error", "unauthorized",
                    "message", "Authorization is required"
            ));
        }
        var res = postsService.edit(
                jwt == null ? null : jwt.getSubject(),
                id,
                body.content(),
                body.removeMediaRequested(),
                body.toAnonProof()
        );
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned",
                    "message", "Complete onboarding before editing posts"
            ));
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "not_found"
            ));
            case FORBIDDEN -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "forbidden",
                    "message", "Only the post author may edit"
            ));
            case INVALID_ANON_PROOF -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "invalid_anon_proof",
                    "message", "Invalid anonymous proof"
            ));
            case CONTENT_UNDER_REVIEW -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "content_under_review",
                    "message", "This content is under review"
            ));
            case POST_REMOVED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "post_removed",
                    "message", "Post has been removed"
            ));
            case OK -> {
                var payload = PostPayloads.from(res.post(), appConfig.defaultProfileImageUrl());
                var capabilitiesByPostId = viewerCapabilities.byPostId(jwt == null ? null : jwt.getSubject(), List.of(res.post()), Map.of());
                PostPayloads.putViewerCapabilities(payload, capabilitiesByPostId.get(res.post().id));
                yield ResponseEntity.ok(payload);
            }
        };
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") long id,
            @RequestBody(required = false) DeleteRequest body
    ) {
        boolean isAnon = body != null && Boolean.TRUE.equals(body.asAnon());
        if (isAnon && (body == null || !body.hasAnonProof())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "invalid_anon_proof",
                    "message", "Invalid anonymous proof"
            ));
        }
        if (isAnon && jwt != null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "error", "anon_jwt_not_allowed",
                    "message", "Do not send Authorization for anonymous actions"
            ));
        }
        if (!isAnon && jwt == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "error", "unauthorized",
                    "message", "Authorization is required"
            ));
        }
        var res = postsService.delete(jwt == null ? null : jwt.getSubject(), id, body == null ? null : body.toAnonProof());
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned",
                    "message", "Complete onboarding before deleting posts"
            ));
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "not_found"
            ));
            case FORBIDDEN -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "forbidden",
                    "message", "Only the post author may delete"
            ));
            case INVALID_ANON_PROOF -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "invalid_anon_proof",
                    "message", "Invalid anonymous proof"
            ));
            case OK -> ResponseEntity.ok(Map.of(
                    "id", id,
                    "deleted", res.deleted()
            ));
        };
    }

    public record CreateRequest(@Size(max = 500) String content,
                               @JsonAlias("media_asset_id") Long mediaAssetId,
                               @Size(max = 4) @JsonAlias("media_asset_ids") List<Long> mediaAssetIds,
                               @JsonAlias("community_id") Long communityId,
                               @JsonAlias("loop_id") Long loopId,
                               @JsonAlias("is_anon") Boolean isAnon,
                               @jakarta.validation.Valid PollRequests.PostPollCreate poll,
                               Long anonProfileId,
                               String anonCert,
                               String anonCertKid,
                               String anonSig,
                               Long anonTimestamp) {}

    public record EditRequest(@NotBlank @Size(max = 500) String content,
                              @JsonAlias("remove_media") Boolean removeMedia,
                              Boolean asAnon,
                              Long anonProfileId,
                              String anonCert,
                              String anonCertKid,
                              String anonSig) {
        boolean removeMediaRequested() {
            return Boolean.TRUE.equals(removeMedia);
        }

        com.looped.anon.AnonProofService.AnonActionProof toAnonProof() {
            if (asAnon == null || !asAnon) return null;
            return new com.looped.anon.AnonProofService.AnonActionProof(anonProfileId, anonCert, anonCertKid, anonSig);
        }

        boolean hasAnonProof() {
            if (asAnon == null || !asAnon) return false;
            return anonProfileId != null
                    && anonCert != null && !anonCert.isBlank()
                    && anonCertKid != null && !anonCertKid.isBlank()
                    && anonSig != null && !anonSig.isBlank();
        }
    }

    public record DeleteRequest(Boolean asAnon, Long anonProfileId, String anonCert, String anonCertKid, String anonSig) {
        com.looped.anon.AnonProofService.AnonActionProof toAnonProof() {
            if (asAnon == null || !asAnon) return null;
            return new com.looped.anon.AnonProofService.AnonActionProof(anonProfileId, anonCert, anonCertKid, anonSig);
        }

        boolean hasAnonProof() {
            if (asAnon == null || !asAnon) return false;
            return anonProfileId != null
                    && anonCert != null && !anonCert.isBlank()
                    && anonCertKid != null && !anonCertKid.isBlank()
                    && anonSig != null && !anonSig.isBlank();
        }
    }

    private Map<String, Object> lockDeniedBody(String errorCode,
                                               String message,
                                               CommunityInteractionLockService.LockEvaluation lock) {
        Map<String, Object> body = new HashMap<>();
        body.put("error", errorCode);
        body.put("error_code", errorCode);
        body.put("message", message);
        if (lock != null) {
            body.put("lockContext", lock.lockContext());
            body.put("primaryUnlockAction", lock.primaryUnlockAction());
        }
        return body;
    }
}

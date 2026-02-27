package com.looped.posts;

import com.looped.settings.AppSettingsKeys;
import com.looped.settings.AppSettingsRepository;
import com.looped.users.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
public class PostShareNudgeService {
    private static final Logger log = LoggerFactory.getLogger(PostShareNudgeService.class);

    private final UserRepository users;
    private final PostRepository posts;
    private final AppSettingsRepository settings;
    private final PostShareNudgeRepository repo;
    private final PostShareNudgeProperties props;

    public PostShareNudgeService(UserRepository users,
                                 PostRepository posts,
                                 AppSettingsRepository settings,
                                 PostShareNudgeRepository repo,
                                 PostShareNudgeProperties props) {
        this.users = users;
        this.posts = posts;
        this.settings = settings;
        this.repo = repo;
        this.props = props;
    }

    @Transactional
    public Map<Long, Map<String, Object>> evaluateAndMaybeServe(String firebaseUid, List<? extends PostRepository.PostRow> postRows) {
        if (firebaseUid == null || firebaseUid.isBlank() || postRows == null || postRows.isEmpty()) return Map.of();
        if (!isEnabled()) return Map.of();

        var user = users.findByFirebaseUid(firebaseUid);
        if (user.isEmpty()) return Map.of();

        long viewerUserId = user.get().id;
        String variant = resolveVariant();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        int maxPerDay = Math.max(0, props.getMaxServedPerDay());
        int minBetweenMinutes = Math.max(0, props.getMinMinutesBetweenServes());

        OffsetDateTime dayStart = now.toLocalDate().atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime dayEnd = dayStart.plusDays(1);
        var stats = repo.loadServeStats(viewerUserId, dayStart, dayEnd);

        int servedToday = Math.max(0, stats.servedToday());
        OffsetDateTime lastServedAt = stats.lastServedAt();

        Map<Long, Map<String, Object>> out = new HashMap<>();
        for (PostRepository.PostRow row : postRows) {
            if (row == null || row.authorId == null || row.authorId != viewerUserId) continue;
            if (row.createdAt == null) continue;
            if (!isPublic(row.visibility)) continue;

            int engagement = Math.max(0, row.likesCount) + Math.max(0, row.commentsCount);
            if (engagement > Math.max(0, props.getMaxCombinedEngagement())) continue;

            OffsetDateTime eligibleAt = row.createdAt.plusMinutes(Math.max(0, props.getDelayMinutes()));
            if (eligibleAt.isAfter(now)) continue;

            repo.upsertState(row.id, viewerUserId, eligibleAt, variant);
            var stateOpt = repo.find(row.id, viewerUserId);
            if (stateOpt.isEmpty()) continue;
            var state = stateOpt.get();

            if (state.dismissedAt() != null || state.shareTappedAt() != null || state.firstServedAt() != null) {
                continue;
            }
            if (maxPerDay > 0 && servedToday >= maxPerDay) {
                continue;
            }
            if (lastServedAt != null && minBetweenMinutes > 0) {
                long minutes = Duration.between(lastServedAt.toInstant(), now.toInstant()).toMinutes();
                if (minutes < minBetweenMinutes) continue;
            }

            boolean served = repo.markServedIfEligible(row.id, viewerUserId, now, variant);
            if (!served) continue;

            servedToday += 1;
            lastServedAt = now;
            Map<String, Object> payload = nudgePayload(row.id, variant);
            out.put(row.id, payload);
            log.info("share_nudge_served post_id={} user_id={} variant={}", row.id, viewerUserId, variant);
        }

        return out;
    }

    @Transactional
    public MutationResult serve(String firebaseUid, long postId) {
        var actor = resolveActorPost(firebaseUid, postId);
        if (actor.status != MutationStatus.OK) return MutationResult.of(actor.status, false, null, null);

        if (!isEnabled()) {
            return MutationResult.of(MutationStatus.OK, false, actor.state, null);
        }

        Map<Long, Map<String, Object>> byPost = evaluateAndMaybeServe(firebaseUid, List.of(actor.post));
        Map<String, Object> nudge = byPost.get(postId);
        return MutationResult.of(MutationStatus.OK, nudge != null, repo.find(postId, actor.user.id).orElse(actor.state), nudge);
    }

    @Transactional
    public MutationResult dismiss(String firebaseUid, long postId) {
        var actor = resolveActorPost(firebaseUid, postId);
        if (actor.status != MutationStatus.OK) return MutationResult.of(actor.status, false, null, null);

        OffsetDateTime eligibleAt = eligibleAt(actor.post);
        String variant = resolveVariant();
        repo.upsertState(postId, actor.user.id, eligibleAt, variant);
        var state = repo.markDismissed(postId, actor.user.id, OffsetDateTime.now(ZoneOffset.UTC));
        state.ifPresent(s -> log.info("share_nudge_dismissed post_id={} user_id={} variant={}", postId, actor.user.id, safeVariant(s.variant(), variant)));
        return MutationResult.of(MutationStatus.OK, false, state.orElse(null), null);
    }

    @Transactional
    public MutationResult shareTap(String firebaseUid, long postId) {
        var actor = resolveActorPost(firebaseUid, postId);
        if (actor.status != MutationStatus.OK) return MutationResult.of(actor.status, false, null, null);

        OffsetDateTime eligibleAt = eligibleAt(actor.post);
        String variant = resolveVariant();
        repo.upsertState(postId, actor.user.id, eligibleAt, variant);
        var state = repo.markShareTapped(postId, actor.user.id, OffsetDateTime.now(ZoneOffset.UTC));
        state.ifPresent(s -> log.info("share_nudge_share_tapped post_id={} user_id={} variant={}", postId, actor.user.id, safeVariant(s.variant(), variant)));
        return MutationResult.of(MutationStatus.OK, false, state.orElse(null), sharePayload(postId));
    }

    public Map<String, Object> sharePayload(long postId) {
        String fmt = props.getShareDeeplinkFormat();
        if (fmt == null || fmt.isBlank()) {
            fmt = "looped://posts/%d";
        }
        String deepLink;
        try {
            deepLink = String.format(Locale.ROOT, fmt, postId);
        } catch (RuntimeException ignored) {
            deepLink = "looped://posts/" + postId;
        }
        Map<String, Object> out = new HashMap<>();
        out.put("deep_link", deepLink);
        out.put("deepLink", deepLink);
        out.put("requires_auth", true);
        out.put("requiresAuth", true);
        out.put("text_key", "share.post.generic");
        out.put("textKey", "share.post.generic");
        return out;
    }

    private ResolveActorResult resolveActorPost(String firebaseUid, long postId) {
        if (firebaseUid == null || firebaseUid.isBlank()) {
            return new ResolveActorResult(MutationStatus.USER_NOT_PROVISIONED, null, null, null);
        }
        var userOpt = users.findByFirebaseUid(firebaseUid);
        if (userOpt.isEmpty()) {
            return new ResolveActorResult(MutationStatus.USER_NOT_PROVISIONED, null, null, null);
        }
        var postOpt = posts.findById(postId);
        if (postOpt.isEmpty()) {
            return new ResolveActorResult(MutationStatus.NOT_FOUND, userOpt.get(), null, null);
        }
        var post = postOpt.get();
        if (!isPublic(post.visibility)) {
            return new ResolveActorResult(MutationStatus.NOT_FOUND, userOpt.get(), post, null);
        }
        if (post.authorId == null || post.authorId != userOpt.get().id) {
            return new ResolveActorResult(MutationStatus.FORBIDDEN, userOpt.get(), post, null);
        }
        OffsetDateTime eligibleAt = eligibleAt(post);
        String variant = resolveVariant();
        repo.upsertState(postId, userOpt.get().id, eligibleAt, variant);
        Optional<PostShareNudgeRepository.StateRow> state = repo.find(postId, userOpt.get().id);
        return new ResolveActorResult(MutationStatus.OK, userOpt.get(), post, state.orElse(null));
    }

    private boolean isEnabled() {
        return settings.findLong(AppSettingsKeys.SHARE_NUDGE_ENABLED)
                .map(v -> v != null && v > 0)
                .orElse(props.isEnabledByDefault());
    }

    private String resolveVariant() {
        return settings.findString(AppSettingsKeys.SHARE_NUDGE_VARIANT)
                .map(String::trim)
                .filter(v -> !v.isBlank())
                .orElseGet(() -> {
                    String fallback = props.getDefaultVariant();
                    return (fallback == null || fallback.isBlank()) ? "v1" : fallback.trim();
                });
    }

    private Map<String, Object> nudgePayload(long postId, String variant) {
        Map<String, Object> payload = new HashMap<>();
        String id = "share_nudge:" + postId;
        payload.put("id", id);
        payload.put("variant", variant);
        payload.put("messageKey", props.getMessageKey());
        payload.put("message_key", props.getMessageKey());
        payload.put("ctaKey", props.getCtaKey());
        payload.put("cta_key", props.getCtaKey());
        return payload;
    }

    private boolean isPublic(String visibility) {
        return visibility == null || visibility.equalsIgnoreCase("public");
    }

    private OffsetDateTime eligibleAt(PostRepository.PostRow post) {
        OffsetDateTime createdAt = post == null ? null : post.createdAt;
        if (createdAt == null) return OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(Math.max(0, props.getDelayMinutes()));
        return createdAt.plusMinutes(Math.max(0, props.getDelayMinutes()));
    }

    private String safeVariant(String stateVariant, String fallback) {
        if (stateVariant != null && !stateVariant.isBlank()) return stateVariant;
        return fallback;
    }

    public enum MutationStatus {
        OK,
        USER_NOT_PROVISIONED,
        NOT_FOUND,
        FORBIDDEN
    }

    public record MutationResult(MutationStatus status,
                                 boolean served,
                                 PostShareNudgeRepository.StateRow state,
                                 Map<String, Object> sharePayload) {
        static MutationResult of(MutationStatus status,
                                 boolean served,
                                 PostShareNudgeRepository.StateRow state,
                                 Map<String, Object> sharePayload) {
            return new MutationResult(status, served, state, sharePayload);
        }
    }

    private record ResolveActorResult(MutationStatus status,
                                      UserRepository.UserRow user,
                                      PostRepository.PostRow post,
                                      PostShareNudgeRepository.StateRow state) {
    }
}

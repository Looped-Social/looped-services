package com.looped.moderation;

import com.looped.comments.CommentsRepository;
import com.looped.media.MediaService;
import com.looped.media.MediaRepository;
import com.looped.posts.PostRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Set;

@Service
public class MediaModerationService {
    private final ModerationProperties props;
    private final OpenAiModerationClient openai;
    private final MediaRepository media;
    private final MediaService mediaService;
    private final ModerationQueueRepository queue;
    private final PostRepository posts;
    private final CommentsRepository comments;
    private final QuarantineService quarantine;

    public MediaModerationService(ModerationProperties props,
                                  OpenAiModerationClient openai,
                                  MediaRepository media,
                                  MediaService mediaService,
                                  ModerationQueueRepository queue,
                                  PostRepository posts,
                                  CommentsRepository comments,
                                  QuarantineService quarantine) {
        this.props = props;
        this.openai = openai;
        this.media = media;
        this.mediaService = mediaService;
        this.queue = queue;
        this.posts = posts;
        this.comments = comments;
        this.quarantine = quarantine;
    }

    @Transactional
    public void moderateOnUpload(long mediaAssetId, String key, String mimeType, String cdnUrlOrNull) {
        if (!props.isEnabled()) return;
        if (mimeType == null) return;
        if (!mimeType.startsWith("image/")) return;

        String url = cdnUrlOrNull;
        if (url == null || url.isBlank()) {
            url = mediaService.presignedGetUrl(key, Duration.ofMinutes(5));
        }

        var res = openai.moderateImageUrl(url);
        if (!openai.shouldQuarantine(res)) return;

        String reason = res.trueCategories() == null || res.trueCategories().isEmpty()
                ? "policy:openai:media:flagged"
                : "policy:openai:media:flagged:" + String.join(",", res.trueCategories());

        boolean quarantined = media.quarantine(mediaAssetId, reason);
        if (!quarantined) {
            // already quarantined/removed; still ensure there's a queue item
            queue.enqueueIfAbsent("media", mediaAssetId, "openai", reason);
            return;
        }

        queue.enqueueIfAbsent("media", mediaAssetId, "openai", reason);

        List<Long> postIds = posts.findPostIdsByMediaAsset(mediaAssetId);
        for (Long postId : postIds) {
            if (postId == null) continue;
            quarantine.quarantinePost(postId, "openai", "policy:media_under_review");
        }

        List<Long> commentIds = comments.findCommentIdsByMediaAsset(mediaAssetId);
        for (Long commentId : commentIds) {
            if (commentId == null) continue;
            quarantine.quarantineComment(commentId, "openai", "policy:media_under_review");
        }
    }
}


package com.looped.posts;

import com.looped.users.UserRepository;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class PostViewCountsService {
    private final UserRepository users;
    private final PostViewCountsRepository views;

    public PostViewCountsService(UserRepository users, PostViewCountsRepository views) {
        this.users = users;
        this.views = views;
    }

    public Map<Long, Long> authorVisibleUniqueViewCounts(String firebaseUid,
                                                         List<? extends PostRepository.PostRow> posts) {
        if (firebaseUid == null || firebaseUid.isBlank() || posts == null || posts.isEmpty()) {
            return Map.of();
        }
        var viewer = users.findByFirebaseUid(firebaseUid);
        if (viewer.isEmpty()) return Map.of();

        return authorVisibleUniqueViewCounts(viewer.get().id, posts);
    }

    public Map<Long, Long> authorVisibleUniqueViewCounts(long viewerUserId,
                                                         List<? extends PostRepository.PostRow> posts) {
        if (posts == null || posts.isEmpty()) {
            return Map.of();
        }

        List<Long> authoredPostIds = posts.stream()
                .filter(Objects::nonNull)
                .filter(p -> p.authorId != null && p.authorId == viewerUserId)
                .map(p -> p.id)
                .distinct()
                .toList();
        if (authoredPostIds.isEmpty()) return Map.of();

        Map<Long, Long> counts = views.uniquePostOpenViewersByPostIds(authoredPostIds);
        Map<Long, Long> out = new HashMap<>();
        for (Long postId : authoredPostIds) {
            out.put(postId, counts.getOrDefault(postId, 0L));
        }
        return out;
    }
}

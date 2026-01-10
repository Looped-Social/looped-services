package com.looped.posts;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@Service
public class PostStateService {
    private final LikesRepository likes;
    private final SavedPostsRepository savedPosts;
    private final RepostsRepository reposts;

    public PostStateService(LikesRepository likes, SavedPostsRepository savedPosts, RepostsRepository reposts) {
        this.likes = likes;
        this.savedPosts = savedPosts;
        this.reposts = reposts;
    }

    public void applyForPrincipal(long principalId, List<? extends PostRepository.PostRow> posts) {
        if (posts == null || posts.isEmpty()) return;
        List<Long> ids = posts.stream().map(p -> p.id).distinct().toList();
        Set<Long> likedIds = likes.findLikedPostIds(principalId, ids);
        Set<Long> savedIds = savedPosts.findSavedPostIds(principalId, ids);
        Set<Long> repostedIds = reposts.findRepostedPostIds(principalId, ids);
        for (PostRepository.PostRow post : posts) {
            post.userLiked = likedIds.contains(post.id);
            post.isSaved = savedIds.contains(post.id);
            post.viewerHasReposted = repostedIds.contains(post.id);
        }
    }
}

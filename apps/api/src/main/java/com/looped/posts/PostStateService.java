package com.looped.posts;

import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
        List<RepostsRepository.FollowedRepostRow> bannerRows = reposts.followedRepostsForPosts(principalId, ids);
        Map<Long, List<PostRepository.RepostBannerUser>> bannerUsersByPost = new HashMap<>();
        Map<Long, Integer> bannerCountsByPost = new HashMap<>();
        for (var row : bannerRows) {
            bannerCountsByPost.put(row.postId(), row.totalCount());
            bannerUsersByPost.computeIfAbsent(row.postId(), ignored -> new java.util.ArrayList<>())
                    .add(new PostRepository.RepostBannerUser(
                            row.userId(),
                            row.username(),
                            row.displayName(),
                            row.handle(),
                            row.profileImageUrl()
                    ));
        }
        for (PostRepository.PostRow post : posts) {
            post.userLiked = likedIds.contains(post.id);
            post.isSaved = savedIds.contains(post.id);
            post.viewerHasReposted = repostedIds.contains(post.id);
            post.repostedByFollowedUsersCount = bannerCountsByPost.getOrDefault(post.id, 0);
            post.repostedByFollowedUsers = bannerUsersByPost.getOrDefault(post.id, List.of());
        }
    }
}

package com.looped.posts;

import com.looped.users.UserRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

@Service
public class PostsService {
    private final PostRepository posts;
    private final UserRepository users;
    private final StringRedisTemplate redis;

    public PostsService(PostRepository posts, UserRepository users, StringRedisTemplate redis) {
        this.posts = posts;
        this.users = users;
        this.redis = redis;
    }

    public CreateResult create(String firebaseUid, String idempotencyKey, String content, Long mediaAssetId) {
        var u = users.findByFirebaseUid(firebaseUid);
        if (u.isEmpty()) return CreateResult.userNotProvisioned();
        long userId = u.get().id;
        long companyId = Optional.ofNullable(u.get().companyId).orElse(0L);
        if (companyId == 0) return CreateResult.userNotProvisioned();

        String redisKey = "idem:posts:" + userId + ":" + idempotencyKey;
        // Try to reserve idempotency key
        Boolean reserved = redis.opsForValue().setIfAbsent(redisKey, "PENDING", Duration.ofHours(24));
        if (Boolean.FALSE.equals(reserved)) {
            // Already exists: either PENDING or a post id
            String val = redis.opsForValue().get(redisKey);
            if (val != null && !val.equals("PENDING")) {
                try {
                    long postId = Long.parseLong(val);
                    var existing = posts.findById(postId).orElse(null);
                    if (existing != null) return CreateResult.ok(existing.id, false);
                } catch (NumberFormatException ignored) {}
            }
            return CreateResult.inFlight();
        }

        // Create new post
        try {
            var p = posts.insert(userId, companyId, content, mediaAssetId);
            redis.opsForValue().set(redisKey, Long.toString(p.id), Duration.ofHours(24));
            return CreateResult.ok(p.id, true);
        } catch (DataAccessException e) {
            // Clean up reservation on failure
            redis.delete(redisKey);
            throw e;
        }
    }

    public Optional<PostRepository.PostRow> get(long id) {
        return posts.findById(id);
    }

    public enum Status { OK, USER_NOT_PROVISIONED, IDEMPOTENCY_IN_FLIGHT }
    public record CreateResult(Status status, Long id, boolean created) {
        static CreateResult ok(long id, boolean created) { return new CreateResult(Status.OK, id, created); }
        static CreateResult userNotProvisioned() { return new CreateResult(Status.USER_NOT_PROVISIONED, null, false); }
        static CreateResult inFlight() { return new CreateResult(Status.IDEMPOTENCY_IN_FLIGHT, null, false); }
    }
}


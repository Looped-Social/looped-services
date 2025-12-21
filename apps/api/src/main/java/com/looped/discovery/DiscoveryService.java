package com.looped.discovery;

import com.looped.shared.Pagination;
import com.looped.communities.CommunitiesRepository;
import com.looped.users.UserRepository;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

@Service
public class DiscoveryService {
    private final CommunitiesRepository communities;
    private final HashtagsRepository hashtags;
    private final UserRepository users;

    public DiscoveryService(CommunitiesRepository communities, HashtagsRepository hashtags, UserRepository users) {
        this.communities = communities;
        this.hashtags = hashtags;
        this.users = users;
    }

    public CommunitySearchResult searchCommunities(String firebaseUid, String query, String cursor, int limit) {
        var actor = users.findByFirebaseUid(firebaseUid);
        if (actor.isEmpty()) return CommunitySearchResult.userNotProvisioned();
        OffsetDateTime cTs = null; Long cId = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                var decoded = Pagination.decode(cursor);
                cTs = decoded.timestamp();
                cId = decoded.id();
            } catch (IllegalArgumentException ignored) {}
        }
        var rows = communities.search(query, cTs, cId, limit);
        String next = null;
        if (rows.size() == limit) {
            var last = rows.get(rows.size() - 1);
            next = Pagination.encode(last.createdAt, last.id);
        }
        return CommunitySearchResult.ok(rows, next);
    }

    public HashtagSearchResult searchHashtags(String firebaseUid, String query, String cursor, int limit) {
        var actor = users.findByFirebaseUid(firebaseUid);
        if (actor.isEmpty() || actor.get().companyId == null) return HashtagSearchResult.userNotProvisioned();
        OffsetDateTime cTs = null; Long cId = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                var decoded = Pagination.decode(cursor);
                cTs = decoded.timestamp();
                cId = decoded.id();
            } catch (IllegalArgumentException ignored) {}
        }
        var rows = hashtags.search(actor.get().companyId, query, cTs, cId, limit);
        String next = null;
        if (rows.size() == limit) {
            var last = rows.get(rows.size() - 1);
            next = Pagination.encode(last.createdAt, last.id);
        }
        return HashtagSearchResult.ok(rows, next);
    }

    public enum Status { OK, USER_NOT_PROVISIONED }

    public record CommunitySearchResult(Status status, List<CommunitiesRepository.CommunityRow> items, String nextCursor) {
        static CommunitySearchResult ok(List<CommunitiesRepository.CommunityRow> items, String next) { return new CommunitySearchResult(Status.OK, items, next); }
        static CommunitySearchResult userNotProvisioned() { return new CommunitySearchResult(Status.USER_NOT_PROVISIONED, List.of(), null); }
    }

    public record HashtagSearchResult(Status status, List<HashtagsRepository.HashtagRow> items, String nextCursor) {
        static HashtagSearchResult ok(List<HashtagsRepository.HashtagRow> items, String next) { return new HashtagSearchResult(Status.OK, items, next); }
        static HashtagSearchResult userNotProvisioned() { return new HashtagSearchResult(Status.USER_NOT_PROVISIONED, List.of(), null); }
    }
}

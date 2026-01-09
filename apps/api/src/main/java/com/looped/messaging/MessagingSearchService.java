package com.looped.messaging;

import com.looped.shared.RankPagination;
import com.looped.users.UserRepository;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class MessagingSearchService {
    private final UserRepository users;
    private final MessagingSearchRepository search;

    public MessagingSearchService(UserRepository users, MessagingSearchRepository search) {
        this.users = users;
        this.search = search;
    }

    public SearchResult search(String firebaseUid, String query, String cursor, int limit) {
        var actor = requireProvisionedUser(firebaseUid);
        if (actor.isEmpty()) return SearchResult.userNotProvisioned();
        if (actor.get().isAnonymous) return SearchResult.anonymousNotAllowed();

        RankPagination.Cursor rankedCursor = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                rankedCursor = RankPagination.decode(cursor);
            } catch (IllegalArgumentException ignored) {}
        }
        OffsetDateTime asOf = rankedCursor == null ? OffsetDateTime.now() : rankedCursor.asOf();
        Long score = rankedCursor == null ? null : rankedCursor.score();
        OffsetDateTime activityAt = rankedCursor == null ? null : rankedCursor.timestamp();
        Long globalId = rankedCursor == null ? null : rankedCursor.id();

        String prefixQuery = MessagingSearchQuery.toPrefixTsquery(query);
        List<MessagingSearchRepository.SearchRow> rows = search.search(
                actor.get().companyId,
                actor.get().id,
                query,
                prefixQuery,
                asOf,
                score,
                activityAt,
                globalId,
                limit
        );

        List<Map<String, Object>> items = rows.stream().map(this::toPayload).toList();

        String next = null;
        if (rows.size() == limit) {
            var last = rows.get(rows.size() - 1);
            next = RankPagination.encode(asOf, last.score, last.activityAt, last.globalId);
        }
        return SearchResult.ok(items, next);
    }

    private Map<String, Object> toPayload(MessagingSearchRepository.SearchRow row) {
        Map<String, Object> out = new HashMap<>();
        out.put("type", row.type);

        if ("conversation".equals(row.type)) {
            out.put("conversation_id", row.threadId);
            out.put("id", row.threadId);
            out.put("other_user_id", row.otherUserId);
            if (row.otherUserId != null) {
                Map<String, Object> profile = new HashMap<>();
                profile.put("id", row.otherUserId);
                profile.put("handle", row.otherUserHandle);
                profile.put("username", row.otherUserHandle);
                profile.put("display_name", row.otherUserDisplayName);
                profile.put("bio", row.otherUserBio);
                profile.put("company_id", row.otherUserCompanyId);
                profile.put("profile_image_url", row.otherUserProfileImageUrl);
                out.put("other_user_profile", profile);
            }
        } else if ("channel".equals(row.type)) {
            out.put("channel_id", row.threadId);
            out.put("id", row.threadId);
            out.put("name", row.channelName);
            out.put("is_public", row.channelIsPublic);
        }

        if (row.lastMessage != null) out.put("last_message", row.lastMessage);
        if (row.lastMessageAt != null) out.put("last_message_timestamp", row.lastMessageAt);

        if (row.matchedMessageId != null) {
            Map<String, Object> matched = new HashMap<>();
            matched.put("id", row.matchedMessageId);
            matched.put("sender_id", row.matchedMessageSenderId);
            matched.put("content", row.matchedMessageContent);
            matched.put("created_at", row.matchedMessageCreatedAt);
            out.put("matched_message", matched);
        }

        return out;
    }

    private Optional<UserRepository.UserRow> requireProvisionedUser(String firebaseUid) {
        var user = users.findByFirebaseUid(firebaseUid);
        if (user.isEmpty() || user.get().companyId == null) return Optional.empty();
        return user;
    }

    public enum Status { OK, USER_NOT_PROVISIONED, ANONYMOUS_NOT_ALLOWED }

    public record SearchResult(Status status, List<Map<String, Object>> items, String nextCursor) {
        static SearchResult ok(List<Map<String, Object>> items, String next) { return new SearchResult(Status.OK, items, next); }
        static SearchResult userNotProvisioned() { return new SearchResult(Status.USER_NOT_PROVISIONED, List.of(), null); }
        static SearchResult anonymousNotAllowed() { return new SearchResult(Status.ANONYMOUS_NOT_ALLOWED, List.of(), null); }
    }
}

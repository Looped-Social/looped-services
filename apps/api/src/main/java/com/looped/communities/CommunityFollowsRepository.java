package com.looped.communities;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Set;

@Repository
public class CommunityFollowsRepository {
    private final JdbcTemplate jdbc;

    public CommunityFollowsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<FollowRow> MAPPER = new RowMapper<>() {
        @Override
        public FollowRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            FollowRow row = new FollowRow();
            row.followId = rs.getLong("follow_id");
            row.communityId = rs.getLong("community_id");
            row.name = rs.getString("name");
            row.kind = rs.getString("kind");
            row.specializationType = rs.getString("specialization_type");
            row.memberCount = rs.getInt("member_count");
            row.isPinned = rs.getBoolean("is_pinned");
            int order = rs.getInt("sort_order");
            row.sortOrder = rs.wasNull() ? null : order;
            row.canPost = rs.getBoolean("can_post");
            row.isJoined = rs.getBoolean("is_joined");
            row.followedAt = rs.getObject("followed_at", OffsetDateTime.class);
            row.lastActivity = rs.getObject("last_activity", OffsetDateTime.class);
            return row;
        }
    };

    public List<FollowRow> findFollowed(long userId, OffsetDateTime cursorTs, Long cursorId, int limit) {
        String base = """
                SELECT cf.id AS follow_id, cf.community_id, cf.is_pinned, cf.sort_order, cf.created_at AS followed_at,
                       c.name, c.kind, c.specialization_type, c.member_count,
                       cf.created_at AS last_activity,
                       CASE
                           WHEN c.kind = 'specialization' THEN true
                           ELSE (COALESCE(cv.verified, false) AND (cv.expires_at IS NULL OR cv.expires_at > now()))
                       END AS can_post,
                       CASE WHEN sj.user_id IS NULL THEN false ELSE true END AS is_joined
                FROM community_follows cf
                JOIN communities c ON c.id = cf.community_id
                LEFT JOIN community_verifications cv
                    ON cv.user_id = cf.user_id AND cv.community_id = cf.community_id
                LEFT JOIN specialization_joins sj
                    ON sj.user_id = cf.user_id AND sj.specialization_id = cf.community_id
                WHERE cf.user_id = ?
                """;
        if (cursorTs == null || cursorId == null) {
            return jdbc.query(base + "ORDER BY cf.created_at DESC, cf.id DESC LIMIT ?", MAPPER, userId, limit);
        }
        return jdbc.query(
                base + "AND (cf.created_at < ? OR (cf.created_at = ? AND cf.id < ?)) " +
                        "ORDER BY cf.created_at DESC, cf.id DESC LIMIT ?",
                MAPPER, userId, cursorTs, cursorTs, cursorId, limit
        );
    }

    public List<FollowRow> findFollowedRelevant(long userId, CommunityFollowCursor.Cursor cursor, int limit) {
        String base = """
                WITH rows AS (
                    SELECT cf.id AS follow_id, cf.community_id, cf.is_pinned, cf.sort_order, cf.created_at AS followed_at,
                           c.name, c.kind, c.specialization_type, c.member_count,
                           COALESCE(lp.last_post_at, cf.created_at) AS last_activity,
                           CASE
                               WHEN c.kind = 'specialization' THEN true
                               ELSE (COALESCE(cv.verified, false) AND (cv.expires_at IS NULL OR cv.expires_at > now()))
                           END AS can_post,
                           CASE WHEN sj.user_id IS NULL THEN false ELSE true END AS is_joined,
                           CASE WHEN cf.is_pinned THEN 0 ELSE 1 END AS pinned_rank,
                           CASE WHEN cf.sort_order IS NULL THEN 1 ELSE 0 END AS sort_rank,
                           COALESCE(cf.sort_order, 2147483647) AS sort_order_value
                    FROM community_follows cf
                    JOIN communities c ON c.id = cf.community_id
                    LEFT JOIN community_verifications cv
                        ON cv.user_id = cf.user_id AND cv.community_id = cf.community_id
                    LEFT JOIN specialization_joins sj
                        ON sj.user_id = cf.user_id AND sj.specialization_id = cf.community_id
                    LEFT JOIN LATERAL (
                        SELECT p.created_at AS last_post_at
                        FROM posts p
                        WHERE p.community_id = cf.community_id
                        ORDER BY p.created_at DESC, p.id DESC
                        LIMIT 1
                    ) lp ON true
                    WHERE cf.user_id = ?
                )
                SELECT * FROM rows
                """;
        String order = "ORDER BY pinned_rank ASC, sort_rank ASC, sort_order_value ASC, last_activity DESC, follow_id DESC LIMIT ?";
        if (cursor == null) {
            return jdbc.query(base + order, MAPPER, userId, limit);
        }
        String where = """
                WHERE (
                    pinned_rank > ?
                    OR (pinned_rank = ? AND sort_rank > ?)
                    OR (pinned_rank = ? AND sort_rank = ? AND sort_order_value > ?)
                    OR (pinned_rank = ? AND sort_rank = ? AND sort_order_value = ? AND last_activity < ?)
                    OR (pinned_rank = ? AND sort_rank = ? AND sort_order_value = ? AND last_activity = ? AND follow_id < ?)
                )
                """;
        return jdbc.query(
                base + where + order,
                MAPPER,
                userId,
                cursor.pinnedRank(),
                cursor.pinnedRank(), cursor.sortRank(),
                cursor.pinnedRank(), cursor.sortRank(), cursor.sortOrderValue(),
                cursor.pinnedRank(), cursor.sortRank(), cursor.sortOrderValue(), cursor.lastActivity(),
                cursor.pinnedRank(), cursor.sortRank(), cursor.sortOrderValue(), cursor.lastActivity(), cursor.followId(),
                limit
        );
    }

    public boolean insertIfAbsent(long userId, long communityId) {
        int rows = jdbc.update(
                "INSERT INTO community_follows(user_id, community_id) VALUES (?, ?) ON CONFLICT (user_id, community_id) DO NOTHING",
                userId, communityId
        );
        return rows > 0;
    }

    public boolean exists(long userId, long communityId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM community_follows WHERE user_id = ? AND community_id = ?",
                Integer.class,
                userId,
                communityId
        );
        return count != null && count > 0;
    }

    public Set<Long> followedIds(long userId, Collection<Long> communityIds) {
        if (communityIds == null || communityIds.isEmpty()) return Set.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(communityIds.size(), "?"));
        java.util.List<Object> args = new java.util.ArrayList<>();
        args.add(userId);
        args.addAll(communityIds);
        List<Long> rows = jdbc.query(
                "SELECT community_id FROM community_follows WHERE user_id = ? AND community_id IN (" + placeholders + ")",
                (rs, rowNum) -> rs.getLong("community_id"),
                args.toArray()
        );
        return Set.copyOf(rows);
    }

    public int countSpecializations(long userId, String specializationType) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM community_follows cf " +
                        "JOIN communities c ON c.id = cf.community_id " +
                        "WHERE cf.user_id = ? AND c.kind = 'specialization' AND c.specialization_type = ?",
                Integer.class,
                userId,
                specializationType
        );
        return count == null ? 0 : count;
    }

    public boolean delete(long userId, long communityId) {
        int rows = jdbc.update(
                "DELETE FROM community_follows WHERE user_id=? AND community_id=?",
                userId, communityId
        );
        return rows > 0;
    }

    public static class FollowRow {
        public long followId;
        public long communityId;
        public String name;
        public String kind;
        public String specializationType;
        public int memberCount;
        public boolean isPinned;
        public Integer sortOrder;
        public boolean canPost;
        public boolean isJoined;
        public OffsetDateTime followedAt;
        public OffsetDateTime lastActivity;
    }
}

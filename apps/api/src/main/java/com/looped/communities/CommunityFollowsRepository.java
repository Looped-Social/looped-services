package com.looped.communities;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;

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
            row.memberCount = rs.getInt("member_count");
            row.isPinned = rs.getBoolean("is_pinned");
            int order = rs.getInt("sort_order");
            row.sortOrder = rs.wasNull() ? null : order;
            row.canPost = rs.getBoolean("can_post");
            row.followedAt = rs.getObject("followed_at", OffsetDateTime.class);
            return row;
        }
    };

    public List<FollowRow> findFollowed(long userId, OffsetDateTime cursorTs, Long cursorId, int limit) {
        String base = """
                SELECT cf.id AS follow_id, cf.community_id, cf.is_pinned, cf.sort_order, cf.created_at AS followed_at,
                       c.name, c.kind, c.member_count,
                       COALESCE(cv.verified, false) AS can_post
                FROM community_follows cf
                JOIN communities c ON c.id = cf.community_id
                LEFT JOIN community_verifications cv
                    ON cv.user_id = cf.user_id AND cv.community_id = cf.community_id
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

    public boolean insertIfAbsent(long userId, long communityId) {
        int rows = jdbc.update(
                "INSERT INTO community_follows(user_id, community_id) VALUES (?, ?) ON CONFLICT (user_id, community_id) DO NOTHING",
                userId, communityId
        );
        return rows > 0;
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
        public int memberCount;
        public boolean isPinned;
        public Integer sortOrder;
        public boolean canPost;
        public OffsetDateTime followedAt;
    }
}

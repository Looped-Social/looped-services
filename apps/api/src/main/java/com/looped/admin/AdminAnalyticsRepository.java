package com.looped.admin;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Repository
public class AdminAnalyticsRepository {
    private final JdbcTemplate jdbc;

    public AdminAnalyticsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<CommunityMetricRow> communityLeaderboard(Long communityId, OffsetDateTime from, OffsetDateTime to,
                                                          String orderByExpr, int limit) {
        Subquery likes = buildLikesSubquery(from, to);
        Subquery shares = buildSharesSubquery(from, to);
        Subquery followers = buildFollowersSubquery(from, to);
        Subquery verifications = buildVerificationsSubquery(from, to);

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT * FROM (");
        sql.append("SELECT c.id, c.kind, c.name, c.image_url, ");
        sql.append("(").append(likes.sql()).append(") AS likes_count, ");
        sql.append("(").append(shares.sql()).append(") AS shares_count, ");
        sql.append("(").append(followers.sql()).append(") AS followers_count, ");
        sql.append("(").append(verifications.sql()).append(") AS verifications_count ");
        sql.append("FROM communities c ");
        if (communityId != null) {
            sql.append("WHERE c.id = ? ");
        }
        sql.append(") t ");
        sql.append("ORDER BY ").append(orderByExpr).append(" DESC, t.id DESC ");
        sql.append("LIMIT ? ");

        List<Object> params = new ArrayList<>();
        params.addAll(likes.params());
        params.addAll(shares.params());
        params.addAll(followers.params());
        params.addAll(verifications.params());
        if (communityId != null) params.add(communityId);
        params.add(limit);

        return jdbc.query(sql.toString(), params.toArray(), COMMUNITY_MAPPER);
    }

    public List<HashtagMetricRow> hashtagsLeaderboard(Long communityId, OffsetDateTime from, OffsetDateTime to, int limit) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT h.id, h.name, COUNT(hp.post_id) AS usage_count ");
        sql.append("FROM hashtags h ");
        sql.append("JOIN hashtag_posts hp ON hp.hashtag_id = h.id ");
        sql.append("JOIN posts p ON p.id = hp.post_id AND p.removed_at IS NULL ");
        sql.append("WHERE 1=1 ");
        List<Object> params = new ArrayList<>();
        if (communityId != null) {
            sql.append("AND p.community_id = ? ");
            params.add(communityId);
        }
        if (from != null) {
            sql.append("AND hp.created_at >= ? ");
            params.add(from);
        }
        if (to != null) {
            sql.append("AND hp.created_at < ? ");
            params.add(to);
        }
        sql.append("GROUP BY h.id, h.name ");
        sql.append("ORDER BY usage_count DESC, h.id DESC ");
        sql.append("LIMIT ? ");
        params.add(limit);

        return jdbc.query(sql.toString(), params.toArray(), HASHTAG_MAPPER);
    }

    public UserStatsRow userStats(OffsetDateTime from, OffsetDateTime to) {
        long totalUsers = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE deleted_at IS NULL",
                Long.class
        );
        long deletedUsers = jdbc.queryForObject(
                buildUserCountQuery("deleted_at IS NOT NULL", "deleted_at", from, to),
                buildUserCountParams(from, to),
                Long.class
        );
        long newUsers = jdbc.queryForObject(
                buildUserCountQuery("deleted_at IS NULL", "created_at", from, to),
                buildUserCountParams(from, to),
                Long.class
        );
        UserStatsRow row = new UserStatsRow();
        row.totalUsers = totalUsers;
        row.newUsers = newUsers;
        row.deletedUsers = deletedUsers;
        return row;
    }

    private Object[] buildUserCountParams(OffsetDateTime from, OffsetDateTime to) {
        List<Object> params = new ArrayList<>();
        if (from != null) params.add(from);
        if (to != null) params.add(to);
        return params.toArray();
    }

    private String buildUserCountQuery(String baseFilter, String timeColumn, OffsetDateTime from, OffsetDateTime to) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM users WHERE ");
        sql.append(baseFilter).append(" ");
        if (from != null) sql.append("AND ").append(timeColumn).append(" >= ? ");
        if (to != null) sql.append("AND ").append(timeColumn).append(" < ? ");
        return sql.toString();
    }

    private Subquery buildLikesSubquery(OffsetDateTime from, OffsetDateTime to) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT COUNT(*) FROM posts p ");
        sql.append("JOIN post_likes pl ON pl.post_id = p.id ");
        sql.append("WHERE p.community_id = c.id AND p.removed_at IS NULL ");
        List<Object> params = new ArrayList<>();
        if (from != null) {
            sql.append("AND pl.created_at >= ? ");
            params.add(from);
        }
        if (to != null) {
            sql.append("AND pl.created_at < ? ");
            params.add(to);
        }
        return new Subquery(sql.toString(), params);
    }

    private Subquery buildSharesSubquery(OffsetDateTime from, OffsetDateTime to) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT COUNT(*) FROM posts p ");
        sql.append("JOIN post_shares ps ON ps.post_id = p.id ");
        sql.append("WHERE p.community_id = c.id AND p.removed_at IS NULL ");
        List<Object> params = new ArrayList<>();
        if (from != null) {
            sql.append("AND ps.created_at >= ? ");
            params.add(from);
        }
        if (to != null) {
            sql.append("AND ps.created_at < ? ");
            params.add(to);
        }
        return new Subquery(sql.toString(), params);
    }

    private Subquery buildFollowersSubquery(OffsetDateTime from, OffsetDateTime to) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT COUNT(*) FROM community_follows cf ");
        sql.append("WHERE cf.community_id = c.id ");
        List<Object> params = new ArrayList<>();
        if (from != null) {
            sql.append("AND cf.created_at >= ? ");
            params.add(from);
        }
        if (to != null) {
            sql.append("AND cf.created_at < ? ");
            params.add(to);
        }
        return new Subquery(sql.toString(), params);
    }

    private Subquery buildVerificationsSubquery(OffsetDateTime from, OffsetDateTime to) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT COUNT(*) FROM community_verifications cv ");
        sql.append("WHERE cv.community_id = c.id AND cv.verified = true ");
        List<Object> params = new ArrayList<>();
        if (from != null) {
            sql.append("AND cv.verified_at >= ? ");
            params.add(from);
        }
        if (to != null) {
            sql.append("AND cv.verified_at < ? ");
            params.add(to);
        }
        if (from == null && to == null) {
            sql.append("AND (cv.expires_at IS NULL OR cv.expires_at > now()) ");
        }
        return new Subquery(sql.toString(), params);
    }

    private static final RowMapper<CommunityMetricRow> COMMUNITY_MAPPER = new RowMapper<>() {
        @Override
        public CommunityMetricRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            CommunityMetricRow row = new CommunityMetricRow();
            row.id = rs.getLong("id");
            row.kind = rs.getString("kind");
            row.name = rs.getString("name");
            row.imageUrl = rs.getString("image_url");
            row.likesCount = rs.getLong("likes_count");
            row.sharesCount = rs.getLong("shares_count");
            row.followersCount = rs.getLong("followers_count");
            row.verificationsCount = rs.getLong("verifications_count");
            return row;
        }
    };

    private static final RowMapper<HashtagMetricRow> HASHTAG_MAPPER = new RowMapper<>() {
        @Override
        public HashtagMetricRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            HashtagMetricRow row = new HashtagMetricRow();
            row.id = rs.getLong("id");
            row.name = rs.getString("name");
            row.usageCount = rs.getLong("usage_count");
            return row;
        }
    };

    private record Subquery(String sql, List<Object> params) {}

    public static class CommunityMetricRow {
        public long id;
        public String kind;
        public String name;
        public String imageUrl;
        public long likesCount;
        public long sharesCount;
        public long followersCount;
        public long verificationsCount;
    }

    public static class HashtagMetricRow {
        public long id;
        public String name;
        public long usageCount;
    }

    public static class UserStatsRow {
        public long totalUsers;
        public long newUsers;
        public long deletedUsers;
    }
}

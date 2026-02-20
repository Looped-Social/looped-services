package com.looped.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;

@Repository
public class AdminUserStatsRepository {
    private static final Logger log = LoggerFactory.getLogger(AdminUserStatsRepository.class);
    private final JdbcTemplate jdbc;

    public AdminUserStatsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<UserModerationStats> MAPPER = new RowMapper<>() {
        @Override
        public UserModerationStats mapRow(ResultSet rs, int rowNum) throws SQLException {
            UserModerationStats stats = new UserModerationStats();
            stats.postsTotal = rs.getLong("posts_total");
            stats.postsRemovedTotal = rs.getLong("posts_removed_total");
            stats.reportsAgainstUserTotal = rs.getLong("reports_against_user_total");
            stats.reportsAgainstUserOpen = rs.getLong("reports_against_user_open");
            stats.reportsAgainstUserResolved = rs.getLong("reports_against_user_resolved");
            stats.reportsAgainstUserDismissed = rs.getLong("reports_against_user_dismissed");
            stats.reportsFiledTotal = rs.getLong("reports_filed_total");
            stats.reportsFiledOpen = rs.getLong("reports_filed_open");
            stats.reportsFiledResolved = rs.getLong("reports_filed_resolved");
            stats.reportsFiledDismissed = rs.getLong("reports_filed_dismissed");
            stats.reportsAgainstPostsTotal = rs.getLong("reports_against_posts_total");
            stats.reportsAgainstPostsOpen = rs.getLong("reports_against_posts_open");
            stats.reportsAgainstPostsResolved = rs.getLong("reports_against_posts_resolved");
            stats.reportsAgainstPostsDismissed = rs.getLong("reports_against_posts_dismissed");
            return stats;
        }
    };

    public UserModerationStats forUser(long userId) {
        String sql = "WITH user_principals AS (SELECT id FROM principals WHERE user_id = ?) " +
                "SELECT " +
                "(SELECT COUNT(*) FROM posts p " +
                "WHERE p.author_principal_id IN (SELECT id FROM user_principals)) AS posts_total, " +
                "(SELECT COUNT(*) FROM posts p " +
                "WHERE p.author_principal_id IN (SELECT id FROM user_principals) AND p.removed_at IS NOT NULL) AS posts_removed_total, " +
                "(SELECT COUNT(*) FROM reports r WHERE r.target_type = 'user' AND r.target_id = ?) AS reports_against_user_total, " +
                "(SELECT COUNT(*) FROM reports r WHERE r.target_type = 'user' AND r.target_id = ? AND r.status = 'open') AS reports_against_user_open, " +
                "(SELECT COUNT(*) FROM reports r WHERE r.target_type = 'user' AND r.target_id = ? AND r.status = 'resolved') AS reports_against_user_resolved, " +
                "(SELECT COUNT(*) FROM reports r WHERE r.target_type = 'user' AND r.target_id = ? AND r.status = 'dismissed') AS reports_against_user_dismissed, " +
                "(SELECT COUNT(*) FROM reports r WHERE r.reporter_id = ?) AS reports_filed_total, " +
                "(SELECT COUNT(*) FROM reports r WHERE r.reporter_id = ? AND r.status = 'open') AS reports_filed_open, " +
                "(SELECT COUNT(*) FROM reports r WHERE r.reporter_id = ? AND r.status = 'resolved') AS reports_filed_resolved, " +
                "(SELECT COUNT(*) FROM reports r WHERE r.reporter_id = ? AND r.status = 'dismissed') AS reports_filed_dismissed, " +
                "(SELECT COUNT(*) FROM reports r JOIN posts p ON p.id = r.target_id " +
                "WHERE r.target_type = 'post' AND p.author_principal_id IN (SELECT id FROM user_principals)) AS reports_against_posts_total, " +
                "(SELECT COUNT(*) FROM reports r JOIN posts p ON p.id = r.target_id " +
                "WHERE r.target_type = 'post' AND p.author_principal_id IN (SELECT id FROM user_principals) AND r.status = 'open') AS reports_against_posts_open, " +
                "(SELECT COUNT(*) FROM reports r JOIN posts p ON p.id = r.target_id " +
                "WHERE r.target_type = 'post' AND p.author_principal_id IN (SELECT id FROM user_principals) AND r.status = 'resolved') AS reports_against_posts_resolved, " +
                "(SELECT COUNT(*) FROM reports r JOIN posts p ON p.id = r.target_id " +
                "WHERE r.target_type = 'post' AND p.author_principal_id IN (SELECT id FROM user_principals) AND r.status = 'dismissed') AS reports_against_posts_dismissed";
        try {
            var rows = jdbc.query(
                    sql,
                    MAPPER,
                    userId,
                    userId,
                    userId,
                    userId,
                    userId,
                    userId,
                    userId,
                    userId,
                    userId
            );
            return rows.isEmpty() ? emptyStats() : rows.get(0);
        } catch (DataAccessException ex) {
            log.warn("Failed to build moderation stats for userId={}, returning zero stats", userId, ex);
            return emptyStats();
        }
    }

    public UserModerationStats emptyStats() {
        return new UserModerationStats();
    }

    public static class UserModerationStats {
        public long postsTotal;
        public long postsRemovedTotal;
        public long reportsAgainstUserTotal;
        public long reportsAgainstUserOpen;
        public long reportsAgainstUserResolved;
        public long reportsAgainstUserDismissed;
        public long reportsFiledTotal;
        public long reportsFiledOpen;
        public long reportsFiledResolved;
        public long reportsFiledDismissed;
        public long reportsAgainstPostsTotal;
        public long reportsAgainstPostsOpen;
        public long reportsAgainstPostsResolved;
        public long reportsAgainstPostsDismissed;
    }
}

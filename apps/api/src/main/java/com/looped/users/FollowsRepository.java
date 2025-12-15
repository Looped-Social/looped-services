package com.looped.users;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;

@Repository
public class FollowsRepository {
    private final JdbcTemplate jdbc;

    public FollowsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<UserFollowRow> MAPPER = new RowMapper<>() {
        @Override
        public UserFollowRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            UserFollowRow row = new UserFollowRow();
            UserRepository.UserRow user = new UserRepository.UserRow();
            user.id = rs.getLong("id");
            user.firebaseUid = rs.getString("firebase_uid");
            user.handle = rs.getString("handle");
            long company = rs.getLong("company_id");
            user.companyId = rs.wasNull() ? null : company;
            user.displayName = rs.getString("display_name");
            user.bio = rs.getString("bio");
            user.isAnonymous = rs.getBoolean("is_anonymous");
            user.profileImageUrl = rs.getString("profile_image_url");
            user.createdAt = rs.getObject("user_created_at", OffsetDateTime.class);
            row.user = user;
            row.createdAt = rs.getObject("follow_created_at", OffsetDateTime.class);
            return row;
        }
    };

    public List<UserFollowRow> findFollowers(long targetUserId, long companyId, OffsetDateTime cursorTs, Long cursorUserId, int limit) {
        String base = """
                SELECT u.id, u.firebase_uid, u.handle, u.company_id, u.display_name, u.bio, u.is_anonymous, u.profile_image_url, u.created_at AS user_created_at,
                       f.created_at AS follow_created_at
                FROM follows f
                JOIN users u ON u.id = f.follower_id
                WHERE f.followee_id = ? AND u.company_id = ?
                """;
        if (cursorTs == null || cursorUserId == null) {
            return jdbc.query(base + "ORDER BY f.created_at DESC, u.id DESC LIMIT ?", MAPPER, targetUserId, companyId, limit);
        }
        return jdbc.query(base + "AND (f.created_at < ? OR (f.created_at = ? AND u.id < ?)) ORDER BY f.created_at DESC, u.id DESC LIMIT ?",
                MAPPER, targetUserId, companyId, cursorTs, cursorTs, cursorUserId, limit);
    }

    public List<UserFollowRow> findFollowing(long targetUserId, long companyId, OffsetDateTime cursorTs, Long cursorUserId, int limit) {
        String base = """
                SELECT u.id, u.firebase_uid, u.handle, u.company_id, u.display_name, u.bio, u.is_anonymous, u.profile_image_url, u.created_at AS user_created_at,
                       f.created_at AS follow_created_at
                FROM follows f
                JOIN users u ON u.id = f.followee_id
                WHERE f.follower_id = ? AND u.company_id = ?
                """;
        if (cursorTs == null || cursorUserId == null) {
            return jdbc.query(base + "ORDER BY f.created_at DESC, u.id DESC LIMIT ?", MAPPER, targetUserId, companyId, limit);
        }
        return jdbc.query(base + "AND (f.created_at < ? OR (f.created_at = ? AND u.id < ?)) ORDER BY f.created_at DESC, u.id DESC LIMIT ?",
                MAPPER, targetUserId, companyId, cursorTs, cursorTs, cursorUserId, limit);
    }

    public static class UserFollowRow {
        public UserRepository.UserRow user;
        public OffsetDateTime createdAt;
    }
}

package com.looped.users;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;

@Repository
public class UserRepository {
    private final JdbcTemplate jdbcTemplate;

    public UserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<UserRow> MAPPER = new RowMapper<>() {
        @Override
        public UserRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            UserRow row = new UserRow();
            row.id = rs.getLong("id");
            row.firebaseUid = rs.getString("firebase_uid");
            row.handle = rs.getString("handle");
            long company = rs.getLong("company_id");
            row.companyId = rs.wasNull() ? null : company;
            row.displayName = rs.getString("display_name");
            row.bio = rs.getString("bio");
            row.isAnonymous = rs.getBoolean("is_anonymous");
            row.profileImageUrl = rs.getString("profile_image_url");
            row.createdAt = rs.getObject("created_at", OffsetDateTime.class);
            return row;
        }
    };

    public Optional<UserRow> findByFirebaseUid(String firebaseUid) {
        var list = jdbcTemplate.query("SELECT id, firebase_uid, handle, company_id, display_name, bio, is_anonymous, profile_image_url, created_at FROM users WHERE firebase_uid = ?", MAPPER, firebaseUid);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public Optional<UserRow> findById(long userId) {
        var list = jdbcTemplate.query("SELECT id, firebase_uid, handle, company_id, display_name, bio, is_anonymous, profile_image_url, created_at FROM users WHERE id = ?", MAPPER, userId);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public void updateProfile(long userId, String displayName, String bio, boolean isAnonymous) {
        jdbcTemplate.update(
                "UPDATE users SET display_name = ?, bio = ?, is_anonymous = ? WHERE id = ?",
                displayName, bio, isAnonymous, userId
        );
    }

    public java.util.List<UserRow> searchCompanyUsers(long companyId, String query, java.time.OffsetDateTime cursorTs, Long cursorId, int limit) {
        String like = "%" + query.toLowerCase() + "%";
        if (cursorTs == null || cursorId == null) {
            return jdbcTemplate.query(
                    "SELECT id, firebase_uid, handle, company_id, display_name, bio, is_anonymous, profile_image_url, created_at " +
                            "FROM users WHERE company_id = ? AND (LOWER(handle) LIKE ? OR LOWER(COALESCE(display_name,'')) LIKE ?) " +
                            "ORDER BY created_at DESC, id DESC LIMIT ?",
                    MAPPER, companyId, like, like, limit
            );
        }
        return jdbcTemplate.query(
                "SELECT id, firebase_uid, handle, company_id, display_name, bio, is_anonymous, profile_image_url, created_at " +
                        "FROM users WHERE company_id = ? AND (LOWER(handle) LIKE ? OR LOWER(COALESCE(display_name,'')) LIKE ?) " +
                        "AND (created_at < ? OR (created_at = ? AND id < ?)) " +
                        "ORDER BY created_at DESC, id DESC LIMIT ?",
                MAPPER, companyId, like, like, cursorTs, cursorTs, cursorId, limit
        );
    }

    public java.util.List<UserRow> listCompanyUsers(long companyId, java.time.OffsetDateTime cursorTs, Long cursorId, int limit) {
        if (cursorTs == null || cursorId == null) {
            return jdbcTemplate.query(
                    "SELECT id, firebase_uid, handle, company_id, display_name, bio, is_anonymous, profile_image_url, created_at " +
                            "FROM users WHERE company_id = ? ORDER BY created_at DESC, id DESC LIMIT ?",
                    MAPPER, companyId, limit
            );
        }
        return jdbcTemplate.query(
                "SELECT id, firebase_uid, handle, company_id, display_name, bio, is_anonymous, profile_image_url, created_at " +
                        "FROM users WHERE company_id = ? AND (created_at < ? OR (created_at = ? AND id < ?)) " +
                        "ORDER BY created_at DESC, id DESC LIMIT ?",
                MAPPER, companyId, cursorTs, cursorTs, cursorId, limit
        );
    }

    public int countFollowers(long userId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM follows WHERE followee_id = ?",
                Integer.class, userId
        );
        return count == null ? 0 : count;
    }

    public int countFollowing(long userId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM follows WHERE follower_id = ?",
                Integer.class, userId
        );
        return count == null ? 0 : count;
    }

    public int countPosts(long userId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM posts WHERE author_id = ?",
                Integer.class, userId
        );
        return count == null ? 0 : count;
    }

    public int countComments(long userId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM comments WHERE user_id = ?",
                Integer.class, userId
        );
        return count == null ? 0 : count;
    }

    public static class UserRow {
        public Long id;
        public String firebaseUid;
        public String handle;
        public Long companyId;
        public String displayName;
        public String bio;
        public boolean isAnonymous;
        public String profileImageUrl;
        public OffsetDateTime createdAt;
    }
}

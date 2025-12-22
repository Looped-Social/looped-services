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
            row.email = rs.getString("email");
            long company = rs.getLong("company_id");
            row.companyId = rs.wasNull() ? null : company;
            row.displayName = rs.getString("display_name");
            row.bio = rs.getString("bio");
            row.isAnonymous = rs.getBoolean("is_anonymous");
            row.showFollowerCount = rs.getBoolean("show_follower_count");
            row.profileImageUrl = rs.getString("profile_image_url");
            row.createdAt = rs.getObject("created_at", OffsetDateTime.class);
            row.deletedAt = rs.getObject("deleted_at", OffsetDateTime.class);
            long deletedBy = rs.getLong("deleted_by");
            row.deletedBy = rs.wasNull() ? null : deletedBy;
            return row;
        }
    };

    public Optional<UserRow> findByFirebaseUid(String firebaseUid) {
        var list = jdbcTemplate.query(
                "SELECT id, firebase_uid, handle, email, company_id, display_name, bio, is_anonymous, show_follower_count, " +
                        "profile_image_url, created_at, deleted_at, deleted_by " +
                        "FROM users WHERE firebase_uid = ? AND deleted_at IS NULL",
                MAPPER, firebaseUid
        );
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public Optional<UserRow> findById(long userId) {
        var list = jdbcTemplate.query(
                "SELECT id, firebase_uid, handle, email, company_id, display_name, bio, is_anonymous, show_follower_count, " +
                        "profile_image_url, created_at, deleted_at, deleted_by " +
                        "FROM users WHERE id = ? AND deleted_at IS NULL",
                MAPPER, userId
        );
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public Optional<UserRow> findByFirebaseUidIncludingDeleted(String firebaseUid) {
        var list = jdbcTemplate.query(
                "SELECT id, firebase_uid, handle, email, company_id, display_name, bio, is_anonymous, show_follower_count, " +
                        "profile_image_url, created_at, deleted_at, deleted_by " +
                        "FROM users WHERE firebase_uid = ?",
                MAPPER, firebaseUid
        );
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public Optional<UserRow> findByIdIncludingDeleted(long userId) {
        var list = jdbcTemplate.query(
                "SELECT id, firebase_uid, handle, email, company_id, display_name, bio, is_anonymous, show_follower_count, " +
                        "profile_image_url, created_at, deleted_at, deleted_by " +
                        "FROM users WHERE id = ?",
                MAPPER, userId
        );
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public void updateProfile(long userId, String displayName, String bio, boolean isAnonymous, Boolean showFollowerCount) {
        jdbcTemplate.update(
                "UPDATE users SET display_name = ?, bio = ?, is_anonymous = ?, " +
                        "show_follower_count = COALESCE(?, show_follower_count) WHERE id = ?",
                displayName, bio, isAnonymous, showFollowerCount, userId
        );
    }

    public java.util.List<UserRow> searchCompanyUsers(long companyId, String query, java.time.OffsetDateTime cursorTs, Long cursorId, int limit) {
        String like = "%" + query.toLowerCase() + "%";
        if (cursorTs == null || cursorId == null) {
            return jdbcTemplate.query(
                    "SELECT id, firebase_uid, handle, email, company_id, display_name, bio, is_anonymous, show_follower_count, " +
                            "profile_image_url, created_at, deleted_at, deleted_by " +
                            "FROM users WHERE company_id = ? AND deleted_at IS NULL AND (LOWER(handle) LIKE ? OR LOWER(COALESCE(display_name,'')) LIKE ?) " +
                            "ORDER BY created_at DESC, id DESC LIMIT ?",
                    MAPPER, companyId, like, like, limit
            );
        }
        return jdbcTemplate.query(
                "SELECT id, firebase_uid, handle, email, company_id, display_name, bio, is_anonymous, show_follower_count, " +
                        "profile_image_url, created_at, deleted_at, deleted_by " +
                        "FROM users WHERE company_id = ? AND deleted_at IS NULL AND (LOWER(handle) LIKE ? OR LOWER(COALESCE(display_name,'')) LIKE ?) " +
                        "AND (created_at < ? OR (created_at = ? AND id < ?)) " +
                        "ORDER BY created_at DESC, id DESC LIMIT ?",
                MAPPER, companyId, like, like, cursorTs, cursorTs, cursorId, limit
        );
    }

    public java.util.List<UserRow> listCompanyUsers(long companyId, java.time.OffsetDateTime cursorTs, Long cursorId, int limit) {
        if (cursorTs == null || cursorId == null) {
            return jdbcTemplate.query(
                    "SELECT id, firebase_uid, handle, email, company_id, display_name, bio, is_anonymous, show_follower_count, " +
                            "profile_image_url, created_at, deleted_at, deleted_by " +
                            "FROM users WHERE company_id = ? AND deleted_at IS NULL ORDER BY created_at DESC, id DESC LIMIT ?",
                    MAPPER, companyId, limit
            );
        }
        return jdbcTemplate.query(
                "SELECT id, firebase_uid, handle, email, company_id, display_name, bio, is_anonymous, show_follower_count, " +
                        "profile_image_url, created_at, deleted_at, deleted_by " +
                        "FROM users WHERE company_id = ? AND deleted_at IS NULL AND (created_at < ? OR (created_at = ? AND id < ?)) " +
                        "ORDER BY created_at DESC, id DESC LIMIT ?",
                MAPPER, companyId, cursorTs, cursorTs, cursorId, limit
        );
    }

    public boolean softDelete(long userId, Long deletedBy) {
        int rows = jdbcTemplate.update(
                "UPDATE users SET deleted_at = now(), deleted_by = ? WHERE id = ? AND deleted_at IS NULL",
                deletedBy, userId
        );
        return rows > 0;
    }

    public void updateEmail(long userId, String email) {
        String normalized = normalizeEmail(email);
        if (normalized == null) return;
        jdbcTemplate.update("UPDATE users SET email = ? WHERE id = ?", normalized, userId);
    }

    public java.util.List<UserRow> searchAll(String query, java.time.OffsetDateTime cursorTs, Long cursorId, int limit) {
        String q = query == null ? "" : query.trim().toLowerCase();
        boolean hasQuery = !q.isBlank();
        String like = "%" + q + "%";
        Long idQuery = null;
        if (hasQuery) {
            try {
                idQuery = Long.parseLong(q);
            } catch (NumberFormatException ignored) {}
        }
        if (!hasQuery) {
            if (cursorTs == null || cursorId == null) {
                return jdbcTemplate.query(
                        "SELECT id, firebase_uid, handle, email, company_id, display_name, bio, is_anonymous, show_follower_count, " +
                                "profile_image_url, created_at, deleted_at, deleted_by " +
                                "FROM users WHERE deleted_at IS NULL ORDER BY created_at DESC, id DESC LIMIT ?",
                        MAPPER, limit
                );
            }
            return jdbcTemplate.query(
                    "SELECT id, firebase_uid, handle, email, company_id, display_name, bio, is_anonymous, show_follower_count, " +
                            "profile_image_url, created_at, deleted_at, deleted_by " +
                            "FROM users WHERE deleted_at IS NULL AND (created_at < ? OR (created_at = ? AND id < ?)) " +
                            "ORDER BY created_at DESC, id DESC LIMIT ?",
                    MAPPER, cursorTs, cursorTs, cursorId, limit
            );
        }
        boolean hasId = idQuery != null;
        String filter = hasId
                ? "(LOWER(handle) LIKE ? OR LOWER(COALESCE(display_name,'')) LIKE ? OR LOWER(COALESCE(email,'')) LIKE ? " +
                "OR LOWER(firebase_uid) LIKE ? OR id = ?)"
                : "(LOWER(handle) LIKE ? OR LOWER(COALESCE(display_name,'')) LIKE ? OR LOWER(COALESCE(email,'')) LIKE ? " +
                "OR LOWER(firebase_uid) LIKE ?)";
        if (cursorTs == null || cursorId == null) {
            Object[] args = hasId
                    ? new Object[]{like, like, like, like, idQuery, limit}
                    : new Object[]{like, like, like, like, limit};
            return jdbcTemplate.query(
                    "SELECT id, firebase_uid, handle, email, company_id, display_name, bio, is_anonymous, show_follower_count, " +
                            "profile_image_url, created_at, deleted_at, deleted_by " +
                            "FROM users WHERE deleted_at IS NULL AND " + filter +
                            " ORDER BY created_at DESC, id DESC LIMIT ?",
                    args, MAPPER
            );
        }
        Object[] args = hasId
                ? new Object[]{like, like, like, like, idQuery, cursorTs, cursorTs, cursorId, limit}
                : new Object[]{like, like, like, like, cursorTs, cursorTs, cursorId, limit};
        return jdbcTemplate.query(
                "SELECT id, firebase_uid, handle, email, company_id, display_name, bio, is_anonymous, show_follower_count, " +
                        "profile_image_url, created_at, deleted_at, deleted_by " +
                        "FROM users WHERE deleted_at IS NULL AND " + filter +
                        " AND (created_at < ? OR (created_at = ? AND id < ?)) " +
                        "ORDER BY created_at DESC, id DESC LIMIT ?",
                args, MAPPER
        );
    }

    private String normalizeEmail(String email) {
        if (email == null) return null;
        String trimmed = email.trim();
        if (trimmed.isBlank()) return null;
        return trimmed.toLowerCase(java.util.Locale.ROOT);
    }

    public boolean hardDelete(long userId) {
        int rows = jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        return rows > 0;
    }

    public int countFollowers(long userId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM principal_follows f " +
                        "JOIN principals target ON target.id = f.followee_principal_id " +
                        "JOIN principals follower ON follower.id = f.follower_principal_id " +
                        "LEFT JOIN users u ON u.id = follower.user_id AND u.deleted_at IS NULL " +
                        "WHERE target.user_id = ? AND (follower.kind = 'anon' OR u.id IS NOT NULL)",
                Integer.class, userId
        );
        return count == null ? 0 : count;
    }

    public int countFollowing(long userId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM principal_follows f " +
                        "JOIN principals target ON target.id = f.follower_principal_id " +
                        "JOIN principals followee ON followee.id = f.followee_principal_id " +
                        "LEFT JOIN users u ON u.id = followee.user_id AND u.deleted_at IS NULL " +
                        "WHERE target.user_id = ? AND (followee.kind = 'anon' OR u.id IS NOT NULL)",
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
        public String email;
        public Long companyId;
        public String displayName;
        public String bio;
        public boolean isAnonymous;
        public boolean showFollowerCount;
        public String profileImageUrl;
        public OffsetDateTime createdAt;
        public OffsetDateTime deletedAt;
        public Long deletedBy;
    }
}

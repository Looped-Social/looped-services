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
    private static final String BASE_COLUMNS = "id, firebase_uid, handle, email, company_id, first_name, last_name, " +
            "date_of_birth, display_name, bio, is_anonymous, show_follower_count, profile_image_url, created_at, deleted_at, deleted_by";

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
            row.firstName = rs.getString("first_name");
            row.lastName = rs.getString("last_name");
            row.dateOfBirth = rs.getObject("date_of_birth", java.time.LocalDate.class);
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
                "SELECT " + BASE_COLUMNS + " FROM users WHERE firebase_uid = ? AND deleted_at IS NULL",
                MAPPER, firebaseUid
        );
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public Optional<UserRow> findById(long userId) {
        var list = jdbcTemplate.query(
                "SELECT " + BASE_COLUMNS + " FROM users WHERE id = ? AND deleted_at IS NULL",
                MAPPER, userId
        );
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public Optional<UserRow> findByFirebaseUidIncludingDeleted(String firebaseUid) {
        var list = jdbcTemplate.query(
                "SELECT " + BASE_COLUMNS + " FROM users WHERE firebase_uid = ?",
                MAPPER, firebaseUid
        );
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public Optional<UserRow> findByIdIncludingDeleted(long userId) {
        var list = jdbcTemplate.query(
                "SELECT " + BASE_COLUMNS + " FROM users WHERE id = ?",
                MAPPER, userId
        );
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public Optional<UserRow> findByHandle(String handle) {
        if (handle == null || handle.isBlank()) return Optional.empty();
        var list = jdbcTemplate.query(
                "SELECT " + BASE_COLUMNS + " FROM users WHERE LOWER(handle) = LOWER(?) AND deleted_at IS NULL",
                MAPPER, handle
        );
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public java.util.List<UserRow> findByHandlesInCompany(long companyId, java.util.Set<String> handles) {
        if (handles == null || handles.isEmpty()) return java.util.List.of();
        var normalized = handles.stream()
                .filter(h -> h != null && !h.isBlank())
                .map(h -> h.toLowerCase(java.util.Locale.ROOT))
                .distinct()
                .toList();
        if (normalized.isEmpty()) return java.util.List.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(normalized.size(), "?"));
        Object[] params = new Object[normalized.size() + 1];
        params[0] = companyId;
        for (int i = 0; i < normalized.size(); i++) {
            params[i + 1] = normalized.get(i);
        }
        return jdbcTemplate.query(
                "SELECT " + BASE_COLUMNS + " FROM users WHERE company_id = ? AND deleted_at IS NULL AND LOWER(handle) IN (" + placeholders + ")",
                MAPPER, params
        );
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
                    "SELECT " + BASE_COLUMNS + " FROM users WHERE company_id = ? AND deleted_at IS NULL AND (LOWER(handle) LIKE ? OR LOWER(COALESCE(display_name,'')) LIKE ?) " +
                            "ORDER BY created_at DESC, id DESC LIMIT ?",
                    MAPPER, companyId, like, like, limit
            );
        }
        return jdbcTemplate.query(
                "SELECT " + BASE_COLUMNS + " FROM users WHERE company_id = ? AND deleted_at IS NULL AND (LOWER(handle) LIKE ? OR LOWER(COALESCE(display_name,'')) LIKE ?) " +
                        "AND (created_at < ? OR (created_at = ? AND id < ?)) " +
                        "ORDER BY created_at DESC, id DESC LIMIT ?",
                MAPPER, companyId, like, like, cursorTs, cursorTs, cursorId, limit
        );
    }

    public java.util.List<UserRow> listCompanyUsers(long companyId, java.time.OffsetDateTime cursorTs, Long cursorId, int limit) {
        if (cursorTs == null || cursorId == null) {
            return jdbcTemplate.query(
                    "SELECT " + BASE_COLUMNS + " FROM users WHERE company_id = ? AND deleted_at IS NULL ORDER BY created_at DESC, id DESC LIMIT ?",
                    MAPPER, companyId, limit
            );
        }
        return jdbcTemplate.query(
                "SELECT " + BASE_COLUMNS + " FROM users WHERE company_id = ? AND deleted_at IS NULL AND (created_at < ? OR (created_at = ? AND id < ?)) " +
                        "ORDER BY created_at DESC, id DESC LIMIT ?",
                MAPPER, companyId, cursorTs, cursorTs, cursorId, limit
        );
    }

    public java.util.List<Long> listActiveUserIdsByCompany(long companyId) {
        return jdbcTemplate.query(
                "SELECT id FROM users WHERE company_id = ? AND deleted_at IS NULL",
                (rs, rowNum) -> rs.getLong("id"),
                companyId
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

    public long insert(String firebaseUid, String handle, String email, long companyId,
                       String firstName, String lastName, java.time.LocalDate dateOfBirth) {
        Long id = jdbcTemplate.query(
                "INSERT INTO users(firebase_uid, handle, email, company_id, first_name, last_name, date_of_birth) " +
                        "VALUES (?,?,?,?,?,?,?) RETURNING id",
                rs -> rs.next() ? rs.getLong(1) : null,
                firebaseUid, handle, normalizeEmail(email), companyId, firstName, lastName, dateOfBirth
        );
        if (id == null) {
            throw new IllegalStateException("Failed to insert user");
        }
        return id;
    }

    public boolean isHandleAvailable(String handle) {
        if (handle == null || handle.isBlank()) return false;
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE LOWER(handle) = LOWER(?)",
                Integer.class,
                handle
        );
        if (count != null && count > 0) return false;
        Integer tombstones = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_tombstones WHERE LOWER(handle) = LOWER(?)",
                Integer.class,
                handle
        );
        return tombstones != null && tombstones == 0;
    }

    public boolean isEmailAvailable(String email) {
        String normalized = normalizeEmail(email);
        if (normalized == null) return false;
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE LOWER(email) = LOWER(?)",
                Integer.class,
                normalized
        );
        if (count != null && count > 0) return false;
        Integer tombstones = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_tombstones WHERE LOWER(email) = LOWER(?)",
                Integer.class,
                normalized
        );
        return tombstones != null && tombstones == 0;
    }

    public boolean isEmailAvailableForUser(long userId, String email) {
        String normalized = normalizeEmail(email);
        if (normalized == null) return false;
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE LOWER(email) = LOWER(?) AND id <> ?",
                Integer.class,
                normalized,
                userId
        );
        if (count != null && count > 0) return false;
        Integer tombstones = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_tombstones WHERE LOWER(email) = LOWER(?)",
                Integer.class,
                normalized
        );
        return tombstones != null && tombstones == 0;
    }

    public boolean isFirebaseUidTombstoned(String firebaseUid) {
        if (firebaseUid == null || firebaseUid.isBlank()) return false;
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_tombstones WHERE firebase_uid = ?",
                Integer.class,
                firebaseUid
        );
        return count != null && count > 0;
    }

    public boolean reactivate(long userId) {
        int rows = jdbcTemplate.update(
                "UPDATE users SET deleted_at = NULL, deleted_by = NULL WHERE id = ? AND deleted_at IS NOT NULL",
                userId
        );
        return rows > 0;
    }

    public java.util.List<UserRow> deleteSoftDeletedBefore(OffsetDateTime cutoff, int limit) {
        return jdbcTemplate.query(
                "DELETE FROM users WHERE id IN (" +
                        "SELECT id FROM users WHERE deleted_at IS NOT NULL AND deleted_at < ? " +
                        "ORDER BY deleted_at ASC, id ASC LIMIT ?" +
                        ") RETURNING " + BASE_COLUMNS,
                MAPPER, cutoff, limit
        );
    }

    public Optional<UserRow> deleteByFirebaseUidIfDeletedBefore(String firebaseUid, OffsetDateTime cutoff) {
        if (firebaseUid == null || firebaseUid.isBlank()) return Optional.empty();
        var list = jdbcTemplate.query(
                "DELETE FROM users WHERE firebase_uid = ? AND deleted_at IS NOT NULL AND deleted_at < ? " +
                        "RETURNING " + BASE_COLUMNS,
                MAPPER, firebaseUid, cutoff
        );
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public Optional<UserRow> deleteById(long userId) {
        var list = jdbcTemplate.query(
                "DELETE FROM users WHERE id = ? RETURNING " + BASE_COLUMNS,
                MAPPER, userId
        );
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public void insertTombstone(UserRow user) {
        if (user == null || user.firebaseUid == null || user.firebaseUid.isBlank()) return;
        jdbcTemplate.update(
                "INSERT INTO user_tombstones(firebase_uid, handle, email) VALUES (?,?,?) ON CONFLICT DO NOTHING",
                user.firebaseUid,
                user.handle,
                normalizeEmail(user.email)
        );
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
                        "SELECT " + BASE_COLUMNS + " FROM users WHERE deleted_at IS NULL ORDER BY created_at DESC, id DESC LIMIT ?",
                        MAPPER, limit
                );
            }
            return jdbcTemplate.query(
                    "SELECT " + BASE_COLUMNS + " FROM users WHERE deleted_at IS NULL AND (created_at < ? OR (created_at = ? AND id < ?)) " +
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
                    "SELECT " + BASE_COLUMNS + " FROM users WHERE deleted_at IS NULL AND " + filter +
                            " ORDER BY created_at DESC, id DESC LIMIT ?",
                    args, MAPPER
            );
        }
        Object[] args = hasId
                ? new Object[]{like, like, like, like, idQuery, cursorTs, cursorTs, cursorId, limit}
                : new Object[]{like, like, like, like, cursorTs, cursorTs, cursorId, limit};
        return jdbcTemplate.query(
                "SELECT " + BASE_COLUMNS + " FROM users WHERE deleted_at IS NULL AND " + filter +
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
        public String firstName;
        public String lastName;
        public java.time.LocalDate dateOfBirth;
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

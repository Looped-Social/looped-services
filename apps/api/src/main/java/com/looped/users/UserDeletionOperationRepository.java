package com.looped.users;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class UserDeletionOperationRepository {
    private final JdbcTemplate jdbc;

    public UserDeletionOperationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final org.springframework.jdbc.core.RowMapper<OperationRow> MAPPER = new org.springframework.jdbc.core.RowMapper<>() {
        @Override
        public OperationRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            OperationRow row = new OperationRow();
            row.id = rs.getLong("id");
            row.operationId = rs.getObject("operation_id", UUID.class);
            row.firebaseUid = rs.getString("firebase_uid");
            long userId = rs.getLong("user_id");
            row.userId = rs.wasNull() ? null : userId;
            row.requestedEmail = rs.getString("requested_email");
            row.mode = rs.getString("mode");
            row.state = rs.getString("state");
            row.errorCode = rs.getString("error_code");
            row.errorMessage = rs.getString("error_message");
            row.requestedAt = rs.getObject("requested_at", OffsetDateTime.class);
            row.updatedAt = rs.getObject("updated_at", OffsetDateTime.class);
            row.completedAt = rs.getObject("completed_at", OffsetDateTime.class);
            return row;
        }
    };

    public UUID create(String firebaseUid, Long userId, String requestedEmail, String mode) {
        if (firebaseUid == null || firebaseUid.isBlank()) {
            throw new IllegalArgumentException("firebaseUid is required");
        }
        UUID operationId = UUID.randomUUID();
        String normalizedMode = normalizeMode(mode);
        String normalizedEmail = normalizeEmail(requestedEmail);
        jdbc.update(
                "INSERT INTO user_deletion_operations(" +
                        "operation_id, firebase_uid, user_id, requested_email, mode, state, requested_at, updated_at" +
                        ") VALUES (?,?,?,?,?,'in_progress', now(), now())",
                operationId, firebaseUid, userId, normalizedEmail, normalizedMode
        );
        return operationId;
    }

    public Optional<OperationRow> latestByFirebaseUid(String firebaseUid) {
        if (firebaseUid == null || firebaseUid.isBlank()) return Optional.empty();
        List<OperationRow> rows = jdbc.query(
                "SELECT id, operation_id, firebase_uid, user_id, requested_email, mode, state, " +
                        "error_code, error_message, requested_at, updated_at, completed_at " +
                        "FROM user_deletion_operations " +
                        "WHERE firebase_uid = ? " +
                        "ORDER BY requested_at DESC, id DESC LIMIT 1",
                MAPPER, firebaseUid
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public boolean existsActiveByFirebaseUidOrEmail(String firebaseUid, String email) {
        String normalizedUid = firebaseUid == null ? null : firebaseUid.trim();
        String normalizedEmail = normalizeEmail(email);
        if ((normalizedUid == null || normalizedUid.isBlank()) && normalizedEmail == null) {
            return false;
        }
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS (" +
                        " SELECT 1 FROM user_deletion_operations " +
                        " WHERE state IN ('in_progress', 'pending') " +
                        "   AND (" +
                        "         (? IS NOT NULL AND ? <> '' AND firebase_uid = ?) " +
                        "      OR (? IS NOT NULL AND requested_email IS NOT NULL AND LOWER(requested_email) = LOWER(?))" +
                        "   )" +
                        ")",
                Boolean.class,
                normalizedUid, normalizedUid, normalizedUid,
                normalizedEmail, normalizedEmail
        );
        return Boolean.TRUE.equals(exists);
    }

    public void markPending(UUID operationId, String errorCode, String errorMessage) {
        if (operationId == null) return;
        jdbc.update(
                "UPDATE user_deletion_operations SET " +
                        "state = 'pending', " +
                        "error_code = COALESCE(?, error_code), " +
                        "error_message = COALESCE(?, error_message), " +
                        "updated_at = now() " +
                        "WHERE operation_id = ?",
                normalizeCode(errorCode), trimToNull(errorMessage), operationId
        );
    }

    public void markFailed(UUID operationId, String errorCode, String errorMessage) {
        if (operationId == null) return;
        jdbc.update(
                "UPDATE user_deletion_operations SET " +
                        "state = 'failed', " +
                        "error_code = COALESCE(?, error_code), " +
                        "error_message = COALESCE(?, error_message), " +
                        "updated_at = now() " +
                        "WHERE operation_id = ?",
                normalizeCode(errorCode), trimToNull(errorMessage), operationId
        );
    }

    public void markCompleted(UUID operationId) {
        if (operationId == null) return;
        jdbc.update(
                "UPDATE user_deletion_operations SET " +
                        "state = 'completed', " +
                        "error_code = NULL, " +
                        "error_message = NULL, " +
                        "updated_at = now(), " +
                        "completed_at = COALESCE(completed_at, now()) " +
                        "WHERE operation_id = ?",
                operationId
        );
    }

    public int markCompletedByFirebaseUid(String firebaseUid) {
        if (firebaseUid == null || firebaseUid.isBlank()) return 0;
        return jdbc.update(
                "UPDATE user_deletion_operations SET " +
                        "state = 'completed', " +
                        "error_code = NULL, " +
                        "error_message = NULL, " +
                        "updated_at = now(), " +
                        "completed_at = COALESCE(completed_at, now()) " +
                        "WHERE firebase_uid = ? AND state IN ('in_progress', 'pending')",
                firebaseUid
        );
    }

    private String normalizeMode(String mode) {
        if (mode == null || mode.isBlank()) return "hard";
        String normalized = mode.trim().toLowerCase(java.util.Locale.ROOT);
        if (!normalized.equals("hard") && !normalized.equals("soft")) return "hard";
        return normalized;
    }

    private String normalizeEmail(String email) {
        if (email == null || email.isBlank()) return null;
        return email.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private String normalizeCode(String code) {
        if (code == null || code.isBlank()) return null;
        return code.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public static class OperationRow {
        public long id;
        public UUID operationId;
        public String firebaseUid;
        public Long userId;
        public String requestedEmail;
        public String mode;
        public String state;
        public String errorCode;
        public String errorMessage;
        public OffsetDateTime requestedAt;
        public OffsetDateTime updatedAt;
        public OffsetDateTime completedAt;
    }
}

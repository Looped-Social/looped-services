package com.looped.verification;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Repository
public class VerificationRequestsRepository {
    private final JdbcTemplate jdbc;

    public VerificationRequestsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Row> MAPPER = new RowMapper<>() {
        @Override
        public Row mapRow(ResultSet rs, int rowNum) throws SQLException {
            Row row = new Row();
            row.id = rs.getLong("id");
            row.userId = rs.getLong("user_id");
            row.userHandle = rs.getString("user_handle");
            row.userDisplayName = rs.getString("user_display_name");
            row.email = rs.getString("email");
            row.method = rs.getString("method");
            row.status = rs.getString("status");
            row.mediaKey = rs.getString("media_key");
            row.selfieKey = rs.getString("selfie_key");
            row.idFrontKey = rs.getString("id_front_key");
            row.idBackKey = rs.getString("id_back_key");
            row.metadata = rs.getString("metadata");
            row.submittedAt = rs.getObject("submitted_at", OffsetDateTime.class);
            row.reviewedAt = rs.getObject("reviewed_at", OffsetDateTime.class);
            long reviewedBy = rs.getLong("reviewed_by");
            row.reviewedBy = rs.wasNull() ? null : reviewedBy;
            row.rejectReason = rs.getString("reject_reason");
            row.deleteAfterAt = rs.getObject("delete_after_at", OffsetDateTime.class);
            row.mediaDeletedAt = rs.getObject("media_deleted_at", OffsetDateTime.class);
            row.companyDomain = rs.getString("company_domain");
            long communityId = rs.getLong("community_id");
            row.communityId = rs.wasNull() ? null : communityId;
            row.communityName = rs.getString("community_name");
            row.communityKind = rs.getString("community_kind");
            return row;
        }
    };

    public long insert(long userId, String email, String method, String status, String mediaKey, String metadata) {
        return insert(userId, null, email, method, status, mediaKey, metadata);
    }

    public long insert(long userId, Long communityId, String email, String method, String status, String mediaKey, String metadata) {
        Long id = jdbc.query(
                "INSERT INTO verification_requests(user_id, community_id, email, method, status, media_key, metadata) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id",
                rs -> rs.next() ? rs.getLong(1) : null,
                userId, communityId, normalizeEmail(email), method, status, mediaKey, metadata
        );
        if (id == null) {
            throw new IllegalStateException("Failed to insert verification request");
        }
        return id;
    }

    public Optional<Row> findById(long id) {
        var list = jdbc.query(
                "SELECT vr.*, u.handle AS user_handle, u.display_name AS user_display_name, c.domain AS company_domain, cm.name AS community_name, cm.kind AS community_kind " +
                        "FROM verification_requests vr " +
                        "JOIN users u ON u.id = vr.user_id " +
                        "JOIN companies c ON c.id = u.company_id " +
                        "LEFT JOIN communities cm ON cm.id = vr.community_id " +
                        "WHERE vr.id = ? LIMIT 1",
                MAPPER, id
        );
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public boolean updateStatus(long id, String status, Long reviewedBy, String rejectReason) {
        int rows = jdbc.update(
                "UPDATE verification_requests SET status = ?, reviewed_at = now(), reviewed_by = ?, reject_reason = ? " +
                        "WHERE id = ?",
                status, reviewedBy, rejectReason, id
        );
        return rows > 0;
    }

    public boolean updateStatusWithDeleteAfter(long id, String status, Long reviewedBy, String rejectReason, OffsetDateTime deleteAfterAt) {
        int rows = jdbc.update(
                "UPDATE verification_requests SET status = ?, reviewed_at = now(), reviewed_by = ?, reject_reason = ?, delete_after_at = ? " +
                        "WHERE id = ?",
                status, reviewedBy, rejectReason, deleteAfterAt, id
        );
        return rows > 0;
    }

    public boolean markMediaDeleted(long id) {
        int rows = jdbc.update(
                "UPDATE verification_requests SET media_deleted_at = now() WHERE id = ?",
                id
        );
        return rows > 0;
    }

    public List<Row> listForAdmin(String status, OffsetDateTime cursorTs, Long cursorId, int limit) {
        return listForAdmin(status, null, cursorTs, cursorId, limit);
    }

    public List<Row> listForAdmin(String status, String method, OffsetDateTime cursorTs, Long cursorId, int limit) {
        String base = "SELECT vr.*, u.handle AS user_handle, u.display_name AS user_display_name, c.domain AS company_domain, cm.name AS community_name, cm.kind AS community_kind " +
                "FROM verification_requests vr " +
                "JOIN users u ON u.id = vr.user_id " +
                "JOIN companies c ON c.id = u.company_id " +
                "LEFT JOIN communities cm ON cm.id = vr.community_id ";
        String where = "";
        Object[] params;
        boolean hasStatus = status != null && !status.isBlank();
        boolean hasMethod = method != null && !method.isBlank();
        if (hasStatus && hasMethod) {
            where = "WHERE vr.status = ? AND vr.method = ? ";
        } else if (hasStatus) {
            where = "WHERE vr.status = ? ";
        } else if (hasMethod) {
            where = "WHERE vr.method = ? ";
        }
        if (cursorTs == null || cursorId == null) {
            if (hasStatus && hasMethod) params = new Object[]{status, method, limit};
            else if (hasStatus) params = new Object[]{status, limit};
            else if (hasMethod) params = new Object[]{method, limit};
            else params = new Object[]{limit};
            return jdbc.query(
                    base + where + "ORDER BY vr.submitted_at DESC, vr.id DESC LIMIT ?",
                    params, MAPPER
            );
        }
        String cursorClause = "AND (vr.submitted_at < ? OR (vr.submitted_at = ? AND vr.id < ?)) ";
        if (where.isBlank()) {
            where = "WHERE ";
            cursorClause = "(vr.submitted_at < ? OR (vr.submitted_at = ? AND vr.id < ?)) ";
        }
        if (hasStatus && hasMethod) params = new Object[]{status, method, cursorTs, cursorTs, cursorId, limit};
        else if (hasStatus) params = new Object[]{status, cursorTs, cursorTs, cursorId, limit};
        else if (hasMethod) params = new Object[]{method, cursorTs, cursorTs, cursorId, limit};
        else params = new Object[]{cursorTs, cursorTs, cursorId, limit};
        return jdbc.query(
                base + where + cursorClause + "ORDER BY vr.submitted_at DESC, vr.id DESC LIMIT ?",
                params, MAPPER
        );
    }

    public Optional<Row> findLatestForUserAndMethod(long userId, String method) {
        return findLatestForUserAndMethodAndCommunityId(userId, method, null);
    }

    public Optional<Row> findLatestForUserAndMethodAndCommunityId(long userId, String method, Long communityId) {
        String communityClause = (communityId == null) ? "vr.community_id IS NULL" : "vr.community_id = ?";
        String sql = "SELECT vr.*, u.handle AS user_handle, u.display_name AS user_display_name, c.domain AS company_domain, cm.name AS community_name, cm.kind AS community_kind " +
                "FROM verification_requests vr " +
                "JOIN users u ON u.id = vr.user_id " +
                "JOIN companies c ON c.id = u.company_id " +
                "LEFT JOIN communities cm ON cm.id = vr.community_id " +
                "WHERE vr.user_id = ? AND vr.method = ? AND " + communityClause + " " +
                "ORDER BY vr.submitted_at DESC, vr.id DESC " +
                "LIMIT 1";
        var list = (communityId == null)
                ? jdbc.query(sql, MAPPER, userId, method)
                : jdbc.query(sql, MAPPER, userId, method, communityId);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public boolean existsPendingForUserAndMethod(long userId, String method) {
        return existsPendingForUserAndMethodAndCommunityId(userId, method, null);
    }

    public boolean existsPendingForUserAndMethodAndCommunityId(long userId, String method, Long communityId) {
        String communityClause = (communityId == null) ? "community_id IS NULL" : "community_id = ?";
        String sql = "SELECT 1 FROM verification_requests WHERE user_id = ? AND method = ? AND " + communityClause + " AND status = 'pending' LIMIT 1";
        Integer n = (communityId == null)
                ? jdbc.query(sql, rs -> rs.next() ? 1 : null, userId, method)
                : jdbc.query(sql, rs -> rs.next() ? 1 : null, userId, method, communityId);
        return n != null;
    }

    public long insertPhotoId(long userId, String email, String status, String selfieKey, String idFrontKey, String idBackKey, String metadata) {
        return insertPhotoId(userId, null, email, status, selfieKey, idFrontKey, idBackKey, metadata);
    }

    public long insertPhotoId(long userId, Long communityId, String email, String status, String selfieKey, String idFrontKey, String idBackKey, String metadata) {
        Long id = jdbc.query(
                "INSERT INTO verification_requests(user_id, community_id, email, method, status, selfie_key, id_front_key, id_back_key, metadata) " +
                        "VALUES (?, ?, ?, 'photo_id', ?, ?, ?, ?, ?) RETURNING id",
                rs -> rs.next() ? rs.getLong(1) : null,
                userId, communityId, normalizeEmail(email), status, selfieKey, idFrontKey, idBackKey, metadata
        );
        if (id == null) {
            throw new IllegalStateException("Failed to insert verification request");
        }
        return id;
    }

    public List<Row> listDuePhotoIdDeletes(int limit) {
        return jdbc.query(
                "SELECT vr.*, u.handle AS user_handle, u.display_name AS user_display_name, c.domain AS company_domain, cm.name AS community_name, cm.kind AS community_kind " +
                        "FROM verification_requests vr " +
                        "JOIN users u ON u.id = vr.user_id " +
                        "JOIN companies c ON c.id = u.company_id " +
                        "LEFT JOIN communities cm ON cm.id = vr.community_id " +
                        "WHERE vr.method = 'photo_id' AND vr.status = 'rejected' AND vr.media_deleted_at IS NULL " +
                        "AND vr.delete_after_at IS NOT NULL AND vr.delete_after_at <= now() " +
                        "ORDER BY vr.delete_after_at ASC, vr.id ASC " +
                        "LIMIT ?",
                MAPPER, limit
        );
    }

    private String normalizeEmail(String email) {
        if (email == null) return null;
        String trimmed = email.trim();
        if (trimmed.isBlank()) return null;
        return trimmed.toLowerCase(Locale.ROOT);
    }

    public static class Row {
        public long id;
        public long userId;
        public String userHandle;
        public String userDisplayName;
        public String email;
        public String method;
        public String status;
        public String mediaKey;
        public String selfieKey;
        public String idFrontKey;
        public String idBackKey;
        public String metadata;
        public OffsetDateTime submittedAt;
        public OffsetDateTime reviewedAt;
        public Long reviewedBy;
        public String rejectReason;
        public OffsetDateTime deleteAfterAt;
        public OffsetDateTime mediaDeletedAt;
        public String companyDomain;
        public Long communityId;
        public String communityName;
        public String communityKind;
    }
}

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
            row.email = rs.getString("email");
            row.method = rs.getString("method");
            row.status = rs.getString("status");
            row.mediaKey = rs.getString("media_key");
            row.metadata = rs.getString("metadata");
            row.submittedAt = rs.getObject("submitted_at", OffsetDateTime.class);
            row.reviewedAt = rs.getObject("reviewed_at", OffsetDateTime.class);
            long reviewedBy = rs.getLong("reviewed_by");
            row.reviewedBy = rs.wasNull() ? null : reviewedBy;
            row.rejectReason = rs.getString("reject_reason");
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
                "SELECT vr.*, c.domain AS company_domain, cm.name AS community_name, cm.kind AS community_kind " +
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

    public List<Row> listForAdmin(String status, OffsetDateTime cursorTs, Long cursorId, int limit) {
        String base = "SELECT vr.*, c.domain AS company_domain, cm.name AS community_name, cm.kind AS community_kind " +
                "FROM verification_requests vr " +
                "JOIN users u ON u.id = vr.user_id " +
                "JOIN companies c ON c.id = u.company_id " +
                "LEFT JOIN communities cm ON cm.id = vr.community_id ";
        String where = "";
        Object[] params;
        if (status != null && !status.isBlank()) {
            where = "WHERE vr.status = ? ";
        }
        if (cursorTs == null || cursorId == null) {
            params = (status != null && !status.isBlank())
                    ? new Object[]{status, limit}
                    : new Object[]{limit};
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
        if (status != null && !status.isBlank()) {
            params = new Object[]{status, cursorTs, cursorTs, cursorId, limit};
        } else {
            params = new Object[]{cursorTs, cursorTs, cursorId, limit};
        }
        return jdbc.query(
                base + where + cursorClause + "ORDER BY vr.submitted_at DESC, vr.id DESC LIMIT ?",
                params, MAPPER
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
        public String email;
        public String method;
        public String status;
        public String mediaKey;
        public String metadata;
        public OffsetDateTime submittedAt;
        public OffsetDateTime reviewedAt;
        public Long reviewedBy;
        public String rejectReason;
        public String companyDomain;
        public Long communityId;
        public String communityName;
        public String communityKind;
    }
}

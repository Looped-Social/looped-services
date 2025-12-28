package com.looped.communities;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class CommunityRequestsRepository {
    private final JdbcTemplate jdbc;

    public CommunityRequestsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Row> MAPPER = new RowMapper<>() {
        @Override
        public Row mapRow(ResultSet rs, int rowNum) throws SQLException {
            Row row = new Row();
            row.id = rs.getLong("id");
            row.userId = rs.getLong("user_id");
            row.kind = rs.getString("kind");
            row.name = rs.getString("name");
            row.description = rs.getString("description");
            row.imageKey = rs.getString("image_key");
            row.status = rs.getString("status");
            row.createdAt = rs.getObject("created_at", OffsetDateTime.class);
            row.reviewedAt = rs.getObject("reviewed_at", OffsetDateTime.class);
            long reviewedBy = rs.getLong("reviewed_by");
            row.reviewedBy = rs.wasNull() ? null : reviewedBy;
            row.rejectReason = rs.getString("reject_reason");
            long communityId = rs.getLong("community_id");
            row.communityId = rs.wasNull() ? null : communityId;
            row.userHandle = rs.getString("user_handle");
            row.userEmail = rs.getString("user_email");
            return row;
        }
    };

    public long insert(long userId, String kind, String name, String description, String imageKey) {
        Long id = jdbc.query(
                "INSERT INTO community_requests(user_id, kind, name, description, image_key) VALUES (?,?,?,?,?) RETURNING id",
                rs -> rs.next() ? rs.getLong(1) : null,
                userId, kind, name, description, imageKey
        );
        if (id == null) {
            throw new IllegalStateException("Failed to insert community request");
        }
        return id;
    }

    public Optional<Row> findById(long id) {
        var list = jdbc.query(
                baseSelect() + "WHERE cr.id = ? LIMIT 1",
                MAPPER, id
        );
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public Optional<Row> findByIdForUpdate(long id) {
        var list = jdbc.query(
                baseSelect() + "WHERE cr.id = ? LIMIT 1 FOR UPDATE OF cr",
                MAPPER, id
        );
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public List<Row> listByUser(long userId, String status) {
        if (status == null || status.isBlank()) {
            return jdbc.query(
                    baseSelect() + "WHERE cr.user_id = ? ORDER BY cr.created_at DESC, cr.id DESC",
                    MAPPER, userId
            );
        }
        return jdbc.query(
                baseSelect() + "WHERE cr.user_id = ? AND cr.status = ? ORDER BY cr.created_at DESC, cr.id DESC",
                MAPPER, userId, status
        );
    }

    public List<Row> listForAdmin(String status, OffsetDateTime cursorTs, Long cursorId, int limit) {
        String base = baseSelect();
        String where = "";
        Object[] params;
        if (status != null && !status.isBlank()) {
            where = "WHERE cr.status = ? ";
        }
        if (cursorTs == null || cursorId == null) {
            params = (status != null && !status.isBlank())
                    ? new Object[]{status, limit}
                    : new Object[]{limit};
            return jdbc.query(
                    base + where + "ORDER BY cr.created_at DESC, cr.id DESC LIMIT ?",
                    params, MAPPER
            );
        }
        String cursorClause = "AND (cr.created_at < ? OR (cr.created_at = ? AND cr.id < ?)) ";
        if (where.isBlank()) {
            where = "WHERE ";
            cursorClause = "(cr.created_at < ? OR (cr.created_at = ? AND cr.id < ?)) ";
        }
        if (status != null && !status.isBlank()) {
            params = new Object[]{status, cursorTs, cursorTs, cursorId, limit};
        } else {
            params = new Object[]{cursorTs, cursorTs, cursorId, limit};
        }
        return jdbc.query(
                base + where + cursorClause + "ORDER BY cr.created_at DESC, cr.id DESC LIMIT ?",
                params, MAPPER
        );
    }

    public boolean review(long id, String status, Long reviewedBy, String rejectReason, Long communityId) {
        int rows = jdbc.update(
                "UPDATE community_requests SET status = ?, reviewed_at = now(), reviewed_by = ?, " +
                        "reject_reason = ?, community_id = ? WHERE id = ? AND status = 'pending'",
                status, reviewedBy, rejectReason, communityId, id
        );
        return rows > 0;
    }

    public boolean delete(long id) {
        int rows = jdbc.update("DELETE FROM community_requests WHERE id = ?", id);
        return rows > 0;
    }

    private String baseSelect() {
        return "SELECT cr.*, u.handle AS user_handle, u.email AS user_email " +
                "FROM community_requests cr JOIN users u ON u.id = cr.user_id ";
    }

    public static class Row {
        public long id;
        public long userId;
        public String userHandle;
        public String userEmail;
        public String kind;
        public String name;
        public String description;
        public String imageKey;
        public String status;
        public OffsetDateTime createdAt;
        public OffsetDateTime reviewedAt;
        public Long reviewedBy;
        public String rejectReason;
        public Long communityId;
    }
}

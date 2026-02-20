package com.looped.moderation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class ModerationQueueRepository {
    private final JdbcTemplate jdbc;

    public ModerationQueueRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<ItemRow> MAPPER = new RowMapper<>() {
        @Override
        public ItemRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            ItemRow row = new ItemRow();
            row.id = rs.getLong("id");
            row.targetType = rs.getString("target_type");
            row.targetId = rs.getLong("target_id");
            row.source = rs.getString("source");
            row.reason = rs.getString("reason");
            row.matchedTerm = rs.getString("matched_term");
            long blocklistTermId = rs.getLong("blocklist_term_id");
            row.blocklistTermId = rs.wasNull() ? null : blocklistTermId;
            row.matchMode = rs.getString("match_mode");
            row.normalizedText = rs.getString("normalized_text");
            row.status = rs.getString("status");
            row.createdAt = rs.getObject("created_at", OffsetDateTime.class);
            row.updatedAt = rs.getObject("updated_at", OffsetDateTime.class);
            row.reviewedAt = rs.getObject("reviewed_at", OffsetDateTime.class);
            long reviewedBy = rs.getLong("reviewed_by");
            row.reviewedBy = rs.wasNull() ? null : reviewedBy;
            row.reviewNote = rs.getString("review_note");
            return row;
        }
    };

    public Optional<ItemRow> findById(long id) {
        List<ItemRow> list = jdbc.query("SELECT * FROM moderation_queue_items WHERE id = ?", MAPPER, id);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public Optional<ItemRow> findOpenForTarget(String targetType, long targetId) {
        List<ItemRow> list = jdbc.query(
                "SELECT * FROM moderation_queue_items WHERE target_type = ? AND target_id = ? AND status = 'open' LIMIT 1",
                MAPPER, targetType, targetId
        );
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public long enqueueIfAbsent(String targetType, long targetId, String source, String reason) {
        return enqueueIfAbsent(targetType, targetId, source, reason, null);
    }

    public long enqueueIfAbsent(String targetType, long targetId, String source, String reason, QueueMatchMetadata match) {
        Long id = jdbc.query(
                "INSERT INTO moderation_queue_items(" +
                        "target_type, target_id, source, reason, matched_term, blocklist_term_id, match_mode, normalized_text" +
                        ") VALUES (?,?,?,?,?,?,?,?) " +
                        "ON CONFLICT (target_type, target_id) WHERE status = 'open' DO UPDATE " +
                        "SET updated_at = now(), source = EXCLUDED.source, reason = EXCLUDED.reason, " +
                        "matched_term = EXCLUDED.matched_term, blocklist_term_id = EXCLUDED.blocklist_term_id, " +
                        "match_mode = EXCLUDED.match_mode, normalized_text = EXCLUDED.normalized_text " +
                        "RETURNING id",
                rs -> rs.next() ? rs.getLong(1) : null,
                targetType,
                targetId,
                source,
                reason,
                match == null ? null : match.matchedTerm(),
                match == null ? null : match.blocklistTermId(),
                match == null ? null : match.matchMode(),
                match == null ? null : match.normalizedText()
        );
        if (id == null) throw new IllegalStateException("Failed to enqueue moderation item");
        return id;
    }

    public boolean review(long id, String status, long adminId, String reviewNote) {
        int rows = jdbc.update(
                "UPDATE moderation_queue_items SET status = ?, reviewed_at = now(), reviewed_by = ?, review_note = ?, updated_at = now() " +
                        "WHERE id = ? AND status = 'open'",
                status, adminId, reviewNote, id
        );
        return rows > 0;
    }

    public List<ItemRow> list(String status, String targetType, OffsetDateTime cursorTs, Long cursorId, int limit) {
        String base = "SELECT * FROM moderation_queue_items ";
        StringBuilder where = new StringBuilder();
        java.util.List<Object> params = new java.util.ArrayList<>();
        if (status != null && !status.isBlank()) {
            where.append("status = ?");
            params.add(status);
        }
        if (targetType != null && !targetType.isBlank()) {
            if (!where.isEmpty()) where.append(" AND ");
            where.append("target_type = ?");
            params.add(targetType);
        }
        if (cursorTs != null && cursorId != null) {
            if (!where.isEmpty()) where.append(" AND ");
            where.append("(created_at < ? OR (created_at = ? AND id < ?))");
            params.add(cursorTs);
            params.add(cursorTs);
            params.add(cursorId);
        }
        String sql = base + (where.isEmpty() ? "" : "WHERE " + where + " ") +
                "ORDER BY created_at DESC, id DESC LIMIT ?";
        params.add(limit);
        return jdbc.query(sql, MAPPER, params.toArray());
    }

    public static class ItemRow {
        public long id;
        public String targetType;
        public long targetId;
        public String source;
        public String reason;
        public String matchedTerm;
        public Long blocklistTermId;
        public String matchMode;
        public String normalizedText;
        public String status;
        public OffsetDateTime createdAt;
        public OffsetDateTime updatedAt;
        public OffsetDateTime reviewedAt;
        public Long reviewedBy;
        public String reviewNote;
    }

    public record QueueMatchMetadata(String matchedTerm, Long blocklistTermId, String matchMode, String normalizedText) {}
}

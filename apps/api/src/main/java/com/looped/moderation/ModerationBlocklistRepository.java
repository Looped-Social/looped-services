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
public class ModerationBlocklistRepository {
    private final JdbcTemplate jdbc;

    public ModerationBlocklistRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<TermRow> MAPPER = new RowMapper<>() {
        @Override
        public TermRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            TermRow row = new TermRow();
            row.id = rs.getLong("id");
            row.term = rs.getString("term");
            row.enabled = rs.getBoolean("enabled");
            row.createdAt = rs.getObject("created_at", OffsetDateTime.class);
            row.updatedAt = rs.getObject("updated_at", OffsetDateTime.class);
            long createdBy = rs.getLong("created_by");
            row.createdBy = rs.wasNull() ? null : createdBy;
            long updatedBy = rs.getLong("updated_by");
            row.updatedBy = rs.wasNull() ? null : updatedBy;
            return row;
        }
    };

    public List<TermRow> list(Boolean enabled, OffsetDateTime cursorTs, Long cursorId, int limit) {
        StringBuilder where = new StringBuilder();
        java.util.List<Object> params = new java.util.ArrayList<>();
        if (enabled != null) {
            where.append("enabled = ?");
            params.add(enabled);
        }
        if (cursorTs != null && cursorId != null) {
            if (!where.isEmpty()) where.append(" AND ");
            where.append("(updated_at < ? OR (updated_at = ? AND id < ?))");
            params.add(cursorTs);
            params.add(cursorTs);
            params.add(cursorId);
        }
        String sql = "SELECT * FROM moderation_blocklist_terms " +
                (where.isEmpty() ? "" : "WHERE " + where + " ") +
                "ORDER BY updated_at DESC, id DESC LIMIT ?";
        params.add(limit);
        return jdbc.query(sql, MAPPER, params.toArray());
    }

    public Optional<TermRow> findById(long id) {
        List<TermRow> list = jdbc.query("SELECT * FROM moderation_blocklist_terms WHERE id = ?", MAPPER, id);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public List<String> listEnabledTerms() {
        return jdbc.queryForList(
                "SELECT term FROM moderation_blocklist_terms WHERE enabled = true ORDER BY id ASC",
                String.class
        );
    }

    public List<EnabledTermRow> listEnabled() {
        return jdbc.query(
                "SELECT id, term FROM moderation_blocklist_terms WHERE enabled = true ORDER BY id ASC",
                (rs, rowNum) -> new EnabledTermRow(
                        rs.getLong("id"),
                        rs.getString("term")
                )
        );
    }

    public OffsetDateTime maxUpdatedAt() {
        return jdbc.query(
                "SELECT MAX(updated_at) FROM moderation_blocklist_terms",
                rs -> rs.next() ? rs.getObject(1, OffsetDateTime.class) : null
        );
    }

    public long upsert(String term, Long adminId) {
        String normalized = term == null ? "" : term.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.isBlank()) throw new IllegalArgumentException("term_required");
        Long id = jdbc.query(
                "INSERT INTO moderation_blocklist_terms(term, enabled, created_by, updated_by) " +
                        "VALUES (?, true, ?, ?) " +
                        "ON CONFLICT (lower(term)) DO UPDATE " +
                        "SET enabled = true, updated_at = now(), updated_by = EXCLUDED.updated_by " +
                        "RETURNING id",
                rs -> rs.next() ? rs.getLong(1) : null,
                normalized, adminId, adminId
        );
        if (id == null) throw new IllegalStateException("Failed to upsert blocklist term");
        return id;
    }

    public boolean setEnabled(long id, boolean enabled, Long adminId) {
        int rows = jdbc.update(
                "UPDATE moderation_blocklist_terms SET enabled = ?, updated_at = now(), updated_by = ? WHERE id = ?",
                enabled, adminId, id
        );
        return rows > 0;
    }

    public static class TermRow {
        public long id;
        public String term;
        public boolean enabled;
        public OffsetDateTime createdAt;
        public OffsetDateTime updatedAt;
        public Long createdBy;
        public Long updatedBy;
    }

    public record EnabledTermRow(long id, String term) {}
}

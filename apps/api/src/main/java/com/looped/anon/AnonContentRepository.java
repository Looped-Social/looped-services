package com.looped.anon;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

@Repository
public class AnonContentRepository {
    private final JdbcTemplate jdbc;

    public AnonContentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ContentRefRow> list(long authorPrincipalId, OffsetDateTime cursorTs, Long cursorSortId, int limit) {
        String postsQuery = """
                SELECT 'post'::text AS type, p.id AS entity_id, p.created_at, p.id AS sort_id
                FROM posts p
                LEFT JOIN users u ON u.id = p.author_id AND u.deleted_at IS NULL
                WHERE p.author_principal_id = ?
                  AND p.removed_at IS NULL
                  AND (p.author_id IS NULL OR u.id IS NOT NULL)
                """;

        String repliesQuery = """
                SELECT 'reply'::text AS type, c.id AS entity_id, c.created_at, (c.id - 9223372036854775807) AS sort_id
                FROM comments c
                JOIN posts p ON p.id = c.post_id AND p.removed_at IS NULL
                LEFT JOIN users u ON u.id = p.author_id AND u.deleted_at IS NULL
                WHERE c.author_principal_id = ?
                  AND (p.author_id IS NULL OR u.id IS NOT NULL)
                """;

        String union = "SELECT type, entity_id, created_at, sort_id FROM (" + postsQuery + " UNION ALL " + repliesQuery + ") items ";

        Object[] params;
        if (cursorTs == null || cursorSortId == null) {
            union += "ORDER BY created_at DESC, sort_id DESC LIMIT ?";
            params = new Object[]{authorPrincipalId, authorPrincipalId, limit};
        } else {
            union += "WHERE (created_at < ? OR (created_at = ? AND sort_id < ?)) ORDER BY created_at DESC, sort_id DESC LIMIT ?";
            params = new Object[]{authorPrincipalId, authorPrincipalId, cursorTs, cursorTs, cursorSortId, limit};
        }

        return jdbc.query(union, (rs, rowNum) -> new ContentRefRow(
                rs.getString("type"),
                rs.getLong("entity_id"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getLong("sort_id")
        ), params);
    }

    public record ContentRefRow(String type, long entityId, OffsetDateTime createdAt, long sortId) {}
}

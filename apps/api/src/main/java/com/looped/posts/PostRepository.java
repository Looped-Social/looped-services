package com.looped.posts;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;

@Repository
public class PostRepository {
    private final JdbcTemplate jdbc;

    public PostRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<PostRow> MAPPER = new RowMapper<>() {
        @Override
        public PostRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            PostRow p = new PostRow();
            p.id = rs.getLong("id");
            p.authorId = rs.getLong("author_id");
            p.companyId = rs.getLong("company_id");
            p.content = rs.getString("content");
            long media = rs.getLong("media_asset_id");
            p.mediaAssetId = rs.wasNull() ? null : media;
            p.likesCount = rs.getInt("likes_count");
            p.createdAt = rs.getObject("created_at", OffsetDateTime.class);
            return p;
        }
    };

    public PostRow insert(long authorId, long companyId, String content, Long mediaAssetId) {
        Long id = jdbc.query(
                "INSERT INTO posts(author_id, company_id, content, media_asset_id) VALUES (?,?,?,?) RETURNING id",
                rs -> rs.next() ? rs.getLong(1) : null,
                authorId, companyId, content, mediaAssetId
        );
        return findById(id).orElseThrow();
    }

    public Optional<PostRow> findById(Long id) {
        var list = jdbc.query("SELECT id, author_id, company_id, content, media_asset_id, likes_count, created_at FROM posts WHERE id=?", MAPPER, id);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public java.util.List<PostRow> findFeed(long companyId, Long cursorEpochMillis, Long cursorId, int limit) {
        if (cursorEpochMillis == null || cursorId == null) {
            return jdbc.query(
                    "SELECT id, author_id, company_id, content, media_asset_id, likes_count, created_at " +
                            "FROM posts WHERE company_id=? ORDER BY created_at DESC, id DESC LIMIT ?",
                    MAPPER, companyId, limit
            );
        } else {
            // keyset: (created_at < ts) OR (created_at = ts AND id < cursorId)
            var ts = java.time.Instant.ofEpochMilli(cursorEpochMillis);
            var odt = java.time.OffsetDateTime.ofInstant(ts, java.time.ZoneOffset.UTC);
            return jdbc.query(
                    "SELECT id, author_id, company_id, content, media_asset_id, likes_count, created_at " +
                            "FROM posts WHERE company_id=? AND (created_at < ? OR (created_at = ? AND id < ?)) " +
                            "ORDER BY created_at DESC, id DESC LIMIT ?",
                    MAPPER, companyId, odt, odt, cursorId, limit
            );
        }
    }

    public static class PostRow {
        public long id;
        public long authorId;
        public long companyId;
        public String content;
        public Long mediaAssetId;
        public int likesCount;
        public OffsetDateTime createdAt;
    }
}

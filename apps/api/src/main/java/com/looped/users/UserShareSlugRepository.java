package com.looped.users;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;

@Repository
public class UserShareSlugRepository {
    private final JdbcTemplate jdbc;

    public UserShareSlugRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<SlugRow> MAPPER = new RowMapper<>() {
        @Override
        public SlugRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            SlugRow row = new SlugRow();
            row.id = rs.getLong("id");
            row.userId = rs.getLong("user_id");
            row.slug = rs.getString("slug");
            row.type = rs.getString("type");
            row.active = rs.getBoolean("active");
            row.createdAt = rs.getObject("created_at", OffsetDateTime.class);
            row.updatedAt = rs.getObject("updated_at", OffsetDateTime.class);
            return row;
        }
    };

    public Optional<SlugRow> findActiveBySlug(String slug) {
        if (slug == null || slug.isBlank()) return Optional.empty();
        var rows = jdbc.query(
                "SELECT id, user_id, slug, type, active, created_at, updated_at " +
                        "FROM user_share_slugs WHERE active = true AND lower(slug) = lower(?) LIMIT 1",
                MAPPER,
                slug
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<SlugRow> findActiveUsernameSlug(long userId) {
        var rows = jdbc.query(
                "SELECT id, user_id, slug, type, active, created_at, updated_at " +
                        "FROM user_share_slugs WHERE user_id = ? AND type = 'username_reserved' AND active = true LIMIT 1",
                MAPPER,
                userId
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<SlugRow> findActiveCustomSlug(long userId) {
        var rows = jdbc.query(
                "SELECT id, user_id, slug, type, active, created_at, updated_at " +
                        "FROM user_share_slugs WHERE user_id = ? AND type = 'custom' AND active = true LIMIT 1",
                MAPPER,
                userId
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public int updateActiveCustomSlug(long userId, String slug) {
        return jdbc.update(
                "UPDATE user_share_slugs SET slug = ?, updated_at = now() " +
                        "WHERE user_id = ? AND type = 'custom' AND active = true",
                slug,
                userId
        );
    }

    public int insertActiveCustomSlug(long userId, String slug) {
        return jdbc.update(
                "INSERT INTO user_share_slugs(user_id, slug, type, active) VALUES (?, ?, 'custom', true)",
                userId,
                slug
        );
    }

    public int clearActiveCustomSlug(long userId) {
        return jdbc.update(
                "UPDATE user_share_slugs SET active = false, updated_at = now() " +
                        "WHERE user_id = ? AND type = 'custom' AND active = true",
                userId
        );
    }

    public static class SlugRow {
        public long id;
        public long userId;
        public String slug;
        public String type;
        public boolean active;
        public OffsetDateTime createdAt;
        public OffsetDateTime updatedAt;
    }
}

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
public class CommunitiesRepository {
    private final JdbcTemplate jdbc;

    public CommunitiesRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<CommunityRow> MAPPER = new RowMapper<>() {
        @Override
        public CommunityRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            CommunityRow row = new CommunityRow();
            row.id = rs.getLong("id");
            row.kind = rs.getString("kind");
            row.name = rs.getString("name");
            row.description = rs.getString("description");
            row.memberCount = rs.getInt("member_count");
            row.imageUrl = rs.getString("image_url");
            row.createdAt = rs.getObject("created_at", OffsetDateTime.class);
            int ttlDays = rs.getInt("verification_ttl_days");
            row.verificationTtlDays = rs.wasNull() ? null : ttlDays;
            return row;
        }
    };

    public List<CommunityRow> search(String query, OffsetDateTime cursorTs, Long cursorId, int limit) {
        String like = "%" + query.toLowerCase() + "%";
        if (cursorTs == null || cursorId == null) {
            return jdbc.query(
                    "SELECT id, kind, name, description, member_count, image_url, created_at, verification_ttl_days " +
                    "FROM communities WHERE LOWER(name) LIKE ? OR LOWER(COALESCE(description,'')) LIKE ? " +
                    "ORDER BY created_at DESC, id DESC LIMIT ?",
                    MAPPER, like, like, limit
            );
        }
        return jdbc.query(
                "SELECT id, kind, name, description, member_count, image_url, created_at, verification_ttl_days " +
                        "FROM communities WHERE (LOWER(name) LIKE ? OR LOWER(COALESCE(description,'')) LIKE ?) " +
                        "AND (created_at < ? OR (created_at = ? AND id < ?)) " +
                        "ORDER BY created_at DESC, id DESC LIMIT ?",
                MAPPER, like, like, cursorTs, cursorTs, cursorId, limit
        );
    }

    public Optional<CommunityRow> findByKindAndName(String kind, String name) {
        if (kind == null || name == null) return Optional.empty();
        var list = jdbc.query(
                "SELECT id, kind, name, description, member_count, image_url, created_at, verification_ttl_days " +
                        "FROM communities WHERE kind = ? AND LOWER(name) = LOWER(?) LIMIT 1",
                MAPPER, kind, name
        );
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public Optional<CommunityRow> findById(long id) {
        var list = jdbc.query(
                "SELECT id, kind, name, description, member_count, image_url, created_at, verification_ttl_days FROM communities WHERE id = ?",
                MAPPER, id
        );
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public List<RecommendedRow> recommended(long userId, int limit) {
        return jdbc.query(
                "SELECT c.id, c.kind, c.name, c.description, c.member_count, c.image_url, c.verification_ttl_days, " +
                        "CASE WHEN cf.user_id IS NULL THEN false ELSE true END AS is_following " +
                        "FROM communities c " +
                        "LEFT JOIN community_follows cf ON cf.community_id = c.id AND cf.user_id = ? " +
                        "ORDER BY c.member_count DESC, c.created_at DESC, c.id DESC LIMIT ?",
                (rs, rowNum) -> {
                    RecommendedRow row = new RecommendedRow();
                    row.id = rs.getLong("id");
                    row.kind = rs.getString("kind");
                    row.name = rs.getString("name");
                    row.description = rs.getString("description");
                    row.memberCount = rs.getInt("member_count");
                    row.imageUrl = rs.getString("image_url");
                    int ttlDays = rs.getInt("verification_ttl_days");
                    row.verificationTtlDays = rs.wasNull() ? null : ttlDays;
                    row.isFollowing = rs.getBoolean("is_following");
                    return row;
                },
                userId, limit
        );
    }

    public long insert(String kind, String name, String description, String imageUrl, Integer verificationTtlDays) {
        Long id = jdbc.query(
                "INSERT INTO communities(kind, name, description, image_url, verification_ttl_days) " +
                        "VALUES (?,?,?,?,?) RETURNING id",
                rs -> rs.next() ? rs.getLong(1) : null,
                kind, name, description, imageUrl, verificationTtlDays
        );
        if (id == null) {
            throw new IllegalStateException("Failed to insert community");
        }
        return id;
    }

    public static class CommunityRow {
        public long id;
        public String kind;
        public String name;
        public String description;
        public int memberCount;
        public String imageUrl;
        public OffsetDateTime createdAt;
        public Integer verificationTtlDays;
    }

    public static class RecommendedRow {
        public long id;
        public String kind;
        public String name;
        public String description;
        public int memberCount;
        public String imageUrl;
        public boolean isFollowing;
        public Integer verificationTtlDays;
    }

    public boolean updateVerificationTtlDays(long communityId, Integer ttlDays) {
        int rows = jdbc.update(
                "UPDATE communities SET verification_ttl_days = ? WHERE id = ?",
                ttlDays, communityId
        );
        return rows > 0;
    }
}

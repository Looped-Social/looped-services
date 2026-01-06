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

    private static final String BASE_COLUMNS =
            "id, kind, name, description, member_count, image_url, specialization_type, created_at, verification_ttl_days, short_name";

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
            row.specializationType = rs.getString("specialization_type");
            row.createdAt = rs.getObject("created_at", OffsetDateTime.class);
            int ttlDays = rs.getInt("verification_ttl_days");
            row.verificationTtlDays = rs.wasNull() ? null : ttlDays;
            row.shortName = rs.getString("short_name");
            return row;
        }
    };

    public List<CommunityRow> search(String query, OffsetDateTime cursorTs, Long cursorId, int limit) {
        String like = "%" + query.toLowerCase() + "%";
        if (cursorTs == null || cursorId == null) {
            return jdbc.query(
                    "SELECT " + BASE_COLUMNS + " " +
                    "FROM communities WHERE LOWER(name) LIKE ? OR LOWER(COALESCE(description,'')) LIKE ? " +
                    "ORDER BY created_at DESC, id DESC LIMIT ?",
                    MAPPER, like, like, limit
            );
        }
        return jdbc.query(
                "SELECT " + BASE_COLUMNS + " " +
                        "FROM communities WHERE (LOWER(name) LIKE ? OR LOWER(COALESCE(description,'')) LIKE ?) " +
                        "AND (created_at < ? OR (created_at = ? AND id < ?)) " +
                        "ORDER BY created_at DESC, id DESC LIMIT ?",
                MAPPER, like, like, cursorTs, cursorTs, cursorId, limit
        );
    }

    public List<CommunityRow> searchByKind(String kind, String query, OffsetDateTime cursorTs, Long cursorId, int limit) {
        if (kind == null || kind.isBlank()) return List.of();
        String like = "%" + query.toLowerCase() + "%";
        if (cursorTs == null || cursorId == null) {
            return jdbc.query(
                    "SELECT " + BASE_COLUMNS + " " +
                            "FROM communities WHERE kind = ? AND (LOWER(name) LIKE ? OR LOWER(COALESCE(description,'')) LIKE ?) " +
                            "ORDER BY created_at DESC, id DESC LIMIT ?",
                    MAPPER, kind, like, like, limit
            );
        }
        return jdbc.query(
                "SELECT " + BASE_COLUMNS + " " +
                        "FROM communities WHERE kind = ? AND (LOWER(name) LIKE ? OR LOWER(COALESCE(description,'')) LIKE ?) " +
                        "AND (created_at < ? OR (created_at = ? AND id < ?)) " +
                        "ORDER BY created_at DESC, id DESC LIMIT ?",
                MAPPER, kind, like, like, cursorTs, cursorTs, cursorId, limit
        );
    }

    public Optional<CommunityRow> findByKindAndName(String kind, String name) {
        return findByKindAndName(kind, name, null);
    }

    public Optional<CommunityRow> findByKindAndName(String kind, String name, String specializationType) {
        if (kind == null || name == null) return Optional.empty();
        boolean isSpecialization = "specialization".equalsIgnoreCase(kind);
        if (isSpecialization) {
            var list = jdbc.query(
                    "SELECT " + BASE_COLUMNS + " " +
                            "FROM communities WHERE kind = ? AND specialization_type = ? AND LOWER(name) = LOWER(?) LIMIT 1",
                    MAPPER, kind, specializationType, name
            );
            return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
        }
        var list = jdbc.query(
                "SELECT " + BASE_COLUMNS + " " +
                        "FROM communities WHERE kind = ? AND LOWER(name) = LOWER(?) LIMIT 1",
                MAPPER, kind, name
        );
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public Optional<CommunityRow> findById(long id) {
        var list = jdbc.query(
                "SELECT " + BASE_COLUMNS + " FROM communities WHERE id = ?",
                MAPPER, id
        );
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public List<CommunityRow> list(OffsetDateTime cursorTs, Long cursorId, int limit) {
        if (cursorTs == null || cursorId == null) {
            return jdbc.query(
                    "SELECT " + BASE_COLUMNS + " " +
                            "FROM communities ORDER BY created_at DESC, id DESC LIMIT ?",
                    MAPPER, limit
            );
        }
        return jdbc.query(
                "SELECT " + BASE_COLUMNS + " " +
                        "FROM communities WHERE (created_at < ? OR (created_at = ? AND id < ?)) " +
                        "ORDER BY created_at DESC, id DESC LIMIT ?",
                MAPPER, cursorTs, cursorTs, cursorId, limit
        );
    }

    public List<CommunityRow> listByKind(String kind, OffsetDateTime cursorTs, Long cursorId, int limit) {
        if (kind == null || kind.isBlank()) return List.of();
        if (cursorTs == null || cursorId == null) {
            return jdbc.query(
                    "SELECT " + BASE_COLUMNS + " " +
                            "FROM communities WHERE kind = ? ORDER BY created_at DESC, id DESC LIMIT ?",
                    MAPPER, kind, limit
            );
        }
        return jdbc.query(
                "SELECT " + BASE_COLUMNS + " " +
                        "FROM communities WHERE kind = ? AND (created_at < ? OR (created_at = ? AND id < ?)) " +
                        "ORDER BY created_at DESC, id DESC LIMIT ?",
                MAPPER, kind, cursorTs, cursorTs, cursorId, limit
        );
    }

    public List<CommunityRow> listByKindAndSpecializationType(String kind, String specializationType,
                                                              OffsetDateTime cursorTs, Long cursorId, int limit) {
        if (kind == null || kind.isBlank()) return List.of();
        if (cursorTs == null || cursorId == null) {
            return jdbc.query(
                    "SELECT " + BASE_COLUMNS + " " +
                            "FROM communities WHERE kind = ? AND specialization_type = ? " +
                            "ORDER BY created_at DESC, id DESC LIMIT ?",
                    MAPPER, kind, specializationType, limit
            );
        }
        return jdbc.query(
                "SELECT " + BASE_COLUMNS + " " +
                        "FROM communities WHERE kind = ? AND specialization_type = ? " +
                        "AND (created_at < ? OR (created_at = ? AND id < ?)) " +
                        "ORDER BY created_at DESC, id DESC LIMIT ?",
                MAPPER, kind, specializationType, cursorTs, cursorTs, cursorId, limit
        );
    }

    public List<CommunityRow> searchByKindAndSpecializationType(String kind, String specializationType, String query,
                                                                OffsetDateTime cursorTs, Long cursorId, int limit) {
        if (kind == null || kind.isBlank()) return List.of();
        String like = "%" + query.toLowerCase() + "%";
        if (cursorTs == null || cursorId == null) {
            return jdbc.query(
                    "SELECT " + BASE_COLUMNS + " " +
                            "FROM communities WHERE kind = ? AND specialization_type = ? " +
                            "AND (LOWER(name) LIKE ? OR LOWER(COALESCE(description,'')) LIKE ?) " +
                            "ORDER BY created_at DESC, id DESC LIMIT ?",
                    MAPPER, kind, specializationType, like, like, limit
            );
        }
        return jdbc.query(
                "SELECT " + BASE_COLUMNS + " " +
                        "FROM communities WHERE kind = ? AND specialization_type = ? " +
                        "AND (LOWER(name) LIKE ? OR LOWER(COALESCE(description,'')) LIKE ?) " +
                        "AND (created_at < ? OR (created_at = ? AND id < ?)) " +
                        "ORDER BY created_at DESC, id DESC LIMIT ?",
                MAPPER, kind, specializationType, like, like, cursorTs, cursorTs, cursorId, limit
        );
    }

    public List<RecommendedRow> recommended(long userId, int limit) {
        return jdbc.query(
                "SELECT c.id, c.kind, c.name, c.description, c.member_count, c.image_url, c.specialization_type, c.verification_ttl_days, " +
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
                    row.specializationType = rs.getString("specialization_type");
                    int ttlDays = rs.getInt("verification_ttl_days");
                    row.verificationTtlDays = rs.wasNull() ? null : ttlDays;
                    row.isFollowing = rs.getBoolean("is_following");
                    return row;
                },
                userId, limit
        );
    }

    public long insert(String kind, String name, String description, String imageUrl, Integer verificationTtlDays) {
        return insert(kind, name, description, imageUrl, verificationTtlDays, null);
    }

    public long insert(String kind, String name, String description, String imageUrl,
                       Integer verificationTtlDays, String specializationType) {
        return insert(kind, name, description, imageUrl, verificationTtlDays, specializationType, null);
    }

    public long insert(String kind, String name, String description, String imageUrl,
                       Integer verificationTtlDays, String specializationType, String shortName) {
        Long id = jdbc.query(
                "INSERT INTO communities(kind, name, description, image_url, verification_ttl_days, specialization_type, short_name) " +
                        "VALUES (?,?,?,?,?,?,?) RETURNING id",
                rs -> rs.next() ? rs.getLong(1) : null,
                kind, name, description, imageUrl, verificationTtlDays, specializationType, shortName
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
        public String specializationType;
        public OffsetDateTime createdAt;
        public Integer verificationTtlDays;
        public String shortName;
    }

    public static class RecommendedRow {
        public long id;
        public String kind;
        public String name;
        public String description;
        public int memberCount;
        public String imageUrl;
        public String specializationType;
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

    public boolean updateDetails(long communityId, boolean descriptionProvided, String description, Integer ttlDays) {
        return updateDetails(communityId, descriptionProvided, description, ttlDays, false, null);
    }

    public boolean updateDetails(long communityId, boolean descriptionProvided, String description,
                                 Integer ttlDays, boolean shortNameProvided, String shortName) {
        boolean hasUpdate = descriptionProvided || ttlDays != null || shortNameProvided;
        if (!hasUpdate) return false;
        StringBuilder sql = new StringBuilder("UPDATE communities SET ");
        java.util.List<Object> params = new java.util.ArrayList<>();
        boolean first = true;
        if (descriptionProvided) {
            sql.append("description = ?");
            params.add(description);
            first = false;
        }
        if (ttlDays != null) {
            if (!first) sql.append(", ");
            sql.append("verification_ttl_days = ?");
            params.add(ttlDays);
            first = false;
        }
        if (shortNameProvided) {
            if (!first) sql.append(", ");
            sql.append("short_name = ?");
            params.add(shortName);
        }
        sql.append(" WHERE id = ?");
        params.add(communityId);
        int rows = jdbc.update(sql.toString(), params.toArray());
        return rows > 0;
    }

    public boolean updateImageUrl(long communityId, String imageUrl) {
        int rows = jdbc.update(
                "UPDATE communities SET image_url = ? WHERE id = ?",
                imageUrl, communityId
        );
        return rows > 0;
    }

    public boolean delete(long communityId) {
        int rows = jdbc.update("DELETE FROM communities WHERE id = ?", communityId);
        return rows > 0;
    }
}

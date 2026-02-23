package com.looped.communities;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Repository
public class CommunitiesRepository {
    private final JdbcTemplate jdbc;

    public CommunitiesRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String BASE_COLUMNS =
            "id, kind, name, description, member_count, image_url, specialization_type, created_at, verification_ttl_days, short_name, specialization_join_cooldown_months, icon_kind, icon_value, icon_updated_at";

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
            int cooldownMonths = rs.getInt("specialization_join_cooldown_months");
            row.specializationJoinCooldownMonths = rs.wasNull() ? null : cooldownMonths;
            row.iconKind = rs.getString("icon_kind");
            row.iconValue = rs.getString("icon_value");
            row.iconUpdatedAt = rs.getObject("icon_updated_at", OffsetDateTime.class);
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

    private static final RowMapper<ScoredCommunityRow> SCORED_MAPPER = new RowMapper<>() {
        @Override
        public ScoredCommunityRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            ScoredCommunityRow out = new ScoredCommunityRow();
            out.community = MAPPER.mapRow(rs, rowNum);
            out.score = rs.getLong("score");
            return out;
        }
    };

    public List<ScoredCommunityRow> searchRanked(String query, String prefixQuery, OffsetDateTime asOf,
                                                 Long cursorScore, OffsetDateTime cursorTs, Long cursorId, int limit) {
        return searchRankedByKind(null, null, query, prefixQuery, asOf, cursorScore, cursorTs, cursorId, limit);
    }

    public List<ScoredCommunityRow> searchRankedByKind(String kind, String query, String prefixQuery, OffsetDateTime asOf,
                                                       Long cursorScore, OffsetDateTime cursorTs, Long cursorId, int limit) {
        return searchRankedByKind(kind, null, query, prefixQuery, asOf, cursorScore, cursorTs, cursorId, limit);
    }

    public List<ScoredCommunityRow> searchRankedByKindAndSpecializationType(String kind, String specializationType,
                                                                            String query, String prefixQuery, OffsetDateTime asOf,
                                                                            Long cursorScore, OffsetDateTime cursorTs, Long cursorId, int limit) {
        return searchRankedByKind(kind, specializationType, query, prefixQuery, asOf, cursorScore, cursorTs, cursorId, limit);
    }

    public List<CommunityRow> searchByLikePopularity(String query, String kind, String specializationType, int limit) {
        String q = query == null ? "" : query.trim();
        String like = "%" + q.toLowerCase(Locale.ROOT) + "%";

        String sql = "SELECT " + BASE_COLUMNS + " FROM communities c " +
                "WHERE (LOWER(c.name) LIKE ? OR LOWER(COALESCE(c.description,'')) LIKE ?) ";
        List<Object> args = new ArrayList<>();
        args.add(like);
        args.add(like);
        if (kind != null && !kind.isBlank()) {
            sql += "AND c.kind = ? ";
            args.add(kind);
        }
        if (specializationType != null && !specializationType.isBlank()) {
            sql += "AND c.specialization_type = ? ";
            args.add(specializationType);
        }
        sql += "ORDER BY c.member_count DESC, c.created_at DESC, c.id DESC LIMIT ?";
        args.add(limit);
        return jdbc.query(sql, MAPPER, args.toArray());
    }

    public List<CommunityRow> browseByPopularity(String kind,
                                                 String specializationType,
                                                 Long cursorMemberCount,
                                                 OffsetDateTime cursorCreatedAt,
                                                 Long cursorId,
                                                 int limit) {
        String sql = "SELECT " + BASE_COLUMNS + " FROM communities c WHERE 1=1 ";
        List<Object> args = new ArrayList<>();
        if (kind != null && !kind.isBlank()) {
            sql += "AND c.kind = ? ";
            args.add(kind);
        }
        if (specializationType != null && !specializationType.isBlank()) {
            sql += "AND c.specialization_type = ? ";
            args.add(specializationType);
        }
        if (cursorMemberCount != null && cursorCreatedAt != null && cursorId != null) {
            sql += "AND (c.member_count < ? OR (c.member_count = ? AND (c.created_at < ? OR (c.created_at = ? AND c.id < ?)))) ";
            args.add(cursorMemberCount);
            args.add(cursorMemberCount);
            args.add(cursorCreatedAt);
            args.add(cursorCreatedAt);
            args.add(cursorId);
        }
        sql += "ORDER BY c.member_count DESC, c.created_at DESC, c.id DESC LIMIT ?";
        args.add(limit);
        return jdbc.query(sql, MAPPER, args.toArray());
    }

    public List<CommunityRow> browseSpecializationsByMemberCount(String specializationType,
                                                                 OffsetDateTime asOf,
                                                                 Long cursorMemberCount,
                                                                 OffsetDateTime cursorCreatedAt,
                                                                 Long cursorId,
                                                                 int limit) {
        String sql = """
                WITH base AS (
                    SELECT c.id, c.kind, c.name, c.description,
                           COUNT(j.user_id) FILTER (WHERE u.deleted_at IS NULL) AS member_count,
                           c.image_url, c.specialization_type, c.created_at, c.verification_ttl_days, c.short_name, c.specialization_join_cooldown_months,
                           c.icon_kind, c.icon_value, c.icon_updated_at
                    FROM communities c
                    LEFT JOIN specialization_joins j ON j.specialization_id = c.id AND j.created_at <= ?
                    LEFT JOIN users u ON u.id = j.user_id
                    WHERE c.kind = 'specialization' AND c.specialization_type = ? AND c.created_at <= ?
                    GROUP BY c.id, c.kind, c.name, c.description,
                             c.image_url, c.specialization_type, c.created_at, c.verification_ttl_days, c.short_name, c.specialization_join_cooldown_months,
                             c.icon_kind, c.icon_value, c.icon_updated_at
                )
                SELECT * FROM base
                """;
        List<Object> args = new ArrayList<>();
        args.add(asOf);
        args.add(specializationType);
        args.add(asOf);
        if (cursorMemberCount != null && cursorCreatedAt != null && cursorId != null) {
            sql += "WHERE (member_count < ? OR (member_count = ? AND (created_at < ? OR (created_at = ? AND id < ?)))) ";
            args.add(cursorMemberCount);
            args.add(cursorMemberCount);
            args.add(cursorCreatedAt);
            args.add(cursorCreatedAt);
            args.add(cursorId);
        }
        sql += "ORDER BY member_count DESC, created_at DESC, id DESC LIMIT ?";
        args.add(limit);
        return jdbc.query(sql, MAPPER, args.toArray());
    }

    private List<ScoredCommunityRow> searchRankedByKind(String kind, String specializationType,
                                                        String query, String prefixQuery, OffsetDateTime asOf,
                                                        Long cursorScore, OffsetDateTime cursorTs, Long cursorId, int limit) {
        String vectorEn = "to_tsvector('english', COALESCE(c.name,'') || ' ' || COALESCE(c.description,''))";
        String vectorSimple = "to_tsvector('simple', COALESCE(c.name,'') || ' ' || COALESCE(c.short_name,'') || ' ' || COALESCE(c.description,''))";
        String ftsMatch = "(" + vectorEn + " @@ q.q_web OR (q.q_prefix IS NOT NULL AND " + vectorSimple + " @@ q.q_prefix))";
        String likeMatch = "(LOWER(c.name) LIKE ? OR LOWER(COALESCE(c.description,'')) LIKE ?)";
        String match = "(" + ftsMatch + " OR " + likeMatch + ")";
        String rank = "GREATEST(" +
                "ts_rank_cd(" + vectorEn + ", q.q_web), " +
                "COALESCE(ts_rank_cd(" + vectorSimple + ", q.q_prefix), 0)" +
                ")";

        String exactName = "CASE WHEN LOWER(c.name) = LOWER(?) THEN 700000 ELSE 0 END";
        String prefixName = "CASE WHEN LOWER(c.name) LIKE LOWER(?) || '%' THEN 400000 ELSE 0 END";
        String prefixShort = "CASE WHEN c.short_name IS NOT NULL AND LOWER(c.short_name) LIKE LOWER(?) || '%' THEN 500000 ELSE 0 END";
        String boost = "(" + exactName + " + " + prefixName + " + " + prefixShort + ")";

        String popularity = "LEAST(200000, LN(1 + GREATEST(c.member_count, 0)) * 70000)";
        String recency = "LEAST(100000, (1.0 / (1.0 + EXTRACT(EPOCH FROM (t.as_of - c.created_at)) / 86400.0)) * 100000)";
        String scoreExpr = "CAST((" + rank + " * 1000000 + " + boost + " + " + popularity + " + " + recency + ") AS BIGINT)";

        String base =
                "WITH q AS (" +
                        "SELECT websearch_to_tsquery('english', ?) AS q_web, " +
                        "to_tsquery('simple', NULLIF(?, '')) AS q_prefix" +
                        "), t AS (SELECT ?::timestamptz AS as_of) " +
                        "SELECT " + BASE_COLUMNS + ", " + scoreExpr + " AS score " +
                        "FROM communities c CROSS JOIN q CROSS JOIN t " +
                        "WHERE " + match;

        String like = "%" + (query == null ? "" : query.trim().toLowerCase(Locale.ROOT)) + "%";

        if (kind != null && !kind.isBlank()) {
            base += " AND c.kind = ? ";
        }
        if (specializationType != null && !specializationType.isBlank()) {
            base += " AND c.specialization_type = ? ";
        }

        String order = " ORDER BY score DESC, created_at DESC, id DESC LIMIT ?";

        if (cursorScore == null || cursorTs == null || cursorId == null) {
            if (kind != null && !kind.isBlank() && specializationType != null && !specializationType.isBlank()) {
                return jdbc.query(
                        "SELECT * FROM (" + base + ") s" + order,
                        SCORED_MAPPER,
                        query, prefixQuery, asOf,
                        query, query, query,
                        like, like,
                        kind, specializationType,
                        limit
                );
            }
            if (kind != null && !kind.isBlank()) {
                return jdbc.query(
                        "SELECT * FROM (" + base + ") s" + order,
                        SCORED_MAPPER,
                        query, prefixQuery, asOf,
                        query, query, query,
                        like, like,
                        kind,
                        limit
                );
            }
            return jdbc.query(
                    "SELECT * FROM (" + base + ") s" + order,
                    SCORED_MAPPER,
                    query, prefixQuery, asOf,
                    query, query, query,
                    like, like,
                    limit
            );
        }

        String page = " WHERE (score < ? OR (score = ? AND (created_at < ? OR (created_at = ? AND id < ?)))) ";
        if (kind != null && !kind.isBlank() && specializationType != null && !specializationType.isBlank()) {
            return jdbc.query(
                    "SELECT * FROM (" + base + ") s" + page + order,
                    SCORED_MAPPER,
                    query, prefixQuery, asOf,
                    query, query, query,
                    like, like,
                    kind, specializationType,
                    cursorScore, cursorScore, cursorTs, cursorTs, cursorId,
                    limit
            );
        }
        if (kind != null && !kind.isBlank()) {
            return jdbc.query(
                    "SELECT * FROM (" + base + ") s" + page + order,
                    SCORED_MAPPER,
                    query, prefixQuery, asOf,
                    query, query, query,
                    like, like,
                    kind,
                    cursorScore, cursorScore, cursorTs, cursorTs, cursorId,
                    limit
            );
        }
        return jdbc.query(
                "SELECT * FROM (" + base + ") s" + page + order,
                SCORED_MAPPER,
                query, prefixQuery, asOf,
                query, query, query,
                like, like,
                cursorScore, cursorScore, cursorTs, cursorTs, cursorId,
                limit
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

    public Optional<CommunityRow> findTopByKind(String kind) {
        if (kind == null || kind.isBlank()) return Optional.empty();
        var list = jdbc.query(
                "SELECT " + BASE_COLUMNS + " FROM communities WHERE lower(kind) = ? " +
                        "ORDER BY member_count DESC, created_at DESC, id DESC LIMIT 1",
                MAPPER,
                kind.trim().toLowerCase(Locale.ROOT)
        );
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public Map<Long, CommunityRow> findByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) return Map.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        List<CommunityRow> rows = jdbc.query(
                "SELECT " + BASE_COLUMNS + " FROM communities WHERE id IN (" + placeholders + ")",
                MAPPER,
                ids.toArray()
        );
        Map<Long, CommunityRow> out = new HashMap<>();
        for (CommunityRow row : rows) {
            out.put(row.id, row);
        }
        return out;
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

    public List<CommunityRow> listByKindFilters(List<KindFilter> kindFilters,
                                                 OffsetDateTime cursorTs,
                                                 Long cursorId,
                                                 int limit) {
        if (kindFilters == null || kindFilters.isEmpty()) {
            return list(cursorTs, cursorId, limit);
        }
        StringBuilder sql = new StringBuilder(
                "SELECT " + BASE_COLUMNS + " FROM communities WHERE 1=1"
        );
        List<Object> args = new ArrayList<>();
        appendKindFiltersClause(sql, args, kindFilters);
        if (cursorTs != null && cursorId != null) {
            sql.append(" AND (created_at < ? OR (created_at = ? AND id < ?))");
            args.add(cursorTs);
            args.add(cursorTs);
            args.add(cursorId);
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
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

    public List<CommunityRow> searchByKindFilters(List<KindFilter> kindFilters,
                                                   String query,
                                                   OffsetDateTime cursorTs,
                                                   Long cursorId,
                                                   int limit) {
        if (kindFilters == null || kindFilters.isEmpty()) {
            return search(query, cursorTs, cursorId, limit);
        }
        String like = "%" + query.toLowerCase(Locale.ROOT) + "%";
        StringBuilder sql = new StringBuilder(
                "SELECT " + BASE_COLUMNS + " FROM communities " +
                        "WHERE (LOWER(name) LIKE ? OR LOWER(COALESCE(description,'')) LIKE ?)"
        );
        List<Object> args = new ArrayList<>();
        args.add(like);
        args.add(like);
        appendKindFiltersClause(sql, args, kindFilters);
        if (cursorTs != null && cursorId != null) {
            sql.append(" AND (created_at < ? OR (created_at = ? AND id < ?))");
            args.add(cursorTs);
            args.add(cursorTs);
            args.add(cursorId);
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    public long countAll() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM communities", Long.class);
        return count == null ? 0L : count;
    }

    public long countSearch(String query) {
        String like = "%" + query.toLowerCase(Locale.ROOT) + "%";
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM communities WHERE LOWER(name) LIKE ? OR LOWER(COALESCE(description,'')) LIKE ?",
                Long.class,
                like, like
        );
        return count == null ? 0L : count;
    }

    public long countByKindFilters(List<KindFilter> kindFilters) {
        if (kindFilters == null || kindFilters.isEmpty()) return countAll();
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM communities WHERE 1=1");
        List<Object> args = new ArrayList<>();
        appendKindFiltersClause(sql, args, kindFilters);
        Long count = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0L : count;
    }

    public long countSearchByKindFilters(List<KindFilter> kindFilters, String query) {
        if (kindFilters == null || kindFilters.isEmpty()) return countSearch(query);
        String like = "%" + query.toLowerCase(Locale.ROOT) + "%";
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) FROM communities " +
                        "WHERE (LOWER(name) LIKE ? OR LOWER(COALESCE(description,'')) LIKE ?)"
        );
        List<Object> args = new ArrayList<>();
        args.add(like);
        args.add(like);
        appendKindFiltersClause(sql, args, kindFilters);
        Long count = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0L : count;
    }

    private void appendKindFiltersClause(StringBuilder sql,
                                         List<Object> args,
                                         List<KindFilter> kindFilters) {
        if (kindFilters == null || kindFilters.isEmpty()) return;
        sql.append(" AND (");
        boolean first = true;
        for (KindFilter filter : kindFilters) {
            if (filter == null || filter.kind() == null || filter.kind().isBlank()) continue;
            if (!first) sql.append(" OR ");
            if (filter.specializationType() != null && !filter.specializationType().isBlank()) {
                sql.append("(kind = ? AND specialization_type = ?)");
                args.add(filter.kind());
                args.add(filter.specializationType());
            } else {
                sql.append("kind = ?");
                args.add(filter.kind());
            }
            first = false;
        }
        if (first) {
            sql.append("1=0");
        }
        sql.append(")");
    }

    public List<RecommendedRow> recommended(Long userId, String kind, String specializationType, int limit) {
        return recommended(userId, kind, specializationType, OffsetDateTime.now(), null, null, null, limit);
    }

    public List<RecommendedRow> recommended(Long userId, String kind, String specializationType,
                                            OffsetDateTime asOf,
                                            Long cursorScore,
                                            OffsetDateTime cursorCreatedAt,
                                            Long cursorId,
                                            int limit) {
        StringBuilder sql = new StringBuilder("""
                WITH me AS (
                    SELECT ?::bigint AS user_id, ?::timestamptz AS as_of
                ),
                my_verified AS (
                    SELECT cv.community_id
                    FROM community_verifications cv
                    CROSS JOIN me
                    WHERE me.user_id IS NOT NULL
                      AND cv.user_id = me.user_id
                      AND cv.verified = true
                      AND (cv.expires_at IS NULL OR cv.expires_at > me.as_of)
                ),
                my_verified_kinds AS (
                    SELECT DISTINCT c.kind
                    FROM my_verified mv
                    JOIN communities c ON c.id = mv.community_id
                ),
                my_followed_kinds AS (
                    SELECT DISTINCT c.kind
                    FROM community_follows cf
                    JOIN communities c ON c.id = cf.community_id
                    CROSS JOIN me
                    WHERE me.user_id IS NOT NULL
                      AND cf.user_id = me.user_id
                ),
                my_followed_specialization_types AS (
                    SELECT DISTINCT c.specialization_type
                    FROM community_follows cf
                    JOIN communities c ON c.id = cf.community_id
                    CROSS JOIN me
                    WHERE me.user_id IS NOT NULL
                      AND cf.user_id = me.user_id
                      AND c.kind = 'specialization'
                      AND c.specialization_type IS NOT NULL
                ),
                my_joined_specialization_types AS (
                    SELECT DISTINCT c.specialization_type
                    FROM specialization_joins sj2
                    JOIN communities c ON c.id = sj2.specialization_id
                    CROSS JOIN me
                    WHERE me.user_id IS NOT NULL
                      AND sj2.user_id = me.user_id
                      AND c.kind = 'specialization'
                      AND c.specialization_type IS NOT NULL
                ),
                ranked AS (
                    SELECT c.id, c.kind, c.name, c.short_name, c.description, c.member_count, c.image_url, c.specialization_type,
                           c.verification_ttl_days, c.created_at, c.icon_kind, c.icon_value, c.icon_updated_at,
                           CASE WHEN cf.user_id IS NULL THEN false ELSE true END AS is_following,
                           CASE WHEN sj.user_id IS NULL THEN false ELSE true END AS is_joined,
                           CAST(FLOOR(
                               -- Global quality baseline: popularity + recency.
                               LEAST(9000000, LN(1 + GREATEST(c.member_count, 0)::double precision) * 1200000) +
                               LEAST(300000, (1.0 / (1.0 + EXTRACT(EPOCH FROM (me.as_of - c.created_at)) / 86400.0)) * 300000) +
                               -- Personal boosts.
                               CASE WHEN mv.community_id IS NULL THEN 0 ELSE 900000 END +
                               CASE WHEN mvk.kind IS NULL THEN 0 ELSE 220000 END +
                               CASE WHEN mfk.kind IS NULL THEN 0 ELSE 140000 END +
                               CASE WHEN c.kind = 'specialization' AND mfs.specialization_type IS NOT NULL THEN 220000 ELSE 0 END +
                               CASE WHEN c.kind = 'specialization' AND mjs.specialization_type IS NOT NULL THEN 280000 ELSE 0 END +
                               -- Lightly de-prioritize already followed/joined rows.
                               CASE WHEN cf.user_id IS NULL THEN 0 ELSE -250000 END +
                               CASE WHEN sj.user_id IS NULL THEN 0 ELSE -250000 END
                           ) AS BIGINT) AS score
                    FROM communities c
                    CROSS JOIN me
                    LEFT JOIN community_follows cf
                           ON cf.community_id = c.id
                          AND cf.user_id = me.user_id
                    LEFT JOIN specialization_joins sj
                           ON sj.specialization_id = c.id
                          AND sj.user_id = me.user_id
                    LEFT JOIN my_verified mv ON mv.community_id = c.id
                    LEFT JOIN my_verified_kinds mvk ON mvk.kind = c.kind
                    LEFT JOIN my_followed_kinds mfk ON mfk.kind = c.kind
                    LEFT JOIN my_followed_specialization_types mfs
                           ON c.kind = 'specialization'
                          AND mfs.specialization_type = c.specialization_type
                    LEFT JOIN my_joined_specialization_types mjs
                           ON c.kind = 'specialization'
                          AND mjs.specialization_type = c.specialization_type
                    WHERE c.created_at <= me.as_of
                )
                SELECT *
                FROM ranked
                """);
        List<Object> params = new ArrayList<>();
        params.add(userId);
        params.add(asOf == null ? OffsetDateTime.now() : asOf);

        boolean hasWhere = false;
        if (kind != null && !kind.isBlank()) {
            sql.append("WHERE kind = ? ");
            params.add(kind);
            hasWhere = true;
        }
        if (specializationType != null && !specializationType.isBlank()) {
            sql.append(hasWhere ? "AND " : "WHERE ");
            sql.append("specialization_type = ? ");
            params.add(specializationType);
            hasWhere = true;
        }
        if (cursorScore != null && cursorCreatedAt != null && cursorId != null) {
            sql.append(hasWhere ? "AND " : "WHERE ");
            sql.append("(score < ? OR (score = ? AND (created_at < ? OR (created_at = ? AND id < ?)))) ");
            params.add(cursorScore);
            params.add(cursorScore);
            params.add(cursorCreatedAt);
            params.add(cursorCreatedAt);
            params.add(cursorId);
        }
        sql.append("ORDER BY score DESC, created_at DESC, id DESC LIMIT ?");
        params.add(limit);
        return jdbc.query(
                sql.toString(),
                (rs, rowNum) -> {
                    RecommendedRow row = new RecommendedRow();
                    row.id = rs.getLong("id");
                    row.kind = rs.getString("kind");
                    row.name = rs.getString("name");
                    row.shortName = rs.getString("short_name");
                    row.description = rs.getString("description");
                    row.memberCount = rs.getInt("member_count");
                    row.imageUrl = rs.getString("image_url");
                    row.specializationType = rs.getString("specialization_type");
                    int ttlDays = rs.getInt("verification_ttl_days");
                    row.verificationTtlDays = rs.wasNull() ? null : ttlDays;
                    row.createdAt = rs.getObject("created_at", OffsetDateTime.class);
                    row.iconKind = rs.getString("icon_kind");
                    row.iconValue = rs.getString("icon_value");
                    row.iconUpdatedAt = rs.getObject("icon_updated_at", OffsetDateTime.class);
                    row.isFollowing = rs.getBoolean("is_following");
                    row.isJoined = rs.getBoolean("is_joined");
                    row.score = rs.getLong("score");
                    return row;
                },
                params.toArray()
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
        return insert(kind, name, description, imageUrl, verificationTtlDays, specializationType, shortName, null);
    }

    public long insert(String kind, String name, String description, String imageUrl,
                       Integer verificationTtlDays, String specializationType, String shortName,
                       Integer specializationJoinCooldownMonths) {
        Long id = jdbc.query(
                "INSERT INTO communities(kind, name, description, image_url, verification_ttl_days, specialization_type, short_name, specialization_join_cooldown_months) " +
                        "VALUES (?,?,?,?,?,?,?,?) RETURNING id",
                rs -> rs.next() ? rs.getLong(1) : null,
                kind, name, description, imageUrl, verificationTtlDays, specializationType, shortName, specializationJoinCooldownMonths
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
        public Integer specializationJoinCooldownMonths;
        public String iconKind;
        public String iconValue;
        public OffsetDateTime iconUpdatedAt;
    }

    public record KindFilter(String kind, String specializationType) {}

    public static class ScoredCommunityRow {
        public CommunityRow community;
        public long score;
    }

    public static class RecommendedRow {
        public long id;
        public String kind;
        public String name;
        public String shortName;
        public String description;
        public int memberCount;
        public String imageUrl;
        public String specializationType;
        public boolean isFollowing;
        public boolean isJoined;
        public Integer verificationTtlDays;
        public OffsetDateTime createdAt;
        public String iconKind;
        public String iconValue;
        public OffsetDateTime iconUpdatedAt;
        public long score;
    }

    public List<SpecializationFilterRow> listSpecializationsForFilters(String specializationType) {
        if (specializationType == null || specializationType.isBlank()) return List.of();
        return jdbc.query(
                "SELECT id, name, short_name, icon_kind, icon_value " +
                        "FROM communities " +
                        "WHERE kind = 'specialization' AND specialization_type = ? " +
                        "ORDER BY lower(name) ASC, id ASC",
                (rs, rowNum) -> new SpecializationFilterRow(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("short_name"),
                        rs.getString("icon_kind"),
                        rs.getString("icon_value")
                ),
                specializationType
        );
    }

    public SpecializationsCacheInfo specializationsCacheInfo(String specializationType) {
        if (specializationType == null || specializationType.isBlank()) {
            return new SpecializationsCacheInfo(null, null);
        }
        return jdbc.query(
                """
                SELECT
                  md5(COALESCE(string_agg(
                    concat_ws('|',
                      id::text,
                      name,
                      COALESCE(short_name,''),
                      COALESCE(icon_kind,''),
                      COALESCE(icon_value,'')
                    ),
                    ';' ORDER BY lower(name), id
                  ), '')) AS etag,
                  MAX(GREATEST(created_at, icon_updated_at)) AS last_modified
                FROM communities
                WHERE kind = 'specialization' AND specialization_type = ?
                """,
                rs -> {
                    if (!rs.next()) return new SpecializationsCacheInfo(null, null);
                    String etag = rs.getString("etag");
                    OffsetDateTime lm = rs.getObject("last_modified", OffsetDateTime.class);
                    return new SpecializationsCacheInfo(etag, lm);
                },
                specializationType
        );
    }

    public boolean updateSpecializationIconAndName(long communityId,
                                                   String requiredSpecializationType,
                                                   boolean nameProvided,
                                                   String name,
                                                   boolean iconProvided,
                                                   String iconKind,
                                                   String iconValue) {
        boolean hasUpdate = nameProvided || iconProvided;
        if (!hasUpdate) return false;
        if (requiredSpecializationType == null || requiredSpecializationType.isBlank()) return false;
        StringBuilder sql = new StringBuilder("UPDATE communities SET ");
        java.util.List<Object> params = new java.util.ArrayList<>();
        boolean first = true;
        if (nameProvided) {
            sql.append("name = ?");
            params.add(name);
            first = false;
        }
        if (iconProvided) {
            if (!first) sql.append(", ");
            sql.append("icon_kind = ?, icon_value = ?");
            params.add(iconKind);
            params.add(iconValue);
            first = false;
        }
        if (!first) sql.append(", ");
        sql.append("icon_updated_at = now()");
        sql.append(" WHERE id = ? AND kind = 'specialization' AND specialization_type = ?");
        params.add(communityId);
        params.add(requiredSpecializationType);
        int rows = jdbc.update(sql.toString(), params.toArray());
        return rows > 0;
    }

    public record SpecializationFilterRow(long id, String name, String shortName, String iconKind, String iconValue) {}

    public record SpecializationsCacheInfo(String etagMd5, OffsetDateTime lastModified) {}

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
        return updateDetails(communityId, descriptionProvided, description, ttlDays, shortNameProvided, shortName, null);
    }

    public boolean updateDetails(long communityId, boolean descriptionProvided, String description,
                                 Integer ttlDays, boolean shortNameProvided, String shortName,
                                 Integer specializationJoinCooldownMonths) {
        boolean hasUpdate = descriptionProvided || ttlDays != null || shortNameProvided || specializationJoinCooldownMonths != null;
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
            first = false;
        }
        if (specializationJoinCooldownMonths != null) {
            if (!first) sql.append(", ");
            sql.append("specialization_join_cooldown_months = ?");
            params.add(specializationJoinCooldownMonths == 0 ? null : specializationJoinCooldownMonths);
        }
        sql.append(" WHERE id = ?");
        params.add(communityId);
        int rows = jdbc.update(sql.toString(), params.toArray());
        return rows > 0;
    }

    public boolean updateNameNonSpecialization(long communityId, String name) {
        if (name == null || name.isBlank()) return false;
        int rows = jdbc.update(
                "UPDATE communities SET name = ? WHERE id = ? AND kind <> 'specialization'",
                name, communityId
        );
        return rows > 0;
    }

    public boolean updateKindAndSpecializationType(long communityId, String kind, String specializationType) {
        if (kind == null || kind.isBlank()) return false;
        int rows = jdbc.update(
                "UPDATE communities SET kind = ?, specialization_type = ? WHERE id = ?",
                kind, specializationType, communityId
        );
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

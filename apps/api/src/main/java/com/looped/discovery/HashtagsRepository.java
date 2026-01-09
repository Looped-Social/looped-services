package com.looped.discovery;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;

@Repository
public class HashtagsRepository {
    private final JdbcTemplate jdbc;

    public HashtagsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<HashtagRow> MAPPER = new RowMapper<>() {
        @Override
        public HashtagRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            HashtagRow row = new HashtagRow();
            row.id = rs.getLong("id");
            row.companyId = rs.getLong("company_id");
            row.name = rs.getString("name");
            row.usageCount = rs.getInt("usage_count");
            row.createdAt = rs.getObject("created_at", OffsetDateTime.class);
            return row;
        }
    };

    public List<HashtagRow> search(long companyId, String query, OffsetDateTime cursorTs, Long cursorId, int limit) {
        String like = "%" + query.toLowerCase() + "%";
        if (cursorTs == null || cursorId == null) {
            return jdbc.query(
                    "SELECT id, company_id, name, usage_count, created_at " +
                            "FROM hashtags WHERE company_id = ? AND LOWER(name) LIKE ? " +
                            "ORDER BY created_at DESC, id DESC LIMIT ?",
                    MAPPER, companyId, like, limit
            );
        }
        return jdbc.query(
                "SELECT id, company_id, name, usage_count, created_at " +
                        "FROM hashtags WHERE company_id = ? AND LOWER(name) LIKE ? " +
                        "AND (created_at < ? OR (created_at = ? AND id < ?)) " +
                        "ORDER BY created_at DESC, id DESC LIMIT ?",
                MAPPER, companyId, like, cursorTs, cursorTs, cursorId, limit
        );
    }

    private static final RowMapper<ScoredHashtagRow> SCORED_MAPPER = new RowMapper<>() {
        @Override
        public ScoredHashtagRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            ScoredHashtagRow out = new ScoredHashtagRow();
            HashtagRow row = MAPPER.mapRow(rs, rowNum);
            out.hashtag = row;
            out.score = rs.getLong("score");
            return out;
        }
    };

    public List<ScoredHashtagRow> searchRanked(long companyId, String query, String prefixQuery,
                                               OffsetDateTime asOf, Long cursorScore, OffsetDateTime cursorTs, Long cursorId, int limit) {
        String vector = "to_tsvector('simple', COALESCE(h.name,''))";
        String match = "(" + vector + " @@ q.q_web OR (q.q_prefix IS NOT NULL AND " + vector + " @@ q.q_prefix))";
        String rank = "GREATEST(" +
                "ts_rank_cd(" + vector + ", q.q_web), " +
                "COALESCE(ts_rank_cd(" + vector + ", q.q_prefix), 0)" +
                ")";

        String exact = "CASE WHEN LOWER(h.name) = LOWER(?) THEN 900000 ELSE 0 END";
        String prefix = "CASE WHEN LOWER(h.name) LIKE LOWER(?) || '%' THEN 500000 ELSE 0 END";
        String boost = "(" + exact + " + " + prefix + ")";

        String popularity = "LEAST(200000, LN(1 + GREATEST(h.usage_count, 0)) * 70000)";
        String recency = "LEAST(50000, (1.0 / (1.0 + EXTRACT(EPOCH FROM (t.as_of - h.created_at)) / 86400.0)) * 50000)";
        String scoreExpr = "CAST((" + rank + " * 1000000 + " + boost + " + " + popularity + " + " + recency + ") AS BIGINT)";

        String base =
                "WITH q AS (" +
                        "SELECT websearch_to_tsquery('simple', ?) AS q_web, " +
                        "to_tsquery('simple', NULLIF(?, '')) AS q_prefix" +
                        "), t AS (SELECT ?::timestamptz AS as_of) " +
                        "SELECT id, company_id, name, usage_count, created_at, " + scoreExpr + " AS score " +
                        "FROM hashtags h CROSS JOIN q CROSS JOIN t " +
                        "WHERE h.company_id = ? AND " + match;

        String order = " ORDER BY score DESC, created_at DESC, id DESC LIMIT ?";

        if (cursorScore == null || cursorTs == null || cursorId == null) {
            return jdbc.query(
                    "SELECT * FROM (" + base + ") s" + order,
                    SCORED_MAPPER,
                    query, prefixQuery, asOf,
                    query, query,
                    companyId,
                    limit
            );
        }
        return jdbc.query(
                "SELECT * FROM (" + base + ") s " +
                        "WHERE (score < ? OR (score = ? AND (created_at < ? OR (created_at = ? AND id < ?)))) " +
                        "ORDER BY score DESC, created_at DESC, id DESC LIMIT ?",
                SCORED_MAPPER,
                query, prefixQuery, asOf,
                query, query,
                companyId,
                cursorScore, cursorScore, cursorTs, cursorTs, cursorId,
                limit
        );
    }

    public long upsert(long companyId, String name) {
        Long id = jdbc.query(
                "INSERT INTO hashtags(company_id, name, usage_count) VALUES (?,?,1) " +
                        "ON CONFLICT (company_id, name) DO UPDATE SET usage_count = hashtags.usage_count + 1 " +
                        "RETURNING id",
                rs -> rs.next() ? rs.getLong(1) : null,
                companyId, name
        );
        if (id == null) {
            throw new IllegalStateException("Failed to upsert hashtag");
        }
        return id;
    }

    public static class HashtagRow {
        public long id;
        public long companyId;
        public String name;
        public int usageCount;
        public OffsetDateTime createdAt;
    }

    public static class ScoredHashtagRow {
        public HashtagRow hashtag;
        public long score;
    }
}

package com.looped.admin;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Repository
public class AdminPostSearchRepository {
    private final JdbcTemplate jdbc;

    public AdminPostSearchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<ScoredPostRow> MAPPER = new RowMapper<>() {
        @Override
        public ScoredPostRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            ScoredPostRow row = new ScoredPostRow();
            row.id = rs.getLong("id");
            row.authorId = (Long) rs.getObject("author_id");
            row.companyId = (Long) rs.getObject("company_id");
            row.communityId = (Long) rs.getObject("community_id");
            row.contentSnippet = rs.getString("content_snippet");
            row.createdAt = rs.getObject("created_at", OffsetDateTime.class);
            row.removedAt = rs.getObject("removed_at", OffsetDateTime.class);
            row.score = rs.getLong("score");
            return row;
        }
    };

    public List<ScoredPostRow> searchFuzzy(
            String query,
            String prefixQuery,
            OffsetDateTime asOf,
            Long cursorScore,
            OffsetDateTime cursorTs,
            Long cursorId,
            int limit,
            StatusFilter status,
            Long companyId,
            Long communityId,
            Long authorId,
            OffsetDateTime from,
            OffsetDateTime toExclusive
    ) {
        String vectorEn = "to_tsvector('english', COALESCE(p.content, ''))";
        String vectorSimple = "to_tsvector('simple', COALESCE(p.content, ''))";
        String ftsMatch = "(" + vectorEn + " @@ q.q_web OR (q.q_prefix IS NOT NULL AND " + vectorSimple + " @@ q.q_prefix))";
        String ftsRank = "GREATEST(" +
                "ts_rank_cd(" + vectorEn + ", q.q_web), " +
                "COALESCE(ts_rank_cd(" + vectorSimple + ", q.q_prefix), 0)" +
                ")";

        // Typo tolerance via pg_trgm word_similarity().
        // We compute it once per row (CROSS JOIN LATERAL) and use a conservative threshold for filtering.
        String trigramMatches = "(q.q_len >= 2 AND trgm.trgm >= 0.25)";
        String containsMatch = "(q.q_len >= 3 AND LOWER(COALESCE(p.content,'')) LIKE '%' || q.q_l || '%')";
        String match = "(" + ftsMatch + " OR " + trigramMatches + " OR " + containsMatch + ")";

        String recency = "LEAST(100000, (1.0 / (1.0 + EXTRACT(EPOCH FROM (ctx.as_of - p.created_at)) / 86400.0)) * 100000)";
        String scoreExpr = "CAST((" +
                "((" + ftsRank + ") * 1000000) " +
                "+ (trgm.trgm * 800000) " +
                "+ " + recency +
                ") AS BIGINT)";

        String base =
                "WITH q AS (" +
                        "SELECT websearch_to_tsquery('english', ?) AS q_web, " +
                        "to_tsquery('simple', NULLIF(?, '')) AS q_prefix, " +
                        "LOWER(TRIM(?)) AS q_l, " +
                        "LENGTH(TRIM(?))::INT AS q_len" +
                        "), ctx AS (" +
                        "SELECT ?::timestamptz AS as_of" +
                        ") " +
                        "SELECT p.id, p.author_id, p.company_id, p.community_id, " +
                        "SUBSTRING(REGEXP_REPLACE(COALESCE(p.content,''), '\\\\s+', ' ', 'g') FOR 200) AS content_snippet, " +
                        "p.created_at, p.removed_at, " +
                        scoreExpr + " AS score " +
                        "FROM posts p " +
                        "CROSS JOIN q " +
                        "CROSS JOIN ctx " +
                        "CROSS JOIN LATERAL (" +
                        "  SELECT CASE WHEN q.q_len < 2 THEN 0 ELSE word_similarity(q.q_l, LOWER(COALESCE(p.content,''))) END AS trgm" +
                        ") trgm " +
                        "WHERE p.created_at <= ctx.as_of AND " + match;

        StringBuilder sql = new StringBuilder();
        List<Object> params = new ArrayList<>();

        // q + ctx
        params.add(query);
        params.add(prefixQuery);
        params.add(query);
        params.add(query);
        params.add(asOf);

        // filters
        if (status == StatusFilter.ACTIVE) {
            base += " AND p.removed_at IS NULL";
        } else if (status == StatusFilter.REMOVED) {
            base += " AND p.removed_at IS NOT NULL";
        }
        if (companyId != null) {
            base += " AND p.company_id = ?";
            params.add(companyId);
        }
        if (communityId != null) {
            base += " AND p.community_id = ?";
            params.add(communityId);
        }
        if (authorId != null) {
            base += " AND p.author_id = ?";
            params.add(authorId);
        }
        if (from != null) {
            base += " AND p.created_at >= ?";
            params.add(from);
        }
        if (toExclusive != null) {
            base += " AND p.created_at < ?";
            params.add(toExclusive);
        }

        if (cursorScore == null || cursorTs == null || cursorId == null) {
            sql.append("SELECT * FROM (").append(base).append(") s ");
            sql.append("ORDER BY score DESC, created_at DESC, id DESC LIMIT ?");
            params.add(limit);
            return jdbc.query(sql.toString(), MAPPER, params.toArray());
        }

        sql.append("SELECT * FROM (").append(base).append(") s ");
        sql.append("WHERE (score < ? OR (score = ? AND (created_at < ? OR (created_at = ? AND id < ?)))) ");
        sql.append("ORDER BY score DESC, created_at DESC, id DESC LIMIT ?");
        params.add(cursorScore);
        params.add(cursorScore);
        params.add(cursorTs);
        params.add(cursorTs);
        params.add(cursorId);
        params.add(limit);
        return jdbc.query(sql.toString(), MAPPER, params.toArray());
    }

    public List<ScoredPostRow> searchByIdPrefix(
            long prefix,
            int prefixLen,
            OffsetDateTime asOf,
            Long cursorScore,
            OffsetDateTime cursorTs,
            Long cursorId,
            int limit,
            StatusFilter status,
            Long companyId,
            Long communityId,
            Long authorId,
            OffsetDateTime from,
            OffsetDateTime toExclusive
    ) {
        // Prefix matching over numeric ids using union-of-ranges to preserve btree index usage on posts.id.
        List<long[]> ranges = idPrefixRanges(prefix, prefixLen);
        if (ranges.isEmpty()) return List.of();

        StringBuilder base = new StringBuilder();
        List<Object> params = new ArrayList<>();

        base.append("SELECT p.id, p.author_id, p.company_id, p.community_id, ")
                .append("SUBSTRING(REGEXP_REPLACE(COALESCE(p.content,''), '\\\\s+', ' ', 'g') FOR 200) AS content_snippet, ")
                .append("p.created_at, p.removed_at, ")
                // Boost exact id match (when it exists) to the top.
                .append("CAST((CASE WHEN p.id = ? THEN 9223372036854775807 ELSE p.id END) AS BIGINT) AS score ")
                .append("FROM posts p WHERE p.created_at <= ? AND (");
        params.add(prefix);
        params.add(asOf);
        for (int i = 0; i < ranges.size(); i++) {
            if (i > 0) base.append(" OR ");
            base.append("(p.id BETWEEN ? AND ?)");
            params.add(ranges.get(i)[0]);
            params.add(ranges.get(i)[1]);
        }
        base.append(")");

        if (status == StatusFilter.ACTIVE) {
            base.append(" AND p.removed_at IS NULL");
        } else if (status == StatusFilter.REMOVED) {
            base.append(" AND p.removed_at IS NOT NULL");
        }
        if (companyId != null) {
            base.append(" AND p.company_id = ?");
            params.add(companyId);
        }
        if (communityId != null) {
            base.append(" AND p.community_id = ?");
            params.add(communityId);
        }
        if (authorId != null) {
            base.append(" AND p.author_id = ?");
            params.add(authorId);
        }
        if (from != null) {
            base.append(" AND p.created_at >= ?");
            params.add(from);
        }
        if (toExclusive != null) {
            base.append(" AND p.created_at < ?");
            params.add(toExclusive);
        }

        if (cursorScore == null || cursorTs == null || cursorId == null) {
            String sql = "SELECT * FROM (" + base + ") s ORDER BY score DESC, created_at DESC, id DESC LIMIT ?";
            params.add(limit);
            return jdbc.query(sql, MAPPER, params.toArray());
        }

        String sql = "SELECT * FROM (" + base + ") s " +
                "WHERE (score < ? OR (score = ? AND (created_at < ? OR (created_at = ? AND id < ?)))) " +
                "ORDER BY score DESC, created_at DESC, id DESC LIMIT ?";
        params.add(cursorScore);
        params.add(cursorScore);
        params.add(cursorTs);
        params.add(cursorTs);
        params.add(cursorId);
        params.add(limit);
        return jdbc.query(sql, MAPPER, params.toArray());
    }

    private static List<long[]> idPrefixRanges(long prefix, int prefixLen) {
        if (prefixLen <= 0) return List.of();
        if (prefix < 0) return List.of();
        int maxDigits = 19; // BIGINT max is 19 digits
        int maxExp = Math.max(0, maxDigits - prefixLen);
        long pow10 = 1;
        List<long[]> out = new ArrayList<>();
        for (int exp = 0; exp <= maxExp; exp++) {
            try {
                long start = Math.multiplyExact(prefix, pow10);
                long end = Math.subtractExact(Math.multiplyExact(prefix + 1, pow10), 1);
                out.add(new long[]{start, end});
            } catch (ArithmeticException overflow) {
                break;
            }
            if (exp == maxExp) break;
            if (pow10 > Long.MAX_VALUE / 10) break;
            pow10 *= 10;
        }
        return out;
    }

    public enum StatusFilter { ACTIVE, REMOVED, ALL }

    public static class ScoredPostRow {
        public long id;
        public Long authorId;
        public Long companyId;
        public Long communityId;
        public String contentSnippet;
        public OffsetDateTime createdAt;
        public OffsetDateTime removedAt;
        public long score;
    }
}

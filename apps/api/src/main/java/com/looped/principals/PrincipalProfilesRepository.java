package com.looped.principals;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Repository
public class PrincipalProfilesRepository {
    private final JdbcTemplate jdbc;

    public PrincipalProfilesRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<PrincipalProfileRow> MAPPER = new RowMapper<>() {
        @Override
        public PrincipalProfileRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            PrincipalProfileRow row = new PrincipalProfileRow();
            row.principalId = rs.getLong("principal_id");
            row.kind = rs.getString("kind");
            long userId = rs.getLong("user_id");
            row.userId = rs.wasNull() ? null : userId;
            long anonProfileId = rs.getLong("anon_profile_id");
            row.anonProfileId = rs.wasNull() ? null : anonProfileId;
            row.handle = rs.getString("handle");
            row.displayName = rs.getString("display_name");
            row.profileImageUrl = rs.getString("profile_image_url");
            long companyId = rs.getLong("company_id");
            row.companyId = rs.wasNull() ? null : companyId;
            row.isAnonymous = rs.getBoolean("is_anonymous");
            row.followCreatedAt = rs.getObject("follow_created_at", OffsetDateTime.class);
            return row;
        }
    };

    public List<PrincipalProfileRow> followers(long followeePrincipalId, OffsetDateTime cursorTs, Long cursorPrincipalId, int limit) {
        return followers(followeePrincipalId, cursorTs, cursorPrincipalId, limit, null);
    }

    public List<PrincipalProfileRow> followers(long followeePrincipalId, OffsetDateTime cursorTs, Long cursorPrincipalId, int limit, String query) {
        String like = normalizeLikeQuery(query);
        boolean hasQuery = like != null;

        var sql = new StringBuilder(baseFollowersQuery());
        var params = new ArrayList<>();
        params.add(followeePrincipalId);

        if (hasQuery) {
            sql.append(" AND (LOWER(COALESCE(u.handle, ap.handle)) LIKE ? OR LOWER(COALESCE(u.display_name, '')) LIKE ?)");
            params.add(like);
            params.add(like);
        }

        if (cursorTs != null && cursorPrincipalId != null) {
            sql.append(" AND (f.created_at < ? OR (f.created_at = ? AND p.id < ?))");
            params.add(cursorTs);
            params.add(cursorTs);
            params.add(cursorPrincipalId);
        }

        sql.append(" ORDER BY f.created_at DESC, p.id DESC LIMIT ?");
        params.add(limit);
        return jdbc.query(sql.toString(), MAPPER, params.toArray());
    }

    public List<PrincipalProfileRow> following(long followerPrincipalId, OffsetDateTime cursorTs, Long cursorPrincipalId, int limit) {
        return following(followerPrincipalId, cursorTs, cursorPrincipalId, limit, null);
    }

    public List<PrincipalProfileRow> following(long followerPrincipalId, OffsetDateTime cursorTs, Long cursorPrincipalId, int limit, String query) {
        String like = normalizeLikeQuery(query);
        boolean hasQuery = like != null;

        var sql = new StringBuilder(baseFollowingQuery());
        var params = new ArrayList<>();
        params.add(followerPrincipalId);

        if (hasQuery) {
            sql.append(" AND (LOWER(COALESCE(u.handle, ap.handle)) LIKE ? OR LOWER(COALESCE(u.display_name, '')) LIKE ?)");
            params.add(like);
            params.add(like);
        }

        if (cursorTs != null && cursorPrincipalId != null) {
            sql.append(" AND (f.created_at < ? OR (f.created_at = ? AND p.id < ?))");
            params.add(cursorTs);
            params.add(cursorTs);
            params.add(cursorPrincipalId);
        }

        sql.append(" ORDER BY f.created_at DESC, p.id DESC LIMIT ?");
        params.add(limit);
        return jdbc.query(sql.toString(), MAPPER, params.toArray());
    }

    public List<PrincipalProfileRow> blocked(long blockerPrincipalId, OffsetDateTime cursorTs, Long cursorPrincipalId, int limit) {
        if (cursorTs == null || cursorPrincipalId == null) {
            return jdbc.query(baseBlockedQuery() +
                            " ORDER BY b.created_at DESC, p.id DESC LIMIT ?",
                    MAPPER, blockerPrincipalId, limit);
        }
        return jdbc.query(baseBlockedQuery() +
                        " AND (b.created_at < ? OR (b.created_at = ? AND p.id < ?)) " +
                        " ORDER BY b.created_at DESC, p.id DESC LIMIT ?",
                MAPPER, blockerPrincipalId, cursorTs, cursorTs, cursorPrincipalId, limit);
    }

    private String baseFollowersQuery() {
        return "SELECT p.id AS principal_id, p.kind, p.user_id, p.anon_profile_id, " +
                "COALESCE(u.handle, ap.handle) AS handle, " +
                "u.display_name, u.profile_image_url, " +
                "COALESCE(u.company_id, ap.company_id) AS company_id, " +
                "CASE WHEN p.kind = 'anon' THEN true ELSE COALESCE(u.is_anonymous, false) END AS is_anonymous, " +
                "f.created_at AS follow_created_at " +
                "FROM principal_follows f " +
                "JOIN principals p ON p.id = f.follower_principal_id " +
                "LEFT JOIN users u ON u.id = p.user_id AND u.deleted_at IS NULL " +
                "LEFT JOIN anonymous_profiles ap ON ap.id = p.anon_profile_id " +
                "WHERE f.followee_principal_id = ? AND (p.kind = 'anon' OR u.id IS NOT NULL)";
    }

    private String baseFollowingQuery() {
        return "SELECT p.id AS principal_id, p.kind, p.user_id, p.anon_profile_id, " +
                "COALESCE(u.handle, ap.handle) AS handle, " +
                "u.display_name, u.profile_image_url, " +
                "COALESCE(u.company_id, ap.company_id) AS company_id, " +
                "CASE WHEN p.kind = 'anon' THEN true ELSE COALESCE(u.is_anonymous, false) END AS is_anonymous, " +
                "f.created_at AS follow_created_at " +
                "FROM principal_follows f " +
                "JOIN principals p ON p.id = f.followee_principal_id " +
                "LEFT JOIN users u ON u.id = p.user_id AND u.deleted_at IS NULL " +
                "LEFT JOIN anonymous_profiles ap ON ap.id = p.anon_profile_id " +
                "WHERE f.follower_principal_id = ? AND (p.kind = 'anon' OR u.id IS NOT NULL)";
    }

    private String baseBlockedQuery() {
        return "SELECT p.id AS principal_id, p.kind, p.user_id, p.anon_profile_id, " +
                "COALESCE(u.handle, ap.handle) AS handle, " +
                "u.display_name, u.profile_image_url, " +
                "COALESCE(u.company_id, ap.company_id) AS company_id, " +
                "CASE WHEN p.kind = 'anon' THEN true ELSE COALESCE(u.is_anonymous, false) END AS is_anonymous, " +
                "b.created_at AS follow_created_at " +
                "FROM principal_blocks b " +
                "JOIN principals p ON p.id = b.blocked_principal_id " +
                "LEFT JOIN users u ON u.id = p.user_id AND u.deleted_at IS NULL " +
                "LEFT JOIN anonymous_profiles ap ON ap.id = p.anon_profile_id " +
                "WHERE b.blocker_principal_id = ? AND (p.kind = 'anon' OR u.id IS NOT NULL)";
    }

    private String normalizeLikeQuery(String query) {
        if (query == null) return null;
        String trimmed = query.trim().toLowerCase(Locale.ROOT);
        if (trimmed.isBlank()) return null;
        if (trimmed.length() > 100) trimmed = trimmed.substring(0, 100);
        return "%" + trimmed + "%";
    }

    public static class PrincipalProfileRow {
        public long principalId;
        public String kind;
        public Long userId;
        public Long anonProfileId;
        public String handle;
        public String displayName;
        public String profileImageUrl;
        public Long companyId;
        public boolean isAnonymous;
        public OffsetDateTime followCreatedAt;
    }
}

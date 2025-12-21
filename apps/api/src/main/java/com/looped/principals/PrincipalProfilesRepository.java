package com.looped.principals;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;

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
        if (cursorTs == null || cursorPrincipalId == null) {
            return jdbc.query(baseFollowersQuery() +
                            " ORDER BY f.created_at DESC, p.id DESC LIMIT ?",
                    MAPPER, followeePrincipalId, limit);
        }
        return jdbc.query(baseFollowersQuery() +
                        " AND (f.created_at < ? OR (f.created_at = ? AND p.id < ?)) " +
                        " ORDER BY f.created_at DESC, p.id DESC LIMIT ?",
                MAPPER, followeePrincipalId, cursorTs, cursorTs, cursorPrincipalId, limit);
    }

    public List<PrincipalProfileRow> following(long followerPrincipalId, OffsetDateTime cursorTs, Long cursorPrincipalId, int limit) {
        if (cursorTs == null || cursorPrincipalId == null) {
            return jdbc.query(baseFollowingQuery() +
                            " ORDER BY f.created_at DESC, p.id DESC LIMIT ?",
                    MAPPER, followerPrincipalId, limit);
        }
        return jdbc.query(baseFollowingQuery() +
                        " AND (f.created_at < ? OR (f.created_at = ? AND p.id < ?)) " +
                        " ORDER BY f.created_at DESC, p.id DESC LIMIT ?",
                MAPPER, followerPrincipalId, cursorTs, cursorTs, cursorPrincipalId, limit);
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
                "WHERE f.followee_principal_id = ?";
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
                "WHERE f.follower_principal_id = ?";
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

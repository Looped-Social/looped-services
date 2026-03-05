package com.looped.users;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;

@Repository
public class UserRepository {
    private final JdbcTemplate jdbcTemplate;
    private static final String BASE_COLUMNS = "id, firebase_uid, handle, email, company_id, first_name, last_name, " +
            "date_of_birth, display_name, bio, is_anonymous, show_follower_count, message_permission, profile_image_url, " +
            "hide_anonymous_posts, display_community_id, display_specialization_id, onboarding_step, onboarding_completed_at, " +
            "profile_completion_dismissed_at, profile_completion_completed_at, last_app_open_at, " +
            "created_at, disabled_at, disabled_reason, disabled_by_admin_id, " +
            "deleted_at, deleted_by, deleted_source, deleted_by_admin_id, deleted_reason";
    private static final String BASE_COLUMNS_U = "u.id, u.firebase_uid, u.handle, u.email, u.company_id, u.first_name, u.last_name, " +
            "u.date_of_birth, u.display_name, u.bio, u.is_anonymous, u.show_follower_count, u.message_permission, u.profile_image_url, " +
            "u.hide_anonymous_posts, u.display_community_id, u.display_specialization_id, u.onboarding_step, u.onboarding_completed_at, " +
            "u.profile_completion_dismissed_at, u.profile_completion_completed_at, u.last_app_open_at, " +
            "u.created_at, u.disabled_at, u.disabled_reason, u.disabled_by_admin_id, " +
            "u.deleted_at, u.deleted_by, u.deleted_source, u.deleted_by_admin_id, u.deleted_reason";

    public UserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<UserRow> MAPPER = new RowMapper<>() {
        @Override
        public UserRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            UserRow row = new UserRow();
            row.id = rs.getLong("id");
            row.firebaseUid = rs.getString("firebase_uid");
            row.handle = rs.getString("handle");
            row.email = rs.getString("email");
            long company = rs.getLong("company_id");
            row.companyId = rs.wasNull() ? null : company;
            row.firstName = rs.getString("first_name");
            row.lastName = rs.getString("last_name");
            row.dateOfBirth = rs.getObject("date_of_birth", java.time.LocalDate.class);
            row.displayName = rs.getString("display_name");
            row.bio = rs.getString("bio");
            row.isAnonymous = rs.getBoolean("is_anonymous");
            row.showFollowerCount = rs.getBoolean("show_follower_count");
            row.messagePermission = rs.getString("message_permission");
            row.profileImageUrl = rs.getString("profile_image_url");
            row.hideAnonymousPosts = rs.getBoolean("hide_anonymous_posts");
            long displayCommunity = rs.getLong("display_community_id");
            row.displayCommunityId = rs.wasNull() ? null : displayCommunity;
            long displaySpecialization = rs.getLong("display_specialization_id");
            row.displaySpecializationId = rs.wasNull() ? null : displaySpecialization;
            row.onboardingStep = rs.getString("onboarding_step");
            row.onboardingCompletedAt = rs.getObject("onboarding_completed_at", OffsetDateTime.class);
            row.profileCompletionDismissedAt = rs.getObject("profile_completion_dismissed_at", OffsetDateTime.class);
            row.profileCompletionCompletedAt = rs.getObject("profile_completion_completed_at", OffsetDateTime.class);
            row.lastAppOpenAt = rs.getObject("last_app_open_at", OffsetDateTime.class);
            row.createdAt = rs.getObject("created_at", OffsetDateTime.class);
            row.disabledAt = rs.getObject("disabled_at", OffsetDateTime.class);
            row.disabledReason = rs.getString("disabled_reason");
            long disabledByAdminId = rs.getLong("disabled_by_admin_id");
            row.disabledByAdminId = rs.wasNull() ? null : disabledByAdminId;
            row.deletedAt = rs.getObject("deleted_at", OffsetDateTime.class);
            long deletedBy = rs.getLong("deleted_by");
            row.deletedBy = rs.wasNull() ? null : deletedBy;
            row.deletedSource = rs.getString("deleted_source");
            long deletedByAdminId = rs.getLong("deleted_by_admin_id");
            row.deletedByAdminId = rs.wasNull() ? null : deletedByAdminId;
            row.deletedReason = rs.getString("deleted_reason");
            return row;
        }
    };

    public Optional<UserRow> findByFirebaseUid(String firebaseUid) {
        var list = jdbcTemplate.query(
                "SELECT " + BASE_COLUMNS + " FROM users WHERE firebase_uid = ? AND deleted_at IS NULL",
                MAPPER, firebaseUid
        );
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public Optional<UserRow> findById(long userId) {
        var list = jdbcTemplate.query(
                "SELECT " + BASE_COLUMNS + " FROM users WHERE id = ? AND deleted_at IS NULL",
                MAPPER, userId
        );
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public Optional<UserRow> findByFirebaseUidIncludingDeleted(String firebaseUid) {
        var list = jdbcTemplate.query(
                "SELECT " + BASE_COLUMNS + " FROM users WHERE firebase_uid = ?",
                MAPPER, firebaseUid
        );
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public Optional<UserRow> findByIdIncludingDeleted(long userId) {
        var list = jdbcTemplate.query(
                "SELECT " + BASE_COLUMNS + " FROM users WHERE id = ?",
                MAPPER, userId
        );
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public Optional<UserRow> findByHandle(String handle) {
        if (handle == null || handle.isBlank()) return Optional.empty();
        var list = jdbcTemplate.query(
                "SELECT " + BASE_COLUMNS + " FROM users WHERE LOWER(handle) = LOWER(?) AND deleted_at IS NULL",
                MAPPER, handle
        );
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public Optional<UserRow> findByHandleIncludingDeleted(String handle) {
        if (handle == null || handle.isBlank()) return Optional.empty();
        var list = jdbcTemplate.query(
                "SELECT " + BASE_COLUMNS + " FROM users WHERE LOWER(handle) = LOWER(?)",
                MAPPER, handle
        );
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public java.util.List<UserRow> findByHandlesInCompany(long companyId, java.util.Set<String> handles) {
        if (handles == null || handles.isEmpty()) return java.util.List.of();
        var normalized = handles.stream()
                .filter(h -> h != null && !h.isBlank())
                .map(h -> h.toLowerCase(java.util.Locale.ROOT))
                .distinct()
                .toList();
        if (normalized.isEmpty()) return java.util.List.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(normalized.size(), "?"));
        Object[] params = new Object[normalized.size() + 1];
        params[0] = companyId;
        for (int i = 0; i < normalized.size(); i++) {
            params[i + 1] = normalized.get(i);
        }
        return jdbcTemplate.query(
                "SELECT " + BASE_COLUMNS + " FROM users WHERE company_id = ? AND deleted_at IS NULL AND LOWER(handle) IN (" + placeholders + ")",
                MAPPER, params
        );
    }

    public void updateProfile(long userId, String displayName, String bio, boolean isAnonymous,
                              Boolean showFollowerCount, String messagePermission, String profileImageUrl) {
        jdbcTemplate.update(
                "UPDATE users SET display_name = ?, bio = ?, is_anonymous = ?, " +
                        "show_follower_count = COALESCE(?, show_follower_count), " +
                        "message_permission = COALESCE(?, message_permission), " +
                        "profile_image_url = COALESCE(?, profile_image_url) WHERE id = ?",
                displayName, bio, isAnonymous, showFollowerCount, messagePermission, profileImageUrl, userId
        );
    }

    public void updateHideAnonymousPosts(long userId, boolean hideAnonymousPosts) {
        jdbcTemplate.update(
                "UPDATE users SET hide_anonymous_posts = ? WHERE id = ?",
                hideAnonymousPosts, userId
        );
    }

    public void updateDisplayCommunity(long userId, Long communityId) {
        jdbcTemplate.update(
                "UPDATE users SET display_community_id = ? WHERE id = ?",
                communityId, userId
        );
    }

    public void setDisplayCommunityIfAbsent(long userId, long communityId) {
        jdbcTemplate.update(
                "UPDATE users SET display_community_id = ? WHERE id = ? AND display_community_id IS NULL",
                communityId, userId
        );
    }

    public void updateDisplaySpecialization(long userId, Long specializationId) {
        jdbcTemplate.update(
                "UPDATE users SET display_specialization_id = ? WHERE id = ?",
                specializationId, userId
        );
    }

    public void setDisplaySpecializationIfAbsent(long userId, long specializationId) {
        jdbcTemplate.update(
                "UPDATE users SET display_specialization_id = ? WHERE id = ? AND display_specialization_id IS NULL",
                specializationId, userId
        );
    }

    public java.util.Optional<DisplayCommunityRow> findDisplayCommunityForUser(long userId) {
        var list = jdbcTemplate.query(
                "SELECT c.id, c.name, c.short_name, c.kind, c.specialization_type " +
                        "FROM users u " +
                        "JOIN community_verifications cv ON cv.user_id = u.id AND cv.community_id = u.display_community_id " +
                        "JOIN communities c ON c.id = cv.community_id " +
                        "WHERE u.id = ? AND cv.verified = true " +
                        "AND (cv.expires_at IS NULL OR cv.expires_at > now()) " +
                        "AND lower(c.kind) <> 'school' " +
                        "AND NOT (c.kind = 'specialization' AND lower(COALESCE(c.specialization_type, '')) = 'major')",
                (rs, rowNum) -> {
                    DisplayCommunityRow row = new DisplayCommunityRow();
                    row.id = rs.getLong("id");
                    row.name = rs.getString("name");
                    row.shortName = rs.getString("short_name");
                    row.kind = rs.getString("kind");
                    row.specializationType = rs.getString("specialization_type");
                    return row;
                },
                userId
        );
        return list.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(list.get(0));
    }

    public java.util.Optional<DisplaySpecializationRow> findDisplaySpecializationForUser(long userId) {
        var list = jdbcTemplate.query(
                "SELECT c.id, c.name, c.short_name, c.kind, c.specialization_type " +
                        "FROM users u " +
                        "JOIN communities c ON c.id = u.display_specialization_id " +
                        "WHERE u.id = ? AND c.kind = 'specialization' " +
                        "AND c.specialization_type = 'field'",
                (rs, rowNum) -> {
                    DisplaySpecializationRow row = new DisplaySpecializationRow();
                    row.id = rs.getLong("id");
                    row.name = rs.getString("name");
                    row.shortName = rs.getString("short_name");
                    row.kind = rs.getString("kind");
                    row.specializationType = rs.getString("specialization_type");
                    return row;
                },
                userId
        );
        return list.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(list.get(0));
    }

    public void updateIdentity(long userId, String handle, String firstName, String lastName, java.time.LocalDate dateOfBirth) {
        jdbcTemplate.update(
                "UPDATE users SET handle = ?, first_name = ?, last_name = ?, date_of_birth = ? WHERE id = ?",
                handle, firstName, lastName, dateOfBirth, userId
        );
    }

    public void updateOnboardingStep(long userId, String step) {
        jdbcTemplate.update(
                "UPDATE users SET onboarding_step = ? WHERE id = ?",
                step, userId
        );
    }

    public void markOnboardingComplete(long userId) {
        jdbcTemplate.update(
                "UPDATE users SET onboarding_step = 'verification_notifications', onboarding_completed_at = COALESCE(onboarding_completed_at, now()) WHERE id = ?",
                userId
        );
    }

    public void markProfileCompletionDismissed(long userId) {
        jdbcTemplate.update(
                "UPDATE users SET profile_completion_dismissed_at = COALESCE(profile_completion_dismissed_at, now()) WHERE id = ?",
                userId
        );
    }

    public void markProfileCompletionCompletedIfEligible(long userId) {
        jdbcTemplate.update(
                "UPDATE users SET profile_completion_completed_at = COALESCE(profile_completion_completed_at, now()) " +
                        "WHERE id = ? " +
                        "AND onboarding_completed_at IS NOT NULL " +
                        "AND profile_image_url IS NOT NULL AND btrim(profile_image_url) <> '' " +
                        "AND bio IS NOT NULL AND btrim(bio) <> '' " +
                        "AND display_specialization_id IS NOT NULL",
                userId
        );
    }

    public OffsetDateTime updateLastAppOpenAt(long userId, OffsetDateTime openedAt) {
        if (openedAt == null || userId <= 0) return null;
        var rows = jdbcTemplate.query(
                "UPDATE users " +
                        "SET last_app_open_at = CASE " +
                        "  WHEN last_app_open_at IS NULL OR ? > last_app_open_at THEN ? " +
                        "  ELSE last_app_open_at " +
                        "END " +
                        "WHERE id = ? " +
                        "RETURNING last_app_open_at",
                (rs, rowNum) -> rs.getObject("last_app_open_at", OffsetDateTime.class),
                openedAt,
                openedAt,
                userId
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    public java.util.List<UserRow> searchCompanyUsers(long companyId, String query, java.time.OffsetDateTime cursorTs, Long cursorId, int limit) {
        String like = "%" + query.toLowerCase() + "%";
        if (cursorTs == null || cursorId == null) {
            return jdbcTemplate.query(
                    "SELECT " + BASE_COLUMNS + " FROM users WHERE company_id = ? AND deleted_at IS NULL AND (LOWER(handle) LIKE ? OR LOWER(COALESCE(display_name,'')) LIKE ?) " +
                            "ORDER BY created_at DESC, id DESC LIMIT ?",
                    MAPPER, companyId, like, like, limit
            );
        }
        return jdbcTemplate.query(
                "SELECT " + BASE_COLUMNS + " FROM users WHERE company_id = ? AND deleted_at IS NULL AND (LOWER(handle) LIKE ? OR LOWER(COALESCE(display_name,'')) LIKE ?) " +
                        "AND (created_at < ? OR (created_at = ? AND id < ?)) " +
                        "ORDER BY created_at DESC, id DESC LIMIT ?",
                MAPPER, companyId, like, like, cursorTs, cursorTs, cursorId, limit
        );
    }

    private static final RowMapper<ScoredUserRow> SCORED_MAPPER = new RowMapper<>() {
        @Override
        public ScoredUserRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            ScoredUserRow row = new ScoredUserRow();
            row.user = MAPPER.mapRow(rs, rowNum);
            row.score = rs.getLong("score");
            return row;
        }
    };

    public java.util.List<ScoredUserRow> searchCompanyUsersRanked(
            long companyId,
            String query,
            String prefixQuery,
            java.time.OffsetDateTime asOf,
            Long cursorScore,
            java.time.OffsetDateTime cursorTs,
            Long cursorId,
            int limit
    ) {
        String vector = "to_tsvector('simple', " +
                "COALESCE(u.handle,'') || ' ' || COALESCE(u.display_name,'') || ' ' || COALESCE(u.first_name,'') || ' ' || COALESCE(u.last_name,''))";
        String match = "(" + vector + " @@ q.q_web OR (q.q_prefix IS NOT NULL AND " + vector + " @@ q.q_prefix))";
        String rank = "GREATEST(" +
                "ts_rank_cd(" + vector + ", q.q_web), " +
                "COALESCE(ts_rank_cd(" + vector + ", q.q_prefix), 0)" +
                ")";

        String exactHandle = "CASE WHEN LOWER(u.handle) = LOWER(?) THEN 2000000 ELSE 0 END";
        String prefixHandle = "CASE WHEN LOWER(u.handle) LIKE LOWER(?) || '%' THEN 1000000 ELSE 0 END";
        String prefixDisplay = "CASE WHEN LOWER(COALESCE(u.display_name,'')) LIKE LOWER(?) || '%' THEN 800000 ELSE 0 END";
        String boost = "(" + exactHandle + " + " + prefixHandle + " + " + prefixDisplay + ")";

        String recency = "LEAST(100000, (1.0 / (1.0 + EXTRACT(EPOCH FROM (?::timestamptz - u.created_at)) / 86400.0)) * 100000)";
        String scoreExpr = "CAST((" + rank + " * 1000000 + " + boost + " + " + recency + ") AS BIGINT)";

        String base =
                "WITH q AS (" +
                        "SELECT websearch_to_tsquery('simple', ?) AS q_web, " +
                        "to_tsquery('simple', NULLIF(?, '')) AS q_prefix" +
                        ") " +
                        "SELECT " + BASE_COLUMNS + ", " + scoreExpr + " AS score " +
                        "FROM users u CROSS JOIN q " +
                        "WHERE u.company_id = ? AND u.deleted_at IS NULL AND " + match;

        if (cursorScore == null || cursorTs == null || cursorId == null) {
            return jdbcTemplate.query(
                    "SELECT * FROM (" + base + ") s ORDER BY score DESC, created_at DESC, id DESC LIMIT ?",
                    SCORED_MAPPER,
                    query, prefixQuery,
                    query, query, query,
                    asOf,
                    companyId,
                    limit
            );
        }
        return jdbcTemplate.query(
                "SELECT * FROM (" + base + ") s " +
                        "WHERE (score < ? OR (score = ? AND (created_at < ? OR (created_at = ? AND id < ?)))) " +
                        "ORDER BY score DESC, created_at DESC, id DESC LIMIT ?",
                SCORED_MAPPER,
                query, prefixQuery,
                query, query, query,
                asOf,
                companyId,
                cursorScore, cursorScore, cursorTs, cursorTs, cursorId,
                limit
        );
    }

    /**
     * Ranked search with:
     * - FTS + prefix matching (existing behavior)
     * - Typo tolerance via pg_trgm similarity/word_similarity (when available)
     * - Social boosts (follow relationship + DM recency) for better "new message" recipient search
     *
     * Cursor semantics match {@link RankPagination}: order by score DESC, created_at DESC, id DESC.
     */
    public java.util.List<ScoredUserRow> searchCompanyUsersRankedV2(
            long companyId,
            long actorUserId,
            String query,
            String prefixQuery,
            java.time.OffsetDateTime asOf,
            Long cursorScore,
            java.time.OffsetDateTime cursorTs,
            Long cursorId,
            int limit
    ) {
        String vector = "to_tsvector('simple', " +
                "COALESCE(u.handle,'') || ' ' || COALESCE(u.display_name,'') || ' ' || COALESCE(u.first_name,'') || ' ' || COALESCE(u.last_name,''))";
        String ftsMatch = "(" + vector + " @@ q.q_web OR (q.q_prefix IS NOT NULL AND " + vector + " @@ q.q_prefix))";
        String ftsRank = "GREATEST(" +
                "ts_rank_cd(" + vector + ", q.q_web), " +
                "COALESCE(ts_rank_cd(" + vector + ", q.q_prefix), 0)" +
                ")";

        // Deterministic boosts for obvious intent.
        String exactHandle = "CASE WHEN LOWER(u.handle) = q.q_l THEN 2000000 ELSE 0 END";
        String prefixHandle = "CASE WHEN LOWER(COALESCE(u.handle,'')) LIKE q.q_l || '%' THEN 1000000 ELSE 0 END";
        String prefixDisplay = "CASE WHEN LOWER(COALESCE(u.display_name,'')) LIKE q.q_l || '%' THEN 800000 ELSE 0 END";
        String containsHandle = "CASE WHEN q.q_len >= 3 AND LOWER(COALESCE(u.handle,'')) LIKE '%' || q.q_l || '%' THEN 300000 ELSE 0 END";
        String containsDisplay = "CASE WHEN q.q_len >= 3 AND LOWER(COALESCE(u.display_name,'')) LIKE '%' || q.q_l || '%' THEN 250000 ELSE 0 END";
        String boost = "(" + exactHandle + " + " + prefixHandle + " + " + prefixDisplay + " + " + containsHandle + " + " + containsDisplay + ")";

        // pg_trgm-based typo tolerance (requires CREATE EXTENSION pg_trgm).
        // Computed once per row via CROSS JOIN LATERAL.
        String trigramMatches = "(q.q_len >= 2 AND trgm.trgm >= 0.25)";

        // Lightweight fallback matching for short queries and substrings.
        String extraMatch = "(" +
                "LOWER(COALESCE(u.handle,'')) LIKE q.q_l || '%' OR " +
                "LOWER(COALESCE(u.display_name,'')) LIKE q.q_l || '%' OR " +
                "(q.q_len >= 3 AND LOWER(COALESCE(u.handle,'')) LIKE '%' || q.q_l || '%') OR " +
                "(q.q_len >= 3 AND LOWER(COALESCE(u.display_name,'')) LIKE '%' || q.q_l || '%')" +
                ")";

        String match = "(" + ftsMatch + " OR " + trigramMatches + " OR " + extraMatch + ")";

        // Social boosts:
        // - Followed users first
        // - Users you've DM'd recently next
        String followedBoost = "CASE WHEN f.other_user_id IS NOT NULL THEN 700000 ELSE 0 END";
        String dmBaseBoost = "CASE WHEN dm.last_message_at IS NOT NULL THEN 600000 ELSE 0 END";
        String dmRecencyBoost = "CASE WHEN dm.last_message_at IS NULL THEN 0 ELSE " +
                "LEAST(600000, (1.0 / (1.0 + EXTRACT(EPOCH FROM (ctx.as_of - dm.last_message_at)) / 86400.0)) * 600000) END";
        String socialBoost = "(" + followedBoost + " + " + dmBaseBoost + " + " + dmRecencyBoost + ")";

        // Keep a small tie-breaker on account recency to prevent "stuck" ordering in equal-score cases.
        String createdRecency = "LEAST(20000, (1.0 / (1.0 + EXTRACT(EPOCH FROM (ctx.as_of - u.created_at)) / 86400.0)) * 20000)";

        String scoreExpr = "CAST((" +
                "(" + ftsRank + " * 900000)" +
                " + (trgm.trgm * 900000)" +
                " + " + boost +
                " + " + socialBoost +
                " + " + createdRecency +
                ") AS BIGINT)";

        String base =
                "WITH q AS (" +
                        "SELECT websearch_to_tsquery('simple', ?) AS q_web, " +
                        "to_tsquery('simple', NULLIF(?, '')) AS q_prefix, " +
                        "LOWER(TRIM(?)) AS q_l, " +
                        "LENGTH(TRIM(?))::INT AS q_len" +
                        "), ctx AS (" +
                        "SELECT ?::timestamptz AS as_of" +
                        "), me_principal AS (" +
                        "SELECT id AS principal_id FROM principals WHERE kind = 'user' AND user_id = ? LIMIT 1" +
                        "), followed AS (" +
                        "SELECT pu.user_id AS other_user_id " +
                        "FROM principal_follows pf " +
                        "JOIN me_principal mp ON mp.principal_id = pf.follower_principal_id " +
                        "JOIN principals pu ON pu.id = pf.followee_principal_id AND pu.kind = 'user'" +
                        "), dm_activity AS (" +
                        "SELECT cp2.user_id AS other_user_id, MAX(cm.created_at) AS last_message_at " +
                        "FROM conversation_participants cp1 " +
                        "JOIN conversation_participants cp2 ON cp1.conversation_id = cp2.conversation_id " +
                        "JOIN conversation_messages cm ON cm.conversation_id = cp1.conversation_id " +
                        "WHERE cp1.user_id = ? AND cp2.user_id <> ? " +
                        "GROUP BY cp2.user_id" +
                        ") " +
                        "SELECT " + BASE_COLUMNS + ", " + scoreExpr + " AS score " +
                        "FROM users u " +
                        "CROSS JOIN q " +
                        "CROSS JOIN ctx " +
                        "CROSS JOIN LATERAL (" +
                        "SELECT CASE WHEN q.q_len < 2 THEN 0 ELSE GREATEST(" +
                        "word_similarity(q.q_l, LOWER(COALESCE(u.handle,''))), " +
                        "word_similarity(q.q_l, LOWER(COALESCE(u.display_name,''))), " +
                        "word_similarity(q.q_l, LOWER(COALESCE(u.first_name,''))), " +
                        "word_similarity(q.q_l, LOWER(COALESCE(u.last_name,'')))" +
                        ") END AS trgm" +
                        ") trgm " +
                        "LEFT JOIN followed f ON f.other_user_id = u.id " +
                        "LEFT JOIN dm_activity dm ON dm.other_user_id = u.id " +
                        "WHERE u.company_id = ? AND u.deleted_at IS NULL AND " + match;

        if (cursorScore == null || cursorTs == null || cursorId == null) {
            return jdbcTemplate.query(
                    "SELECT * FROM (" + base + ") s ORDER BY score DESC, created_at DESC, id DESC LIMIT ?",
                    SCORED_MAPPER,
                    // q + ctx
                    query, prefixQuery, query, query, asOf,
                    // me_principal
                    actorUserId,
                    // dm_activity
                    actorUserId, actorUserId,
                    // where
                    companyId,
                    limit
            );
        }
        return jdbcTemplate.query(
                "SELECT * FROM (" + base + ") s " +
                        "WHERE (score < ? OR (score = ? AND (created_at < ? OR (created_at = ? AND id < ?)))) " +
                        "ORDER BY score DESC, created_at DESC, id DESC LIMIT ?",
                SCORED_MAPPER,
                // q + ctx
                query, prefixQuery, query, query, asOf,
                // me_principal
                actorUserId,
                // dm_activity
                actorUserId, actorUserId,
                // where
                companyId,
                // cursor
                cursorScore, cursorScore, cursorTs, cursorTs, cursorId,
                limit
        );
    }

    public java.util.List<UserRow> listCompanyUsers(long companyId, java.time.OffsetDateTime cursorTs, Long cursorId, int limit) {
        if (cursorTs == null || cursorId == null) {
            return jdbcTemplate.query(
                    "SELECT " + BASE_COLUMNS + " FROM users WHERE company_id = ? AND deleted_at IS NULL ORDER BY created_at DESC, id DESC LIMIT ?",
                    MAPPER, companyId, limit
            );
        }
        return jdbcTemplate.query(
            "SELECT " + BASE_COLUMNS + " FROM users WHERE company_id = ? AND deleted_at IS NULL AND (created_at < ? OR (created_at = ? AND id < ?)) " +
                    "ORDER BY created_at DESC, id DESC LIMIT ?",
            MAPPER, companyId, cursorTs, cursorTs, cursorId, limit
        );
    }

    public java.util.List<Long> listActiveUserIdsByCompany(long companyId) {
        return jdbcTemplate.query(
                "SELECT id FROM users WHERE company_id = ? AND deleted_at IS NULL",
                (rs, rowNum) -> rs.getLong("id"),
                companyId
        );
    }

    public java.util.List<Long> listActiveUserIds() {
        return jdbcTemplate.query(
                "SELECT id FROM users WHERE deleted_at IS NULL",
                (rs, rowNum) -> rs.getLong("id")
        );
    }

    public java.util.Set<Long> listActiveUserIdsByIds(java.util.Set<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) return java.util.Set.of();
        var ids = userIds.stream().filter(id -> id != null && id > 0).distinct().toList();
        if (ids.isEmpty()) return java.util.Set.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        Object[] params = ids.toArray();
        var rows = jdbcTemplate.query(
                "SELECT id FROM users WHERE deleted_at IS NULL AND id IN (" + placeholders + ")",
                (rs, rowNum) -> rs.getLong("id"),
                params
        );
        return new java.util.HashSet<>(rows);
    }

    public boolean softDelete(long userId, Long deletedBy) {
        int rows = jdbcTemplate.update(
                "UPDATE users SET deleted_at = now(), deleted_by = ? WHERE id = ? AND deleted_at IS NULL",
                deletedBy, userId
        );
        return rows > 0;
    }

    public boolean markDeletedSelf(long userId, Long deletedBy, String reason) {
        int rows = jdbcTemplate.update(
                "UPDATE users SET " +
                        "deleted_at = COALESCE(deleted_at, now()), " +
                        "deleted_by = COALESCE(deleted_by, ?), " +
                        "deleted_source = COALESCE(deleted_source, 'self'), " +
                        "deleted_reason = COALESCE(deleted_reason, ?), " +
                        "email = NULL " +
                        "WHERE id = ?",
                deletedBy, reason, userId
        );
        return rows > 0;
    }

    public void updateEmail(long userId, String email) {
        String normalized = normalizeEmail(email);
        if (normalized == null) return;
        jdbcTemplate.update("UPDATE users SET email = ? WHERE id = ?", normalized, userId);
    }

    public Optional<UserRow> claimActiveByEmail(String email, String firebaseUid) {
        String normalized = normalizeEmail(email);
        if (normalized == null || firebaseUid == null || firebaseUid.isBlank()) return Optional.empty();
        var list = jdbcTemplate.query(
                "UPDATE users SET firebase_uid = ? " +
                        "WHERE LOWER(email) = LOWER(?) AND deleted_at IS NULL AND firebase_uid <> ? " +
                        "AND NOT EXISTS (SELECT 1 FROM users x WHERE x.firebase_uid = ?) " +
                        "RETURNING " + BASE_COLUMNS,
                MAPPER, firebaseUid, normalized, firebaseUid, firebaseUid
        );
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public int repairMissingAuthorIdsForUser(long userId) {
        return jdbcTemplate.update(
                "UPDATE posts p SET author_id = ? " +
                        "FROM principals pr " +
                        "WHERE p.author_id IS NULL " +
                        "AND COALESCE(p.is_anon, false) = false " +
                        "AND p.author_principal_id = pr.id " +
                        "AND pr.user_id = ?",
                userId, userId
        );
    }

    public int repairMissingCommentUserIdsForUser(long userId) {
        return jdbcTemplate.update(
                "UPDATE comments c SET user_id = ? " +
                        "FROM principals pr " +
                        "WHERE c.user_id IS NULL " +
                        "AND c.author_principal_id = pr.id " +
                        "AND pr.user_id = ?",
                userId, userId
        );
    }

    public int repairMissingCommentLikeUserIdsForUser(long userId) {
        return jdbcTemplate.update(
                "UPDATE comment_likes cl SET user_id = ? " +
                        "FROM principals pr " +
                        "WHERE cl.user_id IS NULL " +
                        "AND cl.liker_principal_id = pr.id " +
                        "AND pr.user_id = ?",
                userId, userId
        );
    }

    public long insert(String firebaseUid, String handle, String email, long companyId,
                       String firstName, String lastName, java.time.LocalDate dateOfBirth) {
        Long id = jdbcTemplate.query(
                "INSERT INTO users(firebase_uid, handle, email, company_id, first_name, last_name, date_of_birth) " +
                        "VALUES (?,?,?,?,?,?,?) RETURNING id",
                rs -> rs.next() ? rs.getLong(1) : null,
                firebaseUid, handle, normalizeEmail(email), companyId, firstName, lastName, dateOfBirth
        );
        if (id == null) {
            throw new IllegalStateException("Failed to insert user");
        }
        return id;
    }

    public boolean isHandleAvailable(String handle) {
        return isHandleAvailable(handle, null);
    }

    public boolean isHandleAvailable(String handle, OffsetDateTime tombstoneCutoff) {
        if (handle == null || handle.isBlank()) return false;
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE LOWER(handle) = LOWER(?)",
                Integer.class,
                handle
        );
        if (count != null && count > 0) return false;
        Integer tombstones;
        if (tombstoneCutoff == null) {
            tombstones = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM user_tombstones WHERE LOWER(handle) = LOWER(?)",
                    Integer.class,
                    handle
            );
        } else {
            tombstones = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM user_tombstones WHERE LOWER(handle) = LOWER(?) AND purged_at > ?",
                    Integer.class,
                    handle,
                    tombstoneCutoff
            );
        }
        return tombstones != null && tombstones == 0;
    }

    public boolean isEmailAvailable(String email) {
        String normalized = normalizeEmail(email);
        if (normalized == null) return false;
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE LOWER(email) = LOWER(?)",
                Integer.class,
                normalized
        );
        return count == null || count == 0;
    }

    public boolean isEmailAvailableForUser(long userId, String email) {
        String normalized = normalizeEmail(email);
        if (normalized == null) return false;
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE LOWER(email) = LOWER(?) AND id <> ?",
                Integer.class,
                normalized,
                userId
        );
        return count == null || count == 0;
    }

    public boolean isFirebaseUidTombstoned(String firebaseUid) {
        if (firebaseUid == null || firebaseUid.isBlank()) return false;
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_tombstones WHERE firebase_uid = ?",
                Integer.class,
                firebaseUid
        );
        return count != null && count > 0;
    }

    public boolean reactivate(long userId) {
        int rows = jdbcTemplate.update(
                "UPDATE users SET deleted_at = NULL, deleted_by = NULL, deleted_source = NULL, deleted_by_admin_id = NULL, deleted_reason = NULL " +
                        "WHERE id = ? AND deleted_at IS NOT NULL",
                userId
        );
        return rows > 0;
    }

    public Optional<UserAccessStatusRow> accessStatusByFirebaseUid(String firebaseUid) {
        if (firebaseUid == null || firebaseUid.isBlank()) return Optional.empty();
        var list = jdbcTemplate.query(
                "SELECT id, company_id, onboarding_step, onboarding_completed_at, disabled_at, deleted_at, deleted_source " +
                        "FROM users WHERE firebase_uid = ? LIMIT 1",
                (rs, rowNum) -> {
                    UserAccessStatusRow row = new UserAccessStatusRow();
                    row.id = rs.getLong("id");
                    long companyId = rs.getLong("company_id");
                    row.companyId = rs.wasNull() ? null : companyId;
                    row.onboardingStep = rs.getString("onboarding_step");
                    row.onboardingCompletedAt = rs.getObject("onboarding_completed_at", OffsetDateTime.class);
                    row.disabledAt = rs.getObject("disabled_at", OffsetDateTime.class);
                    row.deletedAt = rs.getObject("deleted_at", OffsetDateTime.class);
                    row.deletedSource = rs.getString("deleted_source");
                    return row;
                },
                firebaseUid
        );
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public java.util.List<UserRow> listSoftDeletedBefore(OffsetDateTime cutoff, int limit) {
        return jdbcTemplate.query(
                "SELECT " + BASE_COLUMNS + " FROM users WHERE deleted_at IS NOT NULL AND deleted_at < ? " +
                        "ORDER BY deleted_at ASC, id ASC LIMIT ?",
                MAPPER, cutoff, limit
        );
    }

    public Optional<UserRow> deleteByFirebaseUidIfDeletedBefore(String firebaseUid, OffsetDateTime cutoff) {
        if (firebaseUid == null || firebaseUid.isBlank()) return Optional.empty();
        var list = jdbcTemplate.query(
                "DELETE FROM users WHERE firebase_uid = ? AND deleted_at IS NOT NULL AND deleted_at < ? " +
                        "RETURNING " + BASE_COLUMNS,
                MAPPER, firebaseUid, cutoff
        );
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public Optional<UserRow> deleteById(long userId) {
        var list = jdbcTemplate.query(
                "DELETE FROM users WHERE id = ? RETURNING " + BASE_COLUMNS,
                MAPPER, userId
        );
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public void insertTombstone(UserRow user) {
        if (user == null || user.firebaseUid == null || user.firebaseUid.isBlank()) return;
        jdbcTemplate.update(
                "INSERT INTO user_tombstones(firebase_uid, handle) VALUES (?,?) ON CONFLICT DO NOTHING",
                user.firebaseUid,
                user.handle
        );
    }

    public java.util.List<UserRow> searchAll(String query, java.time.OffsetDateTime cursorTs, Long cursorId, int limit) {
        String q = query == null ? "" : query.trim().toLowerCase();
        boolean hasQuery = !q.isBlank();
        String like = "%" + q + "%";
        Long idQuery = null;
        if (hasQuery) {
            try {
                idQuery = Long.parseLong(q);
            } catch (NumberFormatException ignored) {}
        }
        if (!hasQuery) {
            if (cursorTs == null || cursorId == null) {
                return jdbcTemplate.query(
                        "SELECT " + BASE_COLUMNS + " FROM users WHERE deleted_at IS NULL ORDER BY created_at DESC, id DESC LIMIT ?",
                        MAPPER, limit
                );
            }
            return jdbcTemplate.query(
                    "SELECT " + BASE_COLUMNS + " FROM users WHERE deleted_at IS NULL AND (created_at < ? OR (created_at = ? AND id < ?)) " +
                            "ORDER BY created_at DESC, id DESC LIMIT ?",
                    MAPPER, cursorTs, cursorTs, cursorId, limit
            );
        }
        boolean hasId = idQuery != null;
        String filter = hasId
                ? "(LOWER(handle) LIKE ? OR LOWER(COALESCE(display_name,'')) LIKE ? OR LOWER(COALESCE(email,'')) LIKE ? " +
                "OR LOWER(firebase_uid) LIKE ? OR id = ?)"
                : "(LOWER(handle) LIKE ? OR LOWER(COALESCE(display_name,'')) LIKE ? OR LOWER(COALESCE(email,'')) LIKE ? " +
                "OR LOWER(firebase_uid) LIKE ?)";
        if (cursorTs == null || cursorId == null) {
            Object[] args = hasId
                    ? new Object[]{like, like, like, like, idQuery, limit}
                    : new Object[]{like, like, like, like, limit};
            return jdbcTemplate.query(
                    "SELECT " + BASE_COLUMNS + " FROM users WHERE deleted_at IS NULL AND " + filter +
                            " ORDER BY created_at DESC, id DESC LIMIT ?",
                    args, MAPPER
            );
        }
        Object[] args = hasId
                ? new Object[]{like, like, like, like, idQuery, cursorTs, cursorTs, cursorId, limit}
                : new Object[]{like, like, like, like, cursorTs, cursorTs, cursorId, limit};
        return jdbcTemplate.query(
                "SELECT " + BASE_COLUMNS + " FROM users WHERE deleted_at IS NULL AND " + filter +
                        " AND (created_at < ? OR (created_at = ? AND id < ?)) " +
                        "ORDER BY created_at DESC, id DESC LIMIT ?",
                args, MAPPER
        );
    }

    public java.util.List<UserRow> adminSearchAll(String query, java.time.OffsetDateTime cursorTs, Long cursorId, int limit) {
        String q = query == null ? "" : query.trim().toLowerCase();
        boolean hasQuery = !q.isBlank();
        String like = "%" + q + "%";
        Long idQuery = null;
        if (hasQuery) {
            try {
                idQuery = Long.parseLong(q);
            } catch (NumberFormatException ignored) {}
        }
        if (!hasQuery) {
            if (cursorTs == null || cursorId == null) {
                return jdbcTemplate.query(
                        "SELECT " + BASE_COLUMNS + " FROM users ORDER BY created_at DESC, id DESC LIMIT ?",
                        MAPPER, limit
                );
            }
            return jdbcTemplate.query(
                    "SELECT " + BASE_COLUMNS + " FROM users WHERE (created_at < ? OR (created_at = ? AND id < ?)) " +
                            "ORDER BY created_at DESC, id DESC LIMIT ?",
                    MAPPER, cursorTs, cursorTs, cursorId, limit
            );
        }
        boolean hasId = idQuery != null;
        String filter = hasId
                ? "(LOWER(handle) LIKE ? OR LOWER(COALESCE(display_name,'')) LIKE ? OR LOWER(COALESCE(email,'')) LIKE ? " +
                "OR LOWER(firebase_uid) LIKE ? OR id = ?)"
                : "(LOWER(handle) LIKE ? OR LOWER(COALESCE(display_name,'')) LIKE ? OR LOWER(COALESCE(email,'')) LIKE ? " +
                "OR LOWER(firebase_uid) LIKE ?)";
        if (cursorTs == null || cursorId == null) {
            Object[] args = hasId
                    ? new Object[]{like, like, like, like, idQuery, limit}
                    : new Object[]{like, like, like, like, limit};
            return jdbcTemplate.query(
                    "SELECT " + BASE_COLUMNS + " FROM users WHERE " + filter +
                            " ORDER BY created_at DESC, id DESC LIMIT ?",
                    args, MAPPER
            );
        }
        Object[] args = hasId
                ? new Object[]{like, like, like, like, idQuery, cursorTs, cursorTs, cursorId, limit}
                : new Object[]{like, like, like, like, cursorTs, cursorTs, cursorId, limit};
        return jdbcTemplate.query(
                "SELECT " + BASE_COLUMNS + " FROM users WHERE " + filter +
                        " AND (created_at < ? OR (created_at = ? AND id < ?)) " +
                        "ORDER BY created_at DESC, id DESC LIMIT ?",
                args, MAPPER
        );
    }

    public java.util.List<UserRow> adminSearchBanned(String query, java.time.OffsetDateTime cursorTs, Long cursorId, int limit) {
        String q = query == null ? "" : query.trim().toLowerCase();
        boolean hasQuery = !q.isBlank();
        String like = "%" + q + "%";
        Long idQuery = null;
        if (hasQuery) {
            try {
                idQuery = Long.parseLong(q);
            } catch (NumberFormatException ignored) {}
        }
        boolean hasId = idQuery != null;
        String filter = hasQuery
                ? (hasId
                    ? "(LOWER(u.handle) LIKE ? OR LOWER(COALESCE(u.display_name,'')) LIKE ? OR LOWER(COALESCE(u.email,'')) LIKE ? " +
                    "OR LOWER(u.firebase_uid) LIKE ? OR u.id = ?)"
                    : "(LOWER(u.handle) LIKE ? OR LOWER(COALESCE(u.display_name,'')) LIKE ? OR LOWER(COALESCE(u.email,'')) LIKE ? " +
                    "OR LOWER(u.firebase_uid) LIKE ?)")
                : null;

        String base =
                "SELECT " + BASE_COLUMNS_U + " " +
                "FROM users u " +
                "JOIN LATERAL (" +
                "  SELECT 1 FROM user_bans b " +
                "  WHERE b.user_id = u.id AND b.revoked_at IS NULL AND (b.expires_at IS NULL OR b.expires_at > now()) " +
                "  ORDER BY b.created_at DESC LIMIT 1" +
                ") b ON true ";

        if (!hasQuery) {
            if (cursorTs == null || cursorId == null) {
                return jdbcTemplate.query(
                        base + "ORDER BY u.created_at DESC, u.id DESC LIMIT ?",
                        MAPPER, limit
                );
            }
            return jdbcTemplate.query(
                    base + "WHERE (u.created_at < ? OR (u.created_at = ? AND u.id < ?)) " +
                            "ORDER BY u.created_at DESC, u.id DESC LIMIT ?",
                    MAPPER, cursorTs, cursorTs, cursorId, limit
            );
        }

        if (cursorTs == null || cursorId == null) {
            Object[] args = hasId
                    ? new Object[]{like, like, like, like, idQuery, limit}
                    : new Object[]{like, like, like, like, limit};
            return jdbcTemplate.query(
                    base + "WHERE " + filter + " ORDER BY u.created_at DESC, u.id DESC LIMIT ?",
                    args, MAPPER
            );
        }
        Object[] args = hasId
                ? new Object[]{like, like, like, like, idQuery, cursorTs, cursorTs, cursorId, limit}
                : new Object[]{like, like, like, like, cursorTs, cursorTs, cursorId, limit};
        return jdbcTemplate.query(
                base + "WHERE " + filter +
                        " AND (u.created_at < ? OR (u.created_at = ? AND u.id < ?)) " +
                        "ORDER BY u.created_at DESC, u.id DESC LIMIT ?",
                args, MAPPER
        );
    }

    public Optional<UserRow> adminDisable(long userId, Long disabledByAdminId, String reason) {
        var list = jdbcTemplate.query(
                "UPDATE users SET " +
                        "disabled_at = COALESCE(disabled_at, now()), " +
                        "disabled_reason = COALESCE(disabled_reason, ?), " +
                        "disabled_by_admin_id = COALESCE(disabled_by_admin_id, ?) " +
                        "WHERE id = ? " +
                        "RETURNING " + BASE_COLUMNS,
                MAPPER,
                reason, disabledByAdminId, userId
        );
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public Optional<UserRow> adminEnable(long userId) {
        var list = jdbcTemplate.query(
                "UPDATE users SET disabled_at = NULL, disabled_reason = NULL, disabled_by_admin_id = NULL " +
                        "WHERE id = ? " +
                        "RETURNING " + BASE_COLUMNS,
                MAPPER,
                userId
        );
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public Optional<UserRow> adminSoftDelete(long userId, Long deletedByAdminId, String reason) {
        var list = jdbcTemplate.query(
                "UPDATE users SET " +
                        "deleted_at = COALESCE(deleted_at, now()), " +
                        "deleted_source = 'admin', " +
                        "deleted_by_admin_id = COALESCE(deleted_by_admin_id, ?), " +
                        "deleted_reason = COALESCE(deleted_reason, ?), " +
                        "email = NULL, first_name = DEFAULT, last_name = DEFAULT, date_of_birth = DEFAULT, " +
                        "display_name = NULL, bio = NULL, profile_image_url = NULL " +
                        "WHERE id = ? " +
                        "RETURNING " + BASE_COLUMNS,
                MAPPER,
                deletedByAdminId, reason, userId
        );
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    private String normalizeEmail(String email) {
        if (email == null) return null;
        String trimmed = email.trim();
        if (trimmed.isBlank()) return null;
        return trimmed.toLowerCase(java.util.Locale.ROOT);
    }

    public boolean hardDelete(long userId) {
        int rows = jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        return rows > 0;
    }

    public int countFollowers(long userId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM principal_follows f " +
                        "JOIN principals target ON target.id = f.followee_principal_id " +
                        "JOIN principals follower ON follower.id = f.follower_principal_id " +
                        "LEFT JOIN users u ON u.id = follower.user_id AND u.deleted_at IS NULL " +
                        "WHERE target.user_id = ? AND (follower.kind = 'anon' OR u.id IS NOT NULL)",
                Integer.class, userId
        );
        return count == null ? 0 : count;
    }

    public int countFollowing(long userId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM principal_follows f " +
                        "JOIN principals target ON target.id = f.follower_principal_id " +
                        "JOIN principals followee ON followee.id = f.followee_principal_id " +
                        "LEFT JOIN users u ON u.id = followee.user_id AND u.deleted_at IS NULL " +
                        "WHERE target.user_id = ? AND (followee.kind = 'anon' OR u.id IS NOT NULL)",
                Integer.class, userId
        );
        return count == null ? 0 : count;
    }

    public int countPosts(long userId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM posts WHERE author_id = ?",
                Integer.class, userId
        );
        return count == null ? 0 : count;
    }

    public int countComments(long userId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM comments WHERE user_id = ?",
                Integer.class, userId
        );
        return count == null ? 0 : count;
    }

    public long countLikesReceived(long userId) {
        Long val = jdbcTemplate.queryForObject(
                "SELECT " +
                        "COALESCE((SELECT SUM(p.likes_count) FROM posts p WHERE p.author_id = ? AND p.removed_at IS NULL), 0) " +
                        "+ COALESCE((SELECT SUM(c.likes_count) FROM comments c WHERE c.user_id = ? AND c.deleted_at IS NULL), 0)",
                Long.class,
                userId,
                userId
        );
        return val == null ? 0 : val;
    }

    public static class UserRow {
        public Long id;
        public String firebaseUid;
        public String handle;
        public String email;
        public Long companyId;
        public String firstName;
        public String lastName;
        public java.time.LocalDate dateOfBirth;
        public String displayName;
        public String bio;
        public boolean isAnonymous;
        public boolean showFollowerCount;
        public String messagePermission;
        public String profileImageUrl;
        public boolean hideAnonymousPosts;
        public Long displayCommunityId;
        public Long displaySpecializationId;
        public String onboardingStep;
        public OffsetDateTime onboardingCompletedAt;
        public OffsetDateTime profileCompletionDismissedAt;
        public OffsetDateTime profileCompletionCompletedAt;
        public OffsetDateTime lastAppOpenAt;
        public OffsetDateTime createdAt;
        public OffsetDateTime disabledAt;
        public String disabledReason;
        public Long disabledByAdminId;
        public OffsetDateTime deletedAt;
        public Long deletedBy;
        public String deletedSource;
        public Long deletedByAdminId;
        public String deletedReason;
    }

    public static class UserAccessStatusRow {
        public long id;
        public Long companyId;
        public String onboardingStep;
        public OffsetDateTime onboardingCompletedAt;
        public OffsetDateTime disabledAt;
        public OffsetDateTime deletedAt;
        public String deletedSource;
    }

    public static class ScoredUserRow {
        public UserRow user;
        public long score;
    }

    public static class DisplayCommunityRow {
        public long id;
        public String name;
        public String shortName;
        public String kind;
        public String specializationType;
    }

    public static class DisplaySpecializationRow {
        public long id;
        public String name;
        public String shortName;
        public String kind;
        public String specializationType;
    }
}

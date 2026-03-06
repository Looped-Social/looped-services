package com.looped.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Repository
class MeNoticeStateRepository {
    private final JdbcTemplate jdbc;

    MeNoticeStateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<StateRow> listPending(long userId, Collection<String> noticeKeys) {
        if (userId <= 0 || noticeKeys == null || noticeKeys.isEmpty()) return List.of();
        List<String> keys = noticeKeys.stream()
                .filter(k -> k != null && !k.isBlank())
                .distinct()
                .toList();
        if (keys.isEmpty()) return List.of();

        String placeholders = String.join(",", Collections.nCopies(keys.size(), "?"));
        List<Object> args = new ArrayList<>(keys.size() + 1);
        args.add(userId);
        args.addAll(keys);

        return jdbc.query(
                """
                SELECT user_id, notice_key, eligible, first_eligible_at, acknowledged_at, ack_action
                FROM user_notice_state
                WHERE user_id = ?
                  AND eligible = true
                  AND acknowledged_at IS NULL
                  AND notice_key IN (%s)
                ORDER BY first_eligible_at ASC NULLS LAST, notice_key ASC
                """.formatted(placeholders),
                MAPPER,
                args.toArray()
        );
    }

    boolean markAcknowledgedIfPending(long userId, String noticeKey, String action, OffsetDateTime now) {
        int rows = jdbc.update(
                """
                UPDATE user_notice_state
                SET acknowledged_at = ?,
                    ack_action = ?,
                    updated_at = now()
                WHERE user_id = ?
                  AND notice_key = ?
                  AND eligible = true
                  AND acknowledged_at IS NULL
                """,
                now,
                action,
                userId,
                noticeKey
        );
        return rows > 0;
    }

    Optional<StateRow> findByUserAndKey(long userId, String noticeKey) {
        List<StateRow> rows = jdbc.query(
                """
                SELECT user_id, notice_key, eligible, first_eligible_at, acknowledged_at, ack_action
                FROM user_notice_state
                WHERE user_id = ? AND notice_key = ?
                LIMIT 1
                """,
                MAPPER,
                userId,
                noticeKey
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    long countEligibleUsers(String noticeKey) {
        Long count = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM user_notice_state
                WHERE notice_key = ?
                  AND eligible = true
                """,
                Long.class,
                noticeKey
        );
        return count == null ? 0L : count;
    }

    long countAcknowledgedUsers(String noticeKey) {
        Long count = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM user_notice_state
                WHERE notice_key = ?
                  AND eligible = true
                  AND acknowledged_at IS NOT NULL
                """,
                Long.class,
                noticeKey
        );
        return count == null ? 0L : count;
    }

    record StateRow(
            long userId,
            String noticeKey,
            boolean eligible,
            OffsetDateTime firstEligibleAt,
            OffsetDateTime acknowledgedAt,
            String ackAction
    ) {}

    private static final RowMapper<StateRow> MAPPER = new RowMapper<>() {
        @Override
        public StateRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new StateRow(
                    rs.getLong("user_id"),
                    rs.getString("notice_key"),
                    rs.getBoolean("eligible"),
                    rs.getObject("first_eligible_at", OffsetDateTime.class),
                    rs.getObject("acknowledged_at", OffsetDateTime.class),
                    rs.getString("ack_action")
            );
        }
    };
}

package com.looped.notifications;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Repository
public class CelebrationsRepository {
    private final JdbcTemplate jdbc;

    public CelebrationsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Long> listBirthdayUserIds(int month, int[] days, long cursorId, int limit) {
        if (days == null || days.length == 0) return List.of();
        int lim = Math.max(1, limit);
        long cursor = Math.max(0L, cursorId);

        String dayClause = buildDayClause(days);
        List<Object> params = new ArrayList<>();
        params.add(month);
        for (int day : days) params.add(day);
        params.add(cursor);
        params.add(lim);

        String sql = "SELECT id FROM users " +
                "WHERE deleted_at IS NULL AND company_id IS NOT NULL " +
                "AND EXTRACT(MONTH FROM date_of_birth) = ? " +
                "AND EXTRACT(DAY FROM date_of_birth) " + dayClause + " " +
                "AND NOT (date_of_birth = DATE '1970-01-01' AND first_name = 'Unknown' AND last_name = 'User') " +
                "AND id > ? " +
                "ORDER BY id ASC " +
                "LIMIT ?";
        return jdbc.query(sql, (rs, rowNum) -> rs.getLong("id"), params.toArray());
    }

    public List<AnniversaryCandidate> listAnniversaryCandidates(LocalDate today, int month, int[] days, long cursorId, int limit) {
        if (today == null) return List.of();
        if (days == null || days.length == 0) return List.of();
        int lim = Math.max(1, limit);
        long cursor = Math.max(0L, cursorId);

        String dayClause = buildDayClause(days);
        List<Object> params = new ArrayList<>();
        params.add(today);
        params.add(cursor);
        params.add(month);
        for (int day : days) params.add(day);
        params.add(lim);

        String sql = "SELECT id, years FROM (" +
                "  SELECT id, (created_at AT TIME ZONE 'UTC')::date AS created_date, " +
                "         EXTRACT(YEAR FROM age(?::date, (created_at AT TIME ZONE 'UTC')::date))::int AS years " +
                "  FROM users " +
                "  WHERE deleted_at IS NULL AND company_id IS NOT NULL AND id > ? " +
                ") u " +
                "WHERE EXTRACT(MONTH FROM created_date) = ? " +
                "AND EXTRACT(DAY FROM created_date) " + dayClause + " " +
                "AND years >= 1 " +
                "ORDER BY id ASC " +
                "LIMIT ?";
        return jdbc.query(sql, (rs, rowNum) -> new AnniversaryCandidate(
                rs.getLong("id"),
                rs.getInt("years")
        ), params.toArray());
    }

    private static String buildDayClause(int[] days) {
        if (days == null || days.length == 0) return "= 0";
        if (days.length == 1) return "= ?";
        String placeholders = String.join(",", java.util.Collections.nCopies(days.length, "?"));
        return "IN (" + placeholders + ")";
    }

    public record AnniversaryCandidate(long userId, int years) {}
}

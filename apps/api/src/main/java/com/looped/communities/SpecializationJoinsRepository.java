package com.looped.communities;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Repository
public class SpecializationJoinsRepository {
    private final JdbcTemplate jdbc;

    public SpecializationJoinsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean exists(long userId, long specializationId) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM specialization_joins WHERE user_id = ? AND specialization_id = ?)",
                Boolean.class,
                userId, specializationId
        );
        return Boolean.TRUE.equals(exists);
    }

    public boolean insertIfAbsent(long userId, long specializationId) {
        int rows = jdbc.update(
                "INSERT INTO specialization_joins(user_id, specialization_id) VALUES (?, ?) " +
                        "ON CONFLICT (user_id, specialization_id) DO NOTHING",
                userId, specializationId
        );
        return rows > 0;
    }

    public boolean delete(long userId, long specializationId) {
        int rows = jdbc.update(
                "DELETE FROM specialization_joins WHERE user_id = ? AND specialization_id = ?",
                userId, specializationId
        );
        return rows > 0;
    }

    public int deleteJoinedByType(long userId, String specializationType) {
        int rows = jdbc.update(
                "DELETE FROM specialization_joins j USING communities c " +
                        "WHERE c.id = j.specialization_id AND j.user_id = ? AND c.kind = 'specialization' AND c.specialization_type = ?",
                userId, specializationType
        );
        return rows;
    }

    public int countJoinedByType(long userId, String specializationType) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM specialization_joins j " +
                        "JOIN communities c ON c.id = j.specialization_id " +
                        "WHERE j.user_id = ? AND c.kind = 'specialization' AND c.specialization_type = ?",
                Integer.class,
                userId, specializationType
        );
        return count == null ? 0 : count;
    }

    public List<JoinRow> listJoined(long userId, String specializationType, OffsetDateTime cursorTs, Long cursorId, int limit) {
        String base = """
                SELECT j.id AS join_id, j.specialization_id, j.created_at,
                       c.name, c.short_name, c.kind, c.specialization_type, c.member_count
                FROM specialization_joins j
                JOIN communities c ON c.id = j.specialization_id
                WHERE j.user_id = ? AND c.kind = 'specialization'
                """;
        java.util.List<Object> args = new java.util.ArrayList<>();
        args.add(userId);
        if (specializationType != null && !specializationType.isBlank()) {
            base += "AND c.specialization_type = ? ";
            args.add(specializationType);
        }
        if (cursorTs != null && cursorId != null) {
            base += "AND (j.created_at < ? OR (j.created_at = ? AND j.id < ?)) ";
            args.add(cursorTs);
            args.add(cursorTs);
            args.add(cursorId);
        }
        base += "ORDER BY j.created_at DESC, j.id DESC LIMIT ? ";
        args.add(limit);
        return jdbc.query(base, JOIN_MAPPER, args.toArray());
    }

    public Set<Long> joinedIds(long userId, Collection<Long> specializationIds) {
        if (specializationIds == null || specializationIds.isEmpty()) return Set.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(specializationIds.size(), "?"));
        java.util.List<Object> args = new java.util.ArrayList<>();
        args.add(userId);
        args.addAll(specializationIds);
        List<Long> rows = jdbc.query(
                "SELECT specialization_id FROM specialization_joins WHERE user_id = ? AND specialization_id IN (" + placeholders + ")",
                (rs, rowNum) -> rs.getLong("specialization_id"),
                args.toArray()
        );
        return Set.copyOf(rows);
    }

    public int countMembers(long specializationId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM specialization_joins j " +
                        "JOIN users u ON u.id = j.user_id " +
                        "WHERE j.specialization_id = ? AND u.deleted_at IS NULL",
                Integer.class,
                specializationId
        );
        return count == null ? 0 : count;
    }

    public Map<Long, Integer> countMembersBySpecializationIds(Collection<Long> specializationIds) {
        if (specializationIds == null || specializationIds.isEmpty()) return Map.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(specializationIds.size(), "?"));
        java.util.List<Object> args = new java.util.ArrayList<>();
        args.addAll(specializationIds);
        return jdbc.query(
                "SELECT j.specialization_id, COUNT(*) AS cnt " +
                        "FROM specialization_joins j " +
                        "JOIN users u ON u.id = j.user_id " +
                        "WHERE j.specialization_id IN (" + placeholders + ") AND u.deleted_at IS NULL " +
                        "GROUP BY j.specialization_id",
                rs -> {
                    Map<Long, Integer> out = new java.util.HashMap<>();
                    while (rs.next()) {
                        out.put(rs.getLong("specialization_id"), rs.getInt("cnt"));
                    }
                    return out;
                },
                args.toArray()
        );
    }

    public record JoinRow(long joinId, long specializationId, String name, String shortName, String kind,
                          String specializationType, int memberCount, OffsetDateTime createdAt) {}

    private static final RowMapper<JoinRow> JOIN_MAPPER = new RowMapper<>() {
        @Override
        public JoinRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new JoinRow(
                    rs.getLong("join_id"),
                    rs.getLong("specialization_id"),
                    rs.getString("name"),
                    rs.getString("short_name"),
                    rs.getString("kind"),
                    rs.getString("specialization_type"),
                    rs.getInt("member_count"),
                    rs.getObject("created_at", OffsetDateTime.class)
            );
        }
    };
}

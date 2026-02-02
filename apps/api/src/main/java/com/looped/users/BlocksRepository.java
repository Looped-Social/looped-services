package com.looped.users;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Repository
public class BlocksRepository {
    private final JdbcTemplate jdbc;

    public BlocksRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean insertIfAbsent(long blockerPrincipalId, long blockedPrincipalId) {
        int rows = jdbc.update(
                "INSERT INTO principal_blocks(blocker_principal_id, blocked_principal_id) VALUES (?, ?) " +
                        "ON CONFLICT (blocker_principal_id, blocked_principal_id) DO NOTHING",
                blockerPrincipalId, blockedPrincipalId
        );
        return rows > 0;
    }

    public boolean delete(long blockerPrincipalId, long blockedPrincipalId) {
        int rows = jdbc.update(
                "DELETE FROM principal_blocks WHERE blocker_principal_id=? AND blocked_principal_id=?",
                blockerPrincipalId, blockedPrincipalId
        );
        return rows > 0;
    }

    public boolean existsEitherDirection(long principalA, long principalB) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM principal_blocks " +
                        "WHERE (blocker_principal_id=? AND blocked_principal_id=?) " +
                        "OR (blocker_principal_id=? AND blocked_principal_id=?)",
                Integer.class,
                principalA, principalB, principalB, principalA
        );
        return count != null && count > 0;
    }

    public Set<Long> otherPrincipalsBlockedEitherDirection(long principalId, List<Long> otherPrincipalIds) {
        if (principalId <= 0 || otherPrincipalIds == null || otherPrincipalIds.isEmpty()) return Set.of();
        var ids = otherPrincipalIds.stream().filter(id -> id != null && id > 0).distinct().toList();
        if (ids.isEmpty()) return Set.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        Object[] params = new Object[ids.size() + 3];
        params[0] = principalId;
        params[1] = principalId;
        params[2] = principalId;
        for (int i = 0; i < ids.size(); i++) params[i + 3] = ids.get(i);

        String sql =
                "SELECT CASE " +
                        "WHEN blocker_principal_id = ? THEN blocked_principal_id " +
                        "ELSE blocker_principal_id " +
                        "END AS other_principal_id " +
                        "FROM principal_blocks " +
                        "WHERE (blocker_principal_id = ? AND blocked_principal_id IN (" + placeholders + ")) " +
                        "OR (blocked_principal_id = ? AND blocker_principal_id IN (" + placeholders + "))";

        // We reuse the same ids twice: once for blocked_principal_id IN (...) and once for blocker_principal_id IN (...).
        Object[] fullParams = new Object[3 + ids.size() + ids.size()];
        fullParams[0] = params[0];
        fullParams[1] = params[1];
        for (int i = 0; i < ids.size(); i++) fullParams[2 + i] = ids.get(i);
        fullParams[2 + ids.size()] = params[2];
        for (int i = 0; i < ids.size(); i++) fullParams[3 + ids.size() + i] = ids.get(i);

        var rows = jdbc.query(
                sql,
                (rs, rowNum) -> rs.getLong("other_principal_id"),
                fullParams
        );
        return new HashSet<>(rows);
    }
}

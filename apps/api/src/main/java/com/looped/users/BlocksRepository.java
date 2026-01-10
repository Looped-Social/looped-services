package com.looped.users;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

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
}

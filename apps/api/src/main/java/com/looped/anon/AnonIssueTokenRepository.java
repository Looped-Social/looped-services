package com.looped.anon;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;

@Repository
public class AnonIssueTokenRepository {
    private final JdbcTemplate jdbc;

    public AnonIssueTokenRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void create(byte[] tokenHash, long userId, long communityId, OffsetDateTime expiresAt) {
        jdbc.update(
                "INSERT INTO anon_issue_tokens(token_hash, user_id, community_id, expires_at) VALUES (?,?,?,?)",
                tokenHash, userId, communityId, expiresAt
        );
    }

    public Optional<IssueTokenRow> consumeActive(byte[] tokenHash) {
        var rows = jdbc.query(
                "UPDATE anon_issue_tokens SET consumed_at = now() " +
                        "WHERE token_hash = ? AND consumed_at IS NULL AND expires_at > now() " +
                        "RETURNING user_id, community_id, expires_at",
                (rs, rowNum) -> new IssueTokenRow(
                        rs.getLong("user_id"),
                        rs.getLong("community_id"),
                        rs.getObject("expires_at", OffsetDateTime.class)
                ),
                tokenHash
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public record IssueTokenRow(long userId, long communityId, OffsetDateTime expiresAt) {}
}

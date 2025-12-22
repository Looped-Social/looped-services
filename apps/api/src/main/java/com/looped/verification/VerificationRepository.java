package com.looped.verification;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;

@Repository
public class VerificationRepository {
    private final JdbcTemplate jdbc;

    public VerificationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void upsertMethod(long userId, String method) {
        jdbc.update(
                "INSERT INTO verifications(user_id, method, verified, verified_at) VALUES (?,?,COALESCE((SELECT verified FROM verifications WHERE user_id=?), false), COALESCE((SELECT verified_at FROM verifications WHERE user_id=?), NULL)) " +
                        "ON CONFLICT (user_id) DO UPDATE SET method=EXCLUDED.method",
                userId, method, userId, userId
        );
    }

    public void markVerified(long userId, String method) {
        jdbc.update(
                "INSERT INTO verifications(user_id, method, verified, verified_at) VALUES (?,?,true, now()) " +
                        "ON CONFLICT (user_id) DO UPDATE SET method=EXCLUDED.method, verified=true, verified_at=now()",
                userId, method
        );
    }

    public void markUnverified(long userId, String method) {
        jdbc.update(
                "INSERT INTO verifications(user_id, method, verified, verified_at) VALUES (?,?,false, NULL) " +
                        "ON CONFLICT (user_id) DO UPDATE SET method=EXCLUDED.method, verified=false, verified_at=NULL",
                userId, method
        );
    }

    public Optional<Row> findByUserId(long userId) {
        var list = jdbc.query("SELECT user_id, method, verified, verified_at FROM verifications WHERE user_id=?", MAPPER, userId);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    private static final RowMapper<Row> MAPPER = new RowMapper<>() {
        @Override
        public Row mapRow(ResultSet rs, int rowNum) throws SQLException {
            Row r = new Row();
            r.userId = rs.getLong("user_id");
            r.method = rs.getString("method");
            r.verified = rs.getBoolean("verified");
            r.verifiedAt = rs.getObject("verified_at", OffsetDateTime.class);
            return r;
        }
    };

    public static class Row {
        public long userId;
        public String method;
        public boolean verified;
        public OffsetDateTime verifiedAt;
    }
}

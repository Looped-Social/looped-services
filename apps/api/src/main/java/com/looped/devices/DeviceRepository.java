package com.looped.devices;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

@Repository
public class DeviceRepository {
    private final JdbcTemplate jdbcTemplate;

    public DeviceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<DeviceRow> MAPPER = new RowMapper<>() {
        @Override
        public DeviceRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            DeviceRow row = new DeviceRow();
            row.id = rs.getLong("id");
            row.userId = rs.getLong("user_id");
            row.apnsToken = rs.getString("apns_token");
            row.platform = rs.getString("platform");
            return row;
        }
    };

    public Optional<DeviceRow> findByApnsToken(String token) {
        var list = jdbcTemplate.query("SELECT id, user_id, apns_token, platform FROM devices WHERE apns_token = ?", MAPPER, token);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public UpsertResult upsert(long userId, String apnsToken, String platform) {
        boolean existed = findByApnsToken(apnsToken).isPresent();
        Long id = jdbcTemplate.query(
                "INSERT INTO devices(user_id, apns_token, platform) VALUES (?,?,?) " +
                        "ON CONFLICT (apns_token) DO UPDATE SET user_id=EXCLUDED.user_id, platform=EXCLUDED.platform " +
                        "RETURNING id",
                rs -> rs.next() ? rs.getLong(1) : null,
                userId, apnsToken, platform
        );
        // We can't directly know if it was an insert vs update; fetch existing and compare user
        var row = findByApnsToken(apnsToken).orElseThrow();
        boolean created = !existed;
        return new UpsertResult(row.id, created);
    }

    public record UpsertResult(long id, boolean created) {}

    public static class DeviceRow {
        public long id;
        public long userId;
        public String apnsToken;
        public String platform;
    }
}

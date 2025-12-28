package com.looped.companies;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;

@Repository
public class CompanyRepository {
    private final JdbcTemplate jdbc;

    public CompanyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<CompanyRow> MAPPER = new RowMapper<>() {
        @Override
        public CompanyRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            CompanyRow row = new CompanyRow();
            row.id = rs.getLong("id");
            row.name = rs.getString("name");
            row.domain = rs.getString("domain");
            row.createdAt = rs.getObject("created_at", OffsetDateTime.class);
            return row;
        }
    };

    public Optional<CompanyRow> findByDomain(String domain) {
        if (domain == null || domain.isBlank()) return Optional.empty();
        var list = jdbc.query(
                "SELECT id, name, domain, created_at FROM companies WHERE domain = ?",
                MAPPER, domain.toLowerCase(java.util.Locale.ROOT)
        );
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public static class CompanyRow {
        public long id;
        public String name;
        public String domain;
        public OffsetDateTime createdAt;
    }
}

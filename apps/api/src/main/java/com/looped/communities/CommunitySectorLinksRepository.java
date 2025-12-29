package com.looped.communities;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;

@Repository
public class CommunitySectorLinksRepository {
    private final JdbcTemplate jdbc;

    public CommunitySectorLinksRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<CommunitiesRepository.CommunityRow> COMMUNITY_MAPPER =
            new RowMapper<>() {
                @Override
                public CommunitiesRepository.CommunityRow mapRow(ResultSet rs, int rowNum) throws SQLException {
                    CommunitiesRepository.CommunityRow row = new CommunitiesRepository.CommunityRow();
                    row.id = rs.getLong("id");
                    row.kind = rs.getString("kind");
                    row.name = rs.getString("name");
                    row.description = rs.getString("description");
                    row.memberCount = rs.getInt("member_count");
                    row.imageUrl = rs.getString("image_url");
                    row.createdAt = rs.getObject("created_at", OffsetDateTime.class);
                    int ttlDays = rs.getInt("verification_ttl_days");
                    row.verificationTtlDays = rs.wasNull() ? null : ttlDays;
                    return row;
                }
            };

    public boolean insert(long sectorId, long companyId) {
        int rows = jdbc.update(
                "INSERT INTO community_sector_links(sector_id, company_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                sectorId,
                companyId
        );
        return rows > 0;
    }

    public boolean delete(long sectorId, long companyId) {
        int rows = jdbc.update(
                "DELETE FROM community_sector_links WHERE sector_id = ? AND company_id = ?",
                sectorId,
                companyId
        );
        return rows > 0;
    }

    public List<Long> listCompanyIds(long sectorId) {
        return jdbc.query(
                "SELECT company_id FROM community_sector_links WHERE sector_id = ? ORDER BY company_id ASC",
                (rs, rowNum) -> rs.getLong("company_id"),
                sectorId
        );
    }

    public List<CommunitiesRepository.CommunityRow> listCompanies(long sectorId) {
        return jdbc.query(
                "SELECT c.id, c.kind, c.name, c.description, c.member_count, c.image_url, c.created_at, c.verification_ttl_days " +
                        "FROM community_sector_links l JOIN communities c ON c.id = l.company_id " +
                        "WHERE l.sector_id = ? ORDER BY c.name ASC, c.id ASC",
                COMMUNITY_MAPPER,
                sectorId
        );
    }
}

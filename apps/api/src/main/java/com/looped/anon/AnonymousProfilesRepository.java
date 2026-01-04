package com.looped.anon;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;

@Repository
public class AnonymousProfilesRepository {
    private final JdbcTemplate jdbc;

    public AnonymousProfilesRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<AnonymousProfileRow> MAPPER = new RowMapper<>() {
        @Override
        public AnonymousProfileRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            AnonymousProfileRow row = new AnonymousProfileRow();
            row.id = rs.getLong("id");
            long companyId = rs.getLong("company_id");
            row.companyId = rs.wasNull() ? null : companyId;
            row.publicKey = rs.getBytes("public_key");
            row.handle = rs.getString("handle");
            long displayCommunityId = rs.getLong("display_community_id");
            row.displayCommunityId = rs.wasNull() ? null : displayCommunityId;
            row.displayCommunityCertKid = rs.getString("display_community_cert_kid");
            row.createdAt = rs.getObject("created_at", OffsetDateTime.class);
            return row;
        }
    };

    public Optional<AnonymousProfileRow> findById(long id) {
        var rows = jdbc.query(
                "SELECT id, company_id, public_key, handle, display_community_id, display_community_cert_kid, created_at " +
                        "FROM anonymous_profiles WHERE id = ?",
                MAPPER, id
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<AnonymousProfileRow> findByPublicKey(byte[] publicKey) {
        var rows = jdbc.query(
                "SELECT id, company_id, public_key, handle, display_community_id, display_community_cert_kid, created_at " +
                        "FROM anonymous_profiles WHERE public_key = ?",
                MAPPER, publicKey
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public AnonymousProfileRow create(Long companyId, byte[] publicKey) {
        String placeholder = "tmp-" + java.util.UUID.randomUUID();
        Long id = jdbc.query(
                "INSERT INTO anonymous_profiles(company_id, public_key, handle) VALUES (?,?,?) RETURNING id",
                rs -> rs.next() ? rs.getLong(1) : null,
                companyId, publicKey, placeholder
        );
        String handle = "anonymous" + id;
        jdbc.update("UPDATE anonymous_profiles SET handle = ? WHERE id = ?", handle, id);
        return findById(id).orElseThrow();
    }

    public boolean updateDisplayCommunity(long anonProfileId, Long communityId, String certKid) {
        int rows = jdbc.update(
                "UPDATE anonymous_profiles SET display_community_id = ?, display_community_cert_kid = ? WHERE id = ?",
                communityId, certKid, anonProfileId
        );
        return rows > 0;
    }

    public static class AnonymousProfileRow {
        public long id;
        public Long companyId;
        public byte[] publicKey;
        public String handle;
        public Long displayCommunityId;
        public String displayCommunityCertKid;
        public OffsetDateTime createdAt;
    }
}

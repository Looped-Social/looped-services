package com.looped.communities;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Locale;

@Repository
public class CommunityDomainsRepository {
    private final JdbcTemplate jdbc;

    public CommunityDomainsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean hasDomains(long communityId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM community_domains WHERE community_id = ?",
                Integer.class,
                communityId
        );
        return count != null && count > 0;
    }

    public boolean isDomainAllowed(long communityId, String domain) {
        String normalized = normalizeDomain(domain);
        if (normalized == null) return false;
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM community_domains WHERE community_id = ? AND domain = ?",
                Integer.class,
                communityId,
                normalized
        );
        return count != null && count > 0;
    }

    public List<String> listDomains(long communityId) {
        return jdbc.query(
                "SELECT domain FROM community_domains WHERE community_id = ? ORDER BY domain ASC",
                (rs, rowNum) -> rs.getString("domain"),
                communityId
        );
    }

    public boolean insert(long communityId, String domain) {
        String normalized = normalizeDomain(domain);
        if (normalized == null) return false;
        int rows = jdbc.update(
                "INSERT INTO community_domains(community_id, domain) VALUES (?, ?) ON CONFLICT DO NOTHING",
                communityId,
                normalized
        );
        return rows > 0;
    }

    public boolean delete(long communityId, String domain) {
        String normalized = normalizeDomain(domain);
        if (normalized == null) return false;
        int rows = jdbc.update(
                "DELETE FROM community_domains WHERE community_id = ? AND domain = ?",
                communityId,
                normalized
        );
        return rows > 0;
    }

    public String normalizeDomain(String domain) {
        if (domain == null) return null;
        String trimmed = domain.trim().toLowerCase(Locale.ROOT);
        if (trimmed.startsWith("@")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.isBlank()) return null;
        if (!trimmed.matches("^[a-z0-9.-]+$")) return null;
        return trimmed;
    }
}

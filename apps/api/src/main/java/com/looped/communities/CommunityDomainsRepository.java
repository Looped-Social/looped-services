package com.looped.communities;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

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

    public Optional<String> firstDomain(long communityId) {
        var rows = jdbc.query(
                "SELECT domain FROM community_domains WHERE community_id = ? ORDER BY domain ASC LIMIT 1",
                (rs, rowNum) -> rs.getString("domain"),
                communityId
        );
        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
    }

    public Map<Long, String> firstDomainsForCommunities(List<Long> communityIds) {
        if (communityIds == null || communityIds.isEmpty()) return Map.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(communityIds.size(), "?"));
        Object[] params = communityIds.toArray();
        var rows = jdbc.query(
                "SELECT community_id, domain FROM community_domains WHERE community_id IN (" + placeholders + ") " +
                        "ORDER BY community_id ASC, domain ASC",
                (rs, rowNum) -> Map.entry(rs.getLong("community_id"), rs.getString("domain")),
                params
        );
        Map<Long, String> out = new HashMap<>();
        for (var entry : rows) {
            out.putIfAbsent(entry.getKey(), entry.getValue());
        }
        return out;
    }

    public List<String> listDomainsForCommunities(List<Long> communityIds) {
        if (communityIds == null || communityIds.isEmpty()) return List.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(communityIds.size(), "?"));
        Object[] params = communityIds.toArray();
        return jdbc.query(
                "SELECT DISTINCT domain FROM community_domains WHERE community_id IN (" + placeholders + ") ORDER BY domain ASC",
                (rs, rowNum) -> rs.getString("domain"),
                params
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

    public boolean hasDomainsForCommunities(List<Long> communityIds) {
        if (communityIds == null || communityIds.isEmpty()) return false;
        String placeholders = String.join(",", java.util.Collections.nCopies(communityIds.size(), "?"));
        Object[] params = communityIds.toArray();
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM community_domains WHERE community_id IN (" + placeholders + ")",
                Integer.class,
                params
        );
        return count != null && count > 0;
    }

    public boolean isDomainAllowedForCommunities(List<Long> communityIds, String domain) {
        String normalized = normalizeDomain(domain);
        if (normalized == null || communityIds == null || communityIds.isEmpty()) return false;
        String placeholders = String.join(",", java.util.Collections.nCopies(communityIds.size(), "?"));
        Object[] params = new Object[communityIds.size() + 1];
        params[0] = normalized;
        for (int i = 0; i < communityIds.size(); i++) {
            params[i + 1] = communityIds.get(i);
        }
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM community_domains WHERE domain = ? AND community_id IN (" + placeholders + ")",
                Integer.class,
                params
        );
        return count != null && count > 0;
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

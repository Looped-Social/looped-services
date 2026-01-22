package com.looped.communities;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.net.URI;
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
                "SELECT domain FROM community_domains WHERE community_id = ? " +
                        "ORDER BY (length(domain) - length(replace(domain, '.', ''))) ASC, length(domain) ASC, domain ASC " +
                        "LIMIT 1",
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
                        "ORDER BY community_id ASC, " +
                        "(length(domain) - length(replace(domain, '.', ''))) ASC, length(domain) ASC, domain ASC",
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
        if (rows > 0) {
            jdbc.update(
                    "UPDATE communities SET short_name = split_part(?, '.', 1) " +
                            "WHERE id = ? AND (short_name IS NULL OR btrim(short_name) = '')",
                    normalized,
                    communityId
            );
            return true;
        }
        return false;
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
        String trimmed = domain.trim();
        if (trimmed.isBlank()) return null;

        String normalized = trimmed.toLowerCase(Locale.ROOT);

        // Accept URLs like https://www.unc.edu/path -> unc.edu
        if (normalized.contains("://")) {
            try {
                URI uri = URI.create(normalized);
                String host = uri.getHost();
                if (host != null && !host.isBlank()) normalized = host.toLowerCase(Locale.ROOT);
            } catch (IllegalArgumentException ignored) {
                // Fall through to best-effort parsing below.
            }
        }

        // Accept emails like hello@unc.edu -> unc.edu
        int at = normalized.lastIndexOf('@');
        if (at >= 0 && at + 1 < normalized.length()) {
            normalized = normalized.substring(at + 1);
        }

        if (normalized.startsWith("@")) normalized = normalized.substring(1);
        if (normalized.startsWith("www.")) normalized = normalized.substring(4);
        int slash = normalized.indexOf('/');
        if (slash >= 0) normalized = normalized.substring(0, slash);
        int q = normalized.indexOf('?');
        if (q >= 0) normalized = normalized.substring(0, q);
        int hash = normalized.indexOf('#');
        if (hash >= 0) normalized = normalized.substring(0, hash);
        int colon = normalized.indexOf(':');
        if (colon >= 0) {
            String maybePort = normalized.substring(colon + 1);
            if (!maybePort.isBlank() && maybePort.chars().allMatch(Character::isDigit)) {
                normalized = normalized.substring(0, colon);
            }
        }

        normalized = normalized.trim();
        if (normalized.isBlank()) return null;
        if (!normalized.matches("^[a-z0-9.-]+$")) return null;
        return normalized;
    }
}

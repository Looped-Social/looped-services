-- Backfill communities.short_name from the first segment of an authorized domain.
-- Example: unc.edu -> unc
-- This is a one-time, idempotent backfill and does not overwrite existing short_name values.

UPDATE communities c
SET short_name = split_part(d.domain, '.', 1)
FROM (
    -- Prefer the least-subdomain / shortest domain (e.g., unc.edu over alumni.unc.edu).
    SELECT DISTINCT ON (community_id) community_id, domain
    FROM community_domains
    ORDER BY community_id,
             (length(domain) - length(replace(domain, '.', ''))) ASC,
             length(domain) ASC,
             domain ASC
) d
WHERE c.id = d.community_id
  AND (c.short_name IS NULL OR btrim(c.short_name) = '');

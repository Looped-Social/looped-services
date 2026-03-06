-- Remove school/major user-facing state while keeping school metadata for admin management.

-- Delete posts in deprecated communities (schools + major specializations).
DELETE FROM posts p
USING communities c
WHERE p.community_id = c.id
  AND (
      lower(c.kind) = 'school'
      OR (c.kind = 'specialization' AND lower(COALESCE(c.specialization_type, '')) = 'major')
  );

-- Remove follows/widget state for deprecated communities.
DELETE FROM community_follows cf
USING communities c
WHERE cf.community_id = c.id
  AND (
      lower(c.kind) = 'school'
      OR (c.kind = 'specialization' AND lower(COALESCE(c.specialization_type, '')) = 'major')
  );

DELETE FROM widget_community_state w
USING communities c
WHERE w.community_id = c.id
  AND (
      lower(c.kind) = 'school'
      OR (c.kind = 'specialization' AND lower(COALESCE(c.specialization_type, '')) = 'major')
  );

-- Revoke community verifications and related verification requests for deprecated communities.
DELETE FROM community_verifications cv
USING communities c
WHERE cv.community_id = c.id
  AND (
      lower(c.kind) = 'school'
      OR (c.kind = 'specialization' AND lower(COALESCE(c.specialization_type, '')) = 'major')
  );

DELETE FROM verification_requests vr
USING communities c
WHERE vr.community_id = c.id
  AND (
      lower(c.kind) = 'school'
      OR (c.kind = 'specialization' AND lower(COALESCE(c.specialization_type, '')) = 'major')
  );

-- Revoke anonymous enrollment artifacts for deprecated communities.
DELETE FROM anon_cert_entitlements ace
USING communities c
WHERE ace.community_id = c.id
  AND (
      lower(c.kind) = 'school'
      OR (c.kind = 'specialization' AND lower(COALESCE(c.specialization_type, '')) = 'major')
  );

DELETE FROM anon_issue_tokens ait
USING communities c
WHERE ait.community_id = c.id
  AND (
      lower(c.kind) = 'school'
      OR (c.kind = 'specialization' AND lower(COALESCE(c.specialization_type, '')) = 'major')
  );

-- Remove deprecated major requests.
DELETE FROM community_requests
WHERE lower(kind) = 'major';

-- Clear user display pointers to deprecated communities.
UPDATE users u
SET display_community_id = NULL
WHERE u.display_community_id IN (
    SELECT id
    FROM communities
    WHERE lower(kind) = 'school'
       OR (kind = 'specialization' AND lower(COALESCE(specialization_type, '')) = 'major')
);

UPDATE users u
SET display_specialization_id = NULL
WHERE u.display_specialization_id IN (
    SELECT id
    FROM communities
    WHERE kind = 'specialization'
      AND lower(COALESCE(specialization_type, '')) = 'major'
);

UPDATE anonymous_profiles ap
SET display_community_id = NULL,
    display_community_cert_kid = NULL
WHERE ap.display_community_id IN (
    SELECT id
    FROM communities
    WHERE lower(kind) = 'school'
       OR (kind = 'specialization' AND lower(COALESCE(specialization_type, '')) = 'major')
);

UPDATE anonymous_profiles ap
SET display_specialization_id = NULL,
    display_specialization_cert_kid = NULL
WHERE ap.display_specialization_id IN (
    SELECT id
    FROM communities
    WHERE kind = 'specialization'
      AND lower(COALESCE(specialization_type, '')) = 'major'
);

-- Clear onboarding selections for deprecated kinds.
UPDATE user_onboarding_v2 ov
SET selected_org_id = NULL,
    selected_org_kind = NULL
WHERE lower(COALESCE(ov.selected_org_kind, '')) = 'school'
   OR ov.selected_org_id IN (
       SELECT id FROM communities WHERE lower(kind) = 'school'
   );

UPDATE user_onboarding_v2 ov
SET selected_specialization_id = NULL
WHERE ov.selected_specialization_id IN (
    SELECT id
    FROM communities
    WHERE kind = 'specialization'
      AND lower(COALESCE(specialization_type, '')) = 'major'
);

-- Delete major communities entirely (schools remain as metadata).
DELETE FROM communities
WHERE kind = 'specialization'
  AND lower(COALESCE(specialization_type, '')) = 'major';

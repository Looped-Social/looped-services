-- Seed initial owner admins (email allowlist)

INSERT INTO admin_users (firebase_uid, email, role, status, permissions)
SELECT
    NULL,
    'william@mylooped.app',
    'owner',
    'active',
    ARRAY[
        'manage_admins','ban_user','remove_post','create_community',
        'view_reports','resolve_reports','verify_users','delete_media','view_feedback'
    ]::text[]
WHERE NOT EXISTS (
    SELECT 1 FROM admin_users WHERE email = 'william@mylooped.app'
);

INSERT INTO admin_users (firebase_uid, email, role, status, permissions)
SELECT
    NULL,
    'luke@mylooped.app',
    'owner',
    'active',
    ARRAY[
        'manage_admins','ban_user','remove_post','create_community',
        'view_reports','resolve_reports','verify_users','delete_media','view_feedback'
    ]::text[]
WHERE NOT EXISTS (
    SELECT 1 FROM admin_users WHERE email = 'luke@mylooped.app'
);

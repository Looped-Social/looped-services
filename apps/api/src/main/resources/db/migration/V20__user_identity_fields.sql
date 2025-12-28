-- Required identity fields for onboarding

ALTER TABLE IF EXISTS users
    ADD COLUMN IF NOT EXISTS first_name TEXT NOT NULL DEFAULT 'Unknown',
    ADD COLUMN IF NOT EXISTS last_name TEXT NOT NULL DEFAULT 'User',
    ADD COLUMN IF NOT EXISTS date_of_birth DATE NOT NULL DEFAULT DATE '1970-01-01';

UPDATE users SET first_name = 'Unknown' WHERE first_name IS NULL;
UPDATE users SET last_name = 'User' WHERE last_name IS NULL;
UPDATE users SET date_of_birth = DATE '1970-01-01' WHERE date_of_birth IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS idx_users_handle_lower ON users (lower(handle));

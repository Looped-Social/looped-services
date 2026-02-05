-- Admin verification queue: stable keyset pagination + FIFO pending support

CREATE INDEX IF NOT EXISTS idx_verification_requests_admin_queue
    ON verification_requests(status, method, submitted_at, id);


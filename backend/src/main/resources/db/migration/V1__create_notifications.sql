CREATE TABLE notifications (
    id UUID PRIMARY KEY,
    title VARCHAR(100) NOT NULL,
    description TEXT,
    severity VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    resolution_note VARCHAR(2000),
    resolved_at TIMESTAMPTZ,
    source_message_id VARCHAR(255) UNIQUE,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_notifications_created_at ON notifications (created_at);

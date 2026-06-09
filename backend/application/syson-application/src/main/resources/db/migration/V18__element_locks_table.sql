-- V18: Element locks table (idempotent).
-- Ensures syson_element_locks exists for element-level edit locking.
-- Follows sidecar pattern: parallel table, no modification to upstream Sirius tables.

CREATE TABLE IF NOT EXISTS syson_element_locks (
    project_id TEXT NOT NULL,
    branch_id UUID NOT NULL,
    stable_id TEXT NOT NULL,
    lock_type VARCHAR(50) NOT NULL DEFAULT 'edit',
    owner_user_id UUID NOT NULL,
    owner_username TEXT,
    owner_session_id TEXT,
    reason TEXT,
    acquired_at TIMESTAMPTZ DEFAULT now(),
    refreshed_at TIMESTAMPTZ DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (project_id, branch_id, stable_id, lock_type)
);

CREATE INDEX IF NOT EXISTS idx_sel_owner ON syson_element_locks(owner_user_id, expires_at);
CREATE INDEX IF NOT EXISTS idx_sel_project ON syson_element_locks(project_id, expires_at);

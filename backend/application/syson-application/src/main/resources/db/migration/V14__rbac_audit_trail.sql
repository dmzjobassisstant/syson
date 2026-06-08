-- V14: RBAC Audit Trail
-- Dedicated audit trail for all RBAC configuration changes.
-- This table is append-only — the application layer enforces INSERT-only access.
-- Only SuperUsers can read this table (enforced at the service/controller level).

CREATE TABLE IF NOT EXISTS syson_rbac_audit_trail (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  event_type      VARCHAR(100) NOT NULL,
  actor_id        UUID NOT NULL REFERENCES syson_users(id),
  actor_email     VARCHAR(255) NOT NULL,
  actor_role      VARCHAR(50) NOT NULL,
  target_type     VARCHAR(100) NOT NULL,
  target_id       VARCHAR(255) NOT NULL,
  target_email    VARCHAR(255),
  project_id      TEXT,
  old_value       JSONB,
  new_value       JSONB,
  reason          TEXT,
  ip_address      VARCHAR(45),
  user_agent      TEXT,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Indexes for common query patterns
CREATE INDEX IF NOT EXISTS idx_rbac_audit_actor ON syson_rbac_audit_trail(actor_id);
CREATE INDEX IF NOT EXISTS idx_rbac_audit_target ON syson_rbac_audit_trail(target_type, target_id);
CREATE INDEX IF NOT EXISTS idx_rbac_audit_project ON syson_rbac_audit_trail(project_id);
CREATE INDEX IF NOT EXISTS idx_rbac_audit_event_type ON syson_rbac_audit_trail(event_type);
CREATE INDEX IF NOT EXISTS idx_rbac_audit_created ON syson_rbac_audit_trail(created_at DESC);

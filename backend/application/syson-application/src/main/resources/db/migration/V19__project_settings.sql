-- V19: Per-project settings table + seed element_locking_enabled=false.
-- Sidecar table: no modification to upstream Sirius tables.

CREATE TABLE IF NOT EXISTS syson_project_settings (
    project_id  TEXT NOT NULL,
    key         VARCHAR(255) NOT NULL,
    value       JSONB NOT NULL,
    description TEXT,
    updated_by  UUID,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (project_id, key)
);

-- Seed: element locking disabled by default for all existing projects
INSERT INTO syson_project_settings (project_id, key, value, description)
SELECT p.id::text, 'element_locking_enabled', 'false', 'Enable element-level edit locking for this project'
FROM project p
ON CONFLICT (project_id, key) DO NOTHING;

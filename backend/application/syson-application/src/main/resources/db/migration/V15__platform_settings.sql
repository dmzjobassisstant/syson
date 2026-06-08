-- V15: Platform Settings
-- Key-value store for platform-wide configuration.
-- Only SuperUsers can modify settings (enforced at service level).

CREATE TABLE IF NOT EXISTS syson_platform_settings (
  key             VARCHAR(255) PRIMARY KEY,
  value           JSONB NOT NULL,
  description     TEXT,
  updated_by      UUID REFERENCES syson_users(id),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Seed default settings
INSERT INTO syson_platform_settings (key, value, description) VALUES
  ('platform.name', '"SysON"', 'Platform display name'),
  ('platform.allow_self_registration', 'false', 'Allow users to self-register'),
  ('platform.default_project_role', '"user"', 'Default role assigned to new project members'),
  ('platform.max_projects_per_user', '0', 'Maximum projects per user (0 = unlimited)')
ON CONFLICT (key) DO NOTHING;

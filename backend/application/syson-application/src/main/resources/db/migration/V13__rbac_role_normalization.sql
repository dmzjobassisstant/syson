-- V13: RBAC Role Normalization
-- Normalize platform roles (rename 'editor' → 'user' for consistency)
-- Add role change tracking columns to membership tables
-- Add is_system_admin flag for quick SuperUser lookup

-- 1. Rename 'editor' → 'user' in tenant memberships
UPDATE syson_tenant_memberships SET role = 'user' WHERE role = 'editor';

-- 2. Update the CHECK constraint to use the new role names
ALTER TABLE syson_tenant_memberships
  DROP CONSTRAINT IF EXISTS syson_tenant_memberships_role_check;
ALTER TABLE syson_tenant_memberships
  ADD CONSTRAINT syson_tenant_memberships_role_check
  CHECK (role IN ('superuser', 'admin', 'user', 'viewer'));

-- 3. Add role change tracking to tenant memberships
ALTER TABLE syson_tenant_memberships
  ADD COLUMN IF NOT EXISTS role_changed_at TIMESTAMPTZ DEFAULT now(),
  ADD COLUMN IF NOT EXISTS role_changed_by UUID REFERENCES syson_users(id);

-- 4. Add role change tracking to project memberships
ALTER TABLE syson_project_members
  ADD COLUMN IF NOT EXISTS role_changed_at TIMESTAMPTZ DEFAULT now(),
  ADD COLUMN IF NOT EXISTS role_changed_by UUID REFERENCES syson_users(id);

-- 5. Add is_system_admin flag to syson_users
ALTER TABLE syson_users
  ADD COLUMN IF NOT EXISTS is_system_admin BOOLEAN NOT NULL DEFAULT FALSE;

-- 6. Set existing superusers as system admins
UPDATE syson_users SET is_system_admin = TRUE
  WHERE id IN (
    SELECT user_id FROM syson_tenant_memberships WHERE role = 'superuser'
  );

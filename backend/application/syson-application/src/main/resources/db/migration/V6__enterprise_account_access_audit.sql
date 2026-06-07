-- Enterprise account administration, element access control, and audit support.
-- Additive SysON-owned schema. PostgreSQL-first; safe to run on existing installations.

ALTER TABLE syson_users
    ADD COLUMN IF NOT EXISTS email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS last_login_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS password_changed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS created_by UUID,
    ADD COLUMN IF NOT EXISTS deactivated_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS deactivated_by UUID;

CREATE INDEX IF NOT EXISTS idx_syson_users_active ON syson_users(is_active);
CREATE INDEX IF NOT EXISTS idx_syson_users_email_verified ON syson_users(email_verified);

CREATE TABLE IF NOT EXISTS syson_password_reset_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES syson_users(id) ON DELETE CASCADE,
    token_hash TEXT NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_ip TEXT,
    user_agent TEXT
);

CREATE INDEX IF NOT EXISTS idx_syson_password_reset_tokens_user
    ON syson_password_reset_tokens(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_syson_password_reset_tokens_active
    ON syson_password_reset_tokens(token_hash) WHERE used_at IS NULL;

CREATE TABLE IF NOT EXISTS syson_email_verification_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES syson_users(id) ON DELETE CASCADE,
    token_hash TEXT NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_syson_email_verification_tokens_user
    ON syson_email_verification_tokens(user_id, created_at DESC);

CREATE TABLE IF NOT EXISTS syson_invitations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES syson_tenants(id) ON DELETE CASCADE,
    project_id TEXT,
    email TEXT NOT NULL,
    tenant_role TEXT CHECK (tenant_role IN ('superuser','admin','editor','viewer')),
    project_role TEXT CHECK (project_role IN ('admin','user','viewer')),
    token_hash TEXT NOT NULL UNIQUE,
    invited_by UUID REFERENCES syson_users(id),
    accepted_by UUID REFERENCES syson_users(id),
    expires_at TIMESTAMPTZ NOT NULL,
    accepted_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_syson_invitations_email
    ON syson_invitations(lower(email), created_at DESC);
CREATE INDEX IF NOT EXISTS idx_syson_invitations_tenant
    ON syson_invitations(tenant_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_syson_invitations_project
    ON syson_invitations(project_id, created_at DESC) WHERE project_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS syson_element_permissions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES syson_tenants(id) ON DELETE CASCADE,
    project_id TEXT NOT NULL,
    branch_id UUID,
    element_id UUID NOT NULL,
    subject_type TEXT NOT NULL CHECK (subject_type IN ('user','tenant_role','project_role')),
    subject_id TEXT NOT NULL,
    permission TEXT NOT NULL CHECK (permission IN ('read','comment','write','admin')),
    inherit_to_children BOOLEAN NOT NULL DEFAULT TRUE,
    starts_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,
    created_by UUID REFERENCES syson_users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (project_id, branch_id, element_id, subject_type, subject_id, permission)
);

CREATE INDEX IF NOT EXISTS idx_syson_element_permissions_lookup
    ON syson_element_permissions(project_id, branch_id, element_id, subject_type, subject_id, permission);
CREATE INDEX IF NOT EXISTS idx_syson_element_permissions_subject
    ON syson_element_permissions(subject_type, subject_id, project_id);
CREATE INDEX IF NOT EXISTS idx_syson_element_permissions_element
    ON syson_element_permissions(project_id, branch_id, element_id);

CREATE TABLE IF NOT EXISTS syson_branch_permissions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES syson_tenants(id) ON DELETE CASCADE,
    project_id TEXT NOT NULL,
    branch_id UUID NOT NULL,
    subject_type TEXT NOT NULL CHECK (subject_type IN ('user','tenant_role','project_role')),
    subject_id TEXT NOT NULL,
    permission TEXT NOT NULL CHECK (permission IN ('read','write','admin')),
    created_by UUID REFERENCES syson_users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (branch_id, subject_type, subject_id, permission)
);

CREATE INDEX IF NOT EXISTS idx_syson_branch_permissions_lookup
    ON syson_branch_permissions(project_id, branch_id, subject_type, subject_id, permission);

CREATE INDEX IF NOT EXISTS idx_syson_audit_events_tenant_created
    ON syson_audit_events(tenant_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_syson_audit_events_actor_created
    ON syson_audit_events(actor_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_syson_audit_events_target_created
    ON syson_audit_events(target_type, target_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_syson_audit_events_action_created
    ON syson_audit_events(action, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_syson_audit_events_metadata_gin
    ON syson_audit_events USING GIN(metadata);

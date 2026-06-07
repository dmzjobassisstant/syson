-- V8: Enterprise locks, tags, merge requests, conflicts, integrity checks.
-- All tables use TEXT project_id to match upstream Sirius project(id).

-- syson_tags: named references to specific commits
CREATE TABLE IF NOT EXISTS syson_tags (
    tag_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id TEXT NOT NULL,
    branch_id UUID,
    commit_id UUID NOT NULL REFERENCES syson_commits(commit_id),
    name VARCHAR(255) NOT NULL,
    description TEXT,
    created_by UUID REFERENCES syson_users(id),
    created_at TIMESTAMPTZ DEFAULT now(),
    UNIQUE(project_id, name)
);
CREATE INDEX IF NOT EXISTS idx_st_project ON syson_tags(project_id);

-- syson_merge_requests: branch merge tracking
CREATE TABLE IF NOT EXISTS syson_merge_requests (
    merge_request_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id TEXT NOT NULL,
    source_branch_id UUID NOT NULL,
    target_branch_id UUID NOT NULL,
    base_commit_id UUID,
    source_commit_id UUID,
    target_commit_id UUID,
    status VARCHAR(50) NOT NULL DEFAULT 'open',
    title TEXT,
    description TEXT,
    created_by UUID REFERENCES syson_users(id),
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_smr_project ON syson_merge_requests(project_id, status);

-- syson_merge_conflicts: per-object conflicts in merge requests
CREATE TABLE IF NOT EXISTS syson_merge_conflicts (
    conflict_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merge_request_id UUID NOT NULL REFERENCES syson_merge_requests(merge_request_id) ON DELETE CASCADE,
    object_type VARCHAR(100) NOT NULL,
    object_id TEXT NOT NULL,
    field_path TEXT,
    base_value JSONB,
    source_value JSONB,
    target_value JSONB,
    resolution JSONB,
    status VARCHAR(50) NOT NULL DEFAULT 'unresolved',
    resolved_by UUID REFERENCES syson_users(id),
    resolved_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_smc_mr ON syson_merge_conflicts(merge_request_id, status);

-- syson_branch_locks: write locks on branches
CREATE TABLE IF NOT EXISTS syson_branch_locks (
    project_id TEXT NOT NULL,
    branch_id UUID NOT NULL,
    lock_type VARCHAR(50) NOT NULL DEFAULT 'write',
    owner_user_id UUID NOT NULL REFERENCES syson_users(id),
    owner_session_id TEXT,
    owner_device_id TEXT,
    reason TEXT,
    acquired_at TIMESTAMPTZ DEFAULT now(),
    refreshed_at TIMESTAMPTZ DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (project_id, branch_id, lock_type)
);

-- syson_element_locks: edit locks on individual elements
CREATE TABLE IF NOT EXISTS syson_element_locks (
    project_id TEXT NOT NULL,
    branch_id UUID NOT NULL,
    stable_id TEXT NOT NULL,
    lock_type VARCHAR(50) NOT NULL DEFAULT 'edit',
    owner_user_id UUID NOT NULL REFERENCES syson_users(id),
    owner_session_id TEXT,
    reason TEXT,
    acquired_at TIMESTAMPTZ DEFAULT now(),
    refreshed_at TIMESTAMPTZ DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (project_id, branch_id, stable_id, lock_type)
);
CREATE INDEX IF NOT EXISTS idx_sel_owner ON syson_element_locks(owner_user_id, expires_at);

-- syson_integrity_checks: integrity check results
CREATE TABLE IF NOT EXISTS syson_integrity_checks (
    check_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id TEXT NOT NULL,
    branch_id UUID NOT NULL,
    commit_id UUID,
    status VARCHAR(50) NOT NULL,
    error_count INT DEFAULT 0,
    warning_count INT DEFAULT 0,
    findings JSONB NOT NULL DEFAULT '[]',
    checked_at TIMESTAMPTZ DEFAULT now(),
    checked_by UUID REFERENCES syson_users(id)
);
CREATE INDEX IF NOT EXISTS idx_sic_branch ON syson_integrity_checks(project_id, branch_id, checked_at DESC);

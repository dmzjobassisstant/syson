CREATE TABLE IF NOT EXISTS syson_branches (
    branch_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    name VARCHAR(255) NOT NULL,
    branch_type VARCHAR(50) DEFAULT 'main' CHECK (branch_type IN ('main','feature','release','hotfix')),
    head_commit_id UUID,
    base_commit_id UUID,
    parent_branch_id UUID,
    is_protected BOOLEAN DEFAULT FALSE,
    is_deleted BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ DEFAULT now(),
    created_by UUID REFERENCES syson_users(id)
);
CREATE INDEX idx_syson_branches_project ON syson_branches(project_id, tenant_id);
CREATE UNIQUE INDEX idx_syson_branches_name ON syson_branches(project_id, name) WHERE NOT is_deleted;

CREATE TABLE IF NOT EXISTS syson_commits (
    commit_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL,
    branch_id UUID NOT NULL REFERENCES syson_branches(branch_id),
    commit_number BIGINT NOT NULL,
    message TEXT DEFAULT '',
    author_user_id UUID REFERENCES syson_users(id),
    change_count INT DEFAULT 0,
    commit_hash VARCHAR(64),
    parent_commit_ids JSONB DEFAULT '[]',
    committed_at TIMESTAMPTZ DEFAULT now(),
    source VARCHAR(50) DEFAULT 'direct',
    status VARCHAR(50) DEFAULT 'committed'
);
CREATE INDEX idx_syson_commits_branch ON syson_commits(project_id, branch_id, committed_at DESC);
CREATE UNIQUE INDEX idx_syson_commits_number ON syson_commits(project_id, branch_id, commit_number);

CREATE TABLE IF NOT EXISTS syson_changes (
    change_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL,
    commit_id UUID NOT NULL REFERENCES syson_commits(commit_id),
    change_seq INT NOT NULL,
    object_type VARCHAR(100) NOT NULL CHECK (object_type IN ('element','relationship','diagram_node','diagram_edge')),
    object_id UUID NOT NULL,
    operation VARCHAR(10) NOT NULL CHECK (operation IN ('create','update','delete')),
    before_hash VARCHAR(64),
    after_hash VARCHAR(64),
    patch JSONB,
    before_object JSONB,
    after_object JSONB,
    created_at TIMESTAMPTZ DEFAULT now(),
    created_by UUID
);
CREATE INDEX idx_syson_changes_object ON syson_changes(project_id, object_type, object_id, created_at DESC);

CREATE TABLE IF NOT EXISTS syson_baselines (
    baseline_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    baseline_code VARCHAR(50),
    name VARCHAR(500),
    commit_id UUID NOT NULL REFERENCES syson_commits(commit_id),
    status VARCHAR(50) DEFAULT 'draft' CHECK (status IN ('draft','approved')),
    approved_by UUID REFERENCES syson_users(id),
    approved_at TIMESTAMPTZ,
    description TEXT,
    created_at TIMESTAMPTZ DEFAULT now(),
    created_by UUID REFERENCES syson_users(id)
);

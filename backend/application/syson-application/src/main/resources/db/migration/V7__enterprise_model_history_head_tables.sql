-- V7: Enterprise model history head tables, commit parents, expanded changes.
-- Additive schema for element-level version control with materialized branch heads.
-- All tables use TEXT project_id to match upstream Sirius project(id).

-- Expand syson_changes with new columns for richer history
ALTER TABLE syson_changes ADD COLUMN IF NOT EXISTS project_ref TEXT;
ALTER TABLE syson_changes ADD COLUMN IF NOT EXISTS branch_id UUID;
ALTER TABLE syson_changes ADD COLUMN IF NOT EXISTS object_path TEXT;
ALTER TABLE syson_changes ADD COLUMN IF NOT EXISTS changed_fields JSONB DEFAULT '[]';
ALTER TABLE syson_changes ADD COLUMN IF NOT EXISTS conflict_key TEXT;
ALTER TABLE syson_changes ADD COLUMN IF NOT EXISTS extractor_version VARCHAR(50) DEFAULT 'v1';

-- Replace object_type CHECK constraint to allow new types
ALTER TABLE syson_changes DROP CONSTRAINT IF EXISTS syson_changes_object_type_check;
ALTER TABLE syson_changes ADD CONSTRAINT syson_changes_object_type_check
CHECK (object_type IN (
  'element', 'relationship', 'diagram', 'presentation', 'document', 'metadata', 'permission', 'branch'
));

-- Add project_ref to existing tables for TEXT-based project joins
ALTER TABLE syson_elements ADD COLUMN IF NOT EXISTS project_ref TEXT;
ALTER TABLE syson_relationships ADD COLUMN IF NOT EXISTS project_ref TEXT;
ALTER TABLE syson_branches ADD COLUMN IF NOT EXISTS project_ref TEXT;
ALTER TABLE syson_commits ADD COLUMN IF NOT EXISTS project_ref TEXT;

-- syson_branch_heads: one materialized head per project/branch
CREATE TABLE IF NOT EXISTS syson_branch_heads (
    project_id TEXT NOT NULL,
    branch_id UUID NOT NULL REFERENCES syson_branches(branch_id),
    tenant_id UUID NOT NULL,
    head_commit_id UUID,
    semantic_data_id UUID,
    canonical_hash VARCHAR(64),
    canonical_json JSONB,
    object_count INT DEFAULT 0,
    relationship_count INT DEFAULT 0,
    diagram_count INT DEFAULT 0,
    last_extracted_at TIMESTAMPTZ DEFAULT now(),
    extraction_version VARCHAR(50) DEFAULT 'v1',
    PRIMARY KEY (project_id, branch_id)
);

-- syson_head_elements: current branch state for SysML semantic objects
CREATE TABLE IF NOT EXISTS syson_head_elements (
    project_id TEXT NOT NULL,
    branch_id UUID NOT NULL,
    element_id TEXT NOT NULL,
    stable_id TEXT NOT NULL,
    document_id UUID,
    owner_id TEXT,
    qualified_name TEXT,
    sysml_type VARCHAR(150) NOT NULL,
    name TEXT,
    attributes JSONB NOT NULL DEFAULT '{}',
    raw_object JSONB NOT NULL,
    object_hash VARCHAR(64) NOT NULL,
    created_commit_id UUID,
    updated_commit_id UUID,
    deleted_commit_id UUID,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ DEFAULT now(),
    PRIMARY KEY (project_id, branch_id, stable_id)
);
CREATE INDEX IF NOT EXISTS idx_she_type ON syson_head_elements(project_id, branch_id, sysml_type) WHERE NOT is_deleted;
CREATE INDEX IF NOT EXISTS idx_she_owner ON syson_head_elements(project_id, branch_id, owner_id) WHERE NOT is_deleted;
CREATE INDEX IF NOT EXISTS idx_she_qname ON syson_head_elements(project_id, branch_id, qualified_name) WHERE NOT is_deleted;
CREATE INDEX IF NOT EXISTS idx_she_attrs_gin ON syson_head_elements USING GIN (attributes);

-- syson_head_relationships: current branch state for cross-references
CREATE TABLE IF NOT EXISTS syson_head_relationships (
    project_id TEXT NOT NULL,
    branch_id UUID NOT NULL,
    relationship_id TEXT NOT NULL,
    stable_id TEXT NOT NULL,
    rel_type VARCHAR(150) NOT NULL,
    source_id TEXT,
    target_id TEXT,
    source_role TEXT,
    target_role TEXT,
    owner_id TEXT,
    attributes JSONB NOT NULL DEFAULT '{}',
    raw_object JSONB NOT NULL,
    object_hash VARCHAR(64) NOT NULL,
    created_commit_id UUID,
    updated_commit_id UUID,
    deleted_commit_id UUID,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ DEFAULT now(),
    PRIMARY KEY (project_id, branch_id, stable_id)
);
CREATE INDEX IF NOT EXISTS idx_shr_source ON syson_head_relationships(project_id, branch_id, source_id) WHERE NOT is_deleted;
CREATE INDEX IF NOT EXISTS idx_shr_target ON syson_head_relationships(project_id, branch_id, target_id) WHERE NOT is_deleted;
CREATE INDEX IF NOT EXISTS idx_shr_type ON syson_head_relationships(project_id, branch_id, rel_type) WHERE NOT is_deleted;

-- syson_head_diagrams: diagram current branch state
CREATE TABLE IF NOT EXISTS syson_head_diagrams (
    project_id TEXT NOT NULL,
    branch_id UUID NOT NULL,
    diagram_id TEXT NOT NULL,
    representation_id TEXT,
    target_object_id TEXT,
    name TEXT,
    diagram_kind VARCHAR(150),
    raw_object JSONB NOT NULL,
    object_hash VARCHAR(64) NOT NULL,
    created_commit_id UUID,
    updated_commit_id UUID,
    deleted_commit_id UUID,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMPTZ DEFAULT now(),
    PRIMARY KEY (project_id, branch_id, diagram_id)
);

-- syson_head_presentation_elements: nodes/edges/symbols current state
CREATE TABLE IF NOT EXISTS syson_head_presentation_elements (
    project_id TEXT NOT NULL,
    branch_id UUID NOT NULL,
    presentation_id TEXT NOT NULL,
    diagram_id TEXT NOT NULL,
    semantic_element_id TEXT,
    presentation_type VARCHAR(80) NOT NULL,
    parent_presentation_id TEXT,
    bounds JSONB,
    style JSONB,
    raw_object JSONB NOT NULL,
    object_hash VARCHAR(64) NOT NULL,
    created_commit_id UUID,
    updated_commit_id UUID,
    deleted_commit_id UUID,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMPTZ DEFAULT now(),
    PRIMARY KEY (project_id, branch_id, presentation_id)
);
CREATE INDEX IF NOT EXISTS idx_shpe_diagram ON syson_head_presentation_elements(project_id, branch_id, diagram_id) WHERE NOT is_deleted;
CREATE INDEX IF NOT EXISTS idx_shpe_semantic ON syson_head_presentation_elements(project_id, branch_id, semantic_element_id) WHERE NOT is_deleted;

-- syson_commit_parents: normalized parent references for merge support
CREATE TABLE IF NOT EXISTS syson_commit_parents (
    commit_id UUID NOT NULL REFERENCES syson_commits(commit_id) ON DELETE CASCADE,
    parent_commit_id UUID NOT NULL REFERENCES syson_commits(commit_id),
    parent_order INT NOT NULL DEFAULT 1,
    PRIMARY KEY (commit_id, parent_commit_id)
);
CREATE INDEX IF NOT EXISTS idx_scp_parent ON syson_commit_parents(parent_commit_id);

-- syson_model_snapshots: periodic full-model snapshots for fast reconstruction
CREATE TABLE IF NOT EXISTS syson_model_snapshots (
    snapshot_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id TEXT NOT NULL,
    branch_id UUID NOT NULL,
    commit_id UUID NOT NULL,
    snapshot_kind VARCHAR(50) DEFAULT 'periodic',
    canonical_hash VARCHAR(64) NOT NULL,
    canonical_json JSONB NOT NULL,
    object_count INT DEFAULT 0,
    size_bytes BIGINT,
    created_at TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_sms_branch ON syson_model_snapshots(project_id, branch_id, created_at DESC);

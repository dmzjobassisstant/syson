-- V17__element_warehouse_change_history.sql
-- Append-only change history: object versions + expanded syson_changes.

-- Drop if clean install
DROP TABLE IF EXISTS syson_object_versions CASCADE;
DROP TABLE IF EXISTS syson_commit_parents CASCADE;

-- Expand syson_changes to support TEXT object IDs
ALTER TABLE syson_changes ADD COLUMN IF NOT EXISTS project_ref TEXT;
ALTER TABLE syson_changes ADD COLUMN IF NOT EXISTS branch_id UUID;
ALTER TABLE syson_changes ADD COLUMN IF NOT EXISTS stable_object_id TEXT;
ALTER TABLE syson_changes ADD COLUMN IF NOT EXISTS changed_fields JSONB DEFAULT '[]';
ALTER TABLE syson_changes ADD COLUMN IF NOT EXISTS extractor_version VARCHAR(50) DEFAULT 'v1';

-- Expand object type constraint
ALTER TABLE syson_changes DROP CONSTRAINT IF EXISTS syson_changes_object_type_check;
ALTER TABLE syson_changes ADD CONSTRAINT syson_changes_object_type_check
CHECK (object_type IN (
  'element', 'relationship', 'diagram', 'presentation', 'document', 'metadata'
));

-- Commit parent chain
CREATE TABLE syson_commit_parents (
    commit_id UUID NOT NULL REFERENCES syson_commits(commit_id) ON DELETE CASCADE,
    parent_commit_id UUID NOT NULL REFERENCES syson_commits(commit_id),
    parent_order INT NOT NULL DEFAULT 1,
    PRIMARY KEY (commit_id, parent_commit_id)
);

-- Object versions (current + historical state per element)
CREATE TABLE syson_object_versions (
    project_id TEXT NOT NULL,
    object_type TEXT NOT NULL,
    stable_object_id TEXT NOT NULL,
    commit_id UUID NOT NULL,
    valid_from_commit_number BIGINT NOT NULL,
    valid_to_commit_number BIGINT,
    is_current BOOLEAN NOT NULL,
    object_hash TEXT NOT NULL,
    object_json JSONB NOT NULL,
    created_at TIMESTAMPTZ DEFAULT now(),
    PRIMARY KEY (project_id, object_type, stable_object_id, commit_id)
);

CREATE INDEX idx_sov_current ON syson_object_versions(project_id, object_type, is_current) WHERE is_current = TRUE;
CREATE INDEX idx_sov_timeline ON syson_object_versions(project_id, object_type, stable_object_id, valid_from_commit_number DESC);

-- Indexes on expanded syson_changes
CREATE INDEX IF NOT EXISTS idx_schanges_stable ON syson_changes(project_id, stable_object_id, created_at DESC) WHERE stable_object_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_schanges_branch ON syson_changes(project_id, branch_id, created_at DESC) WHERE branch_id IS NOT NULL;

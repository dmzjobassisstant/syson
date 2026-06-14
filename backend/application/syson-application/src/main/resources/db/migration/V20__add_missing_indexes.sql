-- V20__add_missing_indexes.sql
-- Adds missing performance indexes for version control and merge request tables.
-- All indexes use IF NOT EXISTS for idempotency (safe for re-application).

-- Hot-path join index: changes by commit_id
CREATE INDEX IF NOT EXISTS idx_syson_changes_commit_id ON syson_changes(commit_id);

-- Merge request source/target branch lookups
CREATE INDEX IF NOT EXISTS idx_syson_merge_requests_source_branch ON syson_merge_requests(source_branch_id);
CREATE INDEX IF NOT EXISTS idx_syson_merge_requests_target_branch ON syson_merge_requests(target_branch_id);

-- Tag lookups by project
CREATE INDEX IF NOT EXISTS idx_syson_tags_project_id ON syson_tags(project_id);

-- Commit author lookups (for user activity queries)
CREATE INDEX IF NOT EXISTS idx_syson_commits_author_user_id ON syson_commits(author_user_id);

-- Branch lookups by parent (for branch tree traversal)
CREATE INDEX IF NOT EXISTS idx_syson_branches_parent_branch_id ON syson_branches(parent_branch_id);

-- Element lock active lookups
CREATE INDEX IF NOT EXISTS idx_syson_element_locks_project_expires ON syson_element_locks(project_id, expires_at);

-- Audit event tenant filtering
CREATE INDEX IF NOT EXISTS idx_syson_audit_events_tenant_id ON syson_audit_events(tenant_id);

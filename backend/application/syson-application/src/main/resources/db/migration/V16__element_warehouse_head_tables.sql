-- V16__element_warehouse_head_tables.sql
-- Element warehouse: materialized current-state tables per project/branch.
-- Clean install — drops existing tables if present.

-- Drop existing (safe: these are sidecar tables, not upstream Sirius tables)
DROP TABLE IF EXISTS syson_head_presentation_elements CASCADE;
DROP TABLE IF EXISTS syson_head_diagrams CASCADE;
DROP TABLE IF EXISTS syson_head_relationships CASCADE;
DROP TABLE IF EXISTS syson_head_elements CASCADE;
DROP TABLE IF EXISTS syson_branch_heads CASCADE;

-- Stable element identity using Sirius/EMF IDs
CREATE TABLE syson_head_elements (
    project_id TEXT NOT NULL,
    branch_id UUID NOT NULL,
    stable_id TEXT NOT NULL,
    document_id UUID,
    owner_stable_id TEXT,
    qualified_name TEXT,
    sysml_type VARCHAR(150) NOT NULL,
    name TEXT,
    body TEXT,
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

CREATE INDEX idx_she_type ON syson_head_elements(project_id, branch_id, sysml_type) WHERE NOT is_deleted;
CREATE INDEX idx_she_owner ON syson_head_elements(project_id, branch_id, owner_stable_id) WHERE NOT is_deleted;
CREATE INDEX idx_she_qname ON syson_head_elements(project_id, branch_id, qualified_name) WHERE NOT is_deleted;
CREATE INDEX idx_she_attrs_gin ON syson_head_elements USING GIN (attributes);

-- Relationships between elements
CREATE TABLE syson_head_relationships (
    project_id TEXT NOT NULL,
    branch_id UUID NOT NULL,
    stable_id TEXT NOT NULL,
    rel_type VARCHAR(150) NOT NULL,
    source_stable_id TEXT,
    target_stable_id TEXT,
    source_role TEXT,
    target_role TEXT,
    owner_stable_id TEXT,
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

CREATE INDEX idx_shr_source ON syson_head_relationships(project_id, branch_id, source_stable_id) WHERE NOT is_deleted;
CREATE INDEX idx_shr_target ON syson_head_relationships(project_id, branch_id, target_stable_id) WHERE NOT is_deleted;
CREATE INDEX idx_shr_type ON syson_head_relationships(project_id, branch_id, rel_type) WHERE NOT is_deleted;

-- Diagrams
CREATE TABLE syson_head_diagrams (
    project_id TEXT NOT NULL,
    branch_id UUID NOT NULL,
    stable_id TEXT NOT NULL,
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
    PRIMARY KEY (project_id, branch_id, stable_id)
);

-- Diagram presentation elements (nodes, edges, labels)
CREATE TABLE syson_head_presentation_elements (
    project_id TEXT NOT NULL,
    branch_id UUID NOT NULL,
    stable_id TEXT NOT NULL,
    diagram_stable_id TEXT NOT NULL,
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
    PRIMARY KEY (project_id, branch_id, stable_id)
);

CREATE INDEX idx_shpe_diagram ON syson_head_presentation_elements(project_id, branch_id, diagram_stable_id) WHERE NOT is_deleted;
CREATE INDEX idx_shpe_semantic ON syson_head_presentation_elements(project_id, branch_id, semantic_element_id) WHERE NOT is_deleted;

-- Branch head cache
CREATE TABLE syson_branch_heads (
    project_id TEXT NOT NULL,
    branch_id UUID NOT NULL,
    head_commit_id UUID,
    canonical_hash VARCHAR(64),
    canonical_json JSONB,
    object_count INT DEFAULT 0,
    relationship_count INT DEFAULT 0,
    diagram_count INT DEFAULT 0,
    last_extracted_at TIMESTAMPTZ DEFAULT now(),
    extraction_version VARCHAR(50) DEFAULT 'v1',
    PRIMARY KEY (project_id, branch_id)
);

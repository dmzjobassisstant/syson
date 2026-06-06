CREATE TABLE IF NOT EXISTS syson_elements (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL,
    branch_id UUID NOT NULL,
    sysml_type VARCHAR(100) NOT NULL,
    name VARCHAR(500) NOT NULL,
    owner_id UUID,
    body TEXT,
    is_abstract BOOLEAN DEFAULT FALSE,
    is_variation BOOLEAN DEFAULT FALSE,
    attributes JSONB DEFAULT '[]',
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ DEFAULT now(),
    is_deleted BOOLEAN DEFAULT FALSE
);
CREATE INDEX idx_syson_elements_project ON syson_elements(project_id, branch_id);
CREATE INDEX idx_syson_elements_type ON syson_elements(project_id, branch_id, sysml_type);
CREATE INDEX idx_syson_elements_owner ON syson_elements(owner_id);
CREATE INDEX idx_syson_elements_deleted ON syson_elements(project_id, branch_id, is_deleted);

CREATE TABLE IF NOT EXISTS syson_relationships (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL,
    branch_id UUID NOT NULL,
    rel_type VARCHAR(100) NOT NULL,
    name VARCHAR(500),
    source_id UUID NOT NULL REFERENCES syson_elements(id),
    target_id UUID NOT NULL REFERENCES syson_elements(id),
    source_role VARCHAR(255),
    target_role VARCHAR(255),
    metadata JSONB DEFAULT '{}',
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ DEFAULT now(),
    is_deleted BOOLEAN DEFAULT FALSE
);
CREATE INDEX idx_syson_rels_project ON syson_relationships(project_id, branch_id);
CREATE INDEX idx_syson_rels_source ON syson_relationships(source_id);
CREATE INDEX idx_syson_rels_target ON syson_relationships(target_id);

CREATE TABLE IF NOT EXISTS syson_diagrams (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL,
    branch_id UUID NOT NULL,
    view_id UUID,
    name VARCHAR(500),
    diagram_kind VARCHAR(100),
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ DEFAULT now(),
    is_deleted BOOLEAN DEFAULT FALSE
);

CREATE TABLE IF NOT EXISTS syson_diagram_nodes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    diagram_id UUID NOT NULL REFERENCES syson_diagrams(id) ON DELETE CASCADE,
    element_id UUID REFERENCES syson_elements(id),
    sysml_node_type VARCHAR(100),
    x DOUBLE PRECISION DEFAULT 0,
    y DOUBLE PRECISION DEFAULT 0,
    w DOUBLE PRECISION DEFAULT 100,
    h DOUBLE PRECISION DEFAULT 60,
    style JSONB DEFAULT '{}',
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ DEFAULT now(),
    is_deleted BOOLEAN DEFAULT FALSE
);
CREATE INDEX idx_syson_dn_diagram ON syson_diagram_nodes(diagram_id);

CREATE TABLE IF NOT EXISTS syson_diagram_edges (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    diagram_id UUID NOT NULL REFERENCES syson_diagrams(id) ON DELETE CASCADE,
    relationship_id UUID REFERENCES syson_relationships(id),
    source_node_id UUID REFERENCES syson_diagram_nodes(id),
    target_node_id UUID REFERENCES syson_diagram_nodes(id),
    edge_type VARCHAR(100),
    routing_points JSONB DEFAULT '[]',
    style JSONB DEFAULT '{}',
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ DEFAULT now(),
    is_deleted BOOLEAN DEFAULT FALSE
);
CREATE INDEX idx_syson_de_diagram ON syson_diagram_edges(diagram_id);

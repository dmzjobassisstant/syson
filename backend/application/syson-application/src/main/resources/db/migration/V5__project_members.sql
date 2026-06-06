CREATE TABLE IF NOT EXISTS syson_project_members (
    project_id TEXT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES syson_users(id) ON DELETE CASCADE,
    role VARCHAR(50) NOT NULL CHECK (role IN ('admin','user','viewer')),
    created_at TIMESTAMPTZ DEFAULT now(),
    PRIMARY KEY (project_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_spm_user ON syson_project_members(user_id);
CREATE INDEX IF NOT EXISTS idx_spm_project ON syson_project_members(project_id);

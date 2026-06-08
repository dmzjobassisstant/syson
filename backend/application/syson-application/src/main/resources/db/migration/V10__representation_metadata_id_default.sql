-- Upstream Sirius Web 2025.6.1 inserts representation_metadata and
-- representation_content rows without explicitly setting
-- representation_metadata_id.  The live database has the column as NOT NULL
-- with no default, which causes INSERT failures when creating/opening projects.
-- Fix: add a DEFAULT so PostgreSQL supplies the value automatically.
ALTER TABLE representation_metadata ALTER COLUMN representation_metadata_id SET DEFAULT gen_random_uuid();
ALTER TABLE representation_content ALTER COLUMN representation_metadata_id SET DEFAULT gen_random_uuid();

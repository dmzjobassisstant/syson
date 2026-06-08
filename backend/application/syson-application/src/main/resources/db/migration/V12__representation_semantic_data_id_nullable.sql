-- Upstream Sirius Web 2025.6.1 inserts representation_content rows with only:
--   content, created_on, id, last_migration_performed, last_modified_on, migration_version
-- It does NOT set semantic_data_id or representation_metadata_id.
-- Our table has both as NOT NULL, which causes INSERT failures.
--
-- V10 already added DEFAULT gen_random_uuid() for representation_metadata_id.
-- This migration makes semantic_data_id nullable on both tables.
ALTER TABLE representation_metadata ALTER COLUMN semantic_data_id DROP NOT NULL;
ALTER TABLE representation_content ALTER COLUMN semantic_data_id DROP NOT NULL;

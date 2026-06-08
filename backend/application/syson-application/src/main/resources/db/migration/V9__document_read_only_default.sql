-- Sirius Web 2025.6.1 inserts document rows without explicitly setting
-- is_read_only. This deployment's live database has the column as NOT NULL,
-- so project onboarding actions (for example creating the initial SysMLv2
-- model document) fail unless the database supplies the default.
ALTER TABLE document ALTER COLUMN is_read_only SET DEFAULT false;
UPDATE document SET is_read_only = false WHERE is_read_only IS NULL;

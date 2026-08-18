CREATE TABLE IF NOT EXISTS task_plan_revision (
  id VARCHAR(64) NOT NULL,
  task_id VARCHAR(64) NOT NULL,
  workspace_id VARCHAR(64) NOT NULL,
  revision_no INT NOT NULL,
  status VARCHAR(32) NOT NULL,
  plan_json JSON NOT NULL,
  plan_hash CHAR(64) NOT NULL,
  revision_instruction VARCHAR(2000) NULL,
  created_by VARCHAR(64) NOT NULL,
  approved_by VARCHAR(64) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  approved_at DATETIME NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_task_plan_revision (task_id, revision_no),
  KEY idx_plan_workspace_created (workspace_id, created_at),
  KEY idx_plan_task_status (task_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- MySQL 5.7 does not support ADD COLUMN IF NOT EXISTS.  Keep this migration
-- idempotent for databases where the Day 1 column was created manually.
SET @column_exists := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'research_task'
    AND column_name = 'current_plan_revision_id'
);
SET @add_plan_revision_column := IF(
  @column_exists = 0,
  'ALTER TABLE research_task ADD COLUMN current_plan_revision_id VARCHAR(64) NULL',
  'SELECT 1'
);
PREPARE add_plan_revision_column FROM @add_plan_revision_column;
EXECUTE add_plan_revision_column;
DEALLOCATE PREPARE add_plan_revision_column;

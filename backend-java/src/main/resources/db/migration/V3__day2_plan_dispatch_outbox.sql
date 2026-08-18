CREATE TABLE IF NOT EXISTS task_dispatch_outbox (
  id VARCHAR(64) NOT NULL,
  task_id VARCHAR(64) NOT NULL,
  workspace_id VARCHAR(64) NOT NULL,
  run_id VARCHAR(64) NOT NULL,
  phase VARCHAR(16) NOT NULL,
  payload_json JSON NOT NULL,
  status VARCHAR(16) NOT NULL,
  attempt_count INT NOT NULL DEFAULT 0,
  next_attempt_at DATETIME NULL,
  last_error VARCHAR(1024) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_task_dispatch_run_phase (task_id, run_id, phase),
  KEY idx_dispatch_ready (status, next_attempt_at),
  KEY idx_dispatch_task (task_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

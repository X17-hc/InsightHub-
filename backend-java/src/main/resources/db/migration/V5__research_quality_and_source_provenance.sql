ALTER TABLE research_task
  ADD COLUMN quality_status VARCHAR(32) NOT NULL DEFAULT 'NOT_EVALUATED' AFTER enable_data_analysis,
  ADD COLUMN quality_summary VARCHAR(1024) NULL AFTER quality_status,
  ADD COLUMN verified_citation_count INT NOT NULL DEFAULT 0 AFTER quality_summary,
  ADD COLUMN total_citation_count INT NOT NULL DEFAULT 0 AFTER verified_citation_count;

ALTER TABLE report
  ADD COLUMN quality_status VARCHAR(32) NOT NULL DEFAULT 'NOT_EVALUATED' AFTER status,
  ADD COLUMN quality_summary VARCHAR(1024) NULL AFTER quality_status,
  ADD COLUMN verified_citation_count INT NOT NULL DEFAULT 0 AFTER quality_summary,
  ADD COLUMN candidate_citation_count INT NOT NULL DEFAULT 0 AFTER verified_citation_count;

ALTER TABLE citation
  ADD COLUMN verification_status VARCHAR(32) NOT NULL DEFAULT 'CANDIDATE' AFTER verified,
  ADD COLUMN verification_reason VARCHAR(512) NULL AFTER verification_status,
  ADD COLUMN canonical_uri VARCHAR(1024) NULL AFTER verification_reason,
  ADD COLUMN final_uri VARCHAR(1024) NULL AFTER canonical_uri,
  ADD COLUMN retrieved_at DATETIME NULL AFTER final_uri,
  ADD COLUMN content_hash CHAR(64) NULL AFTER retrieved_at,
  ADD COLUMN http_status INT NULL AFTER content_hash;

ALTER TABLE task_plan_revision
  ADD COLUMN approval_remark VARCHAR(500) NULL AFTER approved_by;

UPDATE citation
SET verification_status = CASE
  WHEN UPPER(COALESCE(source_type, '')) = 'SYNTHETIC' THEN 'SYNTHETIC'
  WHEN verified = 1 THEN 'VERIFIED'
  ELSE 'CANDIDATE'
END;

UPDATE report r
SET quality_status = CASE
  WHEN EXISTS (
    SELECT 1 FROM citation c
    WHERE c.report_id = r.id AND c.verification_status = 'SYNTHETIC'
  ) AND NOT EXISTS (
    SELECT 1 FROM citation c
    WHERE c.report_id = r.id AND c.verification_status = 'VERIFIED'
  ) THEN 'LEGACY_SYNTHETIC'
  ELSE 'NOT_EVALUATED'
END,
verified_citation_count = (
  SELECT COUNT(*) FROM citation c
  WHERE c.report_id = r.id AND c.verification_status = 'VERIFIED'
),
candidate_citation_count = (
  SELECT COUNT(*) FROM citation c
  WHERE c.report_id = r.id AND c.verification_status <> 'VERIFIED'
);

UPDATE research_task t
LEFT JOIN report r ON r.task_id = t.id
  AND r.version = (SELECT MAX(r2.version) FROM report r2 WHERE r2.task_id = t.id)
SET t.quality_status = COALESCE(r.quality_status, 'NOT_EVALUATED'),
    t.quality_summary = r.quality_summary,
    t.verified_citation_count = COALESCE(r.verified_citation_count, 0),
    t.total_citation_count = COALESCE(r.verified_citation_count + r.candidate_citation_count, 0);

CREATE INDEX idx_task_workspace_quality ON research_task(workspace_id, quality_status);
CREATE INDEX idx_citation_report_verification ON citation(report_id, verification_status);

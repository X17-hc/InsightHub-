-- Day4：任务级数据分析开关（与 Agent config.enableDataAnalysis 对齐）
-- MySQL 不支持 ADD COLUMN IF NOT EXISTS（MariaDB 才支持），沿用 V2 的 information_schema 幂等写法。
SET @column_exists := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'research_task'
    AND column_name = 'enable_data_analysis'
);
SET @add_analysis_flag := IF(
  @column_exists = 0,
  'ALTER TABLE research_task ADD COLUMN enable_data_analysis TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''是否启用数据分析沙箱''',
  'SELECT 1'
);
PREPARE add_analysis_flag FROM @add_analysis_flag;
EXECUTE add_analysis_flag;
DEALLOCATE PREPARE add_analysis_flag;

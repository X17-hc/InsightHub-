-- InsightHub PostgreSQL / PGVector 初始化（Docker 首次启动时自动执行）
-- 原生安装场景请使用 scripts/init-postgres.ps1

CREATE EXTENSION IF NOT EXISTS vector;

-- 冒烟测试：确认向量类型可用
DO $$
BEGIN
  PERFORM '[1,2,3]'::vector <-> '[4,5,6]'::vector;
END $$;

-- InsightHub MySQL 初始化（Docker 首次启动时自动执行）
-- 原生安装场景请使用 scripts/init-mysql.ps1

CREATE DATABASE IF NOT EXISTS insighthub
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

-- Docker 官方镜像已通过环境变量创建 MYSQL_USER，这里仅保证授权完整
GRANT ALL PRIVILEGES ON insighthub.* TO 'insighthub'@'%';
FLUSH PRIVILEGES;

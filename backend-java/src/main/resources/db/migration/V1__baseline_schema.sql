-- =============================================================================
-- InsightHub MySQL 业务库表结构
-- 数据库：insighthub
-- 说明：平台业务数据（用户、工作空间、任务、报告、知识库元数据等）
-- 向量与文档片段内容存放在 PostgreSQL/PGVector，本库仅保存元数据与业务关联
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 1. 系统用户
-- -----------------------------------------------------------------------------
CREATE TABLE `sys_user` (
  `id`             VARCHAR(64)  NOT NULL COMMENT '用户ID（业务主键，如 ulid/uuid）',
  `username`       VARCHAR(64)  NOT NULL COMMENT '登录用户名，全局唯一',
  `password_hash`  VARCHAR(255) NOT NULL COMMENT '密码哈希（BCrypt 等，禁止存明文）',
  `email`          VARCHAR(128)          DEFAULT NULL COMMENT '邮箱，可用于找回与通知',
  `display_name`   VARCHAR(64)           DEFAULT NULL COMMENT '展示名称',
  `avatar_url`     VARCHAR(512)          DEFAULT NULL COMMENT '头像地址',
  `status`         TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1=启用 0=禁用 2=待激活',
  `last_login_at`  DATETIME              DEFAULT NULL COMMENT '最近登录时间',
  `created_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sys_user_username` (`username`),
  UNIQUE KEY `uk_sys_user_email` (`email`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统用户表：登录账号与基础资料';

-- -----------------------------------------------------------------------------
-- 2. 刷新令牌（JWT Refresh Token）
-- -----------------------------------------------------------------------------
CREATE TABLE `sys_refresh_token` (
  `id`          VARCHAR(64)  NOT NULL COMMENT '令牌记录ID',
  `user_id`     VARCHAR(64)  NOT NULL COMMENT '所属用户ID',
  `token_hash`  VARCHAR(128) NOT NULL COMMENT 'Refresh Token 哈希值',
  `expires_at`  DATETIME     NOT NULL COMMENT '过期时间',
  `revoked`     TINYINT      NOT NULL DEFAULT 0 COMMENT '是否已吊销：0=否 1=是',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_refresh_token_hash` (`token_hash`),
  KEY `idx_refresh_token_user` (`user_id`),
  CONSTRAINT `fk_refresh_token_user` FOREIGN KEY (`user_id`) REFERENCES `sys_user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='JWT 刷新令牌表：支撑 Access/Refresh 双令牌鉴权';

-- -----------------------------------------------------------------------------
-- 3. 工作空间
-- -----------------------------------------------------------------------------
CREATE TABLE `workspace` (
  `id`                    VARCHAR(64)  NOT NULL COMMENT '工作空间ID',
  `name`                  VARCHAR(128) NOT NULL COMMENT '工作空间名称',
  `description`           VARCHAR(512)          DEFAULT NULL COMMENT '工作空间描述',
  `owner_id`              VARCHAR(64)  NOT NULL COMMENT '所有者用户ID',
  `max_concurrent_tasks`  INT          NOT NULL DEFAULT 3 COMMENT '最大并发研究任务数',
  `monthly_token_quota`   BIGINT       NOT NULL DEFAULT 1000000 COMMENT '每月 Token 配额',
  `status`                TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1=正常 0=归档/禁用',
  `created_at`            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_workspace_owner` (`owner_id`),
  CONSTRAINT `fk_workspace_owner` FOREIGN KEY (`owner_id`) REFERENCES `sys_user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工作空间表：多租户隔离与配额的基本单元';

-- -----------------------------------------------------------------------------
-- 4. 工作空间成员
-- -----------------------------------------------------------------------------
CREATE TABLE `workspace_member` (
  `id`           VARCHAR(64) NOT NULL COMMENT '成员关系ID',
  `workspace_id` VARCHAR(64) NOT NULL COMMENT '工作空间ID',
  `user_id`      VARCHAR(64) NOT NULL COMMENT '用户ID',
  `role`         VARCHAR(32) NOT NULL COMMENT '角色：OWNER/ADMIN/MEMBER',
  `joined_at`    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '加入时间',
  `updated_at`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_workspace_member` (`workspace_id`, `user_id`),
  KEY `idx_workspace_member_user` (`user_id`),
  CONSTRAINT `fk_wm_workspace` FOREIGN KEY (`workspace_id`) REFERENCES `workspace` (`id`),
  CONSTRAINT `fk_wm_user` FOREIGN KEY (`user_id`) REFERENCES `sys_user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工作空间成员表：成员与 RBAC 角色绑定';

-- -----------------------------------------------------------------------------
-- 5. 模型供应商
-- -----------------------------------------------------------------------------
CREATE TABLE `model_provider` (
  `id`              VARCHAR(64)  NOT NULL COMMENT '供应商ID',
  `name`            VARCHAR(64)  NOT NULL COMMENT '供应商名称，如 OpenAI/DashScope',
  `provider_type`   VARCHAR(32)  NOT NULL COMMENT '类型编码：OPENAI/DASHSCOPE/AZURE/CUSTOM',
  `base_url`        VARCHAR(512)          DEFAULT NULL COMMENT 'API Base URL',
  `api_key_ref`     VARCHAR(256)          DEFAULT NULL COMMENT '密钥引用（环境变量名或密钥服务ID，不存明文）',
  `enabled`         TINYINT      NOT NULL DEFAULT 1 COMMENT '是否启用：1=是 0=否',
  `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_model_provider_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='模型供应商表：平台级大模型接入配置';

-- -----------------------------------------------------------------------------
-- 6. 模型配置
-- -----------------------------------------------------------------------------
CREATE TABLE `model_config` (
  `id`              VARCHAR(64)  NOT NULL COMMENT '模型配置ID',
  `provider_id`     VARCHAR(64)  NOT NULL COMMENT '所属供应商ID',
  `workspace_id`    VARCHAR(64)           DEFAULT NULL COMMENT '工作空间ID；空表示平台公共模型',
  `name`            VARCHAR(128) NOT NULL COMMENT '配置名称，如 gpt-4o-mini-router',
  `model_name`      VARCHAR(128) NOT NULL COMMENT '实际模型名',
  `model_role`      VARCHAR(32)  NOT NULL DEFAULT 'CHAT' COMMENT '用途：CHAT/EMBEDDING/RERANK',
  `temperature`     DECIMAL(4,2)          DEFAULT NULL COMMENT '采样温度',
  `max_tokens`      INT                   DEFAULT NULL COMMENT '最大输出 Token',
  `extra_json`      JSON                  DEFAULT NULL COMMENT '其他模型参数 JSON',
  `enabled`         TINYINT      NOT NULL DEFAULT 1 COMMENT '是否启用',
  `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_model_config_provider` (`provider_id`),
  KEY `idx_model_config_workspace` (`workspace_id`),
  CONSTRAINT `fk_model_config_provider` FOREIGN KEY (`provider_id`) REFERENCES `model_provider` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='模型配置表：具体模型参数，供 Agent 引用';

-- -----------------------------------------------------------------------------
-- 7. 工具定义
-- -----------------------------------------------------------------------------
CREATE TABLE `tool_definition` (
  `id`               VARCHAR(64)  NOT NULL COMMENT '工具ID',
  `workspace_id`     VARCHAR(64)           DEFAULT NULL COMMENT '工作空间ID；空表示平台公共工具',
  `name`             VARCHAR(128) NOT NULL COMMENT '工具名称',
  `tool_type`        VARCHAR(32)  NOT NULL COMMENT '类型：WEB_SEARCH/CRAWL/RAG/SANDBOX/MCP/CUSTOM',
  `description`      VARCHAR(512)          DEFAULT NULL COMMENT '工具说明，供 Agent 选择时参考',
  `config_json`      JSON                  DEFAULT NULL COMMENT '工具配置（端点、超时、限制等）',
  `requires_approval` TINYINT     NOT NULL DEFAULT 0 COMMENT '敏感工具是否需人工审批：1=是',
  `enabled`          TINYINT      NOT NULL DEFAULT 1 COMMENT '是否启用',
  `created_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_tool_workspace` (`workspace_id`),
  KEY `idx_tool_type` (`tool_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工具定义表：搜索、抓取、沙箱等可被 Agent 调用的工具';

-- -----------------------------------------------------------------------------
-- 8. MCP Server
-- -----------------------------------------------------------------------------
CREATE TABLE `mcp_server` (
  `id`            VARCHAR(64)  NOT NULL COMMENT 'MCP Server ID',
  `workspace_id`  VARCHAR(64)           DEFAULT NULL COMMENT '工作空间ID；空表示平台公共',
  `name`          VARCHAR(128) NOT NULL COMMENT 'MCP 服务名称',
  `transport`     VARCHAR(32)  NOT NULL DEFAULT 'SSE' COMMENT '传输方式：SSE/STDIO/HTTP',
  `endpoint`      VARCHAR(512)          DEFAULT NULL COMMENT '服务端点或启动命令',
  `auth_ref`      VARCHAR(256)          DEFAULT NULL COMMENT '鉴权信息引用（非明文）',
  `status`        VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE/DISABLED/ERROR',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_mcp_workspace` (`workspace_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='MCP Server 注册表：平台/工作空间级外部工具服务';

-- -----------------------------------------------------------------------------
-- 9. Agent 定义
-- -----------------------------------------------------------------------------
CREATE TABLE `agent_definition` (
  `id`                VARCHAR(64)  NOT NULL COMMENT 'Agent ID',
  `workspace_id`      VARCHAR(64)           DEFAULT NULL COMMENT '工作空间ID；空表示平台模板',
  `name`              VARCHAR(128) NOT NULL COMMENT 'Agent 名称',
  `agent_type`        VARCHAR(64)  NOT NULL COMMENT '类型：PLANNER/SUPERVISOR/WEB_RESEARCHER/KNOWLEDGE/DATA_ANALYST/CRITIC/WRITER',
  `runtime`           VARCHAR(16)  NOT NULL DEFAULT 'PYTHON' COMMENT '运行时：JAVA 或 PYTHON（MVP 以 PYTHON 为主）',
  `model_config_id`   VARCHAR(64)           DEFAULT NULL COMMENT '绑定的模型配置ID',
  `prompt_version`    VARCHAR(64)           DEFAULT NULL COMMENT 'Prompt 版本号',
  `system_prompt`     MEDIUMTEXT            DEFAULT NULL COMMENT '系统提示词内容或模板',
  `tool_permissions`  JSON                  DEFAULT NULL COMMENT '允许使用的工具ID/权限列表 JSON',
  `enabled`           TINYINT      NOT NULL DEFAULT 1 COMMENT '是否启用/可调度',
  `version`           INT          NOT NULL DEFAULT 1 COMMENT '配置版本号，变更时递增',
  `created_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_agent_workspace_type` (`workspace_id`, `agent_type`),
  KEY `idx_agent_model_config` (`model_config_id`),
  CONSTRAINT `fk_agent_model_config` FOREIGN KEY (`model_config_id`) REFERENCES `model_config` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent 定义表：各专业智能体的配置、模型与工具权限';

-- -----------------------------------------------------------------------------
-- 10. 工作流定义
-- -----------------------------------------------------------------------------
CREATE TABLE `workflow_definition` (
  `id`               VARCHAR(64)  NOT NULL COMMENT '工作流ID',
  `workspace_id`     VARCHAR(64)           DEFAULT NULL COMMENT '工作空间ID；空表示平台模板',
  `name`             VARCHAR(128) NOT NULL COMMENT '工作流名称，如技术调研默认流',
  `version`          INT          NOT NULL DEFAULT 1 COMMENT '版本号',
  `definition_json`  JSON         NOT NULL COMMENT '节点、边、中断点等图定义 JSON',
  `status`           VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：DRAFT/ACTIVE/DEPRECATED',
  `description`      VARCHAR(512)          DEFAULT NULL COMMENT '工作流说明',
  `created_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_workflow_name_version` (`workspace_id`, `name`, `version`),
  KEY `idx_workflow_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工作流定义表：固定技术调研等多 Agent 流程模板';

-- -----------------------------------------------------------------------------
-- 11. 研究任务
-- -----------------------------------------------------------------------------
CREATE TABLE `research_task` (
  `id`                 VARCHAR(64)   NOT NULL COMMENT '任务ID（全局唯一 taskId）',
  `workspace_id`       VARCHAR(64)   NOT NULL COMMENT '所属工作空间ID',
  `creator_id`         VARCHAR(64)   NOT NULL COMMENT '创建人用户ID',
  `workflow_id`        VARCHAR(64)            DEFAULT NULL COMMENT '使用的工作流定义ID',
  `title`              VARCHAR(256)           DEFAULT NULL COMMENT '任务标题（可由 Planner 生成）',
  `query`              TEXT          NOT NULL COMMENT '用户原始研究主题/问题',
  `clarified_query`    TEXT                   DEFAULT NULL COMMENT '澄清后的查询',
  `plan_json`          JSON                   DEFAULT NULL COMMENT '结构化研究计划 JSON',
  `plan_approved`      TINYINT                DEFAULT NULL COMMENT '计划是否已确认：1=是 0=否 NULL=未到审批',
  `status`             VARCHAR(32)   NOT NULL DEFAULT 'CREATED' COMMENT '业务状态：CREATED/PLANNING/WAITING_APPROVAL/RUNNING/PAUSING/PAUSED/REVIEWING/GENERATING/COMPLETED/FAILED/CANCELLED',
  `current_node`       VARCHAR(64)            DEFAULT NULL COMMENT '当前执行节点名',
  `progress`           INT           NOT NULL DEFAULT 0 COMMENT '进度百分比 0-100',
  `config_json`        JSON                   DEFAULT NULL COMMENT '任务级配置：maxSteps/并行度/是否联网等',
  `knowledge_base_ids` JSON                   DEFAULT NULL COMMENT '关联知识库 ID 列表',
  `trace_id`           VARCHAR(64)            DEFAULT NULL COMMENT '全链路 Trace ID',
  `current_run_id`     VARCHAR(64)            DEFAULT NULL COMMENT '当前执行轮次 runId',
  `error_code`         VARCHAR(64)            DEFAULT NULL COMMENT '失败错误码',
  `error_message`      VARCHAR(1024)          DEFAULT NULL COMMENT '失败错误信息',
  `started_at`         DATETIME               DEFAULT NULL COMMENT '开始执行时间',
  `completed_at`       DATETIME               DEFAULT NULL COMMENT '完成/终态时间',
  `created_at`         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_task_workspace_status` (`workspace_id`, `status`),
  KEY `idx_task_creator` (`creator_id`),
  KEY `idx_task_trace` (`trace_id`),
  KEY `idx_task_created` (`created_at`),
  CONSTRAINT `fk_task_workspace` FOREIGN KEY (`workspace_id`) REFERENCES `workspace` (`id`),
  CONSTRAINT `fk_task_creator` FOREIGN KEY (`creator_id`) REFERENCES `sys_user` (`id`),
  CONSTRAINT `fk_task_workflow` FOREIGN KEY (`workflow_id`) REFERENCES `workflow_definition` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='研究任务表：Java 侧业务任务状态机主表';

-- -----------------------------------------------------------------------------
-- 12. 任务事件
-- -----------------------------------------------------------------------------
CREATE TABLE `task_event` (
  `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `task_id`      VARCHAR(64)  NOT NULL COMMENT '任务ID',
  `event_no`     BIGINT       NOT NULL COMMENT '任务内事件序号（单调递增，用于 SSE 断线续传）',
  `run_id`       VARCHAR(64)           DEFAULT NULL COMMENT '执行轮次ID',
  `node_run_id`  VARCHAR(64)           DEFAULT NULL COMMENT '节点执行ID',
  `node_name`    VARCHAR(64)           DEFAULT NULL COMMENT '节点名称，如 web_research',
  `event_type`   VARCHAR(64)  NOT NULL COMMENT '事件类型：TASK_STARTED/PLAN_CREATED/NODE_COMPLETED/REPORT_TOKEN 等',
  `payload_json` JSON                  DEFAULT NULL COMMENT '事件载荷 JSON',
  `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '事件产生时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_task_event_no` (`task_id`, `event_no`),
  KEY `idx_task_event_created` (`task_id`, `created_at`),
  KEY `idx_task_event_type` (`task_id`, `event_type`),
  CONSTRAINT `fk_task_event_task` FOREIGN KEY (`task_id`) REFERENCES `research_task` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='任务事件表：Python 节点事件落库，供 SSE 推送与审计回放';

-- -----------------------------------------------------------------------------
-- 13. 任务 Checkpoint 索引
-- -----------------------------------------------------------------------------
CREATE TABLE `task_checkpoint` (
  `id`               VARCHAR(64)  NOT NULL COMMENT '业务侧 Checkpoint 记录ID',
  `task_id`          VARCHAR(64)  NOT NULL COMMENT '任务ID',
  `run_id`           VARCHAR(64)  NOT NULL COMMENT '执行轮次ID',
  `checkpoint_id`    VARCHAR(128) NOT NULL COMMENT 'LangGraph Checkpoint ID',
  `graph_state_ref`  VARCHAR(512)          DEFAULT NULL COMMENT '图状态存储引用（Python Checkpoint 后端键/路径）',
  `node_name`        VARCHAR(64)           DEFAULT NULL COMMENT '产生该 Checkpoint 的节点',
  `created_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_task_checkpoint` (`task_id`, `checkpoint_id`),
  KEY `idx_task_checkpoint_run` (`task_id`, `run_id`),
  CONSTRAINT `fk_task_checkpoint_task` FOREIGN KEY (`task_id`) REFERENCES `research_task` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='任务 Checkpoint 索引表：Java 记录可恢复点，实际图状态由 Python 管理';

-- -----------------------------------------------------------------------------
-- 14. 知识库（元数据）
-- -----------------------------------------------------------------------------
CREATE TABLE `knowledge_base` (
  `id`               VARCHAR(64)  NOT NULL COMMENT '知识库ID',
  `workspace_id`     VARCHAR(64)  NOT NULL COMMENT '所属工作空间ID',
  `name`             VARCHAR(128) NOT NULL COMMENT '知识库名称',
  `description`      VARCHAR(512)          DEFAULT NULL COMMENT '知识库描述',
  `embedding_model`  VARCHAR(128) NOT NULL COMMENT 'Embedding 模型标识',
  `chunk_strategy`   VARCHAR(64)  NOT NULL DEFAULT 'PARENT_CHILD' COMMENT '分块策略：PARENT_CHILD/SEMANTIC/FIXED',
  `status`           VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE/INDEXING/DISABLED',
  `doc_count`        INT          NOT NULL DEFAULT 0 COMMENT '文档数量缓存',
  `created_by`       VARCHAR(64)           DEFAULT NULL COMMENT '创建人用户ID',
  `created_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_kb_workspace` (`workspace_id`, `status`),
  CONSTRAINT `fk_kb_workspace` FOREIGN KEY (`workspace_id`) REFERENCES `workspace` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库元数据表：工作空间内文档集合配置（向量在 PGVector）';

-- -----------------------------------------------------------------------------
-- 15. 文档（元数据）
-- -----------------------------------------------------------------------------
CREATE TABLE `document` (
  `id`                 VARCHAR(64)  NOT NULL COMMENT '文档ID',
  `knowledge_base_id`  VARCHAR(64)  NOT NULL COMMENT '所属知识库ID',
  `workspace_id`       VARCHAR(64)  NOT NULL COMMENT '冗余工作空间ID，便于权限过滤',
  `file_name`          VARCHAR(256) NOT NULL COMMENT '原始文件名',
  `content_type`       VARCHAR(128)          DEFAULT NULL COMMENT 'MIME 类型',
  `file_size`          BIGINT                DEFAULT NULL COMMENT '文件大小（字节）',
  `content_hash`       VARCHAR(128)          DEFAULT NULL COMMENT '内容哈希，用于去重',
  `source_uri`         VARCHAR(1024)         DEFAULT NULL COMMENT '存储路径或来源 URI',
  `parse_status`       VARCHAR(32)  NOT NULL DEFAULT 'PENDING' COMMENT '解析状态：PENDING/PARSING/INDEXED/FAILED',
  `chunk_count`        INT          NOT NULL DEFAULT 0 COMMENT '分块数量',
  `error_message`      VARCHAR(1024)         DEFAULT NULL COMMENT '解析/索引失败原因',
  `uploaded_by`        VARCHAR(64)           DEFAULT NULL COMMENT '上传人用户ID',
  `created_at`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_document_kb` (`knowledge_base_id`),
  KEY `idx_document_workspace` (`workspace_id`),
  KEY `idx_document_hash` (`knowledge_base_id`, `content_hash`),
  KEY `idx_document_parse_status` (`parse_status`),
  CONSTRAINT `fk_document_kb` FOREIGN KEY (`knowledge_base_id`) REFERENCES `knowledge_base` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文档元数据表：上传文件与解析状态；正文片段在 PGVector';

-- -----------------------------------------------------------------------------
-- 16. 研究报告
-- -----------------------------------------------------------------------------
CREATE TABLE `report` (
  `id`                VARCHAR(64)  NOT NULL COMMENT '报告ID',
  `task_id`           VARCHAR(64)  NOT NULL COMMENT '所属任务ID',
  `workspace_id`      VARCHAR(64)  NOT NULL COMMENT '工作空间ID',
  `version`           INT          NOT NULL DEFAULT 1 COMMENT '报告版本号，同一任务可多版本',
  `title`             VARCHAR(256)          DEFAULT NULL COMMENT '报告标题',
  `markdown_content`  LONGTEXT              DEFAULT NULL COMMENT 'Markdown 正文',
  `html_uri`          VARCHAR(1024)         DEFAULT NULL COMMENT '导出 HTML 存储 URI',
  `pdf_uri`           VARCHAR(1024)         DEFAULT NULL COMMENT '导出 PDF 存储 URI',
  `summary`           TEXT                  DEFAULT NULL COMMENT '摘要',
  `status`            VARCHAR(32)  NOT NULL DEFAULT 'DRAFT' COMMENT '状态：DRAFT/READY/ARCHIVED',
  `created_by`        VARCHAR(64)           DEFAULT NULL COMMENT '生成触发人或系统',
  `created_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_report_task_version` (`task_id`, `version`),
  KEY `idx_report_workspace` (`workspace_id`),
  CONSTRAINT `fk_report_task` FOREIGN KEY (`task_id`) REFERENCES `research_task` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='研究报告表：支持多版本 Markdown/HTML/PDF';

-- -----------------------------------------------------------------------------
-- 17. 引用
-- -----------------------------------------------------------------------------
CREATE TABLE `citation` (
  `id`            VARCHAR(64)   NOT NULL COMMENT '引用ID',
  `report_id`     VARCHAR(64)   NOT NULL COMMENT '所属报告ID',
  `task_id`       VARCHAR(64)   NOT NULL COMMENT '所属任务ID（冗余，便于查询）',
  `citation_no`   INT           NOT NULL COMMENT '报告内引用编号，如 [1]',
  `source_title`  VARCHAR(512)           DEFAULT NULL COMMENT '来源标题',
  `source_uri`    VARCHAR(1024)          DEFAULT NULL COMMENT '来源 URL 或内部路径',
  `source_type`   VARCHAR(32)            DEFAULT NULL COMMENT '来源类型：WEB/KNOWLEDGE/ANALYSIS',
  `document_id`   VARCHAR(64)            DEFAULT NULL COMMENT '内部文档ID（知识库来源时）',
  `chunk_id`      VARCHAR(64)            DEFAULT NULL COMMENT 'PGVector 中的片段ID',
  `quoted_text`   TEXT                   DEFAULT NULL COMMENT '引用原文摘录',
  `verified`      TINYINT       NOT NULL DEFAULT 0 COMMENT '是否通过 Critic/引用校验：1=是 0=否',
  `created_at`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_citation_report_no` (`report_id`, `citation_no`),
  KEY `idx_citation_task` (`task_id`),
  KEY `idx_citation_document` (`document_id`),
  CONSTRAINT `fk_citation_report` FOREIGN KEY (`report_id`) REFERENCES `report` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='报告引用表：结论与来源的可追溯绑定';

-- -----------------------------------------------------------------------------
-- 18. Token / 成本用量
-- -----------------------------------------------------------------------------
CREATE TABLE `token_usage` (
  `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `workspace_id`    VARCHAR(64)  NOT NULL COMMENT '工作空间ID',
  `task_id`         VARCHAR(64)           DEFAULT NULL COMMENT '关联任务ID',
  `user_id`         VARCHAR(64)           DEFAULT NULL COMMENT '触发用户ID',
  `agent_type`      VARCHAR(64)           DEFAULT NULL COMMENT 'Agent 类型',
  `model_name`      VARCHAR(128)          DEFAULT NULL COMMENT '模型名',
  `prompt_tokens`   INT          NOT NULL DEFAULT 0 COMMENT '输入 Token 数',
  `completion_tokens` INT        NOT NULL DEFAULT 0 COMMENT '输出 Token 数',
  `total_tokens`    INT          NOT NULL DEFAULT 0 COMMENT '总 Token 数',
  `estimated_cost`  DECIMAL(12,6) NOT NULL DEFAULT 0 COMMENT '估算成本（单位按配置）',
  `trace_id`        VARCHAR(64)           DEFAULT NULL COMMENT 'Trace ID',
  `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录时间',
  PRIMARY KEY (`id`),
  KEY `idx_token_workspace_time` (`workspace_id`, `created_at`),
  KEY `idx_token_task` (`task_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Token 与成本用量表：配额统计与可观测性';

-- -----------------------------------------------------------------------------
-- 19. 审计日志
-- -----------------------------------------------------------------------------
CREATE TABLE `audit_log` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `workspace_id`  VARCHAR(64)           DEFAULT NULL COMMENT '工作空间ID',
  `user_id`       VARCHAR(64)           DEFAULT NULL COMMENT '操作者用户ID',
  `action`        VARCHAR(64)  NOT NULL COMMENT '动作编码，如 TASK_CREATE/PLAN_APPROVE/KB_UPLOAD',
  `resource_type` VARCHAR(64)           DEFAULT NULL COMMENT '资源类型',
  `resource_id`   VARCHAR(64)           DEFAULT NULL COMMENT '资源ID',
  `detail_json`   JSON                  DEFAULT NULL COMMENT '操作详情',
  `ip`            VARCHAR(64)           DEFAULT NULL COMMENT '客户端 IP',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
  PRIMARY KEY (`id`),
  KEY `idx_audit_workspace_time` (`workspace_id`, `created_at`),
  KEY `idx_audit_user` (`user_id`),
  KEY `idx_audit_action` (`action`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='审计日志表：成员操作、审批与安全事件追溯';

-- -----------------------------------------------------------------------------
-- 20. 评测实验运行（可选，支撑简历评测数据落库）
-- -----------------------------------------------------------------------------
CREATE TABLE `evaluation_run` (
  `id`             VARCHAR(64)  NOT NULL COMMENT '评测运行ID',
  `name`           VARCHAR(128) NOT NULL COMMENT '实验名称',
  `dataset_ref`    VARCHAR(256) NOT NULL COMMENT '评测集路径或版本引用',
  `metrics_json`   JSON                  DEFAULT NULL COMMENT '指标结果：Recall@K、Faithfulness 等',
  `config_json`    JSON                  DEFAULT NULL COMMENT '实验配置与基线对比说明',
  `status`         VARCHAR(32)  NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING/RUNNING/COMPLETED/FAILED',
  `started_at`     DATETIME              DEFAULT NULL COMMENT '开始时间',
  `completed_at`   DATETIME              DEFAULT NULL COMMENT '结束时间',
  `created_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='评测实验运行表：记录离线评测与性能对比结果';


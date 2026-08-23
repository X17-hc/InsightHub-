-- =============================================================================
-- InsightHub PostgreSQL / PGVector 向量库表结构
-- 数据库：insighthub_vector
-- 说明：存放文档片段、向量索引、检索辅助数据；业务元数据在 MySQL
-- 注意：embedding 维度默认 1536，需与知识库 embedding_model 保持一致；换模型时重建列/索引
-- =============================================================================

CREATE EXTENSION IF NOT EXISTS vector;

-- -----------------------------------------------------------------------------
-- 1. 文档片段（含向量）
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS document_chunk CASCADE;
CREATE TABLE document_chunk (
  id                 VARCHAR(64)  PRIMARY KEY,
  workspace_id       VARCHAR(64)  NOT NULL,
  knowledge_base_id  VARCHAR(64)  NOT NULL,
  document_id        VARCHAR(64)  NOT NULL,
  chunk_index        INTEGER      NOT NULL,
  parent_chunk_id    VARCHAR(64),
  content            TEXT         NOT NULL,
  content_tokens     INTEGER,
  metadata_json      JSONB,
  embedding          vector(1536),
  embedding_model    VARCHAR(128) NOT NULL,
  page_no            INTEGER,
  loc_start          INTEGER,
  loc_end            INTEGER,
  -- 关键词检索支路：由 content 自动生成 tsvector
  content_tsv        tsvector GENERATED ALWAYS AS (to_tsvector('simple', coalesce(content, ''))) STORED,
  created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  updated_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE document_chunk IS '文档片段表：存储分块正文与 embedding，供混合检索/重排；document_id 对应 MySQL.document.id';
COMMENT ON COLUMN document_chunk.id IS '片段ID（与 MySQL citation.chunk_id 对应）';
COMMENT ON COLUMN document_chunk.workspace_id IS '工作空间ID，检索时必须带权限过滤';
COMMENT ON COLUMN document_chunk.knowledge_base_id IS '知识库ID，对应 MySQL.knowledge_base.id';
COMMENT ON COLUMN document_chunk.document_id IS '文档ID，对应 MySQL.document.id';
COMMENT ON COLUMN document_chunk.chunk_index IS '文档内片段序号（从 0 或 1 起）';
COMMENT ON COLUMN document_chunk.parent_chunk_id IS '父块ID（父子分块策略时使用）';
COMMENT ON COLUMN document_chunk.content IS '片段文本内容';
COMMENT ON COLUMN document_chunk.content_tokens IS '片段大致 Token 数';
COMMENT ON COLUMN document_chunk.metadata_json IS '结构化元数据：标题路径、章节、作者等';
COMMENT ON COLUMN document_chunk.embedding IS '向量表示，维度需与 embedding_model 一致（默认 1536）';
COMMENT ON COLUMN document_chunk.embedding_model IS '生成该向量使用的 Embedding 模型';
COMMENT ON COLUMN document_chunk.page_no IS '页码（PDF 等）';
COMMENT ON COLUMN document_chunk.loc_start IS '原文起始字符/偏移定位';
COMMENT ON COLUMN document_chunk.loc_end IS '原文结束字符/偏移定位';
COMMENT ON COLUMN document_chunk.content_tsv IS '全文检索向量（simple 配置，便于中英混合关键词检索）';
COMMENT ON COLUMN document_chunk.created_at IS '创建时间';
COMMENT ON COLUMN document_chunk.updated_at IS '更新时间';

CREATE UNIQUE INDEX uk_document_chunk_doc_idx
  ON document_chunk (document_id, chunk_index);

CREATE INDEX idx_document_chunk_workspace_kb
  ON document_chunk (workspace_id, knowledge_base_id);

CREATE INDEX idx_document_chunk_kb
  ON document_chunk (knowledge_base_id);

CREATE INDEX idx_document_chunk_document
  ON document_chunk (document_id);

CREATE INDEX idx_document_chunk_parent
  ON document_chunk (parent_chunk_id);

CREATE INDEX idx_document_chunk_tsv
  ON document_chunk USING GIN (content_tsv);

-- 向量近似检索索引（HNSW）；数据量较小时也可先不建，导入后再建
CREATE INDEX IF NOT EXISTS idx_document_chunk_embedding_hnsw
  ON document_chunk
  USING hnsw (embedding vector_cosine_ops);

-- -----------------------------------------------------------------------------
-- 2. 研究证据缓存（可选：跨服务落盘证据对象）
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS research_evidence CASCADE;
CREATE TABLE research_evidence (
  id              VARCHAR(64) PRIMARY KEY,
  task_id         VARCHAR(64) NOT NULL,
  workspace_id    VARCHAR(64) NOT NULL,
  run_id          VARCHAR(64),
  source_type     VARCHAR(32) NOT NULL,
  source_title    VARCHAR(512),
  source_uri      TEXT,
  document_id     VARCHAR(64),
  chunk_id        VARCHAR(64),
  quoted_text     TEXT        NOT NULL,
  credibility     NUMERIC(5,4),
  verified        BOOLEAN     NOT NULL DEFAULT FALSE,
  metadata_json   JSONB,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE research_evidence IS '研究证据表：Web/知识库/分析结果的可追溯证据对象，供 Critic/Writer 使用';
COMMENT ON COLUMN research_evidence.id IS '证据ID';
COMMENT ON COLUMN research_evidence.task_id IS '研究任务ID（对应 MySQL.research_task.id）';
COMMENT ON COLUMN research_evidence.workspace_id IS '工作空间ID';
COMMENT ON COLUMN research_evidence.run_id IS '执行轮次ID';
COMMENT ON COLUMN research_evidence.source_type IS '来源类型：WEB/KNOWLEDGE/ANALYSIS';
COMMENT ON COLUMN research_evidence.source_title IS '来源标题';
COMMENT ON COLUMN research_evidence.source_uri IS '来源 URL 或内部定位';
COMMENT ON COLUMN research_evidence.document_id IS '内部文档ID（可选）';
COMMENT ON COLUMN research_evidence.chunk_id IS '关联 document_chunk.id（可选）';
COMMENT ON COLUMN research_evidence.quoted_text IS '证据原文摘录';
COMMENT ON COLUMN research_evidence.credibility IS '可信度评分 0~1';
COMMENT ON COLUMN research_evidence.verified IS '是否通过交叉核验';
COMMENT ON COLUMN research_evidence.metadata_json IS '作者、发布日期、工具轨迹等扩展信息';
COMMENT ON COLUMN research_evidence.created_at IS '创建时间';

CREATE INDEX idx_research_evidence_task
  ON research_evidence (task_id);

CREATE INDEX idx_research_evidence_workspace
  ON research_evidence (workspace_id, created_at DESC);

CREATE INDEX idx_research_evidence_chunk
  ON research_evidence (chunk_id);

-- -----------------------------------------------------------------------------
-- 3. 分析产物元数据（图表/表格文件索引）
-- -----------------------------------------------------------------------------
-- 该表保存已生成的分析产物。初始化脚本可能随已有数据卷再次被执行，
-- 因此不能用 DROP TABLE，否则会删除用户可下载的历史产物。
CREATE TABLE IF NOT EXISTS analysis_artifact (
  id              VARCHAR(64) PRIMARY KEY,
  task_id         VARCHAR(64) NOT NULL,
  workspace_id    VARCHAR(64) NOT NULL,
  run_id          VARCHAR(64),
  artifact_type   VARCHAR(32) NOT NULL,
  title           VARCHAR(256),
  storage_uri     TEXT        NOT NULL,
  code_ref        TEXT,
  stdout_ref      TEXT,
  status          VARCHAR(32) NOT NULL DEFAULT 'SUCCESS',
  metadata_json   JSONB,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE analysis_artifact IS '数据分析产物表：沙箱生成的图表、表格路径与运行状态索引';
COMMENT ON COLUMN analysis_artifact.id IS '产物ID';
COMMENT ON COLUMN analysis_artifact.task_id IS '研究任务ID';
COMMENT ON COLUMN analysis_artifact.workspace_id IS '工作空间ID';
COMMENT ON COLUMN analysis_artifact.run_id IS '执行轮次ID';
COMMENT ON COLUMN analysis_artifact.artifact_type IS '产物类型：CHART/TABLE/FILE/NOTE';
COMMENT ON COLUMN analysis_artifact.title IS '产物标题';
COMMENT ON COLUMN analysis_artifact.storage_uri IS '产物存储路径/URI';
COMMENT ON COLUMN analysis_artifact.code_ref IS '生成该产物的代码引用或摘要';
COMMENT ON COLUMN analysis_artifact.stdout_ref IS '标准输出摘要或日志引用';
COMMENT ON COLUMN analysis_artifact.status IS '运行状态：SUCCESS/FAILED/TIMEOUT';
COMMENT ON COLUMN analysis_artifact.metadata_json IS '扩展元数据';
COMMENT ON COLUMN analysis_artifact.created_at IS '创建时间';

CREATE INDEX IF NOT EXISTS idx_analysis_artifact_task
  ON analysis_artifact (task_id);

-- -----------------------------------------------------------------------------
-- 授权：确保业务用户可读写
-- -----------------------------------------------------------------------------
GRANT ALL ON ALL TABLES IN SCHEMA public TO insighthub;
GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO insighthub;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO insighthub;

-- ============================================================
-- Orca P2 语义缓存: PgVector 向量库初始化
-- 库: orca_vec, 自带 vector 扩展镜像(pgvector/pgvector:pg16)
-- ============================================================

CREATE EXTENSION IF NOT EXISTS vector;

-- llm_cache: 语义缓存条目
CREATE TABLE IF NOT EXISTS llm_cache (
  id            BIGSERIAL PRIMARY KEY,
  tenant_id     BIGINT       NOT NULL,
  prompt_hash   CHAR(64)     NOT NULL,           -- SHA-256(normalized prompt), 精确快路径
  prompt_text   TEXT         NOT NULL,
  embedding     vector(384)  NOT NULL,           -- Mock embedding 384 维
  response_text TEXT         NOT NULL,
  model         VARCHAR(64)  NOT NULL,
  total_tokens  INT          NOT NULL,
  hit_count     INT          NOT NULL DEFAULT 0,
  created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
  expires_at    TIMESTAMPTZ  NOT NULL,
  UNIQUE (tenant_id, prompt_hash)                -- ON CONFLICT 幂等写依赖
);

-- ivfflat ANN 索引(余弦距离), 加速向量检索
CREATE INDEX IF NOT EXISTS idx_llm_cache_vec
  ON llm_cache USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);

-- 精确快路径 + 过滤索引
CREATE INDEX IF NOT EXISTS idx_llm_cache_tm
  ON llm_cache (tenant_id, model, expires_at);

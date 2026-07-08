-- ============================================================
-- Orca P1 网关库表
-- 扩展 tenant + 新建 api_key / tenant_quota / call_log
-- ============================================================

SET NAMES utf8mb4;

-- 1) 扩展 tenant: 每租户限流/配额(演示不同租户不同额度)
ALTER TABLE `tenant`
  ADD COLUMN `rpm_limit`   INT     NOT NULL DEFAULT 60      COMMENT '每分钟请求数上限',
  ADD COLUMN `tpm_limit`   INT     NOT NULL DEFAULT 100000  COMMENT '每分钟 token 上限',
  ADD COLUMN `daily_quota` BIGINT  NOT NULL DEFAULT 1000000 COMMENT '每日 token 配额',
  ADD COLUMN `updated_at`  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP;

-- 2) api_key 表: 租户凭证
-- P1 简化: api_key 明文存储; 生产应存 key_hash + key_prefix, 见 P2 安全加固
CREATE TABLE IF NOT EXISTS `api_key` (
  `id`           BIGINT       NOT NULL AUTO_INCREMENT,
  `tenant_id`    BIGINT       NOT NULL COMMENT '所属租户',
  `api_key`      VARCHAR(128) NOT NULL COMMENT 'API Key(P1明文;生产存hash)',
  `key_prefix`   VARCHAR(16)  NOT NULL DEFAULT '' COMMENT '前缀 sk-xxx***',
  `status`       TINYINT      NOT NULL DEFAULT 1 COMMENT '1启用 0禁用',
  `last_used_at` DATETIME     NULL,
  `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_api_key` (`api_key`),
  KEY `idx_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='API Key 表';

-- 3) tenant_quota 表: 每日配额源记录(Redis 热计数, DB 兜底/对账)
CREATE TABLE IF NOT EXISTS `tenant_quota` (
  `id`              BIGINT   NOT NULL AUTO_INCREMENT,
  `tenant_id`       BIGINT   NOT NULL,
  `quota_date`      DATE     NOT NULL COMMENT '配额日期',
  `daily_limit`     BIGINT   NOT NULL COMMENT '当日配额上限',
  `consumed_tokens` BIGINT   NOT NULL DEFAULT 0 COMMENT '已消费 token',
  `created_at`      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_date` (`tenant_id`, `quota_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='租户每日配额表';

-- 4) call_log 表: 调用流水(P1 同步写; P2 改 Kafka 异步)
CREATE TABLE IF NOT EXISTS `call_log` (
  `id`                BIGINT       NOT NULL AUTO_INCREMENT,
  `tenant_id`         BIGINT       NOT NULL,
  `api_key_id`        BIGINT       NULL,
  `request_id`        VARCHAR(64)  NULL COMMENT '网关生成, 对应 OpenAI completion id',
  `provider`          VARCHAR(32)  NOT NULL,
  `model`             VARCHAR(64)  NOT NULL,
  `stream`            TINYINT      NOT NULL DEFAULT 0,
  `prompt_tokens`     INT          NOT NULL DEFAULT 0,
  `completion_tokens` INT          NOT NULL DEFAULT 0,
  `total_tokens`      INT          NOT NULL DEFAULT 0,
  `latency_ms`        INT          NOT NULL DEFAULT 0,
  `status`            VARCHAR(16)  NOT NULL COMMENT 'success/fail',
  `error_code`        INT          NULL,
  `trace_id`          VARCHAR(64)  NULL,
  `created_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_tenant_time` (`tenant_id`, `created_at`),
  KEY `idx_trace` (`trace_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='LLM 调用流水';

-- 5) seed: demo 租户 + 测试 API Key
INSERT INTO `tenant` (`tenant_code`, `tenant_name`, `status`, `rpm_limit`, `tpm_limit`, `daily_quota`)
VALUES ('demo', 'Demo Tenant', 1, 60, 100000, 1000000);

INSERT INTO `api_key` (`tenant_id`, `api_key`, `key_prefix`, `status`)
VALUES ((SELECT id FROM (SELECT id FROM tenant WHERE tenant_code='demo') t), 'sk-orca-demo-0001', 'sk-orca-demo', 1);

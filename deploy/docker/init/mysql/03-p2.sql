-- ============================================================
-- Orca P2: Kafka 异步记账幂等 + 计费对账
-- ============================================================

SET NAMES utf8mb4;

-- 1) call_log: requestId 唯一索引(Kafka at-least-once 幂等消费)
ALTER TABLE `call_log`
  MODIFY `request_id` VARCHAR(64) NOT NULL COMMENT '网关生成, Kafka 幂等键',
  ADD UNIQUE KEY `uk_request_id` (`request_id`);

-- 2) billing_record: 计费对账记录(每日每租户一条, UK tenant+period)
CREATE TABLE IF NOT EXISTS `billing_record` (
  `id`             BIGINT        NOT NULL AUTO_INCREMENT,
  `tenant_id`      BIGINT        NOT NULL,
  `period_date`    DATE          NOT NULL COMMENT '对账日期',
  `total_tokens`   BIGINT        NOT NULL DEFAULT 0 COMMENT 'call_log 聚合 token',
  `total_calls`    INT           NOT NULL DEFAULT 0,
  `success_calls`  INT           NOT NULL DEFAULT 0,
  `billing_amount` DECIMAL(12,4) NOT NULL DEFAULT 0 COMMENT '计费金额',
  `redis_consumed` BIGINT        NOT NULL DEFAULT 0 COMMENT 'best-effort Redis 值',
  `quota_consumed` BIGINT        NOT NULL DEFAULT 0 COMMENT 'tenant_quota 持久化值',
  `diff_flag`      TINYINT       NOT NULL DEFAULT 0 COMMENT '1=call_log与quota差异超阈',
  `settled`        TINYINT       NOT NULL DEFAULT 0 COMMENT '0未结算 1已结算',
  `created_at`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_period` (`tenant_id`, `period_date`),
  KEY `idx_period` (`period_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='计费对账记录';

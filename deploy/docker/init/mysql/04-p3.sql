-- ============================================================
-- Orca P3: 工作流引擎库表
-- DAG + 状态机 + 事件溯源 + Saga 补偿 + 人工审批
-- ============================================================
SET NAMES utf8mb4;

-- 1) workflow_definition: 工作流定义(DSL 持久化, 版本化)
CREATE TABLE IF NOT EXISTS `workflow_definition` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT,
  `workflow_code` VARCHAR(64)  NOT NULL COMMENT '工作流编码',
  `version`       INT          NOT NULL DEFAULT 1 COMMENT '版本号',
  `name`          VARCHAR(128) NOT NULL,
  `dsl`           JSON         NOT NULL COMMENT 'DSL 原文(nodes+edges)',
  `status`        TINYINT      NOT NULL DEFAULT 1 COMMENT '1启用 0禁用',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code_version` (`workflow_code`, `version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工作流定义';

-- 2) workflow_instance: 工作流实例(运行态)
-- tenant_snapshot: 创建时租户快照 JSON, 调度/异步线程重建 TenantContext
-- next_schedule_at: 下次可调度时间(延迟/退避), 调度器扫描用
CREATE TABLE IF NOT EXISTS `workflow_instance` (
  `id`               BIGINT       NOT NULL AUTO_INCREMENT,
  `workflow_code`    VARCHAR(64)  NOT NULL,
  `version`          INT          NOT NULL,
  `status`           VARCHAR(16)  NOT NULL COMMENT 'RUNNING/PAUSED/SUCCEEDED/FAILED',
  `trigger_type`     VARCHAR(16)  NOT NULL DEFAULT 'API' COMMENT 'API/SCHEDULE',
  `input`            JSON         NULL COMMENT '启动入参',
  `context`          JSON         NULL COMMENT '运行上下文(节点输出快照)',
  `biz_id`           VARCHAR(64)  NOT NULL COMMENT '业务幂等键',
  `tenant_id`        BIGINT       NOT NULL,
  `tenant_snapshot`  JSON         NOT NULL COMMENT '创建时租户快照(重建 TenantContext)',
  `next_schedule_at` DATETIME     NULL COMMENT '下次可调度时间(延迟/退避)',
  `created_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_biz_id` (`biz_id`),
  KEY `idx_status_schedule` (`status`, `next_schedule_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工作流实例';

-- 3) node_instance: 节点实例(每节点每实例一行, 幂等)
CREATE TABLE IF NOT EXISTS `node_instance` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT,
  `instance_id` BIGINT       NOT NULL,
  `node_id`     VARCHAR(64)  NOT NULL COMMENT 'DSL 内节点 id',
  `status`      VARCHAR(16)  NOT NULL COMMENT 'PENDING/RUNNING/SUCCEEDED/FAILED/SKIPPED',
  `input`       JSON         NULL,
  `output`      JSON         NULL,
  `retry_count` INT          NOT NULL DEFAULT 0,
  `started_at`  DATETIME     NULL,
  `ended_at`    DATETIME     NULL,
  `error`       VARCHAR(512) NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_inst_node` (`instance_id`, `node_id`),
  KEY `idx_instance` (`instance_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='节点实例';

-- 4) workflow_event: 事件溯源 append-only
-- seq 单调递增(应用层 MAX+1 + uk 兜底), 可重放重建任意时刻状态
CREATE TABLE IF NOT EXISTS `workflow_event` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT,
  `instance_id` BIGINT       NOT NULL,
  `seq`         INT          NOT NULL COMMENT '实例内单调递增序号',
  `event_type`  VARCHAR(32)  NOT NULL COMMENT 'STARTED/NODE_STARTED/NODE_SUCCEEDED/NODE_FAILED/PAUSED/RESUMED/COMPLETED/COMPENSATED/COMPENSATE_FAILED/HUMAN_APPROVED/HUMAN_REJECTED/HUMAN_TASK_TIMEOUT',
  `payload`     JSON         NOT NULL,
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_inst_seq` (`instance_id`, `seq`),
  KEY `idx_instance` (`instance_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工作流事件(append-only 事件溯源)';

-- 5) human_task: 人工审批任务(Human-in-loop)
CREATE TABLE IF NOT EXISTS `human_task` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT,
  `instance_id` BIGINT       NOT NULL,
  `node_id`     VARCHAR(64)  NOT NULL,
  `status`      VARCHAR(16)  NOT NULL COMMENT 'PENDING/APPROVED/REJECTED/TIMEOUT',
  `timeout_at`  DATETIME     NOT NULL COMMENT '超时时间(超时扫描用)',
  `assignee`    VARCHAR(64)  NULL COMMENT '审批人',
  `decided_at`  DATETIME     NULL,
  `decision`    VARCHAR(16)  NULL COMMENT 'APPROVED/REJECTED',
  `payload`     JSON         NULL COMMENT '审批上下文(展示给审批人)',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_status_timeout` (`status`, `timeout_at`),
  KEY `idx_instance` (`instance_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='人工审批任务';

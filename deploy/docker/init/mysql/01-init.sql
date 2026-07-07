-- Orca 初始化 DDL
-- 注: 完整库表见 docs/db-schema.sql, 此处先建库与极简元信息表保证依赖就绪。
-- 字符集统一 utf8mb4, 否则 emoji/中文会乱码(面试点: 为什么不用 utf8 → utf8 是 3 字节, 存不下 4 字节 emoji)。

SET NAMES utf8mb4;

-- 租户表(P1 网关鉴权用)
CREATE TABLE IF NOT EXISTS `tenant` (
  `id`           BIGINT       NOT NULL AUTO_INCREMENT,
  `tenant_code`  VARCHAR(64)  NOT NULL COMMENT '租户编码',
  `tenant_name`  VARCHAR(128) NOT NULL,
  `status`       TINYINT      NOT NULL DEFAULT 1 COMMENT '1启用 0禁用',
  `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_code` (`tenant_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='租户表';

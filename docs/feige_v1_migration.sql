-- =============================================================
-- 《飞鸽传书》V1.0 -> V1.1（发送即起飞版）迁移：仅当本地已按 V1.0 建过表时执行
-- 目标：feige_letter 增加分享凭证/认领/到期/结算快照字段，并将默认状态改为 FLYING_UNCLAIMED；
--       feige_letter_event 增加 (letter_id,seq) 唯一约束。
-- 说明：V1.0 尚未上线，若可整表重建，直接用 feige_v1.sql 更干净。
-- 重要：顺序必须是「先加列 -> 回填空值 -> 再收紧 NOT NULL -> 建索引」，
--       否则对已有旧数据执行 MODIFY ... NOT NULL 会报 1138 Invalid use of NULL value。
-- =============================================================

-- 1) 先只加列（暂不改 NOT NULL；share_token 先给默认空串，settled 给默认 0，其余可空）
ALTER TABLE `feige_letter`
  ADD COLUMN `share_token`        VARCHAR(48)  NOT NULL DEFAULT '' COMMENT '不可猜测的分享凭证(公开参数)' AFTER `letter_id`,
  ADD COLUMN `claim_expire_time`  DATETIME     DEFAULT NULL COMMENT '认领截止=起飞+72h' AFTER `departure_time`,
  ADD COLUMN `claimed_at`         DATETIME     DEFAULT NULL COMMENT '收件人认领成功时间' AFTER `claim_expire_time`,
  ADD COLUMN `recalled_at`        DATETIME     DEFAULT NULL COMMENT '主动召回时间' AFTER `claimed_at`,
  ADD COLUMN `expired_at`         DATETIME     DEFAULT NULL COMMENT '自动过期时间' AFTER `recalled_at`,
  ADD COLUMN `settled`            TINYINT      NOT NULL DEFAULT 0 COMMENT '抵达成长是否已结算(0/1)' AFTER `read`,
  ADD COLUMN `settled_at`         DATETIME     DEFAULT NULL COMMENT '成长结算时间' AFTER `settled`,
  ADD COLUMN `settle_exp_delta`   INT          DEFAULT NULL COMMENT '本次结算经验增量' AFTER `settled_at`,
  ADD COLUMN `settle_level_before` INT         DEFAULT NULL COMMENT '结算前等级' AFTER `settle_exp_delta`,
  ADD COLUMN `settle_level_after` INT          DEFAULT NULL COMMENT '结算后等级' AFTER `settle_level_before`,
  ADD COLUMN `settle_level_up`    TINYINT      DEFAULT NULL COMMENT '本次是否升级(0/1)' AFTER `settle_level_after`;

-- 2) 回填旧数据（先把空值补齐，才能安全收紧 NOT NULL）
UPDATE `feige_letter` SET `departure_time` = `create_at` WHERE `departure_time` IS NULL;
UPDATE `feige_letter` SET `status` = 'FLYING_UNCLAIMED' WHERE `status` = 'WAITING_RECIPIENT';
UPDATE `feige_letter` SET `claim_expire_time` = DATE_ADD(`departure_time`, INTERVAL 72 HOUR) WHERE `claim_expire_time` IS NULL;
UPDATE `feige_letter` SET `share_token` = CONCAT('ST', REPLACE(UUID(),'-',''))
   WHERE `share_token` IS NULL OR `share_token` = '';

-- 3) 再收紧为 NOT NULL（此时旧数据已无空值）
ALTER TABLE `feige_letter`
  MODIFY COLUMN `departure_time`  DATETIME     NOT NULL COMMENT '起飞=服务器当前时间(发送即起飞,不可改)',
  MODIFY COLUMN `status`          VARCHAR(24)  NOT NULL DEFAULT 'FLYING_UNCLAIMED' COMMENT 'FLYING_UNCLAIMED/IN_FLIGHT/ARRIVED/DELIVERED/RECALLED/UNCLAIMED_EXPIRED';

-- 4) 索引（share_token 已回填为唯一串，可安全建唯一索引）
ALTER TABLE `feige_letter` ADD UNIQUE KEY `uk_share_token` (`share_token`);
ALTER TABLE `feige_letter` ADD KEY `idx_claim_expire` (`claim_expire_time`);

-- 5) feige_letter_event 唯一约束（防止认领重试重复生成日志）
ALTER TABLE `feige_letter_event` ADD UNIQUE KEY `uk_letter_seq` (`letter_id`, `seq`);

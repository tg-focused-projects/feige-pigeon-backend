-- =============================================================
-- 飞鸽传书 独立项目建库/建表（新库 feige_pigeon，JDK8/SpringBoot）
-- 说明：项目未开 mapUnderscoreToCamelCase，查询要用列别名显式映射驼峰（见 Mapper）。
-- =============================================================
CREATE DATABASE IF NOT EXISTS `feige_pigeon` DEFAULT CHARSET utf8mb4;
USE `feige_pigeon`;

-- ---------- 小程序用户（注册/登录） ----------
CREATE TABLE IF NOT EXISTS `fg_user` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `openid`      VARCHAR(64)  NOT NULL                COMMENT '微信小程序 openid',
  `session_key` VARCHAR(128) DEFAULT NULL            COMMENT 'session_key(登录刷新)',
  `nickname`    VARCHAR(64)  DEFAULT NULL            COMMENT '昵称',
  `face`        VARCHAR(255) DEFAULT NULL            COMMENT '头像',
  `mobile`      VARCHAR(20)  DEFAULT NULL            COMMENT '手机号',
  `app_type`    INT          NOT NULL DEFAULT 1      COMMENT '应用类型:1微信小程序',
  `status`      INT          NOT NULL DEFAULT 1      COMMENT '状态:1正常',
  `create_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_openid` (`openid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='飞鸽传书-小程序用户';

-- ---------- 用户鸽子 ----------
CREATE TABLE IF NOT EXISTS `feige_pigeon` (
  `id`                 BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `openid`             VARCHAR(64)  NOT NULL                COMMENT '所属用户',
  `name`               VARCHAR(32)  NOT NULL DEFAULT '小白'  COMMENT '鸽子名',
  `level`              INT          NOT NULL DEFAULT 1      COMMENT '等级',
  `exp`                INT          NOT NULL DEFAULT 0      COMMENT '当前等级内经验',
  `speed_kmh`          DECIMAL(8,2) NOT NULL DEFAULT 177.00 COMMENT '速度 km/h',
  `stamina`            INT          NOT NULL DEFAULT 3      COMMENT '体力(❤️ 数)',
  `delivered_count`    INT          NOT NULL DEFAULT 0      COMMENT '成功送达次数',
  `total_mileage`      DECIMAL(12,2) NOT NULL DEFAULT 0.00  COMMENT '累计飞行里程 km',
  `farthest_distance`  DECIMAL(12,2) NOT NULL DEFAULT 0.00  COMMENT '最远送信 km',
  `role_key`           VARCHAR(24)  NOT NULL DEFAULT 'XIAOBAI' COMMENT '角色: XIAOBAI/PANGDUN/HUIHUI/ASHAN/LAOYOUCHAI/HUALING',
  `status`             VARCHAR(16)  NOT NULL DEFAULT 'IDLE' COMMENT 'IDLE/SENDING/LOST',
  `create_at`          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_at`          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_openid_role` (`openid`, `role_key`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='飞鸽传书-用户鸽子';

-- ---------- 信件 ----------
CREATE TABLE IF NOT EXISTS `feige_letter` (
  `id`                     BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `letter_id`              VARCHAR(40)  NOT NULL                COMMENT '内部唯一ID',
  `share_token`            VARCHAR(48)  NOT NULL                COMMENT '不可猜测的分享凭证(公开参数)',
  `sender_openid`          VARCHAR(64)  NOT NULL                COMMENT '发件人',
  `sender_province`        VARCHAR(64)  DEFAULT NULL            COMMENT '发件省份',
  `sender_city`            VARCHAR(64)  DEFAULT NULL            COMMENT '发件城市',
  `sender_lat`             DECIMAL(14,8) DEFAULT NULL           COMMENT '发件纬度(仅距离用,不对外)',
  `sender_lng`             DECIMAL(14,8) DEFAULT NULL           COMMENT '发件经度',
  `recipient_openid`       VARCHAR(64)  DEFAULT NULL            COMMENT '收件人(认领后填)',
  `recipient_province`     VARCHAR(64)  DEFAULT NULL            COMMENT '收件省份(认领后填)',
  `recipient_city`         VARCHAR(64)  DEFAULT NULL            COMMENT '收件城市(认领后填)',
  `recipient_lat`          DECIMAL(14,8) DEFAULT NULL           COMMENT '收件纬度(认领后填)',
  `recipient_lng`          DECIMAL(14,8) DEFAULT NULL           COMMENT '收件经度',
  `title`                  VARCHAR(64)  DEFAULT NULL            COMMENT '标题(≤64字,拆信后展示)',
  `signature`              VARCHAR(64)  DEFAULT NULL            COMMENT '落款(≤64字,拆信后展示)',
  `content`                TEXT                                 COMMENT '正文(≤500字,拆信后才返回)',
  `image_url`              VARCHAR(255) DEFAULT NULL            COMMENT '配图',
  `pigeon_id`              BIGINT       DEFAULT NULL            COMMENT '送信鸽子',
  `pigeon_name`            VARCHAR(32)  DEFAULT NULL            COMMENT '送达时鸽子快照',
  `speed_kmh`              DECIMAL(8,2) DEFAULT NULL            COMMENT '送达时速度快照',
  `distance_km`            DECIMAL(12,2) DEFAULT NULL           COMMENT '直线距离(认领后)',
  `flight_hours`           DECIMAL(10,2) DEFAULT NULL           COMMENT '飞行时长=距离/速度(认领后)',
  `departure_time`         DATETIME     NOT NULL                COMMENT '起飞=服务器当前时间(发送即起飞,不可改)',
  `claim_expire_time`      DATETIME     NOT NULL                COMMENT '认领截止=起飞+72h',
  `claimed_at`             DATETIME     DEFAULT NULL            COMMENT '认领成功时间',
  `recalled_at`            DATETIME     DEFAULT NULL            COMMENT '主动召回时间',
  `expired_at`             DATETIME     DEFAULT NULL            COMMENT '自动过期时间',
  `arrival_time`           DATETIME     DEFAULT NULL            COMMENT '预计到达=原始departure+时长(认领后)',
  `status`                 VARCHAR(24)  NOT NULL DEFAULT 'FLYING_UNCLAIMED' COMMENT 'FLYING_UNCLAIMED/IN_FLIGHT/ARRIVED/DELIVERED/RECALLED/UNCLAIMED_EXPIRED',
  `settled`                TINYINT      NOT NULL DEFAULT 0      COMMENT '成长是否已结算(0/1)',
  `settled_at`             DATETIME     DEFAULT NULL            COMMENT '成长结算时间',
  `settle_exp_delta`       INT          DEFAULT NULL            COMMENT '本次结算经验增量',
  `settle_level_before`    INT          DEFAULT NULL            COMMENT '结算前等级',
  `settle_level_after`     INT          DEFAULT NULL            COMMENT '结算后等级',
  `settle_level_up`        TINYINT      DEFAULT NULL            COMMENT '本次是否升级(0/1)',
  `subscribed`             TINYINT      NOT NULL DEFAULT 0      COMMENT '是否订阅到达通知',
  `notified`               TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已发到达通知',
  `read`                   TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已拆信',
  `thread_id`              VARCHAR(40)  DEFAULT NULL            COMMENT '往返会话ID(首信=自身,回信=原信thread)',
  `reply_to_letter_id`     VARCHAR(40)  DEFAULT NULL            COMMENT '回信指向的原信件ID(首信为NULL)',
  `create_at`              DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_at`              DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_letter_id` (`letter_id`),
  UNIQUE KEY `uk_share_token` (`share_token`),
  KEY `idx_sender` (`sender_openid`),
  KEY `idx_recipient` (`recipient_openid`),
  KEY `idx_status` (`status`),
  KEY `idx_arrival` (`arrival_time`),
  KEY `idx_claim_expire` (`claim_expire_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='飞鸽传书-信件';

-- ---------- 飞行日志 ----------
CREATE TABLE IF NOT EXISTS `feige_letter_event` (
  `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
  `letter_id`   VARCHAR(40) NOT NULL                COMMENT '所属信件',
  `seq`         INT         NOT NULL DEFAULT 0      COMMENT '事件序号(按时间)',
  `type`        VARCHAR(24) NOT NULL                COMMENT 'DEPART/ARRIVE/CITY_OVER/COUNTERWIND/RAIN/REST/CAT_SCARE/FOOD/DRIFT',
  `title`       VARCHAR(64) DEFAULT NULL            COMMENT '标题',
  `description` VARCHAR(128) DEFAULT NULL           COMMENT '补充文案',
  `at_time`     DATETIME    DEFAULT NULL            COMMENT '事件发生时间',
  `create_at`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_letter_seq` (`letter_id`, `seq`),
  KEY `idx_letter` (`letter_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='飞鸽传书-飞行日志';

CREATE TABLE IF NOT EXISTS `feige_subscription` (
  `id`            BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
  `letter_id`     VARCHAR(40) NOT NULL                COMMENT '所属信件',
  `openid`        VARCHAR(64) NOT NULL                COMMENT '订阅用户',
  `type`          VARCHAR(24) NOT NULL                COMMENT '订阅类型: ARRIVAL(当前鸽子抵达)/REPLY_ARRIVAL(回信抵达)',
  `notified`      TINYINT     NOT NULL DEFAULT 0      COMMENT '是否已推送(0/1,幂等)',
  `notified_at`   DATETIME    DEFAULT NULL            COMMENT '推送时间',
  `subscribed_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '订阅时间',
  `create_at`     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_at`     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_letter_user_type` (`letter_id`, `openid`, `type`),
  KEY `idx_letter` (`letter_id`),
  KEY `idx_openid` (`openid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='飞鸽传书-通知订阅(信件+用户+类型独立)';

-- ---------- 投诉（V3：最小投诉入口，规格17.1） ----------
CREATE TABLE IF NOT EXISTS `feige_report` (
  `id`                    BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
  `letter_id`             VARCHAR(40) NOT NULL                COMMENT '被投诉信件',
  `reporter_openid`       VARCHAR(64) NOT NULL                COMMENT '投诉人',
  `reported_sender_openid` VARCHAR(64) NOT NULL               COMMENT '被投诉发件人',
  `reason`                VARCHAR(24) NOT NULL                COMMENT '类型: INAPPROPRIATE(不当内容)/HARASSMENT(骚扰诈骗)/PRIVACY(侵犯隐私)/OTHER(其他)',
  `description`           VARCHAR(500) DEFAULT NULL           COMMENT '补充描述(≤500字)',
  `status`                VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/REVIEWED/CLOSED(运营人工处理)',
  `create_at`             DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_at`             DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_letter` (`letter_id`),
  KEY `idx_reporter` (`reporter_openid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='飞鸽传书-内容投诉';

-- ---------- 存量库升级（V2：标题/落款） ----------
-- 已存在的库执行一次即可（幂等：列存在时跳过）：
ALTER TABLE `feige_letter`
  ADD COLUMN `title` VARCHAR(64) DEFAULT NULL COMMENT '标题(≤64字,拆信后展示)' AFTER `recipient_lng`,
  ADD COLUMN `signature` VARCHAR(64) DEFAULT NULL COMMENT '落款(≤64字,拆信后展示)' AFTER `title`,
  ADD COLUMN `thread_id` VARCHAR(40) DEFAULT NULL COMMENT '往返会话ID(首信=自身,回信=原信thread)' AFTER `read`,
  ADD COLUMN `reply_to_letter_id` VARCHAR(40) DEFAULT NULL COMMENT '回信指向的原信件ID(首信为NULL)' AFTER `thread_id`;
-- 存量首信回填 thread_id = letter_id（保证历史数据会话完整）
UPDATE `feige_letter` SET `thread_id` = `letter_id` WHERE `thread_id` IS NULL AND `reply_to_letter_id` IS NULL;

-- ---------- 通知订阅（V3：双方独立订阅模型，规格13.1/13.2/13.3） ----------
-- 每封信件+用户+类型一条记录，替代信件级单字段 subscribed（规格13.3：不能使用一个信件级字段代表双方）
-- ---------- 存量库升级（V3：多鸽/订阅/投诉） ----------
-- feige_pigeon 增加 role_key（多鸽角色）；存量唯一键 uk_openid 改为 (openid, role_key)
ALTER TABLE `feige_pigeon`
  ADD COLUMN `role_key` VARCHAR(24) NOT NULL DEFAULT 'XIAOBAI' COMMENT '角色: XIAOBAI/PANGDUN/HUIHUI/ASHAN/LAOYOUCHAI/HUALING' AFTER `farthest_distance`;
-- 存量库若仍存在单 openid 唯一键，先删除再建组合唯一键（幂等：键不存在时跳过）
-- DROP INDEX `uk_openid` ON `feige_pigeon`;
ALTER TABLE `feige_pigeon` ADD UNIQUE KEY `uk_openid_role` (`openid`, `role_key`);
-- =============================================================
-- 飞鸽传书 生产环境 全量重建脚本（feige_pigeon）
-- 版本：V4.2（2026-09-02，对应 develop 9026750 / 契约 V4.2）
-- 用途：生产库尚未投入交付，直接重建全部表。
-- 注意：执行会清空已有数据（DROP TABLE），仅限全新/可重建环境使用。
-- =============================================================
CREATE DATABASE IF NOT EXISTS `feige_pigeon` DEFAULT CHARSET utf8mb4;
USE `feige_pigeon`;

-- ---------- 1. 小程序用户（注册/登录） ----------
DROP TABLE IF EXISTS `fg_user`;
CREATE TABLE `fg_user` (
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

-- ---------- 2. 用户鸽子（多鸽体系：六角色 + 位置模型） ----------
DROP TABLE IF EXISTS `feige_pigeon`;
CREATE TABLE `feige_pigeon` (
  `id`                 BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `openid`             VARCHAR(64)  NOT NULL                COMMENT '所属用户',
  `name`               VARCHAR(32)  NOT NULL DEFAULT '小白'  COMMENT '鸽子名',
  `level`              INT          NOT NULL DEFAULT 1      COMMENT '等级(保留字段,V1不结算)',
  `exp`                INT          NOT NULL DEFAULT 0      COMMENT '经验(保留字段,V1不结算)',
  `speed_kmh`          DECIMAL(8,2) NOT NULL DEFAULT 177.00 COMMENT '速度 km/h(固定177)',
  `stamina`            INT          NOT NULL DEFAULT 3      COMMENT '体力(保留)',
  `delivered_count`    INT          NOT NULL DEFAULT 0      COMMENT '成功送达次数',
  `total_mileage`      DECIMAL(12,2) NOT NULL DEFAULT 0.00  COMMENT '累计飞行里程 km',
  `farthest_distance`  DECIMAL(12,2) NOT NULL DEFAULT 0.00  COMMENT '最远送信 km',
  `role_key`           VARCHAR(24)  NOT NULL DEFAULT 'XIAOBAI' COMMENT '角色: XIAOBAI/PANGDUN/HUIHUI/ASHAN/LAOYOUCHAI/HUALING',
  `slot_index`         INT          NOT NULL DEFAULT 1      COMMENT '鸽舍位置序号(1~6; 位置1免费小白, 2~6付费; 价格绑位置,规格15.3)',
  `status`             VARCHAR(16)  NOT NULL DEFAULT 'IDLE' COMMENT 'IDLE/SENDING/LOST',
  `create_at`          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_at`          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_openid_role` (`openid`, `role_key`),
  UNIQUE KEY `uk_openid_slot` (`openid`, `slot_index`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='飞鸽传书-用户鸽子';

-- ---------- 3. 信件（含标题/落款/往返关系） ----------
DROP TABLE IF EXISTS `feige_letter`;
CREATE TABLE `feige_letter` (
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
  `settled`                TINYINT      NOT NULL DEFAULT 0      COMMENT '抵达结算(0/1;V1不结算经验,仅旅程数据)',
  `settled_at`             DATETIME     DEFAULT NULL            COMMENT '结算时间',
  `settle_exp_delta`       INT          DEFAULT NULL            COMMENT '经验增量(恒0,保留)',
  `settle_level_before`    INT          DEFAULT NULL            COMMENT '结算前等级(保留)',
  `settle_level_after`     INT          DEFAULT NULL            COMMENT '结算后等级(保留)',
  `settle_level_up`        TINYINT      DEFAULT NULL            COMMENT '是否升级(恒0,保留)',
  `subscribed`             TINYINT      NOT NULL DEFAULT 0      COMMENT '是否订阅到达(兼容字段,实际以feige_subscription为准)',
  `notified`               TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已发到达通知(信件级兼容)',
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

-- ---------- 4. 飞行日志 ----------
DROP TABLE IF EXISTS `feige_letter_event`;
CREATE TABLE `feige_letter_event` (
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

-- ---------- 5. 通知订阅（信件+用户+类型独立） ----------
DROP TABLE IF EXISTS `feige_subscription`;
CREATE TABLE `feige_subscription` (
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

-- ---------- 6. 内容投诉 ----------
DROP TABLE IF EXISTS `feige_report`;
CREATE TABLE `feige_report` (
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

-- ---------- 7. 多鸽购买订单 ----------
DROP TABLE IF EXISTS `feige_order`;
CREATE TABLE `feige_order` (
  `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `order_no`     VARCHAR(40)  NOT NULL                COMMENT '内部订单号(唯一)',
  `openid`       VARCHAR(64)  NOT NULL                COMMENT '下单用户',
  `role_key`     VARCHAR(24)  NOT NULL                COMMENT '购买角色: PANGDUN/HUIHUI/ASHAN/LAOYOUCHAI/HUALING',
  `slot_index`   INT          NOT NULL                COMMENT '鸽舍位置序号(2~6,价格绑定位置,规格15.3)',
  `amount_fen`   INT          NOT NULL                COMMENT '金额(分)',
  `status`       VARCHAR(16)  NOT NULL DEFAULT 'CREATED' COMMENT 'CREATED/PAID/REFUNDED/CANCELLED',
  `pay_trade_no` VARCHAR(64)  DEFAULT NULL            COMMENT '支付平台交易号(回调写入)',
  `pay_time`     DATETIME     DEFAULT NULL            COMMENT '支付成功时间',
  `refund_time`  DATETIME     DEFAULT NULL            COMMENT '退款时间',
  `create_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_no` (`order_no`),
  KEY `idx_openid` (`openid`),
  KEY `idx_openid_role` (`openid`, `role_key`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='飞鸽传书-多鸽购买订单';

-- =============================================================
-- 完成提示：7 张表全部创建成功。
-- 执行后建议核对：SHOW TABLES; 应包含 fg_user / feige_pigeon / feige_letter /
--   feige_letter_event / feige_subscription / feige_report / feige_order
-- =============================================================

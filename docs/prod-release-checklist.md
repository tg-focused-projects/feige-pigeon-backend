# 飞鸽传书 生产发布核查清单（develop V6.2 → main / 生产）

> 适用：develop 联调验收通过后合并 main、打镜像、更新生产 Deployment。
> 生产库：`172.17.0.200:3389`（yaml 凭据 soogif/BuDong20181220!，库 feige_pigeon）
> 生产 yaml 基线：2026-08-27（V1.0，revision 6）→ 需升到 V6.2。
> ⚠️ 执行者：人工（main 合并、镜像构建、kubectl apply 均人工；本清单供逐项核对执行）。

---

## 1. 数据库操作（生产库，发布前执行）

生产库是 V1.0 老结构。按顺序执行（**每段可重复执行需幂等**；唯一键/加列若已存在会报错，需先查再执行）：

### 1.1 feige_letter 加列 + 回填（V2：标题/落款/往返）

```sql
USE feige_pigeon;
-- 逐列检查：SELECT column_name FROM information_schema.columns WHERE table_schema='feige_pigeon' AND table_name='feige_letter' AND column_name='title';
ALTER TABLE `feige_letter`
  ADD COLUMN `title` VARCHAR(64) DEFAULT NULL COMMENT '标题(≤64字,拆信后展示)' AFTER `recipient_lng`,
  ADD COLUMN `signature` VARCHAR(64) DEFAULT NULL COMMENT '落款(≤64字,拆信后展示)' AFTER `title`,
  ADD COLUMN `thread_id` VARCHAR(40) DEFAULT NULL COMMENT '往返会话ID' AFTER `read`,
  ADD COLUMN `reply_to_letter_id` VARCHAR(40) DEFAULT NULL COMMENT '回信指向的原信件ID' AFTER `thread_id`;
UPDATE `feige_letter` SET `thread_id` = `letter_id` WHERE `thread_id` IS NULL AND `reply_to_letter_id` IS NULL;
```

### 1.2 feige_pigeon 多鸽化（V3/V4.2：role_key + slot_index + 唯一键改造）

```sql
USE feige_pigeon;
-- ① 加 role_key（若不存在）
ALTER TABLE `feige_pigeon`
  ADD COLUMN `role_key` VARCHAR(24) NOT NULL DEFAULT 'XIAOBAI' COMMENT '角色: XIAOBAI/PANGDUN/HUIHUI/ASHAN/LAOYOUCHAI/HUALING' AFTER `farthest_distance`;
-- ② 关键：删除单 openid 唯一键（若存在，否则第2只鸽子插不进去！）
-- SELECT index_name FROM information_schema.statistics WHERE table_schema='feige_pigeon' AND table_name='feige_pigeon' AND index_name='uk_openid';
ALTER TABLE `feige_pigeon` DROP INDEX `uk_openid`;
-- ③ 组合唯一键（若不存在）
ALTER TABLE `feige_pigeon` ADD UNIQUE KEY `uk_openid_role` (`openid`, `role_key`);
-- ④ 加 slot_index + 位置唯一键
ALTER TABLE `feige_pigeon` ADD COLUMN `slot_index` INT NOT NULL DEFAULT 1 COMMENT '鸽舍位置序号(1~6)' AFTER `role_key`;
ALTER TABLE `feige_pigeon` ADD UNIQUE KEY `uk_openid_slot` (`openid`, `slot_index`);
-- ⑤ 存量回填 slot_index：小白=1；非小白按同一用户 id 升序占 2..N
UPDATE `feige_pigeon` p JOIN (
  SELECT id, @rn := IF(@cur = openid, @rn + 1, 1) AS rn, @cur := openid
  FROM feige_pigeon, (SELECT @rn := 0, @cur := '') vars
  WHERE role_key <> 'XIAOBAI' AND openid <> ''
  ORDER BY openid, id
) t ON p.id = t.id SET p.slot_index = t.rn + 1 WHERE p.role_key <> 'XIAOBAI';
-- ⚠️ 注意：若生产有 V4.0 期 PAID 订单但槽位错位，按 version-plan「V4.0→V4.2 存量迁移注意」评估：
--   历史订单 slot_index 与鸽子实际位置需 UPDATE 对齐（feige_order 是 V1.0 新增表，V1.0→V6.2 首次上表无此问题）
```

### 1.3 新表（V3/V4/V5.1：订阅/投诉/订单/道具）——建表脚本见 schema.sql 原文

新表 `feige_subscription` / `feige_report` / `feige_order` / **`feige_pay_goods`（V5.1 关键，缺失则真实支付无法下单）**：
直接执行 `src/main/resources/sql/feige_schema.sql` 中对应 `CREATE TABLE IF NOT EXISTS` 段（幂等）。
**feige_pay_goods 建表后需插入 5 档道具**（product_id 填微信虚拟支付后台实际道具 ID；price_fen 与微信后台价格一致，否则下单 -15013）：

```sql
USE feige_pigeon;
INSERT INTO feige_pay_goods (slot_index, product_id, price_fen, remark) VALUES
 (2, '<微信道具ID-槽2>', 100, '槽2'), (3, '<微信道具ID-槽3>', 300, '槽3'),
 (4, '<微信道具ID-槽4>', 600, '槽4'), (5, '<微信道具ID-槽5>', 1000, '槽5'),
 (6, '<微信道具ID-槽6>', 1500, '槽6')
ON DUPLICATE KEY UPDATE product_id=VALUES(product_id), price_fen=VALUES(price_fen);
```

> 建议发布前先在生产库执行全部 DDL（可先跑在**只读副本/备份**验证），再切镜像；DDL 全部向前兼容，旧镜像（V1.0）在加列后仍可运行，可灰度。

---

## 2. 生产 Deployment env 增补（yaml 缺失项对照）

生产 yaml 现有 15 个 env；对照 application.yml（V6.2）**必须新增**：

| env 名称 | 值 | 说明/来源 | 必须性 |
|---|---|---|---|
| `PAID_PIGEON_ENABLED` | `true` | 多鸽付费开关（规格15.6） | ✅ 必须 |
| `FG_PAY_MOCK` | `false` | 关 mock，否则 confirm 直接成功 | ✅ 必须 |
| `FG_PAY_OFFER_ID` | `<正式OfferID>` | 虚拟支付基本配置（与测试机不同，用**正式小程序**的） | ✅ 真实支付必须 |
| `FG_PAY_APP_KEY` | `<正式现网AppKey>` | 同上（勿用沙箱/测试 AppKey） | ✅ 真实支付必须 |
| `FG_MP_PUSH_TOKEN` | `<正式token>` | 微信发货推送配置 token（验 URL） | ✅ 必须（推送接收） |
| `FG_MP_PUSH_AES_KEY` | `<正式43位AESKey>` | 发货推送加密解密 | ✅ 必须 |
| `FG_MP_PUSH_ENCRYPT_MODE` | `1` | 兼容模式 | ✅ 必须 |
| `FG_PAY_QUERY_POLL_ENABLED` | `true` | 查单兜底 Job（推送丢失补发货） | ✅ 建议 true |
| `FG_ORDER_EXPIRE_MINUTES` | `15` | 未支付订单超时自动取消（V5.2） | 可选（默认15） |
| `FG_REPLY_ARRIVAL_TEMPLATE_ID` | `<正式回信模板ID>` | 回信到达通知订阅模板 | ✅ 建议（有订阅功能需配） |
| `FG_RECALL_GRACE_MINUTES` | `30` | 召回宽限（规格5.3） | 可选（默认30） |
| `FG_QINIU_ACCESS_KEY/SECRET_KEY/BUCKET/PREFIX/EXPIRE_SECONDS` | `<正式七牛>` | V12-3 上传凭证（空间 mgif/目录 feige/） | 视功能是否启用 |
| `FG_LOCK_TTL_SECONDS` | `30` | 分布式锁 TTL（已开 FG_LOCK_ENABLED=true） | 可选（默认30） |
| 生产 image | `ccr.ccs.tencentyun.com/feige-pigeon/feige-pigeon:<新tag>` | main 打 tag 构建 | ✅ |

**保留不变**：TZ / FG_PORT / FG_DEV_LOGIN=false / DB 连接 / FG_WECHAT_APPID+SECRET（**换正式小程序 appid**）/ FG_SIGN_SECRET / FG_ARRIVAL_TEMPLATE_ID / FG_LOCK_ENABLED=true / Redis。

> ⚠️ **关键风险**：生产 yaml 的 FG_WECHAT_APPID 是 `<正式小程序AppID>`（占位符未填？）。发布前必须确认正式小程序 AppID/Secret、OfferID/AppKey、AESKey、模板 ID 全部为**正式主体**配置（测试机的 wx66a9a479c9cfe706 是测试小程序，不可用于生产支付——虚拟支付与小程序主体绑定）。

---

## 3. 微信侧后台待办（发布前人工确认）

| 项 | 说明 |
|---|---|
| 虚拟支付道具 | 正式小程序虚拟支付后台「道具管理」发布 5 个道具，productId 与 `feige_pay_goods` 表一致 |
| 发货推送 URL | 配正式域名 https 回调 → `/feige/pay/notify`（Token/AESKey 与 yaml env 一致），保存时验 URL |
| iOS | 如需 iOS 支付：配小程序简称 + 苹果 IAP |
| 订阅模板 | 到达/回信模板需为正式小程序已审核通过的模板 ID |

---

## 4. 发布顺序建议

1. 合并 main（人工）→ main 打 tag
2. 发布机 `build_feigepigeon_images.sh` 构建镜像（tag 日期）
3. **先执行 §1 数据库 DDL**（向前兼容，旧镜像可跑）
4. 更新 yaml（§2 env + 新 image）`kubectl apply`
5. 滚动发布（replicas 2，maxSurge 1/maxUnavailable 0）→ 探活 `/v2/api-docs`
6. 真机回归：发信→认领→到信→拆信→回信→信箱；支付用 1 分钱真单验证 payData→wx.requestVirtualPayment→发货推送→鸽子入住；确认账单
7. 回归通过后观察日志（xpay 推送/解密/Job 无 ERROR）

---

## 5. 常见坑（来自 ops-runbook 生产相关）

- schema.sql 的存量 ALTER 段**不是真幂等**（ADD COLUMN/ADD UNIQUE 重复执行报错）→ 生产手动逐条按"先查后加"执行（本清单 §1 已按此写）。
- **uk_openid 必须先删再加组合键**，否则多鸽第 2 只插入失败（本地/测试都踩过）。
- MySQL 时区必须 +08:00；改时区后重启应用重建连接池。
- `test.soogif.com` 是生产 TKE 域名，联调用 `demo.soogif.com`；别把测试小程序配置带进生产。
- 生产两个副本 + Quartz：FG_LOCK_ENABLED=true 保证 Job 互斥（已配）；锁 TTL>任务最长耗时。

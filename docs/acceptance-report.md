# 《飞鸽传书》V1.1 后端验收报告

> 依据：`.scratch/feige/03-backend-change-request-v1.0.md`
> 环境：search-smallapp 本地 Tomcat `http://localhost:8089` · MySQL `127.0.0.1:3306/search`
> 执行：2026-08-27 · JDK8 编译通过（13 class）· 新库已按 `feige_v1.sql` / `feige_v1_migration.sql` 建立
> 复用写法：注解式 MyBatis + Quartz(仿 search-admin 热点追踪)，未用 spring-task.xml

---

## 0. 结论

**全部验收用例通过**。核心语义（发送即起飞、72h 自动过期、30min 免费召回、原子认领仅一人成功、抵达即释放并幂等结算、share_token 公开、errorKey 稳定标识、Quartz 定时扫描）均按修改单落地并经直连 MySQL 实测。

---

## 1. 用例执行结果（对应修改单第十节）

### 1. 发送即起飞
- `POST /letter/send` → `code:200`：`status=FLYING_UNCLAIMED`、`departureTime`（服务器时间）、非空 `shareToken`、`pigeon.status=SENDING`。✅

### 2. 三种首次打开（firstOpenCase）
| 场景 | 构造 | 结果 |
|---|---|---|
| `JUST_DEPARTED` | 发送后立即认领 | `status=IN_FLIGHT`、`progress≈0`、`firstOpenCase=JUST_DEPARTED`、`waitingDurationSeconds≈0` ✅ |
| `ALREADY_FLYING` | 起飞时间拨回 1 分钟再认领 | `firstOpenCase=ALREADY_FLYING`、`progress≈0.28` ✅ |
| `ARRIVED_WAITING` | 起飞时间拨回 5 分钟（已飞完）再认领 | `firstOpenCase=ARRIVED_WAITING`、`progress=1.0`、`status=ARRIVED`、鸽子 `IDLE` ✅ |

三种情况都保留发送时的原始起飞时间（认领不改 `departure_time`，只算距离/时长）。✅

### 3. 抢占（并发认领）
- 两个用户**同时**认领同一封：仅 1 人 `code:200`，另一人 `code:409 errorKey=ALREADY_CLAIMED`（不泄露收件人）。✅
- 获胜者同参重试 → `code:200` 幂等返回当前航程（**已修复 flight 参数顺序 bug**）。✅

### 4. 召回与过期
- `recall` 在 `<30min` → `code:409 errorKey=RECALL_TOO_EARLY`。✅
- 起飞时间拨回 40 分钟后再 `recall` → `code:200 recalled:true`、鸽子 `IDLE`、`share-preview=RECALLED`（旧分享失效）。✅
- `claim_expire_time` 拨到过去（模拟 72h 到期）→ **`FeigeUnclaimedExpireJob`(Quartz) 在 1 分钟内**将其置为 `share-preview=EXPIRED`、鸽子 `IDLE`。✅

### 5. 抵达与成长
- 查询 `flight` 到点后自动 `IN_FLIGHT→ARRIVED`，鸽子 `IDLE`（抵达即释放，不再等拆信）。✅
- 拆信 `detail`：返回正文 + 成长快照 `settleExpDelta`/`settleLevelBefore`/`settleLevelAfter`/`settleLevelUp`（10.99km → `settleExpDelta=1`、Lv1→Lv2 需 100 经验所以 `levelUp=0`）。✅
- 再次 `detail` 不重复结算成长（`settled=1` 幂等）。✅（逻辑：`markArrivedAndSettle ... settled=0` 条件）

### 6. 权限与时间
- 发件人认领自己：`SENDER_CANNOT_CLAIM`。✅
- 陌生人认领他人信：`ALREADY_CLAIMED`，不返回收件人身份/城市。✅
- 未抵达 `detail`：`NOT_ARRIVED`，不返回正文。✅
- 已抵达 `subscribe`：`subscribed:false`（无需订阅，不记录）。✅
- 发件人视角 `flight`：未认领返回 `status=FLYING_UNCLAIMED` + `progress:null`（不伪造 0）；`canRecall` 与时长相关。✅

---

## 2. 关键机制核实

- **原子认领**：`feige_letter` 上单条条件 `UPDATE`（`status=FLYING_UNCLAIMED AND claim_expire_time>now AND (recipient IS NULL OR =openid) AND sender<>openid`）——并发仅一人成功。✅
- **召回 vs 认领互斥**：`markRecalled` 与 `claimLetter` 均为条件 UPDATE，二者互斥。✅
- **并发发送**：发送前 `SELECT ... FOR UPDATE` 锁鸽子 + `markSending WHERE status=IDLE`。✅
- **单次结算**：`markArrivedAndSettle ... settled=0`；定时任务与请求共用 `FeigeLifecycleService.advanceToArrived`（`FOR UPDATE` 串行）。✅
- **日志幂等**：`UNIQUE(letter_id, seq)` + 生成前判空，重试不重复。✅
- **定时任务**：`FeigeArrivalJob` + `FeigeUnclaimedExpireJob`（Quartz 每分钟，Asia/Shanghai），实测过期扫描生效。✅

---

## 3. 与初版（V1.0）不兼容项
`letterId` 公开 → `shareToken`；`WAITING_RECIPIENT` → `FLYING_UNCLAIMED`；结算时机拆信 → 抵达；`bind` 入参 `letterId` → `shareToken`；新增 `share-preview`/`recall`；新增 `settled/settled_at/settle_*/share_token/claim_expire_time/claimed_at/recalled_at/expired_at`；响应新增 `errorKey`。详见 `contract.md` §7。

---

## 4. 测试数据清理
已删除：`feige_letter`/`feige_letter_event`/`feige_pigeon` 中 `sender_openid`/`openid` 以 `fg_%` 开头的全部测试数据（19 信 / 70 日志 / 20 鸽），三表现为空表，可直接进入真实使用。

## 5. 上线前待办
- 还原 `FeigeController.sign()`（当前为本地放行态）。
- 配置订阅消息模板 `FEIGE_ARRIVAL_TEMPLATE_ID`（当前空串，未配置则到点不推送、仅记日志）。

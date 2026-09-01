# 《飞鸽传书》独立后端 接口契约（V2.0 · 当前基线）

> 项目：`feige-pigeon`（SpringBoot 2.3.12 / JDK8；独立部署）
> 版本：**V2.0**（2026-09-01，对应 develop `88e1225`；含 V1.0 收尾：回信直达/往返关系/信箱/关闭结算）
> 基础地址：本地 `http://localhost:8098`；**测试环境 `http://test.soogif.com`**（= `110.40.183.197:8098`；`FG_DEV_LOGIN` 控制 dev/正式模式）
> 模块：`com.an.feige`（feige 飞鸽 + user 登录/注册 + common）
> 建库：`src/main/resources/sql/feige_schema.sql`（新库 `feige_pigeon`；存量库升级见文件尾部 ALTER）
> 登录：自研 `WeChatClient`（jscode2session/订阅推送），不做 union 绑定
> 签名：`sign = md5(openid + sign-secret)`；写操作（send/bind/recall/reply/subscribe）请求头带 `sign`

---

## 1. 统一约定

- 返回信封 `{ code, msg, data, errorKey }`；`code==200` 成功，非 200 附稳定 `errorKey`（前端勿依赖中文 `msg`）。
- 日期 `yyyy-MM-dd HH:mm:ss`（GMT+8）；JSON 用 FastJSON 序列化。
- **身份**：登录换取 `openid` + `sign`；业务请求用登录返回的 openid（**dev 模式 openid 是派生值，非 jsCode 本身**）。
- **路径**：业务接口前缀 `/feige`（**2026-09 起已去掉 `/small-soogif`**），登录 `/api/auth`。
- **入参格式**：send / bind / reply 为 **JSON body**（`Content-Type: application/json`）；其余 GET/form。
- **坐标规则**：`lat/lng` 可选；缺失时按 `province+city` 从内置行政区划坐标表（31省+341市）兜底；省市也无 → `INVALID_ARGUMENT`。
- **最短旅程**：飞行时长保底 5 分钟（同城/近距离不立即送达）。
- 公开分享参数为 **`shareToken`**；正文/标题/落款/精确坐标仅拆信后返回。

### errorKey
`INVALID_ARGUMENT` `INVALID_SIGNATURE` `LETTER_NOT_FOUND` `ALREADY_CLAIMED` `CLAIM_EXPIRED`
`LETTER_RECALLED` `SENDER_CANNOT_CLAIM` `RECALL_TOO_EARLY` `RECALL_NOT_ALLOWED`
`NOT_ARRIVED` `ACCESS_DENIED` `PIGEON_BUSY` `WECHAT_LOGIN_FAILED` `ACCOUNT_DISABLED` `USER_NOT_FOUND`

---

## 2. 注册/登录（`/api/auth`）

### `GET /api/auth/wechat-login` — 微信登录（注册+登录一体）
- 入参：`jsCode*`（`wx.login` 的 code）、`grantType?`
- 流程：`jscode2session` 换取 `openid/session_key` → 按 `openid` 查/建 `fg_user` → 刷新 `session_key`
- 出参：
  ```json
  { "code":200, "data":{ "openid":"..", "userId":.., "sessionKey":"..",
      "nickname":"飞鸽用户xxx", "face":"", "sign":"<md5(openid+sign-secret)>",
      "isSendLetter": 0 } }
  ```
- `isSendLetter`：0-未寄过信，1-已寄过信（查 feige_letter 发件记录）。**V2 新增**。
- **dev 模式**：`FG_DEV_LOGIN=true` 时 `openid = "dev_"+md5(jsCode)`，不调微信（本地联调用）；`false` 走真实微信。生产必须 `false`。
- 错误：`INVALID_ARGUMENT` `WECHAT_LOGIN_FAILED` `ACCOUNT_DISABLED`

### `POST /api/auth/update-profile` — 更新资料
- 入参：`openid*` `nickname?` `face?` `mobile?`
- 出参：`{ openid, userId, nickname, face, mobile }`
- 错误：`USER_NOT_FOUND`

---

## 3. 信件状态机（`/feige`）

```
send(departure_time=server now, status=FLYING_UNCLAIMED, thread_id=自身)
   ├─ 认领(claim_expire_time>now, 原子) ─> IN_FLIGHT ─(arrival_time<=now)─> ARRIVED ─(拆信)─> DELIVERED
   ├─ 发件人召回(≥30min, 未认领) ─> RECALLED(终态, 分享失效)
   └─ 72h 未认领 ─> UNCLAIMED_EXPIRED(终态, 分享失效)

reply(原信DELIVERED, 收件人) ─> IN_FLIGHT(直达, 预绑定原发件人, 不再认领/分享) ─> ARRIVED ─> DELIVERED
```
- `departure_time` 发送即起飞、不可改；认领按原始起飞时间算时长/抵达。
- 飞行时长 `= max(distance/177, 5分钟)`；回信同理（回信人坐标 → 原发件人坐标）。
- 抵达即：`ARRIVED` + 鸽子 `IDLE` + 结算旅程数据（**V1 不结算等级/经验**，`settle*` 恒 0）。
- 拆信：仅收件人 `read=1` 与 `ARRIVED→DELIVERED`；不结算成长。
- 往返关系：`thread_id`（首信=自身，回信=原信 thread）、`reply_to_letter_id`（回信指向原信）。

---

## 4. 飞鸽接口（`/feige`，写操作需 `sign` 头）

### `POST /feige/letter/send` — 写信并放飞（JSON body）
入参：
```json
{ "openid":"*", "title":"?", "content":"*", "imageUrl":"?", "province":"?", "city":"?",
  "lat":?, "lng":?, "signature":"?", "pigeonId":? }
```
（`title`/`signature` ≤64 字；`content` ≤500 字；坐标可选，缺省城市兜底）
出参：`{ letterId, shareToken, status:"FLYING_UNCLAIMED", departureTime, claimExpireTime, serverTime, pigeon:{name,level,speedKmh}, senderCity }`
错误：`INVALID_ARGUMENT` `INVALID_SIGNATURE` `PIGEON_BUSY`

### `GET /feige/letter/share-preview` — 分享预览（不产生状态变更，不返回正文/坐标/标题/落款）
入参：`shareToken* openid?`
出参：`{ claimStatus:"AVAILABLE|CLAIMED_BY_ME|CLAIMED_BY_OTHER|RECALLED|EXPIRED", letterId, senderProvince, senderCity, pigeonName, serverTime }`
错误：`LETTER_NOT_FOUND`

### `POST /feige/letter/bind` — 原子认领（JSON body）
入参：`{ shareToken:"*", openid:"*", province:"?", city:"?", lat:?, lng:? }`
出参：`{ letterId, status, distanceKm, flightHours, departureTime, arrivalTime, serverTime, progress, firstOpenCase:"JUST_DEPARTED|ALREADY_FLYING|ARRIVED_WAITING", waitingDurationSeconds }`
错误：`LETTER_NOT_FOUND` `SENDER_CANNOT_CLAIM` `LETTER_RECALLED` `CLAIM_EXPIRED` `ALREADY_CLAIMED` `INVALID_ARGUMENT` `INVALID_SIGNATURE`
（并发：仅一人成功；获胜者同参重试幂等返回当前航程。）

### `POST /feige/letter/recall` — 发件人免费召回（form）
入参：`letterId* openid*`
条件：`sender` + `FLYING_UNCLAIMED` + `now>=departure+30min` + `now<claim_expire_time` + `recipient IS NULL`
出参：`{ recalled:true, recalledAt }`
错误：`LETTER_NOT_FOUND` `ACCESS_DENIED` `RECALL_TOO_EARLY` `CLAIM_EXPIRED` `RECALL_NOT_ALLOWED` `ALREADY_CLAIMED`

### `GET /feige/letter/flight` — 飞行页（发件人/收件人皆可见）
入参：`letterId* openid*`
未认领：`{ status:FLYING_UNCLAIMED, departureTime, claimExpireTime, serverTime, progress:null, flownKm:null, remainKm:null, totalKm:null, arrivalTime:null, canRecall, subscribed, flightLog:[] }`
已认领/回信：`{ status, departureTime, arrivalTime, serverTime, distanceKm, progress, flownKm, remainKm, totalKm, flightLog, subscribed }`
错误：`LETTER_NOT_FOUND` `ACCESS_DENIED`

### `GET /feige/letter/detail` — 收信/拆信
入参：`letterId* openid*`
仅 `ARRIVED/DELIVERED` 返回正文；返回 `title`/`signature`（**V2 新增**）与 `settleLevelUp/settleExpDelta/settleLevelBefore/After`（**恒 0，V1 不结算经验**）；发件人看正文不触发已读。
错误：`LETTER_NOT_FOUND` `ACCESS_DENIED` `NOT_ARRIVED`

### `POST /feige/letter/reply` — 回信（JSON body，**V2：直达**）
入参：
```json
{ "openid":"*", "title":"?", "content":"*", "imageUrl":"?", "province":"?", "city":"?",
  "lat":?, "lng":?, "signature":"?", "letterId":"*" }
```
仅原收件人、原信件 `DELIVERED`；**直达**：status=IN_FLIGHT、计算航程（5分钟保底）、预绑定收件人=原发件人、写往返关系，不经过认领/分享。
出参（**与 send 对齐**）：`{ letterId, newLetterId(兼容), shareToken, status:"IN_FLIGHT", departureTime, arrivalTime, distanceKm, flightHours, serverTime, pigeon:{name,level,speedKmh}, senderCity }`
错误：`LETTER_NOT_FOUND` `NOT_ARRIVED` `ACCESS_DENIED` `PIGEON_BUSY` `INVALID_SIGNATURE`

### `POST /feige/letter/subscribe` — 订阅到达通知（form）
入参：`letterId* openid*`
已抵达返回 `{ subscribed:false }`（无需订阅）；否则 `{ subscribed:true }`。
错误：`LETTER_NOT_FOUND` `ACCESS_DENIED` `INVALID_SIGNATURE`

### `GET /feige/letter/list` — 信箱列表（**V2 新增**）
入参：`openid* type?(inbox|sent, 默认inbox) page?(默认0) size?(默认20, ≤50)`
- inbox（来信）：`recipient_openid=openid`，排序 `ARRIVED > IN_FLIGHT > DELIVERED > RECALLED > 其他`
- sent（寄出）：`sender_openid=openid`，按时间倒序
出参：`{ total, page, size, list:[{ letterId, shareToken, status, title, senderCity, recipientCity, departureTime, arrivalTime, createAt, threadId, replyToLetterId, canRecall }] }`
错误：`INVALID_ARGUMENT`

### `GET /feige/pigeon/mine` — 我的鸽子（无 sign）
入参：`openid*`
出参：`{ name, level, exp, expNext, speedKmh, stamina, deliveredCount, totalMileage, farthestDistance, status, motto }`
（注：level/exp 保留字段恒为初始值——V1 不结算等级经验）

---

## 5. 定时任务（Quartz，每 1 分钟，Redis 分布式锁互斥）

- `FeigeArrivalJob`：`IN_FLIGHT AND arrival_time<=now` → `ARRIVED` + 鸽子 `IDLE` + 旅程数据结算（无经验）+ 到达通知。
- `FeigeUnclaimedExpireJob`：`FLYING_UNCLAIMED AND claim_expire_time<=now` → `UNCLAIMED_EXPIRED` + 鸽子 `IDLE`。
- 多副本部署需 `FG_LOCK_ENABLED=true` + Redis（`FG_REDIS_HOST/PORT/PASSWORD`），锁 TTL 默认 30s（`FG_LOCK_TTL_SECONDS`）。

---

## 6. 数据表（新库 `feige_pigeon`）

- `fg_user`：openid/session_key/nickname/face/mobile/app_type/status
- `feige_pigeon`：openid/name/level/exp/speed_kmh/stamina/delivered_count/total_mileage/farthest_distance/status
- `feige_letter`：letter_id/share_token/sender_*/recipient_*/title/signature/content/image_url/pigeon_id/pigeon_name/speed_kmh/distance_km/flight_hours/departure_time/arrival_time/claim_expire_time/claimed_at/recalled_at/expired_at/status/settled/settled_at/settle_*/subscribed/notified/read/**thread_id/reply_to_letter_id**/create_at/update_at
- `feige_letter_event`：letter_id/seq/type/title/description/at_time；`UNIQUE(letter_id,seq)`

**存量升级**（已有库执行一次）：见 `feige_schema.sql` 尾部 ALTER（title/signature/thread_id/reply_to_letter_id + 首信 thread 回填）。

---

## 7. 并发/幂等
原子认领(条件UPDATE)、召回vs认领(条件UPDATE互斥)、并发发送(`FOR UPDATE`+`markSending`)、单次结算(`settled=0`)、通知不重复(`notified=0`)、日志(`UNIQUE(letter_id,seq)`)、回信直达无认领竞争、定时任务 Redis 分布式锁（多副本互斥）。

---

## 8. 变更记录

| 版本 | 日期 | 变更 |
|---|---|---|
| V2.0 | 2026-09-01 | 路径去 `/small-soogif`；send/bind/reply 改 JSON；新增 title/signature、isSendLetter、坐标兜底、5分钟保底、回信直达、往返字段、信箱列表；关闭等级/经验结算 |
| V1.3 | 2026-08-27 | 旧版契约（路径含 /small-soogif，form 入参，无 V2 字段） |

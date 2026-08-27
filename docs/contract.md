# 《飞鸽传书》独立后端 接口契约（V1.3 · 含测试环境路径）

> 项目：`feige-pigeon`（SpringBoot 2.3.12 / JDK8；独立部署）
> 基础地址：本地 `http://localhost:8080`；**测试环境 `http://110.40.183.197:8098`**（`dev-login` 开启，端口由 `FG_PORT` 决定，上下文 `/`）
> 模块：`com.an.feige`（feige 飞鸽 + user 登录/注册 + common）
> 建库：`src/main/resources/sql/feige_schema.sql`（新库 `feige_pigeon`）
> 登录：自研 `WeChatClient`（jscode2session/订阅推送），不做 union 绑定
> 签名：`sign = md5(openid + sign-secret)`；dev 模式可免真实微信凭据

---

## 1. 统一约定

- 返回信封 `{ code, msg, data, errorKey }`；`code==200` 成功，非 200 附稳定 `errorKey`（前端勿依赖中文 `msg`）。
- 日期 `yyyy-MM-dd HH:mm:ss`（GMT+8）；JSON 用 FastJSON 序列化。
- **身份**：登录换取 `openid`；写操作请求头带 `sign`（来自登录返回，或 `md5(openid+sign-secret)`）。
- 公开分享参数为 **`shareToken`**；正文与精确坐标仅在拆信后返回。

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
      "nickname":"飞鸽用户xxx", "face":"", "sign":"<md5(openid+sign-secret)>" } }
  ```
- **dev 模式**：`feige.wechat.dev-login=true`（或环境变量 `FG_DEV_LOGIN=true`）时，直接用 `openid = "dev_"+md5(jsCode)`，不调微信，返回同结构 + `devLogin`。本地联调免真实凭据。
- 错误：`INVALID_ARGUMENT` `WECHAT_LOGIN_FAILED` `ACCOUNT_DISABLED`

### `POST /api/auth/update-profile` — 更新资料
- 入参：`openid*` `nickname?` `face?` `mobile?`
- 出参：`{ openid, userId, nickname, face, mobile }`
- 错误：`USER_NOT_FOUND`

---

## 3. 信件状态机（`/small-soogif/feige`）

```
send(departure_time=server now, status=FLYING_UNCLAIMED)
   ├─ 认领(claim_expire_time>now) ─> IN_FLIGHT ─(arrival_time<=now)─> ARRIVED ─(拆信)─> DELIVERED
   ├─ 发件人召回(≥30min,未认领) ─> RECALLED(终态, 分享失效)
   └─ 72h 未认领 ─> UNCLAIMED_EXPIRED(终态, 分享失效)
```
- `departure_time` 发送即起飞、不可改；认领按原始起飞时间算时长/抵达。
- 抵达即：`ARRIVED` + 鸽子 `IDLE` + 一次性结算（`settled=1`）。
- 拆信：仅收件人 `read=1` 与 `ARRIVED→DELIVERED`；不结算成长。

---

## 4. 飞鸽接口（`/small-soogif/feige`，写操作需 `sign` 头）

### `POST /letter/send` — 写信并放飞
入参：`openid* content* imageUrl? province city lat* lng* pigeonId?`
出参：`{ letterId, shareToken, status:"FLYING_UNCLAIMED", departureTime, claimExpireTime, serverTime, pigeon:{name,level,speedKmh}, senderCity }`
错误：`INVALID_ARGUMENT` `INVALID_SIGNATURE` `PIGEON_BUSY`

### `GET /letter/share-preview` — 分享预览（不产生状态变更，不返回正文/坐标）
入参：`shareToken* openid?`
出参：`{ claimStatus:"AVAILABLE|CLAIMED_BY_ME|CLAIMED_BY_OTHER|RECALLED|EXPIRED", letterId, senderProvince, senderCity, pigeonName, serverTime }`
错误：`LETTER_NOT_FOUND`

### `POST /letter/bind` — 原子认领
入参：`shareToken* openid* province city lat* lng*`
出参：`{ letterId, status, distanceKm, flightHours, departureTime, arrivalTime, serverTime, progress, firstOpenCase:"JUST_DEPARTED|ALREADY_FLYING|ARRIVED_WAITING", waitingDurationSeconds }`
错误：`LETTER_NOT_FOUND` `SENDER_CANNOT_CLAIM` `LETTER_RECALLED` `CLAIM_EXPIRED` `ALREADY_CLAIMED` `INVALID_ARGUMENT` `INVALID_SIGNATURE`
（并发：仅一人成功；获胜者同参重试幂等返回当前航程。）

### `POST /letter/recall` — 发件人免费召回
入参：`letterId* openid*`
条件：`sender` + `FLYING_UNCLAIMED` + `now>=departure+30min` + `now<claim_expire_time` + `recipient IS NULL`
出参：`{ recalled:true, recalledAt }`
错误：`LETTER_NOT_FOUND` `ACCESS_DENIED` `RECALL_TOO_EARLY` `CLAIM_EXPIRED` `RECALL_NOT_ALLOWED` `ALREADY_CLAIMED`

### `GET /letter/flight` — 飞行页（发件人/收件人皆可见）
入参：`letterId* openid*`
未认领：`{ status:FLYING_UNCLAIMED, departureTime, claimExpireTime, serverTime, progress:null, flownKm:null, remainKm:null, totalKm:null, arrivalTime:null, canRecall, subscribed, flightLog:[] }`
已认领：`{ status, departureTime, arrivalTime, serverTime, distanceKm, progress, flownKm, remainKm, totalKm, flightLog, subscribed }`
错误：`LETTER_NOT_FOUND` `ACCESS_DENIED`

### `GET /letter/detail` — 收信/拆信
入参：`letterId* openid*`
仅 `ARRIVED/DELIVERED` 返回正文；返回抵达阶段保存的快照 `settleExpDelta/settleLevelBefore/settleLevelAfter/settleLevelUp`；发件人看正文不触发已读。
错误：`LETTER_NOT_FOUND` `ACCESS_DENIED` `NOT_ARRIVED`

### `POST /letter/subscribe` — 订阅到达通知
入参：`letterId* openid*`
已抵达返回 `{ subscribed:false }`（无需订阅）；否则 `{ subscribed:true }`。
错误：`LETTER_NOT_FOUND` `ACCESS_DENIED` `INVALID_SIGNATURE`

### `POST /letter/reply` — 回信
入参：`openid* content* imageUrl? province city lat* lng* letterId*`
仅原收件人、原信件 `DELIVERED`；生成一封反向新信（预指定收件人=原发件人）。
出参：`{ newLetterId, shareToken }`
错误：`LETTER_NOT_FOUND` `NOT_ARRIVED` `ACCESS_DENIED` `PIGEON_BUSY` `INVALID_SIGNATURE`

### `GET /pigeon/mine` — 我的鸽子（无 sign）
入参：`openid*`
出参：`{ name, level, exp, expNext, speedKmh, stamina, deliveredCount, totalMileage, farthestDistance, status, motto }`

---

## 5. 定时任务（Quartz，每 1 分钟）

- `FeigeArrivalJob`：`IN_FLIGHT AND arrival_time<=now` → `ARRIVED` + 鸽子 `IDLE` + 幂等结算 + 通知。
- `FeigeUnclaimedExpireJob`：`FLYING_UNCLAIMED AND claim_expire_time<=now` → `UNCLAIMED_EXPIRED` + 鸽子 `IDLE`。

---

## 6. 数据表（新库 `feige_pigeon`）

- `fg_user`：openid/session_key/nickname/face/mobile/app_type/status
- `feige_pigeon`：openid/name/level/exp/speed_kmh/stamina/delivered_count/total_mileage/farthest_distance/status
- `feige_letter`：letter_id/share_token/sender_*/recipient_*/content/image_url/pigeon_id/pigeon_name/speed_kmh/distance_km/flight_hours/departure_time/claim_expire_time/claimed_at/recalled_at/expired_at/arrival_time/status/settled/settled_at/settle_*/subscribed/notified/read
- `feige_letter_event`：letter_id/seq/type/title/description/at_time；`UNIQUE(letter_id,seq)`

---

## 7. 并发/幂等
原子认领(条件UPDATE)、召回vs认领(条件UPDATE互斥)、并发发送(`FOR UPDATE`+`markSending`)、单次结算(`settled=0`)、通知不重复(`notified=0`)、日志(`UNIQUE(letter_id,seq)`)。

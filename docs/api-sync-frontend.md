# 飞鸽传书 接口同步文档（V2 版本改动，前端对接用）

> 更新日期：2026-09-01 ｜ 对应后端版本：develop @ 23ba894
> 本文档列出前端需要同步的全部接口与规则变更。**旧路径 /small-soogif 已废弃**，请全部替换。

---

## 一、全局变更

| 变更项 | 旧 | 新 | 说明 |
|---|---|---|---|
| **接口路径前缀** | `/small-soogif/feige` | `/feige` | 所有业务接口去掉了 `/small-soogif`，登录接口 `/api/auth/*` 不变 |
| **请求域名** | 测试 `http://demo.soogif.com` | 不变 | 小程序正式版需 https + 合法域名配置 |
| **签名规则** | 不变 | 不变 | `sign = md5(openid + sign-secret)`，写接口（send/bind/recall/reply/subscribe）请求头带 `sign`，登录接口返回 |

**经纬度规则（重要）**：send / bind / reply 三个接口的 `lat`、`lng` 改为**可选**。
- 传了 → 用精确坐标
- 没传 → 后端按 `province` + `city` 从内置行政区划坐标表（31省+约341城市）兜底取城市中心坐标
- 省、市都没传且没坐标 → 报 `INVALID_ARGUMENT`（缺少定位信息）
- 前端仍建议正常上报 `wx.getLocation()` 的精确坐标（体验更好、距离更准），兜底只是容错

---

## 二、接口明细

### 1. 登录 `GET /api/auth/wechat-login`

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| jsCode | string | 是 | wx.login 的 code（dev 模式任意值） |
| grantType | string | 否 | 保留 |

**新增返回字段**：

```json
{
  "code": 200,
  "data": {
    "openid": "dev_xxx",
    "userId": 1,
    "sessionKey": "xxx",
    "nickname": "飞鸽用户xxx",
    "face": "",
    "sign": "md5(openid+secret)",
    "isSendLetter": 0
  }
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `isSendLetter` | int | **新增**：0-未寄过信，1-已寄过信（查 feige_letter 发件记录） |

---

### 2. 写信放飞 `POST /feige/letter/send`  ⚠️ 改 RequestBody

**请求**（JSON，`Content-Type: application/json`，请求头 `sign`）：

```json
{
  "openid": "dev_xxx",
  "title": "想你的第一天",        // 新增，可选，≤64字
  "content": "正文内容……",        // 必填，≤500字
  "imageUrl": "",                 // 可选
  "province": "广东",             // 可选（兜底用）
  "city": "广州",                 // 可选（兜底用）
  "lat": 23.1291,                 // 可选（精确坐标优先）
  "lng": 113.2644,                // 可选
  "signature": "你的朋友",         // 新增，可选，≤64字
  "pigeonId": null                // 可选，指定鸽子
}
```

**响应**：

```json
{
  "code": 200,
  "data": {
    "letterId": "FGxxx",
    "shareToken": "STxxx",
    "status": "FLYING_UNCLAIMED",
    "departureTime": "2026-09-01 00:13:22",
    "claimExpireTime": "2026-09-04 00:13:22",
    "serverTime": "2026-09-01 00:13:22",
    "pigeon": {"name": "小白", "level": 1, "speedKmh": 177.00},
    "senderCity": "广东 · 广州"
  }
}
```

---

### 3. 收件人认领 `POST /feige/letter/bind`  ⚠️ 改 RequestBody

**请求**（JSON，请求头 `sign`）：

```json
{
  "shareToken": "STxxx",
  "openid": "dev_yyy",
  "province": "广东",       // 可选（兜底用）
  "city": "佛山",           // 可选
  "lat": 23.0218,           // 可选
  "lng": 113.1219           // 可选
}
```

**响应**：`{letterId, status(IN_FLIGHT/ARRIVED), distanceKm, flightHours, departureTime, arrivalTime, serverTime, progress, firstOpenCase, waitingDurationSeconds}`

> `firstOpenCase`：`JUST_DEPARTED`(起飞瞬间) / `ALREADY_FLYING`(飞行中) / `ARRIVED_WAITING`(已到等待拆信)

---

### 4. 回信 `POST /feige/letter/reply`  ⚠️ 改 RequestBody + 新增字段

**请求**（JSON，请求头 `sign`）：

```json
{
  "openid": "dev_yyy",
  "title": "回信标题",          // 新增，可选，≤64字
  "content": "回信正文……",      // 必填，≤500字
  "imageUrl": "",
  "province": "广东",           // 可选
  "city": "佛山",
  "lat": 23.0218,
  "lng": 113.1219,
  "signature": "回信人",         // 新增，可选，≤64字
  "letterId": "FG原信id"        // 必填
}
```

**响应（⚠️ 已对齐 send，新增起飞信息）**：

```json
{
  "code": 200,
  "data": {
    "letterId": "FG新信id",
    "newLetterId": "FG新信id",   // 兼容旧字段，可忽略
    "shareToken": "STxxx",
    "status": "FLYING_UNCLAIMED",
    "departureTime": "2026-09-01 00:20:00",
    "claimExpireTime": "2026-09-04 00:20:00",
    "serverTime": "2026-09-01 00:20:00",
    "pigeon": {"name": "小白", "level": 1, "speedKmh": 177.00},
    "senderCity": "广东 · 佛山"
  }
}
```

---

### 5. 拆信 `GET /feige/letter/detail`  ⚠️ 新增返回字段

参数不变：`letterId`、`openid`（query）。

**新增返回字段**：

```json
{
  "code": 200,
  "data": {
    "letterId": "FGxxx",
    "title": "想你的第一天",        // 新增
    "signature": "你的朋友",        // 新增
    "content": "正文内容……",
    "imageUrl": "",
    "senderProvince": "广东",
    "senderCity": "广州",
    "arriveTime": "2026-09-01 00:19:59",
    "flightHours": 0.11,
    "settleExpDelta": 1,
    "settleLevelBefore": 1,
    "settleLevelAfter": 1,
    "settleLevelUp": 0,
    "canReply": true
  }
}
```

---

### 6. 其他接口（无变化）

| 接口 | 方法 | 参数 |
|---|---|---|
| 分享预览 `GET /feige/letter/share-preview` | GET | shareToken, openid |
| 召回 `POST /feige/letter/recall` | POST | letterId, openid（sign） |
| 飞行页 `GET /feige/letter/flight` | GET | letterId, openid |
| 订阅到达 `POST /feige/letter/subscribe` | POST | openid, letterId（sign） |
| 我的鸽子 `GET /feige/pigeon/mine` | GET | openid |

---

## 三、前端改造清单

1. **全部接口路径**：`/small-soogif/feige/xxx` → `/feige/xxx`（正则替换即可）
2. **send / bind / reply 三个接口**：form-urlencoded → **JSON body**，加 `Content-Type: application/json`
3. **写信页**：新增「标题」「落款」输入项，随 send 提交
4. **回信页**：新增「标题」「落款」输入项，随 reply 提交
5. **拆信页**：展示 detail 返回的 `title`、`signature`
6. **首页/登录后**：用 `isSendLetter` 判断是否展示「寄信引导」类 UI（0 未寄过可引导，1 已寄过不打扰）
7. **坐标上报**：保持现有 `wx.getLocation()` 逻辑即可（兜底是后端容错，前端不强制改）

## 四、联调注意事项

- 测试环境 `FG_DEV_LOGIN=true`：任意 jsCode 可登录，登录返回的 `openid` 是**派生值**（非 jsCode 本身），业务请求必须用返回的 openid + sign
- 写接口全部要求请求头 `sign`，缺失/错误返回 `{"code":401,"errorKey":"INVALID_SIGNATURE"}`
- 错误统一格式：`{code, msg, errorKey, data}`；常见 errorKey：`INVALID_ARGUMENT`(400) / `INVALID_SIGNATURE`(401) / `ACCESS_DENIED`(403) / `LETTER_NOT_FOUND`(404) / `NOT_ARRIVED`(404) / `SENDER_CANNOT_CLAIM` / `ALREADY_CLAIMED` / `PIGEON_BUSY`(203)

---

## 五、信件与鸽子状态机（前端展示用）

### 1. 信件（feige_letter.status）状态流转

```
                      ┌─────────────── 30分钟后可召回 ───────────────┐
                      │                                            ▼
   写信send ──► FLYING_UNCLAIMED (已起飞未认领)              RECALLED (发件人召回,终态)
                      │                                            ▲
                      │ 72小时无人认领                              │ POST /letter/recall(30min后)
                      ▼                                            │
              UNCLAIMED_EXPIRED (自动过期,终态)                    │
                      │                                            │
                      │ 收件人bind认领                              │
                      ▼                                            │
                 IN_FLIGHT (已认领,飞行中)                          │
                      │ 预计到达(arrival_time)后, ArrivalJob每分钟扫描推进
                      ▼                                            │
                   ARRIVED (已抵达未拆信)                           │
                      │ 收件人 GET /letter/detail 拆信              │
                      ▼                                            │
                  DELIVERED (已拆信,终态) ──► 收件人可回信 reply ──► (新信件从头流转)
```

**各状态含义与前端表现**：

| 状态 | 含义 | 谁可见/操作 | 前端表现 |
|---|---|---|---|
| `FLYING_UNCLAIMED` | 已起飞、无人认领 | 发件人（认领页/飞行页） | 显示"等待有缘人认领"，30 分钟后可点"召回" |
| `IN_FLIGHT` | 已被认领、飞行中 | 发件人+收件人 | 显示飞行进度（progress 0~1）、预计到达时间 |
| `ARRIVED` | 已抵达、未拆信 | 收件人 | "信已送达"，可拆信（detail 才返回正文/标题/落款） |
| `DELIVERED` | 已拆信（终态） | 收件人 | 展示全文，可回信（canReply=true） |
| `RECALLED` | 发件人召回（终态） | 发件人 | 显示"已召回"，鸽子已释放可再寄 |
| `UNCLAIMED_EXPIRED` | 72h 无人认领过期（终态） | 发件人 | 显示"信鸽飞走了"（信消失），鸽子自动释放 |

**时间节点**（从起飞 departure_time 起算）：
- **30 分钟**：可免费召回（`POST /letter/recall`），之前调用返回 `RECALL_TOO_EARLY`
- **72 小时**：无人认领自动过期（ArrivalJob 每分钟扫描），信鸽自动释放回 IDLE
- 认领后：`arrival_time = departure_time + flight_hours`，到达后由 ArrivalJob 推进为 ARRIVED（同城约 5 分钟、跨城按距离/速度）

### 2. 鸽子（feige_pigeon.status）状态流转

| 状态 | 含义 | 触发 |
|---|---|---|
| `IDLE` | 空闲，可寄信 | 初始/送达/召回/过期释放后 |
| `SENDING` | 送信中 | 写信 send / 回信 reply 时占用 |
| `LOST` | 丢失（预留） | 当前未使用 |

**鸽子释放回 IDLE 的时机**：
1. 信件**送达**（ARRIVED 推进时结算成长并释放）
2. 信件被**召回**（RECALLED）
3. 信件**72h 过期**（UNCLAIMED_EXPIRED）
4. 回信同样占用鸽子，送达后释放

**前端要点**：
- `pigeon/mine` 返回当前鸽子及 status；`SENDING` 时不能发信（send 返回 `PIGEON_BUSY`）
- 同一用户同时只有一只鸽子，一次只能送一封信；鸽子送信期间想再发信会收到 `PIGEON_BUSY`（code 203）

### 3. 接口与状态对应速查

| 想做什么 | 接口 | 状态前提 |
|---|---|---|
| 写信放飞 | `POST /letter/send` | 鸽子 IDLE |
| 认领 | `POST /letter/bind` | 信 FLYING_UNCLAIMED 且未过期 |
| 召回 | `POST /letter/recall` | 信 FLYING_UNCLAIMED 且 ≥30 分钟 |
| 拆信 | `GET /letter/detail` | 信 ARRIVED/DELIVERED 且为收件人 |
| 回信 | `POST /letter/reply` | 原信 DELIVERED 且为收件人、鸽子 IDLE |
| 查飞行 | `GET /letter/flight` | 任意参与方 |

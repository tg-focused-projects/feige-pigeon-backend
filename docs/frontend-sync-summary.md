# 飞鸽传书 前端同步文档（总览版）

> 同步日期：2026-09-01 ｜ 后端基线：develop @ 487110d
> 面向：前端开发/产品 ｜ 涵盖：接口现状 + 已决议事项 + 版本规划（前端配合点）
> 详细接口字段见 `api-sync-frontend.md`；规格对齐见 `spec-vs-implementation.md`；规划见 `version-plan.md`

---

## 一、当前已实现（前端可直接对接）

### 接口一览

| 接口 | 方法 | 入参 | 说明 |
|---|---|---|---|
| `/api/auth/wechat-login` | GET | jsCode | 登录，返回 openid/sign/**isSendLetter** |
| `/api/auth/update-profile` | POST | openid,nickname,face,mobile | 更新用户资料 |
| `/feige/letter/send` | POST | **JSON**：openid,title,content,imageUrl,province,city,lat,lng,signature,pigeonId | 写信放飞（发送即起飞） |
| `/feige/letter/share-preview` | GET | shareToken,openid | 分享安全预览（不泄露正文） |
| `/feige/letter/bind` | POST | **JSON**：shareToken,openid,province,city,lat,lng | 原子认领 |
| `/feige/letter/recall` | POST | letterId,openid | 召回（30分钟后） |
| `/feige/letter/flight` | GET | letterId,openid | 飞行状态（进度/首态） |
| `/feige/letter/detail` | GET | letterId,openid | 拆信（抵达后） |
| `/feige/letter/reply` | POST | **JSON**：openid,title,content,imageUrl,province,city,lat,lng,signature,letterId | 回信 |
| `/feige/letter/subscribe` | POST | openid,letterId | 订阅到达通知（收件人） |
| `/feige/pigeon/mine` | GET | openid | 我的鸽子（单只） |

**路径注意**：已去掉 `/small-soogif` 前缀；send/bind/reply 为 **JSON body**（`Content-Type: application/json`）+ 请求头 `sign`。

### 核心规则（已实现）

- **发送即起飞**：send 成功即 FLYING_UNCLAIMED，departure_time=服务器时间，不可修改
- **最短旅程 5 分钟**：同城/近距离飞行时长保底 5 分钟（不会立即送达）
- **认领互斥**：第一个完成城市确认的人获得信件；发件人不能认领自己；重复认领幂等
- **召回**：起飞 30 分钟后可免费召回；72 小时无人认领自动过期
- **不显示已读回执**：发件人只能看到 等待认领/飞行中/已抵达/已被收下
- **回信**：已预绑定原发件人，不需要分享/认领
- **坐标兜底**：lat/lng 可选，缺省按 province+city 从内置城市坐标表（341 市）取中心点
- **登录**：任意 jsCode 联调（dev 模式）或真实微信登录（正式模式，由 `FG_DEV_LOGIN` 控制）

---

## 二、已决议事项（前端必须知晓）

| # | 决议 | 对前端的影响 |
|---|---|---|
| D1 | **内容安全审核由前端直连外部接口**，后端 send 接口不做内容校验 | 前端在调 send 前自行调审核接口；审核通过后才放飞；send 成功即起飞，无审核回调 |
| D2 | **关闭等级/经验结算**（按规格 14.1） | detail 返回的 `settleLevelUp/settleExpDelta/settleLevelBefore/After` **恒为 0**，前端可隐藏相关 UI（成长/升级提示）；鸽子速度固定 177km/h 不成长 |
| D3 | **静默登录维持 openid + sign 方案** | 不变：写接口请求头带 `sign`（登录返回），openid 为登录返回值 |
| D4 | 回信/发送返回体已对齐：`letterId`（newLetterId 保留兼容）、status、departureTime、claimExpireTime、serverTime、pigeon、senderCity | 前端可用统一结构处理 send/reply 结果 |

---

## 三、版本规划（前端配合/预留点）

### V1.0 收尾（后端开发中，接口近期会新增）

| # | 后端规划 | 前端配合点 |
|---|---|---|
| P1 | **回信直达修复**：回信改为 IN_FLIGHT 直达（当前回信可能永远到不了，属缺陷修复） | 回信后前端可直接进飞行页展示航程（有 arrivalTime），不需要分享/认领 UI；**接口响应不变**，但 flight 查询回信会有完整航程数据 |
| P2 | **信箱列表接口**：`GET /feige/letter/list?type=inbox\|sent` | 前端信箱页对接：来信（已抵达未接 > 正在飞来 > 历史）、寄出（等待认领/飞行中/已抵达/已被收下/已召回/已过期） |
| P3 | **往返关系字段**：feige_letter 增加 thread_id/reply_to_letter_id | 信箱时间线/叠放展示可依据往返关系；列表接口会返回 |
| P4 | 关闭结算代码已完成 | 随部署生效，前端 UI 无需改动（字段恒 0） |

### V1.1（规划中，接口新增）

| # | 后端规划 | 前端配合点 |
|---|---|---|
| P5 | **投诉入口**：`POST /feige/report` | 前端需做投诉页（不当内容/骚扰诈骗/侵犯隐私/其他） |
| P6 | **发件人订阅**：飞行页发件人可订阅到达通知「到了叫我」 | 前端飞行页对发件人展示订阅入口（当前仅收件人可订阅） |
| P7 | **回信到达通知**：原发件人订阅回信抵达 | 前端回信页可选「有回信时告诉我」 |
| P8 | **6 只鸽子体系**：`GET /pigeon/list`、创建/选择、改名、履历 | 前端鸽舍页扩展：多鸽展示/选择/改名入口（当前仅单鸽小白） |
| P9 | **鸽子改名接口** | 首次送达后邀请改名 UI |
| P10 | **旅程履历接口**：`GET /pigeon/journeys` | 鸽子详情页：里程/送达次数/去过城市 |

### V1.2（付费，依赖虚拟支付资格）

| # | 后端规划 | 前端配合点 |
|---|---|---|
| P11 | 多鸽购买（第 2~6 只付费）、鸽舍扩建 | 前端鸽舍空位/候选角色/支付页（需等微信虚拟支付资格） |

---

## 四、前端开发须知（联调要点）

1. **测试环境登录**：`FG_DEV_LOGIN` 当前为 **false**（正式模式），任意 jsCode 无法登录；联调需真实微信 code，或联系后端临时切 dev 模式
2. **sign 计算**：`md5(openid + sign-secret)`，secret 由登录接口的 sign 字段直接返回，前端**不需要自己算**，直接用登录返回的 sign 作为请求头即可
3. **时间字段**：全部为 `yyyy-MM-dd HH:mm:ss`（东八区）
4. **错误格式**：`{code, msg, errorKey, data}`；常见：`INVALID_SIGNATURE(401)` / `INVALID_ARGUMENT(400)` / `ACCESS_DENIED(403)` / `LETTER_NOT_FOUND(404)` / `NOT_ARRIVED(404)` / `SENDER_CANNOT_CLAIM` / `ALREADY_CLAIMED` / `PIGEON_BUSY(203)` / `RECALL_TOO_EARLY` / `CLAIM_EXPIRED`
5. **订阅消息**：`FG_ARRIVAL_TEMPLATE_ID` 尚未配置，到达通知暂不推送（功能已接好，模板到位即生效）

---

## 五、文档索引

| 文档 | 内容 |
|---|---|
| `docs/api-sync-frontend.md` | 接口字段级同步（V2 改动明细 + 状态机） |
| `docs/spec-vs-implementation.md` | 规格↔实现对齐报告 + 待决策点 |
| `docs/version-plan.md` | 后端版本规划（V1.0收尾/V1.1/V1.2） |
| 本文 | 前端总览：现状 + 决议 + 规划 + 配合点 |

# 《飞鸽传书》独立后端 接口契约（V6.1 · 当前基线）

> 项目：`feige-pigeon`（SpringBoot 2.3.12 / JDK8；独立部署）
> 版本：**V6.1**（2026-09-03，share-preview 返参新增 pigeonRoleKey；含 V6.0 及以下全部）
> 基础地址：本地 `http://localhost:8098`；**测试环境 `http://demo.soogif.com`**（= `110.40.183.197:8098`；`FG_DEV_LOGIN` 控制 dev/正式模式；⚠️ `test.soogif.com` 指向生产 TKE 集群，切勿用于测试）
> 模块：`com.an.feige`（feige 飞鸽 + user 登录/注册 + common）
> 建库：`src/main/resources/sql/feige_schema.sql`（新库 `feige_pigeon`；存量库升级见文件尾部 ALTER）
> 登录：自研 `WeChatClient`（jscode2session/订阅推送），不做 union 绑定
> 签名：`sign = md5(openid + sign-secret)`；写操作（send/bind/recall/reply/subscribe/report/rename/create/order/confirm）请求头带 `sign`

---

## 1. 统一约定

- 返回信封 `{ code, msg, data, errorKey }`；`code==200` 成功，非 200 附稳定 `errorKey`（前端勿依赖中文 `msg`）。
- 日期 `yyyy-MM-dd HH:mm:ss`（GMT+8）；JSON 用 FastJSON 序列化。
- **身份**：登录换取 `openid` + `sign`；业务请求用登录返回的 openid（**dev 模式 openid 是派生值，非 jsCode 本身**）。
- **路径**：业务接口前缀 `/feige`（**2026-09 起已去掉 `/small-soogif`**），登录 `/api/auth`。
- **入参格式**：send / bind / reply / report 为 **JSON body**（`Content-Type: application/json`）；其余 GET/form。
- **坐标规则**：`lat/lng` 可选；缺失时按 `province+city` 从内置行政区划坐标表（31省+341市）兜底；省市也无 → `INVALID_ARGUMENT`。
- **最短旅程**：飞行时长保底 5 分钟（同城/近距离不立即送达）。
- 公开分享参数为 **`shareToken`**；正文/标题/落款/精确坐标仅拆信后返回。
- **通知订阅（V3）**：按「信件+用户+类型」独立记录（feige_subscription 表），类型 `ARRIVAL`（当前鸽子抵达）/ `REPLY_ARRIVAL`（回信抵达）；替代信件级单字段 subscribed。
- **订阅模板（V3 接通）**：`FG_ARRIVAL_TEMPLATE_ID` / `FG_REPLY_ARRIVAL_TEMPLATE_ID`（当前均为 `PM7gZ6hVG8yOXGtcjWdsid2vmB_rKyt_ZtZJ7PfIdo4`，可用环境变量覆盖）。模板字段：`thing1` 昵称 / `time2` 时间(`yyyy-MM-dd HH:mm`) / `thing3` 通知事项 / `thing4` 温馨提醒。文案按订阅者区分：发件人「{鸽子名}已经抵达收信城市·信已经送到，可以查看这次旅程了」；收件人「一封给你的信已经抵达·{鸽子名}正在等你，回来接过这封信吧」；回信到达「有人给你回了一封信·你的信鸽带着回信抵达了，来看看吧」。

### errorKey
`INVALID_ARGUMENT` `INVALID_SIGNATURE` `LETTER_NOT_FOUND` `ALREADY_CLAIMED` `CLAIM_EXPIRED`
`LETTER_RECALLED` `SENDER_CANNOT_CLAIM` `RECALL_TOO_EARLY` `RECALL_NOT_ALLOWED`
`NOT_ARRIVED` `ACCESS_DENIED` `PIGEON_BUSY` `WECHAT_LOGIN_FAILED` `ACCOUNT_DISABLED` `USER_NOT_FOUND`
`ROLE_UNAVAILABLE` `PIGEON_NOT_FOUND` `RENAME_NOT_ALLOWED`

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
   ├─ 发件人召回(≥recall_grace 默认30min, 未认领) ─> RECALLED(终态, 分享失效)
   └─ 72h 未认领 ─> UNCLAIMED_EXPIRED(终态, 分享失效)

reply(原信DELIVERED, 收件人) ─> IN_FLIGHT(直达, 预绑定原发件人, 不再认领/分享) ─> ARRIVED ─> DELIVERED
```
- `departure_time` 发送即起飞、不可改；认领按原始起飞时间算时长/抵达。
- 飞行时长 `= max(distance/177, 5分钟)`；回信同理（回信人坐标 → 原发件人坐标）。
- 抵达即：`ARRIVED` + 鸽子 `IDLE` + 结算旅程数据（**V1 不结算等级/经验**，`settle*` 恒 0）。
- 拆信：仅收件人 `read=1` 与 `ARRIVED→DELIVERED`；不结算成长。
- 往返关系：`thread_id`（首信=自身，回信=原信 thread）、`reply_to_letter_id`（回信指向原信）。
- **到达通知（V3）**：抵达时按订阅表推送——普通信 `ARRIVAL`（发件人+收件人分别订阅）；回信 `REPLY_ARRIVAL`（原发件人订阅）。推送幂等（每订阅 notified 独立），模板未配置时静默跳过。

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

### `GET /feige/letter/share-preview` — 分享预览（不产生状态变更；返回发件落款与起飞时间，不返回正文/标题/精确坐标）
入参：`shareToken* openid?`
出参：`{ claimStatus:"AVAILABLE|CLAIMED_BY_ME|CLAIMED_BY_OTHER|RECALLED|EXPIRED", letterId, senderProvince, senderCity, **senderSignature**, **departureTime**, pigeonName, **pigeonRoleKey**(V6.1 新增), serverTime }`
（`senderSignature` 发件落款，可为空；`departureTime` 信件发出时间 `yyyy-MM-dd HH:mm:ss`；`pigeonRoleKey` 送信鸽子角色（XIAOBAI/PANGDUN/…），鸽子缺失或旧信无绑定为 null。规格6.1：认领前可展示落款与起飞时间。）
错误：`LETTER_NOT_FOUND`

### `POST /feige/letter/bind` — 原子认领（JSON body）
入参：`{ shareToken:"*", openid:"*", province:"?", city:"?", lat:?, lng:? }`
出参：`{ letterId, status, distanceKm, flightHours, departureTime, arrivalTime, serverTime, progress, firstOpenCase:"JUST_DEPARTED|ALREADY_FLYING|ARRIVED_WAITING", waitingDurationSeconds }`
错误：`LETTER_NOT_FOUND` `SENDER_CANNOT_CLAIM` `LETTER_RECALLED` `CLAIM_EXPIRED` `ALREADY_CLAIMED` `INVALID_ARGUMENT` `INVALID_SIGNATURE`
（并发：仅一人成功；获胜者同参重试幂等返回当前航程。）

### `POST /feige/letter/recall` — 发件人免费召回（form）
入参：`letterId* openid*`
条件：`sender` + `FLYING_UNCLAIMED` + `now>=departure+recall_grace`（默认30min，可配 `FG_RECALL_GRACE_MINUTES`）+ `now<claim_expire_time` + `recipient IS NULL`
出参：`{ recalled:true, recalledAt }`
错误：`LETTER_NOT_FOUND` `ACCESS_DENIED` `RECALL_TOO_EARLY` `CLAIM_EXPIRED` `RECALL_NOT_ALLOWED` `ALREADY_CLAIMED`

### `GET /feige/letter/flight` — 飞行页（发件人/收件人皆可见）
入参：`letterId* openid*`
未认领：`{ status:FLYING_UNCLAIMED, departureTime, claimExpireTime, serverTime, progress:null, flownKm:null, remainKm:null, totalKm:null, arrivalTime:null, canRecall, subscribed, flightLog:[] }`
已认领/回信：`{ status, departureTime, arrivalTime, serverTime, distanceKm, progress, flownKm, remainKm, totalKm, flightLog, subscribed }`
**V3 新增字段**：`subscribedArrival`（是否订阅当前鸽子抵达）、`subscribedReplyArrival`（是否订阅回信抵达）；`subscribed` 兼容保留（=subscribedArrival）。
**flightLog 条数（V6.0 起按飞行时长）**：固定含起飞(DEPART)+抵达(ARRIVE)；中途随机事件按飞行时长设定——<10分钟 0条 / 10分钟–2小时 1条 / 2–8小时 2条 / 8–16小时 3条 / 16–24小时 4条 / ≥24小时 5条，中途事件沿时间轴均匀分布。
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

### `POST /feige/letter/subscribe` — 订阅到达通知（form，**V3：支持类型**）
入参：`letterId* openid* type?(ARRIVAL|REPLY_ARRIVAL，默认ARRIVAL)`
- `ARRIVAL`：当前鸽子抵达（发件人/收件人分别授权，飞行页「到了叫我」）
- `REPLY_ARRIVAL`：回信抵达（原发件人「有回信时告诉我」，仅回信可订阅）
- 已抵达/已拆信返回 `{ subscribed:false }`（无需订阅）；否则 `{ subscribed:true, type }`。
- 同信+同人+同类型重复订阅幂等（刷新订阅时间）。
错误：`LETTER_NOT_FOUND` `ACCESS_DENIED` `INVALID_ARGUMENT` `INVALID_SIGNATURE`

### `GET /feige/letter/list` — 信箱列表（**V2 新增**）
入参：`openid* type?(inbox|sent, 默认inbox) page?(默认0) size?(默认20, ≤50)`
- inbox（来信）：`recipient_openid=openid`，排序 `ARRIVED > IN_FLIGHT > DELIVERED > RECALLED > 其他`
- sent（寄出）：`sender_openid=openid`，按时间倒序
出参：`{ total, page, size, list:[{ letterId, shareToken, status, title, senderCity, recipientCity, departureTime, arrivalTime, createAt, threadId, replyToLetterId, canRecall }] }`
错误：`INVALID_ARGUMENT`

### `POST /feige/report` — 内容投诉（**V3 新增**，JSON body）
入参：`{ letterId:"*", openid:"*"(投诉人), reason:"*", description:"?" }`
`reason`：`INAPPROPRIATE`(不当内容) / `HARASSMENT`(骚扰诈骗) / `PRIVACY`(侵犯隐私) / `OTHER`(其他)；`description` ≤500字。
出参：`{ reportId }`
说明：记录 letter_id/reporter/reported_sender/reason/description/status(PENDING)，运营人工处理，不做自动封禁/拉黑（规格17.1）。
错误：`INVALID_ARGUMENT` `LETTER_NOT_FOUND` `INVALID_SIGNATURE`

### `GET /feige/pigeon/mine` — 我的鸽子（无 sign）
入参：`openid*`
出参：`{ name, level, exp, expNext, speedKmh, stamina, deliveredCount, totalMileage, farthestDistance, status, motto }`
（注：level/exp 保留字段恒为初始值——V1 不结算等级经验）

### `GET /feige/pigeon/list` — 鸽舍列表（**V3 新增**，无 sign）
入参：`openid*`
首次进入自动获得小白（规格3.2）。
出参：`{ total, list:[{ id, name, roleKey, level, exp, speedKmh, stamina, deliveredCount, totalMileage, farthestDistance, status, motto }] }`
`roleKey`：`XIAOBAI`/`PANGDUN`/`HUIHUI`/`ASHAN`/`LAOYOUCHAI`/`HUALING`（规格14.3六角色）。

### `POST /feige/pigeon/create` — 创建角色鸽子（**V3 新增**，form，需 sign）
入参：`openid* roleKey*`
规则：同一用户不能重复拥有同一角色；每用户最多6只（规格15.1）；V1.1 创建免费（V1.2 起第2~6只付费，PAID_PIGEON_ENABLED 开关）。
出参：`{ id, name, roleKey, status:"IDLE" }`
错误：`ROLE_UNAVAILABLE`（角色非法/已拥有/超上限）`INVALID_SIGNATURE`

### `POST /feige/pigeon/rename` — 鸽子改名（**V3 新增**，form，需 sign）
入参：`openid* pigeonId* name*`
规则：仅首次送达后（deliveredCount≥1）可改名（规格3.2）；≤12字。
出参：`{ id, name, renamed:true }`
错误：`PIGEON_NOT_FOUND` `RENAME_NOT_ALLOWED` `INVALID_ARGUMENT` `INVALID_SIGNATURE`

### `GET /feige/pigeon/journeys` — 鸽子旅程履历（**V3 新增**，无 sign）
### `GET /feige/pigeon/slots` — 鸽舍槽位（**V4.2：槽位模型；V5.1：价格表驱动**，无 sign）
入参：`openid*`
出参：`{ slots:[{ index, occupied, roleKey|null, name|null, status(IDLE|SENDING|EMPTY), deliveredCount, totalMileage, amountFen, paid, goodsConfigured }], candidates:[{ roleKey, name, motto }], freeCount, maxSlots:6, paidEnabled, mockPay }`
说明：**位置模型（规格15.3）**：价格绑定第 N 个位置（位置1小白免费，2~6 价格递增），不绑定角色；`occupied` 表示该位置是否已入住；`candidates` 为尚未拥有的全部候选角色（买位置后从中选择入住）。**V5.1**：`amountFen` 来自 `feige_pay_goods` 表（表未配置该槽位=0）；`goodsConfigured=false` 表示该收费槽位未配置商品（前端应禁用购买按钮）。

### `POST /feige/pigeon/order` — 创建购买订单（**V4.2：买位置+选角色；V5.0：真实虚拟支付返回 payData；V5.1：商品以表配置**，form，需 sign）
入参：`openid* slotIndex* roleKey* session_key?`（规格15.5：点击空位置 → 选择候选角色 → 按位置价下单；`session_key` 用于算用户态签名 signature，前端 wx.login 当次有效）
条件：`PAID_PIGEON_ENABLED=true`；`slotIndex` 2~6 且该位置无鸽子；角色合法且未拥有（鸽子校验）；**`feige_pay_goods` 表已配置该槽位商品**（价格/productId 以表为准，未配置返回 `ORDER_CREATE_FAILED`）。
**V5.2 占位释放**：同角色或同位置的**存活 CREATED 旧单**在下单时被自动置 CANCELLED 再建新单（覆盖式：换角色/换槽/重下永不被旧单拦截；PAID/REFUNDED 不覆盖——已支付/已拥有的角色或已占位置仍由鸽子校验拒绝）。
出参：`{ orderNo, roleKey, slotIndex, amountFen(=表 price_fen), status:"CREATED", mockPay, payData? }`
- **mock 模式**（offer-id/app-key 未配）：无 `payData`，前端调 `POST /pigeon/confirm` 模拟确认；
- **真实虚拟支付模式**（V5.0，offer-id/app-key 已配）：`payData` = `{ signData(JSON串), paySig, signature, mode:"short_series_goods", outTradeNo }`，前端原样传给 `wx.requestVirtualPayment` 拉起微信支付；`paySig = hmac_sha256(appKey, "requestVirtualPayment&"+signData)`、`signature = hmac_sha256(sessionKey, signData)`；`signData.productId` 来自表 `feige_pay_goods`、`goodsPrice=price_fen`。
错误：`ORDER_CREATE_FAILED` `INVALID_SIGNATURE`

### `POST /feige/pigeon/confirm` — 支付确认（**V4 新增**，form，需 sign；仅 mock 模式可用）
入参：`openid* orderNo* payTradeNo?`
说明：**真实虚拟支付模式（offer-id/app-key 已配）下该接口直接拒绝（`REAL_PAY_ENABLED`）**，支付确认只能由微信发货推送 `/feige/pay/notify` 或查单兜底触发，防绕过支付。mock 模式下（`FG_PAY_MOCK=true` 且凭证未配）允许直接确认（仅测试环境）。确认成功置订单 `PAID` 并在**订单位置**发放权益（创建鸽子入住该位置，幂等：角色已拥有/位置已占/重复回调均不重复发放）。
出参：`{ orderNo, roleKey, slotIndex, amountFen, status:"PAID", paid:true }`
错误：`ORDER_NOT_FOUND` `ORDER_STATE_INVALID` `INVALID_SIGNATURE` `REAL_PAY_ENABLED`

### `POST /feige/pay/notify` — 虚拟支付发货推送接收（**V5.0 新增**，XML，微信平台→服务端）
- **GET**（URL 校验）：微信后台「发货推送配置」保存时带 `signature/timestamp/nonce/echostr` 验 URL；校验 `sha1(sort(token,timestamp,nonce))==signature`（token=`feige.mp-push.token`），通过回 echostr。
- **POST**（推送）：兼容模式密文 `{ <Encrypt> }` → AES 解密（`feige.mp-push.aes-key`）→ 解析业务 XML：
  - `Event=xpay_goods_deliver_notify`：按 `OutTradeNo` 确认支付（幂等 CREATED→PAID，在**订单位置**发鸽入住），写 `pay_trade_no=TransactionId`，随后调 `/xpay/notify_provide_goods` 上报发货完成；返回 `success`。
  - `Event=xpay_refund_notify`：按 `MchOrderId`(=orderNo) 置订单 `REFUNDED`（鸽子保留，规格15.5）。
  - 其它事件：记日志返回 success。
说明：处理异常也返回 success（防微信 15 次重试风暴；漏单由查单兜底定时任务补）。响应格式：`success` 纯文本（微信接受空/success/ErrCode XML 三种）。

### `POST /feige/pay/callback` — 旧 mock 确认回调（**V4 新增，V5.0 起真实模式禁用**，JSON body）
入参：`{ orderNo, payTradeNo }`
说明：真实虚拟支付模式（offer-id/app-key 已配）直接拒绝（`REAL_PAY_ENABLED`）——支付确认统一走 `/feige/pay/notify`；mock 模式下保留（幂等：重复回调不重复发放权益）。
出参：`{ orderNo, roleKey, status:"PAID", paid:true }`

### `GET /feige/order/status` — 订单状态查询（**V5.0 新增**，前端支付结果轮询，无 sign）
入参：`orderNo* openid*`
出参：`{ orderNo, roleKey, slotIndex, amountFen, status(CREATED|PAID|REFUNDED|CANCELLED), paid }`
说明：仅本人可查（openid 归属校验，否则 `ORDER_NOT_FOUND`）；前端 wx.requestVirtualPayment 回调后 2~3s 轮询，PAID 即权益已发放（跳鸽舍/入住动画），超时 60s 提示「支付结果确认中」。

### `GET /feige/pigeon/orders` — 我的购买订单（**V4 新增**，无 sign）
入参：`openid*`
出参：`[{ orderNo, roleKey, slotIndex, amountFen, status(CREATED|PAID|REFUNDED|CANCELLED), payTime, createAt }]`

### `POST /feige/upload/token` — 获取七牛上传凭证（**V4.1 新增**，form）
入参：`suffix[]?`（文件后缀数组，如 `.jpg`/`.png`/`.mp4`；缺省默认 `.jpg`）
出参：`[{ token, fileName }]`（每个后缀生成一条；`fileName` = `feige/<uuid><suffix>`）
说明：七牛空间 `mgif`、目录 `feige/`（需求指定）；大小限制 1KB~100MB；`.mp4`/`.mov` 追加 `avthumb/mp4/vcodec/libx264` 转码（persistentPipeline=budong）；凭证有效期 1 小时。凭证 `FG_QINIU_ACCESS_KEY/FG_QINIU_SECRET_KEY` 未配置时返回 `QINIU_NOT_CONFIGURED`。
错误：`QINIU_NOT_CONFIGURED`


**配置（V1.2/V5.1）**：`PAID_PIGEON_ENABLED`（规格15.6 开关，默认 false=免费创建兼容）、`FG_PAY_OFFER_ID`（虚拟支付 OfferID）、`FG_PAY_APP_KEY`（虚拟支付现网 AppKey；两者齐全=真实支付模式，缺失=fallback mock）、`FG_PAY_MOCK`（mock 支付，默认 true 仅测试）、`FG_PAY_QUERY_POLL_ENABLED`（查单兜底定时任务开关，默认 false）、`FG_MP_PUSH_TOKEN/FG_MP_PUSH_AES_KEY/FG_MP_PUSH_ENCRYPT_MODE`（虚拟支付发货推送接收配置：token 验 URL、EncodingAESKey 解密、兼容模式=1）。**槽位价格与微信道具 productId 自 V5.1 起配置在 `feige_pay_goods` 表**（slot_index 2~6 唯一；`product_id`=后台道具 ID、`price_fen`=分，两者须与微信「虚拟支付 → 道具管理」一致，否则下单报 -15013；**废弃** `FG_PIGEON_PRICES`/`FG_PAY_GOODS_IDS`）。

入参：`openid* pigeonId*`
出参：`{ pigeonId, name, roleKey, deliveredCount, totalMileage, farthestDistance, cities:[去过去重城市], journeys:[{ letterId, status, senderCity, recipientCity, distanceKm, flightHours, departureTime, arrivalTime, reply }] }`
说明：单次旅程履历含真实旅程数据（规格14.2）；城市足迹仅自己可见（规格17.3）。
错误：`PIGEON_NOT_FOUND`

---

## 5. 定时任务（Quartz，每 1 分钟，Redis 分布式锁互斥）

- `FeigeArrivalJob`：`IN_FLIGHT AND arrival_time<=now` → `ARRIVED` + 鸽子 `IDLE` + 旅程数据结算（无经验）+ 到达通知（按订阅表）。
- `FeigeUnclaimedExpireJob`：`FLYING_UNCLAIMED AND claim_expire_time<=now` → `UNCLAIMED_EXPIRED` + 鸽子 `IDLE`。
- `FeigeXPayQueryJob`（V5.0，`FG_PAY_QUERY_POLL_ENABLED=true` 时生效）：扫描最近 10 分钟仍 CREATED 的订单，调微信 `/xpay/query_order` 查单；`status∈{2,3,4}`（已支付）则确认支付发权益 + 调 `/xpay/notify_provide_goods` 上报发货（发货推送丢失兜底）。
- 多副本部署需 `FG_LOCK_ENABLED=true` + Redis（`FG_REDIS_HOST/PORT/PASSWORD`），锁 TTL 默认 30s（`FG_LOCK_TTL_SECONDS`）。

---

## 6. 数据表（新库 `feige_pigeon`）

- `fg_user`：openid/session_key/nickname/face/mobile/app_type/status
- `feige_pigeon`：openid/name/level/exp/speed_kmh/stamina/delivered_count/total_mileage/farthest_distance/role_key/**slot_index**(1~6)/status；UNIQUE(openid, role_key) + UNIQUE(openid, slot_index)
- `feige_letter`：letter_id/share_token/sender_*/recipient_*/title/signature/content/image_url/pigeon_id/pigeon_name/speed_kmh/distance_km/flight_hours/departure_time/arrival_time/claim_expire_time/claimed_at/recalled_at/expired_at/status/settled/settled_at/settle_*/subscribed/notified/read/thread_id/reply_to_letter_id/create_at/update_at
- `feige_letter_event`：letter_id/seq/type/title/description/at_time；`UNIQUE(letter_id,seq)`
- `feige_subscription`（**V3 新增**）：letter_id/openid/type(ARRIVAL|REPLY_ARRIVAL)/notified/notified_at/subscribed_at；`UNIQUE(letter_id,openid,type)`
- `feige_report`（**V3 新增**）：letter_id/reporter_openid/reported_sender_openid/reason/description/status/created_at

**存量升级**（已有库执行一次）：见 `feige_schema.sql` 尾部 ALTER（title/signature/thread_id/reply_to_letter_id + 首信 thread 回填 + role_key/uk_openid_role）。

---

## 7. 并发/幂等
原子认领(条件UPDATE)、召回vs认领(条件UPDATE互斥)、并发发送(`FOR UPDATE`+`markSending`)、单次结算(`settled=0`)、通知不重复(`notified=0`)、日志(`UNIQUE(letter_id,seq)`)、回信直达无认领竞争、定时任务 Redis 分布式锁（多副本互斥）、订阅幂等(`UNIQUE(letter_id,openid,type)` upsert)、推送幂等(每订阅 notified 独立)。

---

## 8. 变更记录

| 版本 | 日期 | 变更 |
|---|---|---|
| V6.1 | 2026-09-03 | **share-preview 返参新增 pigeonRoleKey**：分享预览返回送信鸽子角色（按 pigeon_id 关联 feige_pigeon 查 role_key；鸽子缺失/旧信无绑定为 null） |
| V6.0 | 2026-09-03 | **飞行日志按飞行时长设定中途事件条数**：generateEvents 中途随机事件数改为按时长分档（<10min:0 / 10min–2h:1 / 2–8h:2 / 8–16h:3 / 16–24h:4 / ≥24h:5），中途事件沿时间轴均匀分布（替代原固定 4 条）；起飞+抵达固定记录；文案池保留 7 种；确定性生成+幂等不变 |
| V5.2 | 2026-09-03 | **订单占位释放**（V12-5）：POST /pigeon/order 同角色/同位置存活 CREATED 旧单自动取消后建新单（方案A覆盖，PAID/REFUNDED 不覆盖）；新增 FeigeOrderExpireJob 每分钟扫 CREATED 且超时（`FG_ORDER_EXPIRE_MINUTES` 默认 15）自动置 CANCELLED 释放占位；事务原子（下单+覆盖）；无前端主动取消接口 |
| V5.1 | 2026-09-02 | **虚拟支付道具商品表 feige_pay_goods**（slot_index/product_id/price_fen/remark）：槽位价格与微信道具 productId 以表为准（slots.amountFen、下单 amountFen、signData.productId/goodsPrice 全表驱动）；slots 出参加 goodsConfigured（表未配置该槽位则禁用购买）；表未配置槽位下单拒绝（ORDER_CREATE_FAILED）；废弃 FG_PIGEON_PRICES/FG_PAY_GOODS_IDS 环境变量 |
| V5.0 | 2026-09-02 | **真实虚拟支付（米大师 xpay）接入**（资格已通过）：POST /pigeon/order 入参加 session_key、xpay 已配置时出参加 payData（signData/paySig/signature/mode 供 wx.requestVirtualPayment）；POST /feige/pay/notify 发货推送接收（GET 验 URL + 兼容模式 AES 解密；xpay_goods_deliver_notify 幂等确认发货 / xpay_refund_notify 退款置 REFUNDED）；GET /feige/order/status 订单状态轮询；confirm 与 pay/callback 在真实支付模式下拒绝（REAL_PAY_ENABLED）；FeigeXPayQueryJob 查单兜底 + notify_provide_goods 上报；orderNo 改 8~32 位；配置 FG_PAY_OFFER_ID/FG_PAY_APP_KEY/FG_PAY_GOODS_IDS/FG_PAY_QUERY_POLL_ENABLED/FG_MP_PUSH_*（废弃 FG_PAY_MCH_ID/FG_PAY_API_KEY） |
| V4.3 | 2026-09-02 | 召回宽限期可配置：`FG_RECALL_GRACE_MINUTES`（默认 30 分钟=规格5.3，测试可设 5 便于验证） |
| V4.2 | 2026-09-02 | V1.2 槽位模型（规格15.3/15.5）：feige_pigeon 加 slot_index+UNIQUE(openid,slot_index)；价格绑定位置、角色可选入住；POST /pigeon/order 入参改 slotIndex+roleKey；slots 返回物理位置+已入住角色+candidates 候选；支付权益按订单位置入住；免费创建自动分配最小空位 |
| V4.1 | 2026-09-02 | V1.2 补充：POST /feige/upload/token 七牛上传凭证（空间 mgif/目录 feige/，参考 MaterialController#uploadToken；qiniu-java-sdk 7.13.0） |
| V4.0 | 2026-09-02 | V1.2 付费能力：feige_order 订单表、PAID_PIGEON_ENABLED 开关（规格15.6）、GET /pigeon/slots（空位/候选/价格）、POST /pigeon/order、POST /pigeon/confirm（mock）、POST /pay/callback（支付回调）、GET /pigeon/orders；支付确认幂等发放权益、退款不删历史（规格15.5）；价格配置 FG_PIGEON_PRICES（A1 待定） |
| V3.2 | 2026-09-01 | share-preview 返参新增 senderSignature（发件落款）、departureTime（发出时间），对齐规格6.1 认领前展示 |
| V3.1 | 2026-09-01 | V11-8 通知接通：订阅模板审核通过并适配（thing1/time2/thing3/thing4 字段，发件人/收件人区分文案）；WeChatClient 推送支持指定模板 ID；yml 默认填入模板 ID |
| V3.0 | 2026-09-01 | V1.1：订阅表 feige_subscription 双方独立订阅（ARRIVAL/REPLY_ARRIVAL）、订阅接口支持 type、飞行页返回订阅状态；投诉 POST /feige/report；多鸽体系 pigeon/role_key + PigeonRole 六角色、GET /pigeon/list、POST /pigeon/create、POST /pigeon/rename（首达后）、GET /pigeon/journeys（含去过城市）；回信到达通知模板配置 reply-arrival-template-id |
| V2.0 | 2026-09-01 | 路径去 `/small-soogif`；send/bind/reply 改 JSON；新增 title/signature、isSendLetter、坐标兜底、5分钟保底、回信直达、往返字段、信箱列表；关闭等级/经验结算 |
| V1.3 | 2026-08-27 | 旧版契约（路径含 /small-soogif，form 入参，无 V2 字段） |
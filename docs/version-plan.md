# 《飞鸽传书》版本规划（已确认基线）

> 状态：已确认 ｜ 日期：2026-09-01
> 依据：`04-product-interaction-spec-v1.0-final.md`（产品规格）+ `spec-vs-implementation.md`（对齐报告）
> 本文件为版本规划基线，后续改动须更新本文件并同步相关文档。

---

## 版本总览

| 版本 | 主题 | 状态 |
|---|---|---|
| V1.0（当前） | 核心免费版：写信/认领/抵达/回信/单鸽 | ✅ 已上线测试服，全链路回归 13/13 通过 |
| V1.0 收尾 | 缺陷修复 + 信箱基础 | ✅ **全部完成并部署验证**（契约已更新至 V2.0） |
| **V1.1** | 完整 V1 范围：投诉/通知/多鸽/改名 | ✅ 测试机回归通过（含通知链路验证） |
| **V1.2** | 付费购买（依赖虚拟支付资格） | 🚧 开发中（订单/回调/权益幂等已实现，开关控制，本地自测通过；**2026-09-02 虚拟支付资格已通过**，待按上方米大师 xpay 清单接入真实支付） |

---

## V1.0 收尾（当前，立即执行）

### 缺陷修复（P0）—— ✅ 全部完成并部署验证（develop 88e1225）

| # | 缺陷 | 说明 | 状态 |
|---|---|---|---|
| F1 | **回信永远无法抵达** | reply 未计算 distance/flight_hours/arrival_time，且 status=FLYING_UNCLAIMED 无认领流程 → ArrivalJob 永不推进 | ✅ 已修复：回信直达（计算航程+5分钟保底+IN_FLIGHT+预绑定+飞行日志），测试机全链路验证（回信→到信→拆信）通过 |
| F2 | 回信 status 语义 | 规格 12.2「直接进入 IN_FLIGHT」 | ✅ 随 F1 修复（status=IN_FLIGHT） |

### 功能补齐（P0，信箱/往返必需）—— ✅ 全部完成并部署验证（develop 88e1225）

| # | 功能 | 规格出处 | 说明 | 状态 |
|---|---|---|---|---|
| V10-1 | **信箱列表接口** | 16.5 | `GET /feige/letter/list?type=inbox\|sent&openid=`：来信（已抵达未接>正在飞来>历史）、寄出（等待认领/飞行中/已抵达/已被收下/已召回/已过期） | ✅ 已上线，测试机验证（inbox 状态排序/往返关系返回） |
| V10-2 | **往返信件关系字段** | 12.3 | feige_letter 增加 `thread_id`（往返会话）、`reply_to_letter_id`（回信指向原信）；回信时写入 | ✅ 已上线（DDL+回填+回信写入，测试机验证） |
| V10-3 | 关闭等级/经验结算 | 14.1 | 只保留真实旅程数据累积（里程/送达次数），exp/level/speed 不再变更 | ✅ **已完成并部署验证**（commit 487110d 关闭结算 + 2bdfba4 修复 settleDelivery SQL；测试机确认 settled=1 且 exp/level 不再变更） |

### 已决议事项（记录在案）

- 内容安全审核：**后端不提供**，前端直连外部接口（规格 4.5 已决议）
- 等级/经验结算：**关闭**（只保留真实旅程数据累积）
- 静默登录：维持 openid + sign 防伪方案（B8 决议）

---

## V1.1（完整 V1 范围）—— ✅ 测试机回归通过（2026-09-02）

| # | 功能 | 规格出处 | 说明 | 状态 |
|---|---|---|---|---|
| V11-1 | **投诉入口** | 17.1 | `POST /feige/report`：不当内容/骚扰诈骗/侵犯隐私/其他；表 feige_report（letter_id/reporter/reported/reason/description/status/created_at）；运营人工处理 | ✅ 回归通过（reportId 落库 PENDING） |
| V11-2 | **发件人订阅** | 13.1 | 发件人「到了叫我」：飞行页发件人可订阅到达通知（已确认：V1.1 补发件人订阅） | ✅ 回归通过（订阅表独立记录） |
| V11-3 | **回信到达通知** | 13.2 | 原发件人「有回信时告诉我」，回信抵达后推送 | ✅ 回归通过（修复双层状态检查 bug；推送链路验证，notified 幂等） |
| V11-4 | **双方独立订阅模型** | 13.3 | 建订阅表（letter_id + user_id + type + subscribed_at），替换信件级单字段 subscribed | ✅ 回归通过（ARRIVAL/REPLY_ARRIVAL 独立记录） |
| V11-5 | **6 只鸽子角色体系** | 14.3 | 角色表/常量（小白/胖墩/灰灰/阿闪/老邮差/花翎）；`GET /pigeon/list`；创建/选择接口；统一 177km/h；独立履历 | ✅ 回归通过（list/create/重复拒绝） |
| V11-6 | **鸽子改名** | 3.2 | 首次送达后邀请改名；`POST /pigeon/rename` | ✅ 回归通过（未送达拒绝/送达后成功） |
| V11-7 | **鸽子旅程履历接口** | 14.2 | `GET /pigeon/journeys`：累计里程/送达次数/最远/去过城市/单次履历 | ✅ 回归通过（journeys 含城市/里程） |
| V11-8 | **到达通知接通** | 13.1 | 配置 FG_ARRIVAL_TEMPLATE_ID（依赖订阅模板审核通过，A5） | ✅ **已接通**：模板审核通过（PM7gZ6hVG8yOXGtcjWdsid2vmB_rKyt_ZtZJ7PfIdo4），字段适配 thing1/time2/thing3/thing4，发件人/收件人区分文案；本地自测通过 |

**V1.1 执行顺序**（依赖关系）：
1. **V11-4 订阅模型**（先建订阅表，后续订阅功能依赖）
2. **V11-2 发件人订阅** + **V11-3 回信到达通知**（基于新订阅模型）
3. **V11-1 投诉入口**（独立，可并行）
4. **V11-5 6只鸽子** + **V11-6 改名** + **V11-7 履历**（多鸽体系，改鸽舍相关）
5. **V11-8 通知接通**（等模板审核，不阻塞开发）

---

## V1.2（付费能力，虚拟支付）—— ✅ **真实虚拟支付接入完成（feature/v1.2-virtual-pay，本地自测通过）**；待测试机回归

| # | 功能 | 规格出处 | 说明 | 状态 |
|---|---|---|---|
| V12-1 | 多鸽购买（第 2~6 只付费） | 15 | 订单/支付回调/权益发放幂等/退款规则（A1/A2/A7） | ✅ V4.2 回归通过：槽位模型（价格绑位置、买位置+选角色）——下单槽4+HUIHUI(600分)→confirm→PAID→入住槽4；同槽占位拦截；feige_order 表已建、PAID_PIGEON_ENABLED=true |
| V12-2 | 鸽舍扩建与候选角色 | 15.4/15.5 | 鸽舍管理接口、空位置、PAID_PIGEON_ENABLED 开关（B7） | ✅ V4.2 回归通过：slots 物理位置模型（occupied/roleKey/amountFen/candidates/freeCount），免费创建自动分配最小空位 |
| V12-3 | **七牛上传凭证** | 需求 | `POST /feige/upload/token`：空间 mgif、目录 feige/（参考 MaterialController#uploadToken；qiniu-java-sdk 7.13.0；fsize 1KB~100MB；mp4/mov 转码） | ✅ 开发完成，本地自测通过（token 策略验证：scope=mgif:feige/..、fsize 限制、mp4 转码） |
| V12-4 | **真实虚拟支付（米大师 xpay）** | 官方文档+search111 参考实现 | 下单返回 payData（offerId/productId/signData/paySig/signature）→ 前端 wx.requestVirtualPayment → xpay_goods_deliver_notify 发货推送(主)+query_order 查单+notify_provide_goods 上报(兜底)；**道具 productId/价格配置于 feige_pay_goods 表（V5.1）** | ✅ **开发完成，本地自测通过（V5.0+V5.1）**：payData 下单/发货推送(GET验URL+AES解密+幂等发货)/退款推送/order/status/查单兜底 Job/真实模式拦截 confirm 防绕过/道具表驱动价格与productId——详见下方清单；契约更新至 V5.1 |

| V12-5 | **订单占位释放（取消兜底）** | 需求（2026-09-03 用户确认） | CREATED 订单防重占位导致无法换槽/换角色再下单；**方案A：下单自动覆盖**——createOrder 前若同槽位或同角色存在存活 CREATED 旧单，自动置 CANCELLED 再建新单（任何"重新下单"路径都不被旧单拦截；PAID/REFUNDED 不受影响）；**超时 Job：FeigeOrderExpireJob** 每分钟扫 CREATED 且 create_at 超时（`FG_ORDER_EXPIRE_MINUTES` 默认 15）自动置 CANCELLED（清理彻底放弃的残留单）；**不做前端主动取消接口**（已确认） | ✅ 开发完成并合并 develop（a5c7af6）：本地自测通过（覆盖换角色/换槽/重下、PAID 保护 409、1min 超时自动取消+释放可重下）；契约更新至 V5.2；待测试机回归 |

**V1.2 执行顺序**（依赖关系）：
1. **V12-2 开关与槽位**（PAID_PIGEON_ENABLED + slots 空位/候选/价格，先有模型）
2. **V12-1 订单/支付**（下单 → 支付确认(mock) → 回调 → 权益发放幂等 → 退款）
3. ✅ **真实虚拟支付接入（米大师 xpay）**（资格已通过 A2：控制台建道具 → 配置 OfferID/AppKey/发货推送 → 改造下单返回 payData → xpay 推送/查单/退款接入，关闭 mock）—— 代码待办见下方清单
4. ⏳ **价格确认**（A1 定稿后 UPDATE feige_pay_goods.price_fen + 同步微信后台道具价格）

### ✅ 虚拟支付（米大师 xpay）接入清单（2026-09-02 资格已通过，对照官方文档重写）

> **重要结论（资格通过后重新核对官方文档得出）**：虚拟支付 = **米大师 xpay 体系**，**不是**传统「微信支付 JSAPI」。
> 前端拉起的是 `wx.requestVirtualPayment`（不是 `wx.requestPayment`），后端对接的是 **`/xpay/*` 服务端接口 + 发货推送**，没有「统一下单 prepay_id」概念，也没有 v2/v3 证书体系。
> 下面配置/待办按真实 xpay 模型编写。

#### 0. 资格通过后，控制台必须先做这些（开通后仍显示「已签约」才算完成）

| 事项 | 位置 | 说明 |
|---|---|---|
| ✅ 虚拟支付开通签约 | MP 后台 → 虚拟支付 | 已通过（本项即你当前状态） |
| **新建 5 个「道具」并发布现网** | MP 后台 → 虚拟支付 → **道具管理** | **每档鸽舍位置建一个道具**，记下各 productId + 价格（分）；道具须「发布至现网版本」且审核通过后约 10 分钟生效（未发布下单报 -15010 / 未生效报 -15014 / 审核不通过 -15018）。道具 Android/iOS 双端互通。 |
| **配置发货推送 URL** | 虚拟支付 → 基本配置 → 基础配置 → 发货推送配置 | 指向后端 `/feige/pay/notify`（必须 https）；支付成功平台推 XML 到此 URL |
| 记录 3 个关键参数 | 虚拟支付 → 基本配置 | **OfferID**（=支付账号）、**现网 AppKey**（HMAC-SHA256 支付签名密钥；区分沙箱 AppKey/现网 AppKey）、AppID |
| iOS 端可选开通 | 虚拟支付 → 基本配置 → 苹果 IAP | 需先配「小程序简称」（Apple display name 要求）；Apple 支付**不支持沙箱**，仅现网 |

> **用户问题回答：是的，必须新建道具。** 鸽舍 5 个收费槽位建议各建一个道具（productId 对应 slotIndex），在微信后台配置道具价格并发布现网。**V5.1 起道具 productId/价格配置到数据库 `feige_pay_goods` 表**（slot_index/product_id/price_fen），下单 goodsPrice=price_fen，微信会与后台道具价格校验（不一致报 -15013）。

#### 后端配置（测试机 env.conf / 生产环境变量）

| 配置项 | 环境变量 | 说明 |
|---|---|---|
| 付费开关 | `FG_PIGEON_PAID_ENABLED=true` | `feige.pigeon.paid-enabled`；关闭时下单接口直接拒绝 |
| 关闭模拟支付 | `FG_PAY_MOCK=false` | 必须关，否则支付确认仍是 mock 直接成功 |
| **OfferID** | `FG_PAY_OFFER_ID=<offerid>` | 虚拟支付基本配置中的 offerid（= 支付账号），替代原 mch_id |
| **现网 AppKey** | `FG_PAY_APP_KEY=<现网AppKey>` | 支付签名 paySig 密钥（hmac_sha256(appKey, uri+"&"+postBody)），替代原 API 证书体系 |
| **小程序 AppID/Secret** | `FG_WECHAT_APPID / FG_WECHAT_SECRET`（已有） | 服务端 `/xpay/*` 接口（query_order/notify_provide_goods）需 `getAccessToken`（`cgi-bin/token`）；feige 的 `WeChatClient#getAccessToken` 已实现但为 private，需扩展复用 |
| 槽位价格/道具 | 数据库 `feige_pay_goods` 表 | **V5.1 起以表为准**（productId+price_fen）；废弃 `FG_PIGEON_PRICES`/`FG_PAY_GOODS_IDS`（A1 定稿后 UPDATE price_fen + 同步微信后台） |
| （原 mch_id/API 密钥配置废弃） | ~~FG_PAY_MCH_ID / FG_PAY_API_KEY~~ | 虚拟支付不是微信支付商户号体系，这两项不适用，删除 |

#### 真实虚拟支付代码待办（当前为 mock 确认；对照官方文档 + 参考项目已跑通实现（search111 `SmallAppVipController`/`SoogifSmallAppVipController`/`WeiXinUtil`）补 4 块）

> 参考项目结论（重要）：官方文档与真实代码吻合——下单**无统一下单**，服务端只算签名返回 payData；**服务端 `/xpay/*` 都要带 access_token + pay_sig**（`hmac_sha256(appKey, uri+"&"+postBody)`，postBody 用 **TreeMap 序列化保证键序稳定**）；支付成功以「发货推送」为主 + `query_order` 查单兜底，**查单确认支付后必须再调 `notify_provide_goods` 主动上报发货**；回调 XML 按官方结构为 `WeChatPayInfo`(含 `MchOrderNo/TransactionId`)/`GoodsInfo` 嵌套，参考项目 xmlToMap 将其拍平后从 `map.get("TransactionId")` 读取；幂等靠「本地订单是否已存在/是否已发」。

官方标准流程：① 前端请求服务器下单 → ② 服务器生成唯一 outTradeNo、返回签名后的 payData → ③ 前端 `wx.requestVirtualPayment` 拉起支付 → ④ 平台推「发货推送」给服务器（主），推送丢失用 `query_order` 查单兜底 → ⑤ 前端查自己服务器订单状态展示结果。发货**以后端推送为准**，前端 success 回调**可能丢失**，不作发货依据。

1. **下单返回 payData 接口**（改造现有 `POST /feige/pigeon/order` 或新增）：
   生成业务单号 `outTradeNo`（8-32 位、数字/大小写字母/`_-|*@`、不能 `_` 开头、须唯一不复用；参考项目用 `UUID` 去横线，feige 现 `newOrderNo()` 前缀 "OD"+uuid 即满足格式）→ 落库 CREATED → 返回前端 payData：
   `signData`（JSON 字符串：`{ offerId, buyQuantity:1, env:0, currencyType:"CNY", productId:<该槽位道具ID>, goodsPrice:<分>, outTradeNo, attach:<orderNo等透传> }`）、
   `mode:"short_series_goods"`（道具直购）、
   `paySig = to_hex(hmac_sha256(appKey, "requestVirtualPayment&" + signData原串))`、
   `signature = to_hex(hmac_sha256(sessionKey, signData原串))`（用户态签名，sessionKey 需为**当次有效**的 code2session 返回值——现状登录接口已存 fg_user.session_key，需注意刷新时机；参考项目是前端把 session_key 传上来）。
   ⚠️ 签名 post_body 必须与真正发出的请求体**逐字节一致**（不格式化/不改键序）；参考项目用 `TreeMap` + fastjson `toJSONString`（键序稳定）生成。
   ✅ 参考签名实现：`HmacUtils.hmacSha256Hex(appKey, needSignMsg)`（apache commons-codec，本项目已有该依赖）。
2. **发货推送接口**：`POST /feige/pay/notify`（微信平台推送，XML，需 GET 验证 + POST 接收两段，参考项目 `/small/vip/virtualGoods/notify`）：
   - **GET**：微信「发货推送配置」首次保存会带 signature/timestamp/nonce/echostr 来验 URL——需实现「微信公众平台消息校验」`checkSignature(timestamp,nonce,signature,token)`（sha1 排序拼接比对，返回 echostr），否则后台保存 URL 失败。
   - **POST**：读 XML → 解析（参考项目 xmlToMap 会把 `WeChatPayInfo`/`GoodsInfo` 嵌套子元素**拍平**为顶层 key，故代码里 `map.get("TransactionId")` 实为 `WeChatPayInfo.TransactionId`；若自写解析需按官方嵌套结构取 `WeChatPayInfo.MchOrderNo/TransactionId/PaidTime`、`GoodsInfo.ProductId/Quantity`，或同样拍平）→
     Event=`xpay_goods_deliver_notify` 时按**本地下单时存的缓存/本地订单表**找到 openid+槽位（参考项目以「下单时把订单信息写入 Redis `CacheUtils.set(out_trade_no, orderJson)`」作幂等闸门，feige 已有 feige_order 表可直接查，**更优**）→
     若本地订单仍 CREATED → 调 `confirmPaid(orderNo=OutTradeNo, payTradeNo=TransactionId)`（现有 CREATED→PAID 一次生效 + 权益发放查重复用）；若已 PAID 直接幂等成功 →
     返回 `<xml><Errcode>0</Errcode><ErrMsg><![CDATA[success]]></ErrMsg></xml>`（注意参考项目实际返回是 **Errcode/ErrMsg 或 return_code/return_msg** 两种——官方现网要求 `<ErrCode>`/`ErrMsg` 或空/success；**响应格式必须与官方一致**，格式错误微信重试最多 15 次，间隔 2/4/8/16…）。失败返回非 0，微信会重试。
   ⚠️ 现有 `/feige/pay/callback` 是自定义 JSON body 格式，**不能直接**当 xpay 推送入口，需按上述 XML+Event 结构改造/新增；且需配套**微信公众平台 URL 校验（GET echostr）**。
3. **兜底查单 + 主动发货上报 + 订单状态查询**：
   - **后端定时查单兜底**：调 `POST https://api.weixin.qq.com/xpay/query_order?access_token=<token>&pay_sig=<paySig>`，body `{ openid, env:0, order_id:<outTradeNo> }`（TreeMap；paySig uri=`/xpay/query_order`）→ 响应含 `order.status`（**2/3/4 表示已支付/成功**，0 未支付）、`order.wxpay_order_id`、`order.paid_fee`（分）→ 本地仍 CREATED 且已支付则 `confirmPaid` 发权益，**随后调 `POST .../xpay/notify_provide_goods?access_token=&pay_sig=`，body `{ env:0, order_id:<outTradeNo> }` 主动上报发货完成**（参考项目支付成功/兜底都调它，避免微信侧一直等发货）。建议每 5 分钟扫一次 CREATED 超时单。
   - **前端轮询接口** `GET /feige/order/status?orderNo=&openid=`：返回 `{ orderNo, status(CREATED/PAID/REFUNDED/CLOSED), roleKey, slotIndex, amountFen }`，仅本人可查；PAID 即权益已发放，前端跳鸽舍/入住动画；轮询 2~3 秒，超时（如 60s）提示「支付结果确认中」保留入口（对应规格 18.5）。**该接口当前缺失，需新增。**
4. **退款**：Android 等端在 MP 后台「交易订单」手工退款或调 `/xpay/refund_order`；退款完成收 `xpay_refund_notify` 推送 → 更新订单 REFUNDED（现状 refund() 已具备幂等置 REFUNDED 逻辑，但**推送接入与 mapping 需补**）；iOS 端用户向 App Store 申请、开发者无法主动退，成功后同样收 `xpay_refund_notify`。180 天以内退款平台退手续费。注意参考项目对「发货后用户主动退款」会做回调里 `return_code != 0` 或财务侧处理，鸽舍道具属一次性虚拟权益，退款策略（A7）仍需产品确认。

#### 微信平台配置（虚拟支付后台 + 小程序后台）

| 平台 | 配置项 | 说明 |
|---|---|---|
| MP 后台 → 虚拟支付 | 道具管理 | **新建并发布 5 个道具（槽位2~6）**，价格=分；**同步写入 feige_pay_goods 表**（product_id/price_fen 与后台一致） |
| MP 后台 → 虚拟支付 | 发货推送配置 | 填 https 回调 URL（指向 `/feige/pay/notify`） |
| MP 后台 → 虚拟支付 | 基础配置 | 记录 OfferID / 现网 AppKey / AppID |
| MP 后台 → 虚拟支付 | iOS 支付（可选） | 先配「小程序简称」，Apple 支付仅现网环境 |
| 小程序后台 | request 合法域名 | 先配置 vs 域名（如 `https://demo.soogif.com`）as 测试环境；**`test.soogif.com` 是生产，勿混用** |

#### 参考项目落地经验（search111 已跑通虚拟支付，接入时可对照源码）

- **配置**：`PayCodeConfig`（`small_offerId=1450481823`、`small_app_key`、`appid_small`…）——两个小程序两套 offerId/appKey。feige 需改为环境变量注入（FG_PAY_OFFER_ID/FG_PAY_APP_KEY），勿硬编码。
- **下单**：`SmallAppVipController#unifiedOrderVirtualGoods`（L822）——TreeMap 组 signData → `hmacSha256Hex(appKey, "requestVirtualPayment&"+signData)` 算 paySig、`hmacSha256Hex(session_key, signData)` 算 signature → 返回 `{signData,paySig,signature,mode:"short_series_goods",out_trade_no}`；同时把订单信息写入 Redis（`CacheUtils.set(out_trade_no, orderJson)`，2h 过期）作为回调幂等锚点。
- **回调**：`WxNotify`（L1935）——POST 读 XML→map；`event="xpay_goods_deliver_notify" && CacheUtils.exists(out_trade_no)` 才处理；从 map 取 `OutTradeNo/TransactionId/ActualPrice`，从 Redis 取 openid/productId；`orderList.size()==0`（本地订单不存在）才建单/发货，否则直接返回成功（幂等）；成功回 `<xml><Errcode>0</Errcode><ErrMsg><![CDATA[success]]></ErrMsg></xml>`。
- **兜底查单+主动发货**：`queryPayStatusVirtualGoods`（L1568）——`virtualGoodsQueryOrder(appid,secret,redisKey,openid,out_trade_no)`（WeiXinUtil L101）带 access_token+pay_sig 调 `api.weixin.qq.com/xpay/query_order`；`order.status ∈{2,3,4}` 判已支付；`wxpay_order_id`/`paid_fee` 取平台单号金额；本地订单不存在才建单发权益；**之后调 `virtualGoodsNotifyProvideGoods(...)`（WeiXinUtil L165，`/xpay/notify_provide_goods`，body `{env:0, order_id}`）主动上报发货完成**。
- **access_token**：`WeiXinUtil#getOrRedis` 按 redisKey 缓存（600s 过期策略，遇 40001 失效删除重取）；feige `WeChatClient#getAccessToken` 每次实时获取，接入时可加 Redis 缓存。
- **注意点**：WeiXinUtil 里两个**硬编码 appKey 的重载**是历史遗留坏味道（生产代码泄露密钥），feige 接入时一律走配置注入；参考项目「Redis 缓存订单 JSON + CacheUtils.exists」做幂等，feige 用 DB feige_order（orderNo 唯一 + CREATED→PAID 原子更新）更稳，可不用 Redis 闸门，但需注意**微信会因响应格式错重试 15 次**，务必返回官方要求的 XML 结构。

#### ⚠️ 上线前须知

- **iOS 可用性（重要更正）**：虚拟支付**支持 iOS**（Apple IAP 渠道，微信 ≥ 8.0.68、iOS ≥ 15、最低 1 元、仅大陆 App Store 账号）；但**结算走苹果**（约 45-60 天、12% Apple 佣金，含在费率里）。原「iOS 不可用需隐藏入口」的假设已过时——现在是 Android/鸿蒙/Windows/iOS 全终端可用，前端**不要**隐藏 iOS 购买入口，只需做微信版本/系统版本前置校验。
- **费率**：工具/社交类目 Android 主动支付当前 1%（标准 10% 有活动），iOS 12%（全为 Apple 佣金）；技术服务费按虚拟支付流水结算。
- **沙箱**：虚拟支付有沙箱环境（env=1 + 沙箱 AppKey），但**现网发布版 env 只能 0**（-15011）；Apple 支付不支持沙箱。联调建议用「现网 + 小额真单」验证。
- **A1 价格确认**：默认 100/300/600/1000/1500 分（1/3/6/10/15 元），定稿后 **UPDATE `feige_pay_goods`.price_fen 并同步微信后台道具价格**。
- **A7 退款规则**：代码已实现退款不删历史（REFUNDED）、旅程完成后再停用；Android 平台可主动退款、iOS 只能用户发起；最终以平台规则为准。

---

## 待外部确认项（不阻塞开发）

| # | 事项 | 影响 |
|---|---|---|
| A1 | 鸽舍位置价格 | V1.2 付费 |
| ~~A2~~ | ~~虚拟支付资格及费率~~ | **✅ 2026-09-02 资格已通过**；费率：Android 主动支付当前 1%、iOS 12%（Apple 佣金），待按 xpay 清单接入真实支付 |
| A3 | 六只鸽子最终造型/角色名 | V1.1 多鸽视觉 |
| A4 | Logo/字体/品牌资产 | 视觉 |
| A5 | 订阅消息模板审核文案 | V1.1 通知接通 |
| A6 | 隐私政策数据保存期限 | 合规，上线前 |
| A7 | 支付退款平台规则 | V1.2 付费 |

---

## 执行顺序（V1.0 收尾）—— ✅ 已全部完成（develop 88e1225）

1. ✅ **F1/F2 回信直达修复**（含 5 分钟保底、arrival_time 计算）→ 已部署测试服，全链路验证通过
2. ✅ **V10-2 往返关系字段**（thread_id/reply_to_letter_id）→ DDL + 实体 + 回信写入
3. ✅ **V10-1 信箱列表接口** → inbox/sent 列表，测试机验证通过
4. ✅ **V10-3 关闭结算** → 已部署验证（含 settleDelivery SQL 修复）
5. ⏳ **合入 main** → 由人工手动合并（不自动执行）

> V1.0 收尾功能全部就绪：测试机运行 develop（回归 13/13 通过），契约已更新至 **V2.0**（82fdfc4）。
> **下一步：V1.1**（按上节执行顺序：订阅模型 → 发件人/回信通知 → 投诉 → 多鸽体系 → 模板接通）。

---

## V1.2 槽位模型（V4.2）回归记录

- **回归日期**：2026-09-02（测试机 develop 9026750）
- **回归结果**：✅ 通过 —— 买槽4+选HUIHUI(600分) → confirm → PAID → 鸽子入住槽4；slots 展示 occupied/候选角色/位置价正确；同槽/同角色防重复下单拦截正常
- **测试库执行**：feige_pigeon 加 `slot_index`（默认1）+ `UNIQUE(openid, slot_index)` + 存量回填
- **⚠️ 存量数据迁移注意事项（V4.0 → V4.2）**：V4.0 按「角色」创建的历史鸽子与订单，在 V4.2 回填时可能出现**订单位置与鸽子实际槽位错位**（如 ASHAN 订单在槽4、鸽子回填到槽3），导致槽4 被历史 PAID 订单占用而无法售卖新鸽。测试环境已手工修正；**生产上线前需评估存量用户回填正确性**（schema.sql 回填 SQL 对 V4.0 存量鸽子的槽位分配与历史订单 slot_index 的同步），建议上线脚本一并 UPDATE 历史订单 slot_index 对齐鸽子实际位置。

## 版本计划变更记录

| 日期 | 变更 |
|---|---|
| 2026-09-01 | 规划基线建立：V1.0 收尾（回信修复+信箱+往返字段）、V1.1（投诉/通知/多鸽/改名/履历）、V1.2（付费） |
| 2026-09-01 | V1.0 收尾**全部完成并部署验证**（F1/F2/V10-1/2/3），测试机回归 13/13 通过；契约更新至 V2.0；V1.1 列为下一迭代并给出执行顺序 |
| 2026-09-01 | **V1.1 开发完成（本地自测通过）**：订阅模型（feige_subscription，ARRIVAL/REPLY_ARRIVAL 双方独立）、发件人订阅+回信到达通知、投诉入口（feige_report）、六鸽角色体系（role_key + pigeon/list + create）、改名（首达后）、旅程履历（journeys）；契约更新至 V3.0；待测试机回归 |
| 2026-09-02 | **V1.1 测试机回归通过**（V11-1~8 全部验证；含通知链路与 REPLY_ARRIVAL 订阅双层状态检查修复）；契约更新至 V3.1 |
| 2026-09-02 | **V1.1 修复**：`share-preview` 返参新增 `senderSignature`（发件落款）、`departureTime`（发出时间），对齐规格6.1 认领前展示；契约更新至 V3.2 |
| 2026-09-02 | **V1.2 补充**：七牛上传凭证接口 `POST /feige/upload/token`（空间 mgif/目录 feige/）；契约更新至 V4.1 |
| 2026-09-02 | **V1.2 槽位模型（规格15.3/15.5）**：确认现状为「角色锁位置」不符规格 → 改造为「买位置+选角色」：feige_pigeon 落 slot_index（含存量回填）、order 按位置定价、支付权益入住订单位置、slots 物理位置+candidates；契约更新至 V4.2；本地自测通过（跳过空位买第4位/边界拦截验证） |
| 2026-09-02 | **召回宽限期可配置**：`FG_RECALL_GRACE_MINUTES` 环境变量（默认30分钟=规格5.3；测试设5便于验证）；契约更新至 V4.3 |
| 2026-09-02 | **V1.2 开发完成（本地自测通过）**：多鸽付费购买（feige_order 订单表/下单/支付确认 mock/回调/权益发放幂等/退款不删历史）、鸽舍槽位（slots 空位/候选/价格）、PAID_PIGEON_ENABLED 开关（开=付费/关=免费兼容）、价格配置 FG_PIGEON_PRICES；契约更新至 V4.0；待测试机回归 |
| 2026-09-02 | **虚拟支付资格已通过（A2 完成）**：对照官方文档确认支付体系为米大师 xpay（非微信支付 JSAPI），重写 V1.2 接入清单：控制台需建 5 个道具并发布 + 配发货推送 URL + 记 OfferID/现网 AppKey；后端改造为「下单返回 payData（signData/paySig/signature）→ wx.requestVirtualPayment → xpay_goods_deliver_notify 发货推送 + query_order 兜底」；澄清 iOS 实际可用（Apple IAP，非隐藏入口）；契约 V4.3 不变（行为未动，接入待开发） |
| 2026-09-02 | **V1.2 支付清单补充 search111 参考实现细节**：核对已跑通项目（SmallAppVipController/SoogifSmallAppVipController/WeiXinUtil）：服务端 xpay 接口需 access_token；兜底查单确认支付后须再调 notify_provide_goods 上报发货；回调 XML 的 TransactionId 在顶层；幂等锚点参考「Redis 下单缓存/本地订单表」；签名用 HmacUtils.hmacSha256Hex（commons-codec）；新增「参考项目落地经验」小节与回调 GET 验 URL 说明 |
| 2026-09-02 | **V1.2 真实虚拟支付接入完成（本地自测通过，契约 V5.0，feature/v1.2-virtual-pay）**：offer-id/app-key/goods-ids/mp-push 配置；WeChatClient access_token 缓存 + WxXPayClient(query_order/notify_provide_goods/paySig)；WxMsgCryptUtil（checkSignature + AES 兼容模式解密）；下单返回 payData（signData/paySig/signature，outTradeNo 8~32 位）；POST /feige/pay/notify（GET 验 URL + xpay_goods_deliver_notify 幂等发货 + xpay_refund_notify 退款）；GET /feige/order/status；FeigeXPayQueryJob 查单兜底；真实模式 confirm//pay/callback 拒绝（REAL_PAY_ENABLED）；本地全链路自测：payData 签名校验通过、加密推送→解密→确认→发鸽→退款推送→REFUNDED、幂等重复不重复发货；契约 V5.0；待测试机回归 |
| 2026-09-02 | **V1.2 道具商品表化（契约 V5.1，feature/v1.2-goods-table）**：新增 feige_pay_goods 表（slot_index/product_id/price_fen/remark，slot_index 唯一）——槽位价格与微信道具 productId 以表为准（slots.amountFen/下单定价/signData.productId/goodsPrice 全表驱动）；slots 出参加 goodsConfigured（表未配置该槽位前端禁用购买）；表未配置槽位下单拒绝；废弃 FG_PIGEON_PRICES/FG_PAY_GOODS_IDS；本地自测通过（价格/productId 表驱动、删表拒单、恢复可购）；待测试机回归 |

---

## 迭代流程（固定，2026-09-01 起）

1. **功能开发**：feature worktree（基于 develop）
2. **本地自测（合并前置门禁）**：worktree 内 JDK8 启动服务自测（本地 DB/Redis），通过才算可合并；标准流程见 `docs/local-dev-test.md`
3. **合并 develop**：--no-ff 合并 + 推送 —— **⚠️ 必须第 2 步自测已通过（编译无错+启动探活 200+核心用例断言），未自测不得 merge；自测过程与结论须在最终回复/commit 中可见**
4. **测试机部署**：发布服务器 `feige_git_release.sh develop` → 测试机全链路回归
5. **合入 main**：测试机通过后 → main（生产基线）
6. **发生产**：main 打 tag → Docker 镜像 → 腾讯云

> 铁律：未经本地 worktree 自测的代码不得合并 develop（自测 = JDK8 编译通过 + 本地启动探活 200 + 本次改动核心用例 curl 断言，见 `docs/local-dev-test.md`）；未经测试机回归不得合 main。

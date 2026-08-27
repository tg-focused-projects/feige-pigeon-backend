# 《飞鸽传书》V1.0 后端逻辑修改单

> 用途：基于现有《V1.0 后端接口契约》修正核心产品时序，供后端修改现有实现。
>
> 状态：产品侧后端规则稿，不包含前端 UI 和动效实现。
>
> 日期：2026-08-26

## 一、修改目标

现有实现是“发送后等待，收件人绑定时才起飞”。统一改为：

> 发件人点击放飞时，鸽子立即起飞并记录服务器时间；收件人首次确认城市后，系统根据原始起飞时间和两地距离，判断鸽子是刚起飞、已经在路上，还是早已抵达。

同时补齐原子认领、72小时有效期、免费召回、抵达结算、到点通知、分享失效和前端本地计时。

## 二、已确定规则

### 1. 发送即起飞

`POST /letter/send` 成功时立即写入：

```text
departure_time = 数据库/服务器当前时间
status = FLYING_UNCLAIMED
recipient_openid = NULL
recipient location = NULL
distance_km = NULL
flight_hours = NULL
arrival_time = NULL
pigeon.status = SENDING
```

目的城市尚未确认，所以此时不能伪造路线、距离、进度或 ETA，但起飞时间已经开始累计。

### 2. 首次打开只做身份和城市确认

产品文案：

```text
一只鸽子带着给你的信出发了。
确认你所在的城市，看看它现在飞到哪里了。

[让它认出我]
```

打开分享不占用信件。第一个成功提交确认并完成数据库原子写入的人，才成为唯一收件人。

### 3. 未认领有效期与召回

```text
unclaimed_expire_hours = 72
recall_grace_minutes = 30
```

- 放飞满30分钟且仍未认领，发件人可免费召回。
- 72小时无人认领，信件自动过期，鸽子归巢。
- 召回或过期后，旧分享永久失效。
- 重新放飞必须生成新的分享凭证和新的起飞时间。
- V1不通过广告解除占用，也不通过广告替换用户自己的鸽子。
- 信件一旦被认领，不能召回、换收件人或修改正文。

### 4. 抵达即释放鸽子

当前契约在收件人调用 `/detail` 时才结算成长，可能导致对方一直不拆信时，小白长期无法恢复空闲。修改为：

```text
到达：letter.status = ARRIVED
到达：pigeon.status = IDLE
到达：幂等结算里程、送达次数、经验和升级
拆信：read = 1
拆信：ARRIVED → DELIVERED
```

拆信不再释放鸽子，也不重复结算成长。

## 三、状态机

### 1. 信件状态

| 状态 | 含义 | 下一状态 |
| --- | --- | --- |
| `FLYING_UNCLAIMED` | 已起飞，尚未认领，目的地未知 | `IN_FLIGHT`、`ARRIVED`、`RECALLED`、`UNCLAIMED_EXPIRED` |
| `IN_FLIGHT` | 已认领，尚未抵达 | `ARRIVED` |
| `ARRIVED` | 已抵达，尚未拆信 | `DELIVERED` |
| `DELIVERED` | 已拆信 | 终态 |
| `RECALLED` | 发件人主动召回 | 终态，旧分享失效 |
| `UNCLAIMED_EXPIRED` | 72小时无人认领 | 终态，旧分享失效 |

停用原 `WAITING_RECIPIENT`，不能继续返回“等待起飞”。V1.1 的 `LOST` 不在本次范围。

### 2. 鸽子状态

- `send`：`IDLE → SENDING`
- 抵达、召回或未认领过期：`SENDING → IDLE`
- 信件和鸽子的状态更新必须处于同一事务。

## 四、数据模型调整

建议在 `feige_letter` 补充：

| 字段 | 用途 |
| --- | --- |
| `claim_expire_time` | `departure_time + 72h` |
| `claimed_at` | 收件人认领成功时间 |
| `recalled_at` | 主动召回时间 |
| `expired_at` | 自动过期时间 |
| `settled` | 抵达成长是否已结算，默认0 |
| `settled_at` | 成长结算时间 |
| `share_token` | 不可猜测的唯一分享凭证 |

要求：

- 未认领时，距离、时长、抵达时间和收件位置字段允许为 `NULL`。
- 发件位置在发送时固定；收件位置在首次认领时固定。
- 自动定位失败时允许提交手动选择城市的中心点或服务端认可的城市编码。
- 精确经纬度不能出现在分享参数、飞行日志或无权限接口中。
- 如果继续以 `letter_id` 作为公开参数，必须保证不可枚举；更推荐独立 `share_token`。
- 为保证拆信时还能展示“这次是否升级”，建议保存本次结算经验、升级前后等级和 `level_up`。

## 五、接口调整

路由前缀继续使用 `/small-soogif/feige`。

### 1. 修改 `POST /letter/send`

流程：

1. 校验签名、发件位置和鸽子状态。
2. 事务内锁定鸽子，防止并发发送。
3. 写入服务器 `departure_time`。
4. 写入 `claim_expire_time = departure_time + 72h`。
5. 写入 `status = FLYING_UNCLAIMED`。
6. 鸽子置为 `SENDING`。

建议出参增加：

```json
{
  "letterId": "FGxxxxxx",
  "shareToken": "不可猜测的随机令牌",
  "status": "FLYING_UNCLAIMED",
  "departureTime": "2026-08-26 10:00:00",
  "claimExpireTime": "2026-08-29 10:00:00",
  "serverTime": "2026-08-26 10:00:00",
  "pigeon": { "name": "小白", "level": 2, "speedKmh": 180.0 },
  "senderCity": "广西 · 南宁"
}
```

### 2. 新增 `GET /letter/share-preview`

打开分享后读取安全预览，不发生认领。入参为 `shareToken` 和当前用户身份。

建议返回 `claimStatus`：

```text
AVAILABLE
CLAIMED_BY_ME
CLAIMED_BY_OTHER
RECALLED
EXPIRED
```

安全要求：

- 不返回正文、图片或正文摘要。
- 不返回发件人精确坐标。
- `CLAIMED_BY_OTHER` 不返回收件人身份、城市或头像。
- 预览请求不修改任何业务状态。

### 3. 修改 `POST /letter/bind`

原子认领条件：

```text
recipient_openid IS NULL
AND status = FLYING_UNCLAIMED
AND claim_expire_time > now
```

成功后：

```text
recipient_openid = 当前用户
recipient location = 本次确认城市
claimed_at = now
distance_km = Haversine(sender, recipient)
flight_hours = distance_km / speed_kmh
arrival_time = 原 departure_time + flight_hours
```

禁止修改原始 `departure_time`。随后立即判断：

```text
now < arrival_time  → IN_FLIGHT
now >= arrival_time → ARRIVED，并执行一次抵达结算
```

首次体验分支：

```text
progress < 0.10  → JUST_DEPARTED
progress < 1.00  → ALREADY_FLYING
progress >= 1.00 → ARRIVED_WAITING
```

建议返回 `serverTime`、`progress`、`firstOpenCase`、`waitingDurationSeconds`。

并发和幂等要求：

- 两个不同用户同时认领，只允许一个成功。
- 失败方返回稳定错误 `ALREADY_CLAIMED`。
- 获胜者用相同参数重试，幂等返回当前航程。
- 获胜者用不同位置重试，不允许重新规划路线。
- 发件人不能认领自己发送的信。

### 4. 新增 `POST /letter/recall`

允许条件：

```text
当前用户 = sender_openid
status = FLYING_UNCLAIMED
now >= departure_time + 30min
now < claim_expire_time
recipient_openid IS NULL
```

事务内执行：

```text
letter.status = RECALLED
letter.recalled_at = now
pigeon.status = IDLE
```

认领与召回并发时，只能有一个操作成功。

### 5. 修改 `GET /letter/flight`

未认领时，发件人可查看，但未知航程字段返回 `null`：

```json
{
  "status": "FLYING_UNCLAIMED",
  "departureTime": "2026-08-26 10:00:00",
  "claimExpireTime": "2026-08-29 10:00:00",
  "serverTime": "2026-08-26 12:17:00",
  "progress": null,
  "flownKm": null,
  "remainKm": null,
  "totalKm": null,
  "arrivalTime": null,
  "canRecall": true
}
```

不能返回“等待起飞”，也不能用 `0` 假装有效进度。

已认领后至少返回 `departureTime`、`arrivalTime`、`serverTime`、`distanceKm`、`status`、`flightLog`、`subscribed`。

前端根据时间戳本地插值，不需要每30秒轮询；首次进入、从后台恢复、主动刷新或跨过抵达时间时再同步。

### 6. 修改 `GET /letter/detail`

- 只有 `ARRIVED/DELIVERED` 才返回正文。
- 首次拆信只更新 `read=1`，并可将 `ARRIVED → DELIVERED`。
- 不在这里增加里程、送达次数或经验。
- 返回抵达阶段保存的本次升级结果。
- 发件人查看自己写出的正文不能触发收件已读。

### 7. 修改 `POST /letter/subscribe`

- 只允许有权限的发件人或已认领收件人操作。
- 已经抵达时明确返回“无需订阅”，不记录不会发送的通知。
- 微信授权结果以前端 `requestSubscribeMessage` 为准；后端只记录业务授权状态。

## 六、定时任务

不能依赖有人请求 `/flight` 才推进状态。

### 1. 抵达扫描

查询：

```text
status = IN_FLIGHT
AND arrival_time <= now
```

幂等执行：

1. 信件改为 `ARRIVED`。
2. 鸽子改为 `IDLE`。
3. `settled=0` 时结算成长并保存结算快照。
4. 写入 `settled=1`。
5. 有有效订阅且 `notified=0` 时发送一次通知。
6. 记录通知结果，避免重复推送。

`bind`、`flight`、`detail` 可以调用同一“推进到当前状态”方法兜底，但查询接口不能是唯一调度机制。

### 2. 未认领过期扫描

```text
status = FLYING_UNCLAIMED
AND claim_expire_time <= now
```

幂等执行：

```text
letter.status = UNCLAIMED_EXPIRED
expired_at = now
pigeon.status = IDLE
```

## 七、飞行日志

目的地确认前不生成依赖距离和路线的日志。认领成功后，基于 `letter_id`、`departure_time`、`arrival_time`、`distance_km` 确定性生成。

要求：

- 同一信件重试不能生成重复或不同日志。
- 已发生日志作为旅程回顾返回。
- 未来日志即使预生成，也只能在 `at_time <= serverTime` 时返回。
- `ARRIVED_WAITING` 必须包含起飞与抵达记录。
- V1日志只影响故事，不改变速度和抵达时间。
- 建议唯一约束：`feige_letter_event(letter_id, seq) UNIQUE`。

## 八、安全与并发

### 1. 身份

当前接口由客户端传 `openid`。必须确认签名与 `openid`、请求内容和有效期绑定，并防止重放。更理想的方式是从可信登录态解析身份，而不是把裸 `openid` 作为最终依据。

### 2. 正文与隐私

- 分享预览永远不返回正文和图片。
- 未认领浏览者不能调用 `detail`。
- 被别人认领后，无关用户只看到“这只鸽子已经找到它要找的人了”。
- 不泄露收件人身份、头像、城市和精确位置。
- 分享参数只携带随机令牌，绝不携带正文。

### 3. 必须在数据库层解决的并发

1. 两个用户同时认领。
2. 召回与认领同时发生。
3. 同一只鸽子并发发送两封。
4. 定时任务与用户请求同时结算。
5. 到达通知重复发送。
6. 认领重试导致飞行日志重复。

## 九、错误码

可保留现有数字 `code` 兼容旧模块，但增加稳定字符串 `errorKey`，前端不要依赖中文 `msg`：

| errorKey | 场景 |
| --- | --- |
| `INVALID_ARGUMENT` | 参数错误 |
| `INVALID_SIGNATURE` | 签名失败 |
| `LETTER_NOT_FOUND` | 信件不存在 |
| `ALREADY_CLAIMED` | 已被别人认领 |
| `CLAIM_EXPIRED` | 认领期已过 |
| `LETTER_RECALLED` | 已被发件人召回 |
| `SENDER_CANNOT_CLAIM` | 发件人认领自己的信 |
| `RECALL_TOO_EARLY` | 尚未达到召回时间 |
| `RECALL_NOT_ALLOWED` | 状态不允许召回 |
| `NOT_ARRIVED` | 尚未抵达 |
| `ACCESS_DENIED` | 无权限 |
| `PIGEON_BUSY` | 鸽子正在执行任务 |

## 十、验收用例

### 1. 发送即起飞

- `send` 返回 `FLYING_UNCLAIMED` 和非空 `departureTime`。
- 未认领时抵达时间、距离和时长为空。
- 发送成功后鸽子为 `SENDING`。

### 2. 三种首次打开

- 长距离且立即认领：`JUST_DEPARTED`。
- 延迟一段时间认领：`ALREADY_FLYING`。
- 短距离且隔天认领：`ARRIVED_WAITING`，等待时长正确。
- 三种情况都保留发送时的原始起飞时间。

### 3. 抢占

- 两个用户同时认领，只有一个成功。
- 失败者获得 `ALREADY_CLAIMED`，且看不到获胜者信息。
- 获胜者重试不改变路线、不重复生成日志。

### 4. 召回与过期

- 30分钟内召回返回 `RECALL_TOO_EARLY`。
- 30分钟后未认领可免费召回并释放鸽子。
- 召回与认领并发时只有一个成功。
- 72小时后自动过期并释放鸽子。
- 召回或过期后旧分享不能认领。

### 5. 抵达与成长

- 无人访问接口时，定时任务仍按时推进到 `ARRIVED`。
- 抵达后鸽子立即为 `IDLE`。
- 并发扫描和查询不重复增加成长。
- 收件人一直不拆信，不影响鸽子执行新任务。
- 拆信不重复结算。

### 6. 权限与时间

- 发件人不能认领自己的分享。
- 无关用户不能读取正文。
- 被别人认领后不泄露其身份和位置。
- 拒绝自动定位后，手动城市仍能完成认领。
- 修改设备时间不影响真实抵达判断。
- GMT+8跨日正确，前端恢复时可用 `serverTime` 校准。

## 十一、本次明确不做

- 失联、死亡和激励视频搜救；
- 看广告获得替代鸽子；
- 多只自有鸽子；
- 天气事件真实改变速度或抵达时间；
- 共享天空、陌生人拦截；
- 完整鸽舍、皮肤、稀有度和复杂属性系统。

## 十二、后端交付物

修改完成后请同步提供：

1. 更新后的接口契约；
2. 更新后的建表或迁移 SQL；
3. 状态机及定时任务说明；
4. 并发认领和幂等结算说明；
5. 可直接导入的接口测试集合或请求样例；
6. 上述验收用例的执行结果；
7. 与初版实现不兼容的字段和状态清单。

在产品、交互和前端方案最终确认前，后端不要自行扩展 V1.1/V2，也不要根据临时 UI 假设新增业务状态。


# 《飞鸽传书》本地自测手册（V5.x，纯本地，可入库）

> 用途：**在另一会话/新 AI 会话里，对本机 develop 分支改动做「JDK8 编译 + 本地启动 + 接口自测」的标准流程**。
> 满足 AGENTS.md 铁律第 2 条「本地自测通过才算可合并」。全部命令本机执行，不碰服务器。
> 本文件不含任何服务器凭据（服务器操作见 gitignore 的 `docs/ops-runbook.md`）。

---

## 0. 本机环境速查（2026-09 实测）

| 项 | 值 | 备注 |
|---|---|---|
| 仓库 | `/Users/hucong/Downloads/feige-remote_git` | 主工作区（默认 develop） |
| JDK8 | `/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home` | 必须 JDK8 编译（`/usr/libexec/java_home -V` 可查） |
| Maven | `/opt/homebrew/bin/mvn` | （homebrew） |
| 本机 MySQL | `127.0.0.1:3306`，root / `budong12345`（application.yml 明文默认） | 库 `feige_pigeon` |
| 本机 Redis | `127.0.0.1:6379`（无密码） | 仅分布式锁需要；单实例可不开 |
| 应用端口 | `8098`（application.yml `server.port`） | 上下文 `/` |
| mysql 客户端 | `/usr/local/mysql/bin/mysql`（或 `/opt/homebrew/opt/mysql@8.0/bin/mysql`） | 本机未入 PATH |

> ⚠️ `deploy/env.conf`（含支付密钥）已被 gitignore，**不要** source 它来启动（会开真实支付模式，
> 且本机 mock 测试不需要）。本地自测用下文的**临时环境变量**即可。

---

## 1. 编译（JDK8）

```bash
cd /Users/hucong/Downloads/feige-remote_git
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
/opt/homebrew/bin/mvn -q compile -DskipTests && echo "compile OK"          # 快速编译
/opt/homebrew/bin/mvn -q package -DskipTests && echo "package OK"          # 打 jar（启动自测前）
# 产物：target/feige-pigeon.jar
```

**常见编译失败**：
- `java: 错误: 不支持发行版本 5` 或用了高版本语法 → JAVA_HOME 没指到 JDK8。
- 找不到符号（某方法/类）→ 先确认是否漏了新文件（`git status` 看 untracked）。

---

## 2. 本地建库（重要：schema 有「存量库幂等坑」）

`schema.sql` 的 `CREATE TABLE IF NOT EXISTS` 是幂等的，但**中间夹的 `ALTER TABLE ... ADD COLUMN` 不是条件式的**——对已存在的旧列会报 `Duplicate column name`。因此：

- **全新库**：`mysql -uroot -pbudong12345 feige_pigeon < src/main/resources/sql/feige_schema.sql` 一次到位。
- **存量库（本机通常是）**：不要整体重放 schema.sql；**只手工执行缺失的建表/加列**（按 `git log`/contract 判断本次改动涉及的表），或对报错行单独跳过。

### 2.1 已知本地库历史坑（务必检查）

1. **feige_pigeon 唯一键**：多鸽体系要求 `UNIQUE(openid, role_key)` + `UNIQUE(openid, slot_index)`，
   老库可能残留单鸽时代的 `UNIQUE(openid)`（会挡第 2 只鸽子插入）→ 必须删：
   ```sql
   ALTER TABLE feige_pigeon.feige_pigeon DROP INDEX uk_openid;   -- 仅当 SHOW INDEX 里有 uk_openid
   ```
2. **新增表**（如 V5.1 的 feige_pay_goods、订单/订阅等）直接：
   ```sql
   -- 从 src/main/resources/sql/feige_schema.sql 里复制对应 CREATE TABLE IF NOT EXISTS 单独执行
   ```
3. 验证表结构/索引：
   ```sql
   SHOW TABLES FROM feige_pigeon;
   SHOW INDEX FROM feige_pigeon.feige_pigeon;
   SELECT slot_index, product_id, price_fen FROM feige_pigeon.feige_pay_goods;
   ```

### 2.2 支付道具表种子（V5.1 起价格/productId 以表为准，测试前必插）

```sql
INSERT INTO feige_pay_goods (slot_index, product_id, price_fen, remark) VALUES
 (2,'1001',100,'胖墩位'),(3,'1002',300,'灰灰位'),(4,'1003',600,'阿闪位'),
 (5,'1004',1000,'老邮差位'),(6,'1005',1500,'花翎位')
ON DUPLICATE KEY UPDATE product_id=VALUES(product_id), price_fen=VALUES(price_fen);
```
（productId 可用任意测试值；本地 mock 不走微信，仅验证表驱动链路。）

---

## 3. 本地启动

```bash
cd /Users/hucong/Downloads/feige-remote_git
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"

# 开发自测最小环境：dev 登录 + mock 支付 + 开付费
FG_DEV_LOGIN=true \
PAID_PIGEON_ENABLED=true \
FG_PAY_MOCK=true \
nohup java -jar target/feige-pigeon.jar > /tmp/feige-boot.log 2>&1 &
echo $! > /tmp/feige-boot.pid

# 等启动（冷启动约 40~60s）：看到 "Started FeigeApplication" 即成功
sleep 45 && grep "Started FeigeApplication" /tmp/feige-boot.log
curl -s -o /dev/null -w "%{http_code}\n" "http://127.0.0.1:8098/api/auth/wechat-login?jsCode=probe"   # 期望 200
```

**启动环境变量说明**：

| 变量 | 自测值 | 作用 |
|---|---|---|
| `FG_DEV_LOGIN=true` | 本地必开 | dev 登录兜底：jsCode→`dev_<md5>`，不真调微信 |
| `PAID_PIGEON_ENABLED=true` | 测支付时开 | 第2~6只必须购买（否则下单直接拒绝） |
| `FG_PAY_MOCK=true` | 本地默认 | mock 支付：confirm 直接置 PAID（**不开 FG_PAY_OFFER_ID/APP_KEY 时自动就是 mock**） |
| `FG_PAY_QUERY_POLL_ENABLED` | 可不开 | 查单兜底 Job（依赖真实 xpay，本地不开） |
| `FG_MP_PUSH_TOKEN/AES_KEY` | 可不配 | 测发货推送解密时才配（见 §5.3） |

**停止**：`kill $(cat /tmp/feige-boot.pid)`（或 `pkill -f feige-pigeon.jar`）

**启动失败排查**：`tail -50 /tmp/feige-boot.log`——常见：
- 表不存在 / Unknown column → 见 §2
- `APPLICATION FAILED TO START` → 多半配置项/端口占用
- Redis 连不上 → 本机若没起 redis 且没开 `FG_LOCK_ENABLED`，Redis 只在锁/缓存用到，启动不应失败

---

## 4. 通用自测套路（dev 登录 + sign 头）

```bash
B=http://127.0.0.1:8098
# 1) dev 登录：每用一个新 jsCode 得到一个新测试用户
LOGIN=$(curl -s "$B/api/auth/wechat-login?jsCode=test101")
echo "$LOGIN"
# data.openid / data.sign / data.sessionKey 三个都要用
OPENID=$(echo "$LOGIN" | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['openid'])")
SIGN=$(echo "$LOGIN" | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['sign'])")
SK=$(echo "$LOGIN" | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['sessionKey'])")

# 2) 写接口都要带请求头 sign: $SIGN（sign = md5(openid+sign-secret)，登录返回）
curl -s -X POST "$B/feige/pigeon/order?openid=$OPENID&slotIndex=4&roleKey=HUIHUI" -H "sign: $SIGN" -d "session_key=$SK"
```

---

## 5. 支付链路自测（V1.2 / V5.x，mock 模式）

> mock 模式（§3 变量）下：`下单 → 返回 CREATED + mockPay:true → confirm → PAID → 鸽子入住槽位`。
> 真实虚拟支付模式（配了 FG_PAY_OFFER_ID/APP_KEY）本地一般不测——见 §5.3 可用自加密推送模拟。

### 5.1 快乐路径（买槽位+角色）

```bash
# slots（应看到 amountFen 来自 feige_pay_goods：槽2=100 ... 槽6=1500）
curl -s "$B/feige/pigeon/slots?openid=$OPENID" | python3 -m json.tool

# 下单（返回 CREATED；真实模式下会带 payData，mock 模式无）
ORDER=$(curl -s -X POST "$B/feige/pigeon/order?openid=$OPENID&slotIndex=4&roleKey=HUIHUI" -H "sign: $SIGN")
echo "$ORDER"
ORDERNO=$(echo "$ORDER" | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['orderNo'])")

# mock 确认支付（payTradeNo 任意）
curl -s -X POST "$B/feige/pigeon/confirm?openid=$OPENID&orderNo=$ORDERNO&payTradeNo=MOCKTX1" -H "sign: $SIGN"

# 订单状态轮询接口（V5.0）
curl -s "$B/feige/order/status?orderNo=$ORDERNO&openid=$OPENID"

# 验证鸽子真入库：HUIHUI 入住槽4 + 小白槽1
/usr/local/mysql/bin/mysql -uroot -pbudong12345 -e \
  "SELECT role_key, slot_index, status FROM feige_pigeon.feige_pigeon WHERE openid='$OPENID';" 2>/dev/null
```

**期望**：下单 code 200 → confirm code 200 status=PAID → DB 有 XIAOBAI(槽1)+HUIHUI(槽4)。

### 5.2 幂等/拦截用例

| 用例 | 操作 | 期望 |
|---|---|---|
| 重复 confirm | 对已 PAID 单再 confirm | code 200 且 DB 鸽子数不变（幂等） |
| 重复下单同角色 | 已有 PAID HUIHUI 再下单 roleKey=HUIHUI | 409 `ORDER_CREATE_FAILED`（角色已拥有） |
| 同槽位换角色 | 槽4 已住 HUIHUI 再下单 slotIndex=4 roleKey=PANGDUN | 409（位置已占） |
| 全新组合 | 槽5+ASHAN（未拥有） | 200 可下 |
| 退款 | （如需）直接 SQL `UPDATE feige_order SET status='REFUNDED'` 或用支付推送模拟 | REFUNDED 后鸽子保留 |

> ⚠️ **已知设计点**（V12-5 前）：某角色/槽位若存在 **PAID 但鸽子表无对应鸽子**（脏数据），
> `selectLatestByOpenidAndRole/Slot` 会拿 PAID 单拦截新下单——排查“为什么换角色/槽位也下不了单”时，
> 先查 `feige_order` 有没有这种 PAID 脏单：
> ```sql
> SELECT o.order_no,o.role_key,o.slot_index,o.status FROM feige_order o
> LEFT JOIN feige_pigeon p ON p.openid=o.openid AND p.role_key=o.role_key
> WHERE o.status='PAID' AND p.id IS NULL;
> ```

### 5.3 真实虚拟支付「发货推送」模拟（不依赖真微信）

配推送参数重启（token/aes 可用任意值，但要与下面加密脚本一致）：

```bash
FG_MP_PUSH_TOKEN=feige FG_MP_PUSH_AES_KEY=Efi9kQa6VdSwh6fI2HBxW5z5r9KxvwJdY1rK4ccY5PX \
FG_MP_PUSH_ENCRYPT_MODE=1 \
java -jar target/feige-pigeon.jar &   # 加其余变量同上
```

1) **URL 校验（GET）**——期望返回 `echostr` 原文（**无引号**！项目 FastJSON 会把 String 加引号，此接口必须直写 response）：
```bash
python3 - <<'EOF'
import hashlib, urllib.request
token="feige"; ts="1700000000"; nonce="abc123xyz"
sig=hashlib.sha1(("".join(sorted([token,ts,nonce]))).encode()).hexdigest()
url=f"http://127.0.0.1:8098/feige/pay/notify?signature={sig}&timestamp={ts}&nonce={nonce}&echostr=HELLOECHO"
print(urllib.request.urlopen(url).read().decode())   # 期望: HELLOECHO（不带引号）
EOF
```

2) **密文推送（POST）**——构造 `xpay_goods_deliver_notify`（AES-256-CBC，key=EncodingAESKey 解 base64 前32字节）：
```bash
# 先正常 mock 下一单拿到 orderNo 与 openid，再跑下面脚本（openssl 加密 → POST /feige/pay/notify）
python3 - <<'EOF'
import base64, os, struct, subprocess, urllib.request
key = base64.b64decode("Efi9kQa6VdSwh6fI2HBxW5z5r9KxvwJdY1rK4ccY5PX" + "="); iv = key[:16]
plain = f"""<xml><ToUserName><![CDATA[gh_x]]></ToUserName><CreateTime>1700000000</CreateTime>
<MsgType><![CDATA[event]]></MsgType><Event><![CDATA[xpay_goods_deliver_notify]]></Event>
<OpenId><![CDATA[{OPENID}]]></OpenId><OutTradeNo><![CDATA[{ORDERNO}]]></OutTradeNo><Env>0</Env>
<WeChatPayInfo><MchOrderNo>4200000001</MchOrderNo><TransactionId>4200000000000001</TransactionId><PaidTime>1700000000</PaidTime></WeChatPayInfo>
<GoodsInfo><ProductId>1001</ProductId><Quantity>1</Quantity></GoodsInfo></xml>"""
msg=plain.encode()
content=os.urandom(16)+struct.pack(">I",len(msg))+msg+b"wx66a9a479c9cfe706"
pad=32-(len(content)%32); content+=bytes([pad])*pad
open("/tmp/p.bin","wb").write(content)
subprocess.run(["openssl","enc","-aes-256-cbc","-e","-K",key.hex(),"-iv",iv.hex(),"-in","/tmp/p.bin","-out","/tmp/p.enc"],check=True)
enc=base64.b64encode(open("/tmp/p.enc","rb").read()).decode()
outer=f"<xml><ToUserName><![CDATA[gh_x]]></ToUserName><Encrypt><![CDATA[{enc}]]></Encrypt></xml>"
req=urllib.request.Request("http://127.0.0.1:8098/feige/pay/notify", data=outer.encode(), headers={"Content-Type":"application/xml"})
print("resp:", urllib.request.urlopen(req,timeout=10).read().decode())   # 期望: success
EOF
# 验证订单被推送确认为 PAID + 鸽子入住
```
期望：订单 `CREATED→PAID`（pay_trade_no=TransactionId）、鸽子入住订单位置；**重复推送同一单**不再发鸽（幂等）。

3) **退款推送**：Event 换 `xpay_refund_notify`，字段含 `MchOrderId`（=orderNo），期望订单 `PAID→REFUNDED` 且鸽子保留。

---

## 6. 通用信件链路冒烟（改动可能影响时）

```bash
# 发信（写信即放飞）→ 认领 → 轮询到信 → 拆信 → 回信 —— 依赖城市坐标与飞行时间，
# 快速场景用 广州→佛山(~11km,3.7min) 或直接查 arrival_time 后手动改库推进（见既有 docs/test-api.sh 思路）。
# 本手册聚焦支付链路；信件链路细节已有 docs/test-api.sh / test-acceptance*.sh（旧路径 8089/small-soogif，仅参考）。
```

---

## 7. 自测完成后（合并前 checklist）

```bash
git status --short          # 确认改动文件符合预期、无密钥文件（deploy/env.conf 必须被忽略）
git log --oneline -3
# 1) JDK8 编译通过 ✅
# 2) 启动日志无 ERROR（业务 Job 若因本地库缺列报错 → 回 §2 补表；与本次改动无关的旧错要能分辨）
# 3) 核心用例 curl 全过 ✅
# 4) 清理：kill 应用；测试数据可选清理（DELETE feige_order/feige_pigeon/fg_user WHERE openid LIKE 'dev%'）
```

**合并**（AGENTS.md 铁律）：`git checkout develop && git pull && git merge --no-ff <feature> -m "merge: <摘要>" && git push origin develop`

---

## 8. 本手册未覆盖 / 需要服务器

- **真实微信支付端到端**（真机 wx.requestVirtualPayment、真回调）→ 只能测试机/体验版真机：见 ops-runbook（人工触发）。
- **查单兜底 Job**（FeigeXPayQueryJob，依赖 xpay 服务端真实响应）→ 本地不开。
- **七牛上传凭证** → 需要 FG_QINIU_* 配置，本地通常不测。
- **多实例分布式锁** → 本地单实例默认 `FG_LOCK_ENABLED=false`。

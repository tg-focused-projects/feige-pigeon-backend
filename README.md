# 飞鸽传书独立后端（feige-pigeon）

> 从 search-smallapp 抽取的 微信小程序「飞鸽传书」独立 SpringBoot 后端。
> 技术栈：**SpringBoot 2.3.12 (JDK8) · MyBatis · MySQL · Quartz · Swagger · FastJSON**。
> 版本管理：**Git**（master/develop 双分支 + worktree 并行开发，见 `docs/git-workflow.md`）。
> 部署：`sh deploy/test.sh` 一键发测试服（systemd 托管、健康检查、失败自动回滚）。

---

## 一、功能

- **小程序注册/登录**：`wx.login → code → jscode2session → fg_user 注册/登录一体`（不做 union 绑定）。
- **飞鸽传书 V1.1**：写信即放飞、72h 认领期、30min 免费召回、原子认领、抵达即释放鸽子并幂等结算、飞行日志、到达通知、拆信/回信。
- **定时任务**：`FeigeArrivalJob`（抵达扫描）+ `FeigeUnclaimedExpireJob`（未认领过期扫描），Quartz 每分钟。
- **接口契约 / 前端对接**：见 `docs/contract.md`、`docs/frontend-flow.md`（含时序图）。
- **验收报告**：`docs/acceptance-report.md`；测试脚本：`docs/test-api.sh`、`docs/test-acceptance.sh`。

---

## 二、目录结构

```
soogif-feige-pigeon/
├── pom.xml
├── src/main/java/com/an/feige/
│   ├── FeigeApplication.java
│   ├── config/          SwaggerConfig, WebConfig(FastJSON+CORS)
│   ├── common/          Result(信封), SignUtil(md5签名), WeChatClient(jscode2session/订阅推送)
│   ├── user/            登录/注册: entity/mapper/service/controller(AuthController)
│   └── feige/           飞鸽: entity/mapper/service/controller/job(Quartz)
├── src/main/resources/
│   ├── application.yml
│   └── sql/feige_schema.sql       # 新库 feige_pigeon 建库建表
└── docs/                契约/前端对接/验收/测试脚本/修改单
```

---

## 三、配置（`application.yml`）

| 项 | 说明 |
|---|---|
| `server.port` | 默认 `8080` |
| `spring.datasource` | `127.0.0.1:3306/feige_pigeon`（`root/budong12345`，可按环境改） |
| `feige.wechat.appid/secret` | 微信小程序凭据，可用环境变量 `FG_WECHAT_APPID/FG_WECHAT_SECRET` 注入 |
| `feige.wechat.arrival-template-id` | 到达通知订阅模板 ID（空=不推送），`FG_ARRIVAL_TEMPLATE_ID` |
| `feige.wechat.sign-secret` | 签名盐 `FG_SIGN_SECRET`（默认 `soogif-feige-dev-sign`），`sign = md5(openid+sign-secret)` |
| `feige.wechat.dev-login` | **dev 登录兜底** `FG_DEV_LOGIN`，`true` 时 jscode2session 用 jsCode 派生 openid（本地联调，勿上生产） |

---

## 四、建库

```bash
mysql -h127.0.0.1 -uroot -p < src/main/resources/sql/feige_schema.sql
# 生成 feige_pigeon 库：fg_user + feige_pigeon + feige_letter + feige_letter_event
```

---

## 五、运行（JDK8）

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
FG_DEV_LOGIN=true mvn spring-boot:run          # 本地 dev 登录联调
# 或
mvn spring-boot:run                             # 需已配置真实 appid/secret
```
- Swagger：`http://localhost:8080/swagger-ui.html`
- 打包：`mvn clean package -DskipTests` → `target/feige-pigeon.jar`（`java -jar`）

---

## 六、接口一览

### 登录/注册（`/api/auth`）
| 接口 | 说明 |
|---|---|
| `GET /wechat-login?jsCode=..` | 微信登录(注册+登录一体) → `{openid,userId,sessionKey,nickname,face,sign}`；dev 模式免真实凭据 |
| `POST /update-profile` | 更新资料 |

### 飞鸽（`/small-soogif/feige`，写操作需 `sign` 头）
| 接口 | 说明 |
|---|---|
| `POST /letter/send` | 写信并放飞 |
| `GET /letter/share-preview` | 分享预览（`claimStatus`，不返回正文/坐标） |
| `POST /letter/bind` | 原子认领（仅一人成功） |
| `POST /letter/recall` | 发件人 ≥30min 未认领召回 |
| `GET /letter/flight` | 飞行页 |
| `GET /letter/detail` | 收信/拆信（仅抵达返回正文+成长快照） |
| `POST /letter/subscribe` | 订阅到达通知（已抵达返回 `subscribed:false`） |
| `POST /letter/reply` | 回信 |
| `GET /pigeon/mine` | 我的鸽子 |

> 统一返回 `{code, msg, data, errorKey}`；`code==200` 成功；其余见 `docs/contract.md`。

---

## 七、本地联调

```bash
# 登录
curl "http://localhost:8080/api/auth/wechat-login?jsCode=abc"
# 飞鸽全链路（docs/test-api.sh 可复用，需改 BASE=http://localhost:8080 并带 dev-login）
sh docs/test-api.sh
```

---

## 八、Git 与部署

- 分支模型：`master`（稳定线）+ `develop`（集成线，对应测试服）+ `feature/*` 配合 worktree 并行开发。
- worktree 用法、一键部署、服务器结构与回滚方法详见 **`docs/git-workflow.md`**。
- 测试服一键发布：`sh deploy/test.sh`（构建→上传→原子切换→健康检查→失败自动回滚）。
- 原 SVN 母仓库（`~/Downloads/soogif-feige-pigeon-svnrepo`、`svn://118.25.178.99`）确认不再使用后可归档下线；删除本工作副本内 `.svn/` 目录即彻底脱离。

---

## 九、上线前必做

1. 关闭 `FG_DEV_LOGIN`（或 yml `dev-login=false`）。
2. 填真实微信 `appid/secret`、`sign-secret`、订阅模板 `arrival-template-id`。
3. 修改数据源账号密码为生产值。
4. 跑一遍 `docs/test-acceptance.sh` 验收。

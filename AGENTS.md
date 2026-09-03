# AGENTS.md — 飞鸽传书后端 协作规则

飞鸽传书微信小程序后端：SpringBoot 2.3.12 / JDK8，MyBatis，MySQL，Quartz + Redis 分布式锁。

## 铁律：迭代流程

1. **功能开发**在 feature worktree（基于 `develop` 创建）
2. **本地自测（合并前置门禁）**：worktree 内 JDK8 编译 + 启动自测通过，代码才算可合并
3. **合并 develop**：`--no-ff` 合并 + 推送远端 —— **⚠️ 前置条件：第 2 条本地自测必须已通过（编译无错 + 核心链路启动验证），未自测不得执行 merge；自测过程与结论须在最终回复/commit 中可见**
4. **测试机回归**：发布服务器执行 `sh /data/deploy/feige_git_release.sh develop` 部署测试机，跑全链路回归（发信→认领→到信→拆信→回信→信箱），通过后才算完成
5. **main 归人工所有**：main 分支的任何操作（commit / merge / push）都由人工执行，AI 不触碰 main
6. **生产发布**：main 打 tag → Docker 镜像（发布机 `build_feigepigeon_images.sh`）→ 腾讯云

> **本地自测标准流程见 `docs/local-dev-test.md`**（JDK8/mvn/mysql 本机路径、schema 幂等坑、
> dev 登录+sign、支付 mock/发货推送模拟等可复制命令）。修改涉及支付/订单/鸽子/DB 时必须按该手册自测；
> 自测含：JDK8 编译通过 + 应用本地启动成功（探活 200）+ 本次改动核心用例 curl 断言通过。

## 运维操作（⚠️ 人工触发，勿自动执行）

连接发布机、测试机、部署测试机、全链路回归、各类版本回归要点、常见坑，
见本地文件 `docs/ops-runbook.md`（**含凭据，gitignore 本地专属，勿上传**）。

**触发规则**：仅当用户明确说「部署测试机 / 测试机回归 / 部署到测试机」等字眼时，
才读该文件执行部署/回归；其余时间**不要**主动连接服务器、部署或跑测试。
## 启动新迭代 / 查询进度

引用 `docs/version-plan.md`：它记录版本总览、V1.x 功能清单与执行顺序、当前进度、已决议事项。新迭代开始时先读它，按其中的执行顺序推进。

## 变更纪律

- **接口或行为改动** → 同步更新 `docs/contract.md`（接口契约）并更新版本号与变更记录
- **版本进度** → 每完成一项就更新 `docs/version-plan.md` 对应状态
- **提交规范**：`type: 摘要`（feat / fix / chore / docs），中文摘要
- 新增数据库字段 → 同步 `src/main/resources/sql/feige_schema.sql`（建表 + 存量 ALTER），测试库 DDL 需人工/已授权方式执行

## 环境速查

- 分支模型：`main`（生产，人工）→ `develop`（集成，测试机跟踪）→ `feature/*`（开发）
- 测试机：`110.40.183.197`（应用端口 8098，健康探针 `GET /v2/api-docs`）
- 发布机：`118.25.193.163`，代码仓库 `/data/git/feige-pigeon-backend`，发布脚本 `/data/deploy/feige_git_release.sh`
- 服务器访问凭据来自会话上下文，不写入本文件
- 测试机数据库时区：MySQL 已设 `+08:00`；应用重启后连接池重建

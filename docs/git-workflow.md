# Git 版本管理与部署工作流

> 项目已从 SVN 切换为 Git（历史仅 2 个导入提交，未做迁移，以本次初始提交为起点）。
> 开发采用 **worktree 多工作区并行**，部署采用 **一键脚本 + systemd 托管 + 失败自动回滚**。

---

## 一、分支模型

| 分支 | 用途 | 部署去向 |
|---|---|---|
| `main` | 稳定线，随时可发布 | 生产（打 tag 发布） |
| `develop` | 日常集成线 | 测试服默认来源 |
| `feature/xxx` | 功能开发 | 在独立 worktree 中并行 |

约定：**测试服始终部署 develop**（或多人在同一时段协商，同一端口只能跑一个版本）；
功能验收通过后 `develop --merge--> main`，生产上线时 `git tag v1.x.x`。

```mermaid
gitGraph
   commit id: "init"
   branch develop
   branch feature/recall
   checkout feature/recall
   commit id: "feat"
   checkout develop
   merge feature/recall id: "验收通过合入"
   sh deploy/test.sh 测试服验证
   checkout main
   merge develop tag: "v1.0.1"
```

---

## 二、worktree 使用（多工作区并行开发）

首次设置（只需一次）：

```bash
cd ~/Downloads/feige-remote_git        # 主工作区（main）
git branch develop                     # 建集成分支
git worktree add worktrees/feige-develop develop   # 建第二工作区（仓库内 worktrees/ 已 ignore）
```

之后的日常：

```bash
git worktree add worktrees/feige-fa-recall -b feature/recall   # 新功能开新工作区
cd worktrees/feige-fa-recall                                   # 各工作区独立文件、独立分支
# ...编码、提交...
git worktree list                                              # 查看所有工作区
git worktree remove worktrees/feige-fa-recall                  # 功能合并后清理
```

> 也可以把工作区建在仓库同级目录（如 `../feige-develop`），效果相同，按个人习惯选。

要点：

- worktree 之间共享同一个 `.git` 仓库（提交/对象互通），但各自有独立的文件快照与当前分支，
  适合「一边调 A 功能、一边热修 B」不用来回 stash 切分支。
- 同一分支不能同时被两个工作区检出（Git 会拒绝，天然防冲突）。
- 主工作区建议长期停在 `main`，日常去 `develop`/feature 工作区干活。

### 日常一图流

```
feature worktree 编码 ──> 合入 develop ──> sh deploy/test.sh 部署测试服
                                              │ 通过
                              develop 合入 main ◄┘
                                              │
                                    main 打 tag 发生产
```

---

## 三、测试服部署（两条通道）

> 当前默认走 **A 通道（发布服务器构建）**；B 通道适合本机调试或紧急直发。

### A. 发布打包服务器构建发布（主流程 ✅）

**角色分工**：开发机只负责 `git push` → **118.25.193.163** 拉代码+编译+发布 → 测试服 **110.40.183.197** 原子切换。

```
开发机(mac) --push--> GitHub(origin) <--拉取-- 发布打包服务器(118.25.193.163)
                                                  │ mvn编译(JDK8)
                                                  ▼ scp+ssh(内网172.17.48.3免密)
                                            测试服 systemd 原子切换+健康检查
```

本地推完代码后，登录发布服务器执行：

```bash
sh /data/deploy/feige_git_release.sh           # 拉 develop 分支 → 编译 → 发测试服
sh /data/deploy/feige_git_release.sh main      # 发 main 分支
sh /data/deploy/feige_git_release.sh develop build-only   # 只编译不发布
```

该脚本等价替代旧菜单 `online_soa_deploy.sh` 选 **9 → 选 6（测试feige-pigeon）** 的 svn 流程，
源码位置从 `/data/svn/feige-pigeon` 变为 `/data/git/feige-pigeon-backend`。
脚本源文件随仓库维护：`deploy/release-server/feige_git_release.sh`（服务器上为副本，改完记得重新拷贝）。

发布服务器环境备忘：

| 组件 | 说明 |
|---|---|
| GitHub 认证 | `~/.ssh/id_ed25519_github`（已加到 joe-dev111 账号 SSH Keys），`~/.ssh/config` 已配 github.com |
| 到测试服 | `~/.ssh/id_ed25519_to-test`，别名 `ssh feige-test`（内网 172.17.48.3 免密） |
| Maven / JDK | `/opt/apache-maven-3.3.9`（阿里云镜像）/ `/usr/java/jdk1.8.0_192` |

### B. 开发机直发（备用）

```bash
sh deploy/test.sh                # 构建 + 上传 + 原子切换 + 健康检查（失败自动回滚）
sh deploy/test.sh --skip-build   # 复用 target/feige-pigeon.jar，不重新打包
```

背后发生的事：

1. JDK8 `mvn clean package` 产出 `target/feige-pigeon.jar`，并写入 `RELEASE_INFO`（分支/commit/时间/构建人）
2. scp 到服务器 `/tmp/feige-upload/`
3. 远程执行 `/opt/feige/bin/deploy-switch.sh`：收进 `releases/<时间戳>/` → `current` 符号链接切换 →
   `systemctl restart feige-pigeon` → 探活 `http://127.0.0.1:8098/v2/api-docs`（最长 ~90s）
4. 健康检查不过 ⇒ 自动切回上一版本重启；只保留最近 5 个版本

### 服务器标准结构

```
/opt/feige/
├── current -> releases/20260827xxxx    # systemd 启动入口（符号链接）
├── releases/<时间戳>/feige-pigeon.jar  # 每次部署一个版本目录，含 RELEASE_INFO
├── releases/<时间戳>/RELEASE_INFO      # 对应的分支+commit+构建信息
├── bin/deploy-switch.sh                # 服务器端原子切换脚本
└── env.conf                            # systemd 环境文件(600)，由 init-server.sh 从旧配置迁移
/etc/systemd/system/feige-pigeon.service  # 服务单元（崩溃自动拉起 Restart=on-failure）
/data/logs/feige/feige-pigeon.log         # 应用日志（logrotate: 日转、留7份、50M切割压缩）
/usr/local/bin/app-clean-logs.sh          # 历史日志清理（cron 每天01:30，保留5天）
```

### 常用运维命令（登录服务器）

```bash
sudo systemctl status feige-pigeon          # 服务状态
sudo journalctl -u feige-pigeon -f          # 实时日志
tail -f /data/logs/feige/feige-pigeon.log   # 传统日志文件
ls -lt /opt/feige/releases | head           # 部署历史
cat /opt/feige/current/RELEASE_INFO         # 当前线上版本是哪个 commit
```

### 手动回滚

```bash
cd /opt/feige && ln -sfn releases/<旧时间戳> current && sudo systemctl restart feige-pigeon
```

### C. 生产环境发布（Docker 镜像 → 腾讯云）

生产走 **镜像交付**：发布机把 main 分支构建成 Docker 镜像推送到腾讯云上海镜像仓，
线上服务（TKE/容器编排）更新镜像 tag 滚动重启即完成上线。

```bash
# 登录发布打包服务器 118.25.193.163 执行：
sh /data/Dockerfile/feige-api/build_feigepigeon_images.sh             # main 构建并推送
sh /data/Dockerfile/feige-api/build_feigepigeon_images.sh main v1.0.0 # 显式指定镜像 tag
```

关键信息：

| 项 | 值 |
|---|---|
| 镜像 | `ccr.ccs.tencentyun.com/feige-pigeon/feige-pigeon:<日期_时间>` |
| 基础镜像 | `ccr.ccs.tencentyun.com/gif-tools/openjdk8:latest`（纯 JDK8） |
| 源码分支 | 固定 `main` 主干（脚本对非 main 分支有警告） |
| 脚本位置 | 发布机 `/data/Dockerfile/feige-api/build_feigepigeon_images.sh`（仓库留档于 `deploy/docker/feige-api/`，以服务器为权威） |
| 构建日志 | 同目录 `docker-build.log` |

**运行时环境变量在腾讯云容器侧配置**（镜像不携带任何凭据）：`FG_DEV_LOGIN=false`、
`SPRING_DATASOURCE_URL/USERNAME/PASSWORD`、`FG_WECHAT_APPID/SECRET`、`FG_SIGN_SECRET`、
`FG_ARRIVAL_TEMPLATE_ID`；容器内 `cat /app/BUILD_INFO` 可查版本来源。

---

## 四、挂接远程托管仓库（按需）

当前仅本地单机仓库，建议尽快推一份到托管平台防丢：

```bash
# 已挂接 GitHub（tg-focused-projects/feige-pigeon-backend）
git remote add origin git@github.com:tg-focused-projects/feige-pigeon-backend.git
git push -u origin main develop            # 首推带 -u
git push origin --tags
```

svn 历史如确需追溯：原 SVN 服务 `svn://118.25.178.99/soogif/feige-pigeon/trunk` 与本地母仓库
`~/Downloads/soogif-feige-pigeon-svnrepo` 在确认不再使用后可归档下线（本地工作副本中的 `.svn/`
目录删除即彻底脱离）。

---

## 五、提交规范（轻量约定）

格式 `type: 摘要`，常用 type：`feat / fix / chore / docs / refactor / test`。
例：`feat: 回信支持追加图片`、`fix: 认领并发下重复结算 (#12)`。

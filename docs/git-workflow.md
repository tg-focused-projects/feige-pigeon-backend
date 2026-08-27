# Git 版本管理与部署工作流

> 项目已从 SVN 切换为 Git（历史仅 2 个导入提交，未做迁移，以本次初始提交为起点）。
> 开发采用 **worktree 多工作区并行**，部署采用 **一键脚本 + systemd 托管 + 失败自动回滚**。

---

## 一、分支模型

| 分支 | 用途 | 部署去向 |
|---|---|---|
| `master` | 稳定线，随时可发布 | 生产（打 tag 发布） |
| `develop` | 日常集成线 | 测试服默认来源 |
| `feature/xxx` | 功能开发 | 在独立 worktree 中并行 |

约定：**测试服始终部署 develop**（或多人在同一时段协商，同一端口只能跑一个版本）；
功能验收通过后 `develop --merge--> master`，生产上线时 `git tag v1.x.x`。

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
   checkout master
   merge develop tag: "v1.0.1"
```

---

## 二、worktree 使用（多工作区并行开发）

首次设置（只需一次）：

```bash
cd ~/Downloads/feige-remote_git        # 主工作区（master）
git branch develop                     # 建集成分支
git worktree add ../feige-develop develop   # 建第二工作区
```

之后的日常：

```bash
git worktree add ../feige-fa-recall -b feature/recall   # 新功能开新工作区
cd ../feige-fa-recall                                   # 各工作区独立文件、独立分支
# ...编码、提交...
git worktree list                                       # 查看所有工作区
git worktree remove ../feige-fa-recall                  # 功能合并后清理
```

要点：

- worktree 之间共享同一个 `.git` 仓库（提交/对象互通），但各自有独立的文件快照与当前分支，
  适合「一边调 A 功能、一边热修 B」不用来回 stash 切分支。
- 同一分支不能同时被两个工作区检出（Git 会拒绝，天然防冲突）。
- 主工作区建议长期停在 `master`，日常去 `develop`/feature 工作区干活。

### 日常一图流

```
feature worktree 编码 ──> 合入 develop ──> sh deploy/test.sh 部署测试服
                                              │ 通过
                              develop 合入 master ◄┘
                                              │
                                    master 打 tag 发生产
```

---

## 三、测试服部署（110.40.183.197）

### 一键部署

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

---

## 四、挂接远程托管仓库（按需）

当前仅本地单机仓库，建议尽快推一份到托管平台防丢：

```bash
# 以 Gitee 私有仓库为例（GitHub/GitLab 同理）
git remote add origin git@gitee.com:<你的账号>/feige-pigeon.git
git push -u origin master develop            # 首推带 -u
git push origin --tags
```

svn 历史如确需追溯：原 SVN 服务 `svn://118.25.178.99/soogif/feige-pigeon/trunk` 与本地母仓库
`~/Downloads/soogif-feige-pigeon-svnrepo` 在确认不再使用后可归档下线（本地工作副本中的 `.svn/`
目录删除即彻底脱离）。

---

## 五、提交规范（轻量约定）

格式 `type: 摘要`，常用 type：`feat / fix / chore / docs / refactor / test`。
例：`feat: 回信支持追加图片`、`fix: 认领并发下重复结算 (#12)`。

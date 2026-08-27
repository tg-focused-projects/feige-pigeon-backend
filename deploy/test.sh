#!/usr/bin/env bash
# =============================================================================
# 一键部署到测试服务器（SVN 时代手动拷 jar 的工作由此替代）
#
# 用法：
#   sh deploy/test.sh                 # 构建 + 上传 + 原子切换 + 健康检查
#   sh deploy/test.sh --skip-build    # 复用 target/feige-pigeon.jar 直接部署
#
# 流程：JDK8 打包(target/feige-pigeon.jar) -> 附带构建信息 ->
#       scp 上传 -> 远程 /opt/feige/bin/deploy-switch.sh 完成原子切换，
#       失败自动回滚上一版本。
# 依赖：本机 JDK8 + Maven；SSH 免密已配置（deploy 阶段不再需要密码）。
# 目标机可通过环境变量覆盖：FG_DEPLOY_HOST / FG_DEPLOY_USER
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")/.."   # 仓库根目录

HOST=${FG_DEPLOY_HOST:-110.40.183.197}
USER_NAME=${FG_DEPLOY_USER:-ubuntu}
REMOTE_TMP=/tmp/feige-upload
SWITCH_SCRIPT=/opt/feige/bin/deploy-switch.sh
SKIP_BUILD=0
for a in "$@"; do [ "$a" = "--skip-build" ] && SKIP_BUILD=1; done

JAR=target/feige-pigeon.jar

if [ "$SKIP_BUILD" != 1 ]; then
    # ---- 1) JDK8 构建 ------------------------------------------------------
    if [ -z "${JAVA_HOME:-}" ] || [[ "$(java -version 2>&1)" != *'"1.8'* ]]; then
        if command -v /usr/libexec/java_home >/dev/null 2>&1; then
            export JAVA_HOME=$(/usr/libexec/java_home -v 1.8)   # macOS
        else
            echo "!! 请设置 JAVA_HOME 指向 JDK8" >&2; exit 1
        fi
    fi
    export PATH="$JAVA_HOME/bin:$PATH"
    echo "==> 构建 JAVA_HOME=${JAVA_HOME}"
    mvn -B -q clean package -DskipTests
fi
[ -f "$JAR" ] || { echo "!! 缺少 ${JAR} 请先构建" >&2; exit 1; }

# ---- 2) 构建信息（随版本入库，方便追溯线上到底跑的哪个提交） -----------------
BRANCH=$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo no-git)
COMMIT=$(git rev-parse --short HEAD 2>/dev/null || echo unknown)
INFO="branch=$BRANCH commit=$COMMIT built_at=$(date '+%F %T') by=$(whoami)@$(hostname -s)"
echo "$INFO" > target/RELEASE_INFO
echo "==> 本次构建：$INFO"

# ---- 3) 上传 ---------------------------------------------------------------
ssh "$USER_NAME@$HOST" "mkdir -p $REMOTE_TMP"
echo "==> 上传 jar（$(du -h "$JAR" | cut -f1)）"
scp -q "$JAR" "$USER_NAME@$HOST:$REMOTE_TMP/feige-pigeon.jar"
scp -q target/RELEASE_INFO "$USER_NAME@$HOST:$REMOTE_TMP/RELEASE_INFO" 2>/dev/null || true

# ---- 4) 远程原子切换 + 健康检查 + 失败回滚 ----------------------------------
echo "==> 切换部署"
ssh "$USER_NAME@$HOST" "bash $SWITCH_SCRIPT $REMOTE_TMP/feige-pigeon.jar '$INFO'"

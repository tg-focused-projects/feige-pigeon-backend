#!/bin/bash
# =============================================================================
# feige-pigeon 发布流程（git 版）—— 部署在发布打包服务器 118.25.193.163
# 路径：/data/deploy/feige_git_release.sh
# 用法：
#   sh feige_git_release.sh              # 默认拉 develop 分支发测试服
#   sh feige_git_release.sh main         # 指定分支
#   sh feige_git_release.sh develop build-only   # 只编译不发布
# 流程（对应旧 online_soa_deploy.sh 选9->选6）：
#   svn update                =>  git fetch + reset --hard origin/<branch>
#   mvn clean package         =>  同款 maven(/opt/apache-maven-3.3.9) JDK8 编译
#   sshpass scp 到内网目标     =>  ssh feige-test 免密传输(ed25519)
#   远程手动重启               =>  测试服 deploy-switch.sh 原子切换+健康检查+自动回滚
# =============================================================================
set -euo pipefail

repo_dir=/data/git/feige-pigeon-backend          # git 工作副本
branch=${1:-develop}                             # 默认发布 develop
action=${2:-deploy}                              # deploy | build-only
mvn=/opt/apache-maven-3.3.9/bin/mvn
export JAVA_HOME=/usr/java/jdk1.8.0_192          # 与测试服运行时同款 JDK8
export PATH="$JAVA_HOME/bin:$PATH"
test_host=feige-test                             # ~/.ssh/config 内网免密别名 -> ubuntu@172.17.48.3
remote_jar_dir=/tmp/feige-upload

echo "==> [1/4] 更新代码 branch=$branch"
cd "$repo_dir"
git fetch origin --prune
# 本地严格对齐远端指定分支（发布机不承载开发，强覆盖保证与仓库一致）
git checkout -q "$branch" 2>/dev/null || git checkout -q -b "$branch" "origin/$branch"
git reset --hard "origin/$branch"
commit=$(git log -1 --format='%h %an %s')
echo "    当前版本: $commit"

echo "==> [2/4] Maven 编译 (JDK8)"
$mvn clean package -DskipTests -q
jar=target/feige-pigeon.jar
[ -f "$jar" ] || { echo "✘ 构建产物缺失: $jar" >&2; exit 1; }
echo "    构建OK: $(du -h "$jar" | cut -f1)"

if [ "$action" = "build-only" ]; then
    echo "==> build-only 模式，不发布。产物: $repo_dir/$jar"
    exit 0
fi

echo "==> [3/4] 上传到测试服"
ssh "$test_host" "mkdir -p $remote_jar_dir"
scp -q "$jar" "$test_host:$remote_jar_dir/feige-pigeon.jar"

echo "==> [4/4] 原子切换部署（健康检查失败自动回滚上一版本）"
info="branch=$branch commit=$(git log -1 --format=%h) built_on=release-server(118.25.193.163) at=$(date '+%F %T')"
ssh "$test_host" "bash /opt/feige/bin/deploy-switch.sh $remote_jar_dir/feige-pigeon.jar '$info'"

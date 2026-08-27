#!/bin/bash
# =============================================================================
# 飞鸽传书(feige-pigeon) 生产环境发布脚本 —— Docker 镜像版
# 部署位置：发布打包服务器 118.25.193.163 的 /data/Dockerfile/feige-api/
# 对标脚本：/data/Dockerfile/tool-api/build_toolapi_images.sh（已改为 git 流程）
#
# 用法：
#   sh build_feigepigeon_images.sh                # 从 origin/main 构建+推送(默认)
#   sh build_feigepigeon_images.sh develop        # 指定分支(生产请用 main!)
#   sh build_feigepigeon_images.sh main v1.0.1    # 显式指定镜像 tag(默认日期时间戳)
#
# 流程：git fetch + reset 对齐远端分支 → Maven(JDK8) 打包 → 生成 Dockerfile
#       → docker build → tag 到腾讯云上海镜像仓 → push → 清理本地构建镜像
# 产物：ccr.ccs.tencentyun.com/gif-tools/feige-pigeon:<tag>
# 上线最后一步：在腾讯云容器服务控制台把服务镜像更新为该 tag 并滚动重启。
#
# 运行时环境变量在腾讯云侧配置(容器编排的环境变量)，生产必须注入：
#   FG_PORT(默认8080)、FG_DEV_LOGIN=false、SPRING_DATASOURCE_URL/USERNAME/PASSWORD、
#   FG_WECHAT_APPID/FG_WECHAT_SECRET、FG_SIGN_SECRET、FG_ARRIVAL_TEMPLATE_ID
# =============================================================================
set -euo pipefail

repo_dir=/data/git/feige-pigeon-backend        # git 工作副本(与测试服发布共用)
branch=${1:-main}                              # 生产默认 main 主干
img_tag=${2:-$(date +%Y-%m-%d_%H%M)}           # 默认镜像tag: 日期_时间

mvn=/opt/apache-maven-3.3.9/bin/mvn
export JAVA_HOME=/usr/java/jdk1.8.0_192
export PATH="$JAVA_HOME/bin:$PATH"

registry=ccr.ccs.tencentyun.com
# 镜像命名空间：feige 独立于旧业务的 gif-tools；可用环境变量 FG_IMAGE_NS 覆盖
namespace=${FG_IMAGE_NS:-feige-pigeon}
app_name=feige-pigeon
# 基础镜像沿用账号下既有 gif-tools/openjdk8(纯JDK8)，个人版同账号跨命名空间可直接拉取
base_image=$registry/gif-tools/openjdk8:latest
dockerfile_path=/data/Dockerfile/feige-api

echo "==> [1/5] 更新代码 branch=$branch"
cd "$repo_dir"
git fetch origin --prune
git checkout -q "$branch" 2>/dev/null || git checkout -q -b "$branch" "origin/$branch"
git reset --hard "origin/$branch"
commit=$(git log -1 --format='%h %an %s')
if [ "$branch" != "main" ]; then
    echo "!! ⚠⚠ 警告: 正在从非主干分支[$branch]构建生产镜像，确认无误可忽略"
fi
echo "    构建版本: $commit"

echo "==> [2/5] Maven 编译 (JDK8)"
$mvn -q clean package -DskipTests
jar=target/feige-pigeon.jar
[ -f "$jar" ] || { echo "✘ 构建产物缺失: $jar" >&2; exit 1; }
echo "    构建OK: $(du -h "$jar" | cut -f1)"

mkdir -p "$dockerfile_path"
cd "$dockerfile_path"
cp -f "$repo_dir/$jar" ./feige-pigeon.jar

echo "==> [3/5] 生成 Dockerfile"
cat > Dockerfile <<EOF
FROM $base_image
ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-Xms512m -Xmx1024m"
WORKDIR /app
ADD feige-pigeon.jar /app/app.jar
RUN echo "$img_tag | $commit | built_on=\$(hostname)" > /app/BUILD_INFO
EXPOSE 8080
CMD ["sh", "-c", "java \$JAVA_OPTS -jar /app/app.jar --server.port=\${FG_PORT:-8080}"]
EOF
cat Dockerfile

echo "==> [4/5] docker build -> ${app_name}:${img_tag}"
docker build -t "${app_name}:${img_tag}" . --no-cache > docker-build.log 2>&1 \
    || { echo "✘ 构建失败，日志尾部:" >&2; tail -20 docker-build.log >&2; exit 1; }
grep -E "Successfully built|Successfully tagged" docker-build.log || true
rm -f ./feige-pigeon.jar                       # 收尾清掉临时jar避免堆积

echo "==> [5/5] 推送腾讯云镜像仓库并清理本地"
full_image=$registry/$namespace/$app_name:$img_tag
docker tag "${app_name}:${img_tag}" "$full_image"
docker push "$full_image"
docker rmi "${app_name}:${img_tag}" >/dev/null 2>&1 || true
docker rmi "$full_image" >/dev/null 2>&1 || true

echo ""
echo "✔ 生产镜像发布完成"
echo "   镜像地址: $full_image"
echo "   构建版本: $commit"
echo "---------------------------------------------------------------"
echo "上线收尾（腾讯云控制台）：容器服务 -> 相应集群/服务 -> 更新镜像 -> "
echo "  选择 $registry/$namespace/$app_name:$img_tag 后滚动更新即可。"
echo "运行时环境变量务必核对：FG_DEV_LOGIN=false 与生产数据库/微信凭据。"

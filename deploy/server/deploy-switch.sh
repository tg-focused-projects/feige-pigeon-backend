#!/usr/bin/env bash
# =============================================================================
# 服务器端原子切换脚本（由开发机 deploy/test.sh 通过 SSH 调用，也可手工执行）
# 用法：bash /opt/feige/bin/deploy-switch.sh <上传的新jar绝对路径> [构建信息]
# 流程：收包入库 -> 记录上一版本 -> 切 current 符号链接 -> 重启服务
#       -> 健康检查（约90s）-> 失败自动回滚上一版本并重启
# ============================================================================
set -uo pipefail

UPLOADED_JAR=$1
BUILD_INFO=${2:-unknown}
APP_NAME=feige-pigeon
APP_DIR=/opt/feige
CUR_LINK=$APP_DIR/current
JAR_NAME=${APP_NAME}.jar
KEEP=5                     # 线上保留的历史版本数
PORT=$(grep -E '^FG_PORT=' "$APP_DIR/env.conf" | cut -d= -f2 | tr -d '"' || echo 8080)
HEALTH_URL="http://127.0.0.1:${PORT}/v2/api-docs"

die() { echo "✘ $*" >&2; exit 1; }

[ -f "$UPLOADED_JAR" ] || die "找不到待部署 jar: $UPLOADED_JAR"

TS=$(date +%Y%m%d%H%M%S)
REL_DIR=$APP_DIR/releases/$TS
PREV_TARGET=$(readlink -f "$CUR_LINK" 2>/dev/null || true)

echo "==> [1/5] 收包入库 $REL_DIR"
mkdir -p "$REL_DIR"
cp -f "$UPLOADED_JAR" "$REL_DIR/$JAR_NAME"
rm -f "$UPLOADED_JAR"
echo "$BUILD_INFO
deployed_at=$(date '+%F %T')" > "$REL_DIR/RELEASE_INFO"
cat "$REL_DIR/RELEASE_INFO"

echo "==> [2/5] 切换符号链接（prev=${PREV_TARGET:-<无>}）"
ln -sfn "$REL_DIR" "$CUR_LINK"

echo "==> [3/5] 重启服务"
sudo systemctl restart "$APP_NAME.service" || true

echo "==> [4/5] 健康检查 $HEALTH_URL"
OK=0
for i in $(seq 1 30); do
    sleep 3
    CODE=$(curl -s -o /dev/null -w '%{http_code}' -m 3 "$HEALTH_URL" 2>/dev/null || echo 000)
    if [ "$CODE" = "200" ]; then OK=1; break; fi
    printf '    [%02d/30] http=%s\n' "$i" "$CODE"
done

if [ "$OK" = 1 ]; then
    echo "==> [5/5] 清理旧版本（保留最新 $KEEP 个）"
    ls -1dt "$APP_DIR"/releases/*/ | tail -n +$((KEEP + 1)) | xargs -r rm -rf
    ACTIVE=$(readlink -f "$CUR_LINK")
    echo ""
    echo "✔ 部署成功：$ACTIVE"
    sudo systemctl --no-pager status "$APP_NAME.service" | head -5 || true
    exit 0
fi

echo "✘ 健康检查未通过，打印错误日志："
sudo journalctl -u "$APP_NAME.service" -n 60 --no-pager 2>/dev/null | tail -40

if [ -n "$PREV_TARGET" ] && [ -d "$PREV_TARGET" ] && [ "$PREV_TARGET" != "$REL_DIR" ]; then
    echo "==> 自动回滚到上一版本 $PREV_TARGET"
    ln -sfn "$PREV_TARGET" "$CUR_LINK"
    sudo systemctl restart "$APP_NAME.service" || true
    for i in $(seq 1 20); do
        sleep 3
        CODE=$(curl -s -o /dev/null -w '%{http_code}' -m 3 "$HEALTH_URL" 2>/dev/null || echo 000)
        [ "$CODE" = "200" ] && { echo "✔ 回滚成功，服务已恢复"; exit 2; }
    done
    die "回滚后仍不健康(http=$CODE)，请登录服务器排查"
else
    die "无上一版本可回滚（首次部署），请查看上方日志排查"
fi

#!/usr/bin/env bash
# =============================================================================
# feige-pigeon 测试/生产服务器 一次性初始化（幂等，可重复执行）
# 在目标服务器上以具备 sudo 的用户运行：sudo bash init-server.sh
# 职责：
#   1) 建立 /opt/feige 标准目录结构（releases/current/bin）
#   2) 将旧式 /data/deploy/conf_feige/env.conf 转换为 systemd 环境
#      文件 /opt/feige/env.conf（去 export 前缀，保留原值）
#   3) 安装 systemd 单元 feige-pigeon.service（enable 不 start，
#      首次启动交给 deploy-switch.sh / deploy/test.sh 完成）
#   4) 安装 logrotate 规则与日志目录授权
#   5) 若发现旧方式（nohup/root）运行的 feige-pigeon 进程则先行停止
# =============================================================================
set -euo pipefail

APP_NAME=feige-pigeon
APP_DIR=/opt/feige
BIN_JAVA=/usr/java/jdk1.8.0_192/bin/java
LOG_DIR=/data/logs/feige
OLD_ENV=/data/deploy/conf_feige/env.conf
RUN_USER=${SUDO_USER:-$(logname 2>/dev/null || echo ubuntu)}

echo "==> [1/6] 目录结构"
install -d -o "$RUN_USER" -g "$RUN_USER" "$APP_DIR/releases" "$APP_DIR/bin" "$LOG_DIR"

echo "==> [2/6] 环境配置 /opt/feige/env.conf"
if [ ! -f "$APP_DIR/env.conf" ]; then
    if [ -f "$OLD_ENV" ]; then
        # 旧配置沿用原值：去掉 export 前缀即为 systemd EnvironmentFile 格式
        sed -E 's/^[[:space:]]*export[[:space:]]+//' "$OLD_ENV" \
          | grep -Ev '^[[:space:]]*(#|$)' > "$APP_DIR/env.conf"
        chmod 600 "$APP_DIR/env.conf"; chown "$RUN_USER":"$RUN_USER" "$APP_DIR/env.conf"
        echo "    已从 $OLD_ENV 迁移生成（原文件保留未动）"
    else
        echo "!! 未找到 $OLD_ENV，请手工创建 $APP_DIR/env.conf（参考仓库 deploy/env.systemd.conf.example）" >&2
        exit 1
    fi
else
    echo "    已存在，跳过"
fi

echo "==> [3/6] systemd 单元 /etc/systemd/system/${APP_NAME}.service"
cat > "/etc/systemd/system/${APP_NAME}.service" <<UNIT
[Unit]
Description=feige-pigeon SpringBoot backend (WeChat mini-program)
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=${RUN_USER}
Group=${RUN_USER}
WorkingDirectory=${APP_DIR}/current
EnvironmentFile=${APP_DIR}/env.conf
ExecStart=${BIN_JAVA} \$FG_JAVA_OPTS -jar ${APP_DIR}/current/${APP_NAME}.jar --server.port=\$FG_PORT
SuccessExitStatus=143
Restart=on-failure
RestartSec=5
TimeoutStopSec=45
LimitNOFILE=65536
StandardOutput=append:${LOG_DIR}/${APP_NAME}.log
StandardError=append:${LOG_DIR}/${APP_NAME}.err.log

[Install]
WantedBy=multi-user.target
UNIT

echo "==> [4/6] logrotate 规则"
cat > /etc/logrotate.d/feige-pigeon <<'ROTATE'
/data/logs/feige/*.log {
    daily
    rotate 7
    size 50M
    maxsize 200M
    compress
    delaycompress
    missingok
    notifempty
    copytruncate
}
ROTATE

echo "==> [5/6] 停旧进程 + 启用服务"
# 终止旧式 nohup 启动的同名 jar（仅匹配 feige-pigeon.jar，不影响 tomcat 等其他业务）
if pgrep -f "java .*${APP_NAME}\.jar" >/dev/null 2>&1; then
    echo "    发现旧 feige-pigeon 进程，停止中..."
    sudo pkill -TERM -f "java .*${APP_NAME}\.jar" || true
    for i in $(seq 1 20); do pgrep -f "java .*${APP_NAME}\.jar" >/dev/null || break; sleep 1; done
    pgrep -f "java .*${APP_NAME}\.jar" >/dev/null 2>&1 && sudo pkill -KILL -f "java .*${APP_NAME}\.jar" || true
    echo "    已停止"
else
    echo "    无旧进程"
fi
systemctl daemon-reload
systemctl enable "${APP_NAME}.service" >/dev/null 2>&1
echo "    已 enable（启动见下一步）"

echo "==> [6/6] 纳管存量 jar 并启动（最小化服务中断）"
if [ ! -e "$APP_DIR/current" ] && [ -f "$APP_DIR/${APP_NAME}.jar" ]; then
    REL0="$APP_DIR/releases/legacy-$(date +%Y%m%d%H%M)"
    install -d -o "$RUN_USER" -g "$RUN_USER" "$REL0"
    cp -f "$APP_DIR/${APP_NAME}.jar" "$REL0/${APP_NAME}.jar"
    { echo "branch=legacy(旧nohup部署迁移) commit=unknown"; echo "deployed_at=$(date '+%F %T')"; } > "$REL0/RELEASE_INFO"
    ln -sfn "$REL0" "$APP_DIR/current"
fi
if [ -e "$APP_DIR/current" ]; then
    sudo systemctl restart "${APP_NAME}.service" || true
    sleep 5
    PORT=$(grep -E '^FG_PORT=' "$APP_DIR/env.conf" | cut -d= -f2 | tr -d '"' || echo 8080)
    CODE=$(curl -s -o /dev/null -w '%{http_code}' -m 3 "http://127.0.0.1:${PORT}/v2/api-docs" 2>/dev/null || echo 000)
    if [ "$CODE" = "200" ]; then
        echo "    ✔ systemd 已接管，服务健康（port=$PORT）"
    else
        echo "    ⚠ 服务暂未探活(http=$CODE)，属正常——首次纳管冷启动较慢；可稍后重试或直接跑 deploy/test.sh 部署新版本"
    fi
else
    echo "    （无存量 jar，跳过启动，等待首次 deploy/test.sh）"
fi

echo ""
echo "✔ 初始化完成。回开发机执行： sh deploy/test.sh 完成首次正式部署"

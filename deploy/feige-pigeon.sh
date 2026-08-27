#!/bin/bash
# =============================================================================
# 飞鸽传书 feige-pigeon.jar 启动/发布脚本（SpringBoot 单 jar，内嵌 Tomcat）
# 结构参照 deploy_smallapp2018.sh：停进程 -> 备份 -> 部署 -> 启动 -> 状态检查
# 用法：sh deploy-feige-pigeon.sh
# 前提：
#  - mvn clean package -DskipTests 已产出 target/feige-pigeon.jar
#  - JDK8（export JAVA_HOME 指向 JDK8）
#  - 环境配置从 /data/deploy/conf_feige/env.conf 读取（DB/微信/dev-login/端口）
# =============================================================================
# JAVA_HOME：若未显式设置，则尝试从 java 命令推导（发布机可改成实际 JDK8 路径，如 /usr/java/jdk1.8.0_275）
if [ -z "$JAVA_HOME" ]; then
    _java_bin=$(readlink -f "$(command -v java 2>/dev/null)" 2>/dev/null || true)
    if [ -n "$_java_bin" ]; then
        JAVA_HOME=$(dirname "$(dirname "$_java_bin")")
    fi
fi
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

baseName=/opt/feige              # 运行目录(程序所在)
fileName=feige-pigeon
jarName=${fileName}.jar
configDir=/data/deploy/conf_feige   # 环境配置目录
deploySrc=/data/deploy/${fileName}.jar   # 待发布的新 jar
logFile=/data/logs/feige/feige-pigeon.log
FG_PORT=${FG_PORT:-8080}

mkdir -p "$baseName" "$(dirname "$logFile")" /data/backup

echo "feige-pigeon will restart"

# 1) 关闭旧进程（按 jar 名匹配，等价于旧脚本的 tomcat_id 处理）
pid=$(ps -ef | grep "$jarName" | grep -v grep | awk '{print $2}')
echo "pid=$pid"
kill -9 $pid 2>/dev/null
sleep 3
pid=$(ps -ef | grep "$jarName" | grep -v grep | awk '{print $2}')
if [ -z "$pid" ]; then
    echo "old process already shutdown"

    # 2) 备份旧 jar
    if [ -f "$baseName/$jarName" ]; then
        mv -f "$baseName/$jarName" /data/backup/${fileName}.$(date +%Y%m%d%H%M).jar
    fi

    # 3) 部署新 jar
    if [ -f "$deploySrc" ]; then
        cp -f "$deploySrc" "$baseName/$jarName"
    else
        echo "no new jar: $deploySrc"
        exit 1
    fi

    # 4) 读取环境配置（DB/微信/dev-login/端口）
    [ -f "$configDir/env.conf" ] && . "$configDir/env.conf"

    # 5) 启动
    cd "$baseName"
    nohup java -jar "$jarName" --server.port="$FG_PORT" > "$logFile" 2>&1 &
    if [ $? -eq 0 ]; then
        echo "feige-pigeon start cmd issued"
    else
        echo "feige-pigeon start fail"
    fi

    # 6) 状态检查（等待 + 探活）
    sleep 20
    code=$(curl -s -o /dev/null -w "%{http_code}" "http://127.0.0.1:${FG_PORT}/api/auth/wechat-login?jsCode=probe" 2>/dev/null)
    if [ "$code" = "200" ]; then
        echo "feige-pigeon running successful (port=$FG_PORT)"
    else
        echo "feige-pigeon running fail (http=$code)；看日志 $logFile"
        tail -50 "$logFile"
        exit 1
    fi
else
    echo "old process still exist"
fi

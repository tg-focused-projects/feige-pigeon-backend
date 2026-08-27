#!/usr/bin/env bash
# =============================================================================
# 《飞鸽传书》V1.1（发送即起飞版）接口本地联调脚本
#
# 前提：
#  1) 后端部署在本地 Tomcat（search-smallapp 默认 8089，上下文 /）
#  2) 【重要】先临时放行 FeigeController.sign()，否则写接口返回 errorKey=INVALID_SIGNATURE
#  3) 已执行 feige_v1.sql（或 feige_v1_migration.sql）
#
# 用法：
#   sh test-api.sh                  # 默认 short；SCENARIO=fast|short|city
#   BASE=http://host:port sh test-api.sh
# =============================================================================
set -e
BASE="${BASE:-http://localhost:8089}"
API="$BASE/small-soogif/feige"
SCENARIO="${SCENARIO:-short}"
RUN="$(date +%s)"
SENDER_OPENID="${SENDER_OPENID:-fg_sender_$RUN}"
RECIPIENT_OPENID="${RECIPIENT_OPENID:-fg_recipient_$RUN}"
CONTENT="嗨，这是一封测试信 🕊️ 记得回我哦！"

case "$SCENARIO" in
  fast)
    echo "[场景] fast  同城约1.8km / 约37秒到信"
    S_PROV="广东"; S_CITY="广州"; S_LAT="23.1291"; S_LNG="113.2644"
    R_PROV="广东"; R_CITY="广州"; R_LAT="23.1140"; R_LNG="113.2590"
    POLL=5 ;;
  city)
    echo "[场景] city  广州→深圳 约105km / 约35分钟"
    S_PROV="广东"; S_CITY="广州"; S_LAT="23.1291"; S_LNG="113.2644"
    R_PROV="广东"; R_CITY="深圳"; R_LAT="22.5431"; R_LNG="114.0579"
    POLL=30 ;;
  *) # short
    echo "[场景] short  广州→佛山 约11km / 约3.7分钟(观察飞行中)"
    S_PROV="广东"; S_CITY="广州"; S_LAT="23.1291"; S_LNG="113.2644"
    R_PROV="广东"; R_CITY="佛山"; R_LAT="23.0500"; R_LNG="113.2000"
    POLL=10 ;;
esac

echo "== 1. 我的鸽子(初始化小白) =="
curl -s "$API/pigeon/mine?openid=$SENDER_OPENID"; echo; echo

echo "== 2. 写信并放飞(发送即起飞) =="
SEND=$(curl -s -X POST "$API/letter/send" \
  --data-urlencode "openid=$SENDER_OPENID" --data-urlencode "content=$CONTENT" --data-urlencode "imageUrl=" \
  --data-urlencode "province=$S_PROV" --data-urlencode "city=$S_CITY" \
  --data-urlencode "lat=$S_LAT" --data-urlencode "lng=$S_LNG")
echo "$SEND"; echo
LETTER_ID=$(echo "$SEND" | jq -r '.data.letterId // empty')
SHARE_TOKEN=$(echo "$SEND" | jq -r '.data.shareToken // empty')
echo ">>> letterId=$LETTER_ID  shareToken=$SHARE_TOKEN"; echo

if [ -z "$SHARE_TOKEN" ]; then echo "!! send 未返回 shareToken，终止"; exit 1; fi

echo "== 3. 分享预览 =="
curl -s "$API/letter/share-preview?shareToken=$SHARE_TOKEN&openid=$RECIPIENT_OPENID"; echo; echo

echo "== 4. 收件人原子认领 =="
BIND=$(curl -s -X POST "$API/letter/bind" \
  --data-urlencode "shareToken=$SHARE_TOKEN" --data-urlencode "openid=$RECIPIENT_OPENID" \
  --data-urlencode "province=$R_PROV" --data-urlencode "city=$R_CITY" \
  --data-urlencode "lat=$R_LAT" --data-urlencode "lng=$R_LNG")
echo "$BIND"; echo

echo "== 5. 自动轮询飞行页(每 ${POLL}s, 直到 ARRIVED/DELIVERED/LOST) =="
for i in $(seq 1 600); do
  R=$(curl -s "$API/letter/flight?letterId=$LETTER_ID&openid=$RECIPIENT_OPENID")
  ST=$(echo "$R" | jq -r '.data.status // empty')
  PR=$(echo "$R" | jq -r '.data.progress // empty')
  echo "  第${i}次  status=$ST  progress=$PR"
  case "$ST" in ARRIVED|DELIVERED|LOST) echo "$R"; break ;; esac
  sleep "$POLL"
done
echo

echo "== 6. 拆信(收件人, 此时才返回正文 + 成长快照) =="
curl -s "$API/letter/detail?letterId=$LETTER_ID&openid=$RECIPIENT_OPENID"; echo; echo

echo "== 7. 订阅到达通知 =="
curl -s -X POST "$API/letter/subscribe" --data-urlencode "openid=$RECIPIENT_OPENID" --data-urlencode "letterId=$LETTER_ID"; echo; echo

echo "== 8. 回信 =="
curl -s -X POST "$API/letter/reply" --data-urlencode "openid=$RECIPIENT_OPENID" \
  --data-urlencode "content=我收到了，谢谢你！" --data-urlencode "province=$R_PROV" \
  --data-urlencode "city=$R_CITY" --data-urlencode "lat=$R_LAT" --data-urlencode "lng=$R_LNG" \
  --data-urlencode "letterId=$LETTER_ID"; echo; echo

echo "== 完成。可复用 letterId=$LETTER_ID 测试 recall(需在未认领且≥30min 场景)。"

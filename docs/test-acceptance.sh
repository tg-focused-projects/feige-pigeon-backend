#!/usr/bin/env bash
# =============================================================================
# 《飞鸽传书》V1.1 验收用例执行脚本（对《03-backend-change-request》第十节）
# 前提：服务已启动(8089) + 已建表(migration) + 已放行 sign
# 说明：仅做“可即时验证”的用例；依赖时间流逝的用例单独给出模拟 SQL。
# =============================================================================
set -e
BASE="${BASE:-http://localhost:8089}"
API="$BASE/small-soogif/feige"
RUN="$(date +%s)"
S="fg_acc_sender_$RUN"; R="fg_acc_receiver_$RUN"
SP="广东"; SC="广州"; SLAT="23.1291"; SLNG="113.2644"
RP="广东"; RC="佛山"; RLAT="23.0500"; RLNG="113.2000"   # ~11km, 约3.7min 达

code() { echo "$1" | jq -r '.code' 2>/dev/null; }
key()  { echo "$1" | jq -r '.errorKey // ""' 2>/dev/null; }
show() { echo "  code=$(code "$1")  errorKey=$(key "$1")  $2"; }
echo "== sender=$S  receiver=$R =="

echo; echo "【用例1】发送即起飞：send 返回 FLYING_UNCLAIMED + depart + 非空 shareToken + 鸽子 SENDING"
SEND=$(curl -s -X POST "$API/letter/send" --data-urlencode "openid=$S" --data-urlencode "content=验收信" \
  --data-urlencode "province=$SP" --data-urlencode "city=$SC" --data-urlencode "lat=$SLAT" --data-urlencode "lng=$SLNG")
show "$SEND" "status=$(echo "$SEND"|jq -r '.data.status') depart=$(echo "$SEND"|jq -r '.data.departureTime') shareToken=$(echo "$SEND"|jq -r '.data.shareToken'|cut -c1-8)…"
LID=$(echo "$SEND"|jq -r '.data.letterId'); ST=$(echo "$SEND"|jq -r '.data.shareToken')
echo "  pigeon/mine 状态:"; curl -s "$API/pigeon/mine?openid=$S" | jq -r '.data.status'

echo; echo "【用例2】分享预览(不发生认领) -> AVAILABLE；且不返回正文"
curl -s "$API/letter/share-preview?shareToken=$ST&openid=$R"; echo

echo; echo "【用例3】发件人不能认领自己的信 -> SENDER_CANNOT_CLAIM"
curl -s -X POST "$API/letter/bind" --data-urlencode "shareToken=$ST" --data-urlencode "openid=$S" \
  --data-urlencode "province=$RP" --data-urlencode "city=$RC" --data-urlencode "lat=$RLAT" --data-urlencode "lng=$RLNG"; echo

echo; echo "【用例4】收件人原子认领 -> JUST_DEPARTED / ALREADY_FLYING / ARRIVED_WAITING"
BIND=$(curl -s -X POST "$API/letter/bind" --data-urlencode "shareToken=$ST" --data-urlencode "openid=$R" \
  --data-urlencode "province=$RP" --data-urlencode "city=$RC" --data-urlencode "lat=$RLAT" --data-urlencode "lng=$RLNG")
show "$BIND" "status=$(echo "$BIND"|jq -r '.data.status') firstOpenCase=$(echo "$BIND"|jq -r '.data.firstOpenCase') waiting=$(echo "$BIND"|jq -r '.data.waitingDurationSeconds')"

echo; echo "【用例5】他人(T)再认领 -> ALREADY_CLAIMED；且不泄露收件人"
curl -s -X POST "$API/letter/bind" --data-urlencode "shareToken=$ST" --data-urlencode "openid=someone_else_$RUN" \
  --data-urlencode "province=$RP" --data-urlencode "city=$RC" --data-urlencode "lat=$RLAT" --data-urlencode "lng=$RLNG"; echo

echo; echo "【用例6】获胜者同参重试(幂等) -> 返回当前航程"
curl -s -X POST "$API/letter/bind" --data-urlencode "shareToken=$ST" --data-urlencode "openid=$R" \
  --data-urlencode "province=$RP" --data-urlencode "city=$RC" --data-urlencode "lat=$RLAT" --data-urlencode "lng=$RLNG"; echo

echo; echo "【用例7】未抵达 detail -> NOT_ARRIVED"
curl -s "$API/letter/detail?letterId=$LID&openid=$R"; echo

echo; echo "【用例8】订阅(未抵达) -> subscribed:true"
curl -s -X POST "$API/letter/subscribe" --data-urlencode "openid=$R" --data-urlencode "letterId=$LID"; echo

echo; echo "提示：等待飞行结束后(short 约3.7min)再跑下面用例："
echo "  先 sleep 到 ARRIVED（可用 test-api.sh 自动轮询），再验证："
echo "    - detail 返回正文 + settleLevelUp/快照；再次 detail 不重复结算成长"
echo "    - flight 未认领分支含 canRecall/status=FLYING_UNCLAIMED 且 progress=null（发件人视角）"

echo; echo "------------------------ 以下为“依赖时间流逝”用例，给出模拟 SQL（自行在 DB 执行） ------------------------"
cat <<'EOF'
-- (a) 30min 召回边界：把未认领信件的起飞时间拨回 40 分钟前，再调 POST /letter/recall
UPDATE feige_letter SET departure_time = DATE_SUB(NOW(), INTERVAL 40 MINUTE)
 WHERE letter_id = '<待召回LID>' AND status='FLYING_UNCLAIMED';
-- (b) 72h 过期：把未认领信件认领截止拨到过去；等待 FeigeUnclaimedExpireJob(每分钟) 处理，或直接查状态
UPDATE feige_letter SET claim_expire_time = DATE_SUB(NOW(), INTERVAL 1 HOUR)
 WHERE letter_id = '<待过期LID>' AND status='FLYING_UNCLAIMED';
-- 验证：flight/最终 pigeon 状态变 IDLE；share-preview 返回 EXPIRED
EOF
echo "完成。"

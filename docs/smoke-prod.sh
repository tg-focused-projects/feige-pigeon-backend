#!/bin/bash
# =============================================================================
# 飞鸽传书 上线流程冒烟验收（对 http://test.soogif.com）
# 覆盖：基础连通 / dev登录 / 建用户 / 写信放飞 / 分享预览 / 认领互斥与幂等 /
#       订阅 / ArrivalJob定时扫描到信(验证容器内定时任务+分布式锁) / 回信
# 用法：sh smoke-prod.sh   （在发布机或任一能访问域名的机器执行）
# =============================================================================
BASE="${BASE:-http://test.soogif.com}"
API="$BASE/small-soogif/feige"
SALT="${FG_SIGN_SECRET:-dev_soogif_feige}"
RUN=$(date +%s)
S="prod_smoke_s_$RUN"; R="prod_smoke_r_$RUN"
sign() { printf '%s' "$1$SALT" | md5sum | awk '{print $1}'; }
LSIGN=$(sign "$S"); RSIGN=$(sign "$R")
PASS=0; FAIL=0
ok()  { echo "  ✓ $1"; PASS=$((PASS+1)); }
bad() { echo "  ✘ $1"; FAIL=$((FAIL+1)); }
j() { echo "$1" | jq -r "$2 // empty" 2>/dev/null; }

echo "【0】基础连通"
C=$(curl -s -o /dev/null -w '%{http_code}' -m 10 "$BASE/v2/api-docs")
[ "$C" = 200 ] && ok "GET /v2/api-docs → 200" || bad "api-docs=$C"

echo "【1】dev登录派生用户（FG_DEV_LOGIN=true）"
curl -s "$BASE/api/auth/wechat-login?jsCode=$S" > /tmp/.l1
curl -s "$BASE/api/auth/wechat-login?jsCode=$R" > /tmp/.l2
LOPENID=$(jq -r '.data.openid // empty' /tmp/.l1); LSIGN=$(jq -r '.data.sign // empty' /tmp/.l1)
ROPENID=$(jq -r '.data.openid // empty' /tmp/.l2); RSIGN=$(jq -r '.data.sign // empty' /tmp/.l2)
[ -n "$LSIGN" ] && [ -n "$RSIGN" ] && ok "登录返回 openid(${LOPENID:0:16}…)+sign" || bad "登录异常: $(head -c 120 /tmp/.l1)"

echo "【2】pigeon/mine（初始化小白）"
PM=$(curl -s "$API/pigeon/mine?openid=$LOPENID")
[ "$(j "$PM" .code)" = 200 ] && ok "code=200 status=$(j "$PM" .data.status)" || bad "pigeon/mine: $(echo "$PM" | head -c 130)"

echo "【3】写信放飞"
SEND=$(curl -s -X POST "$API/letter/send" -H "sign: $LSIGN" \
  --data-urlencode "openid=$LOPENID" --data-urlencode "content=上线冒烟信$RUN🕊️" --data-urlencode "imageUrl=" \
  --data-urlencode "province=广东" --data-urlencode "city=广州" \
  --data-urlencode "lat=23.1291" --data-urlencode "lng=113.2644")
LID=$(j "$SEND" .data.letterId); ST=$(j "$SEND" .data.shareToken); SST=$(j "$SEND" .data.status)
[ -n "$ST" ] && ok "send→$SST letterId=${LID:0:14}… shareToken=${ST:0:8}…" || bad "send: $(echo "$SEND" | head -c 150)"

echo "【4】分享预览（不认领、不泄露正文）"
SPR=$(curl -s "$API/letter/share-preview?shareToken=$ST&openid=$ROPENID")
[ "$(j "$SPR" .code)" = 200 ] && ok "claimStatus=$(j "$SPR" .data.claimStatus) 正文泄露=$(echo "$SPR" | jq -r '.data.content != null')" || bad "share-preview: $(echo "$SPR" | head -c 130)"

echo "【5】发件人自领 → 应拒"
SC=$(curl -s -X POST "$API/letter/bind" -H "sign: $LSIGN" --data-urlencode "shareToken=$ST" --data-urlencode "openid=$LOPENID" \
  --data-urlencode "province=广东" --data-urlencode "city=广州" --data-urlencode "lat=23.1140" --data-urlencode "lng=113.2590")
[ "$(j "$SC" .errorKey)" = SENDER_CANNOT_CLAIM ] && ok "SENDER_CANNOT_CLAIM" || bad "自领: $(echo "$SC" | head -c 130)"

echo "【6】收件人认领"
BIND=$(curl -s -X POST "$API/letter/bind" -H "sign: $RSIGN" --data-urlencode "shareToken=$ST" --data-urlencode "openid=$ROPENID" \
  --data-urlencode "province=广东" --data-urlencode "city=广州" --data-urlencode "lat=23.1140" --data-urlencode "lng=113.2590")
BS=$(j "$BIND" .data.status)
[ -n "$BS" ] && ok "认领→$BS firstOpenCase=$(j "$BIND" .data.firstOpenCase)" || bad "bind: $(echo "$BIND" | head -c 150)"

echo "【7】第三人认领 → ALREADY_CLAIMED"
OC=$(curl -s -X POST "$API/letter/bind" -H "sign: $(sign someone_else_$RUN)" --data-urlencode "shareToken=$ST" --data-urlencode "openid=someone_else_$RUN" \
  --data-urlencode "province=广东" --data-urlencode "city=广州" --data-urlencode "lat=23.1140" --data-urlencode "lng=113.2590")
[ "$(j "$OC" .errorKey)" = ALREADY_CLAIMED ] && ok "ALREADY_CLAIMED" || bad "他人认领: $(echo "$OC" | head -c 130)"

echo "【8】未抵达 detail → NOT_ARRIVED"
DET=$(curl -s "$API/letter/detail?letterId=$LID&openid=$ROPENID")
[ "$(j "$DET" .errorKey)" = NOT_ARRIVED ] && ok "NOT_ARRIVED" || echo "  ℹ detail: code=$(j "$DET" .code) err=$(j "$DET" .errorKey) status=$(j "$DET" .data.status)"

echo "【9】订阅到信通知"
SUB=$(curl -s -X POST "$API/letter/subscribe" -H "sign: $RSIGN" --data-urlencode "openid=$ROPENID" --data-urlencode "letterId=$LID")
[ "$(j "$SUB" .code)" = 200 ] && ok "subscribed=$(j "$SUB" .data.subscribed)" || bad "subscribe: $(echo "$SUB" | head -c 130)"

echo "【10】等待 ArrivalJob 扫描到信（同城约37s飞行 + 每分钟扫描 → 最长约100s）"
FINAL=""
for i in $(seq 1 24); do
  sleep 5
  DET=$(curl -s "$API/letter/detail?letterId=$LID&openid=$ROPENID")
  FINAL=$(j "$DET" .code); ARR=$(echo "$DET" | jq -r ".data.arriveTime // empty" 2>/dev/null)
  [ "$FINAL" = 200 ] && [ -n "$ARR" ] && break
done
CODE=$(j "$DET" .code); ARR=$(echo "$DET" | jq -r ".data.arriveTime // empty" 2>/dev/null)
[ "$CODE" = 200 ] && [ -n "$ARR" ] && \
  ok "到信! arriveTime=$ARR canReply=$(j "$DET" .data.canReply) settleLevelUp=$(j "$DET" .data.settleLevelUp)" || bad "未到信(定时任务?): $(echo "$DET" | head -c 150)"

echo "【11】回信"
REP=$(curl -s -X POST "$API/letter/reply" -H "sign: $RSIGN" --data-urlencode "openid=$ROPENID" --data-urlencode "letterId=$LID" --data-urlencode "content=冒烟回信$RUN" \
  --data-urlencode "province=广东" --data-urlencode "city=广州" --data-urlencode "lat=23.1140" --data-urlencode "lng=113.2590")
[ "$(j "$REP" .code)" = 200 ] && ok "reply成功 replyId=$(j "$REP" .data.replyId)" || bad "reply: $(echo "$REP" | head -c 130)"

echo ""
echo "================ 冒烟结果: PASS=$PASS  FAIL=$FAIL ================"
[ $FAIL -gt 0 ] && exit 1
exit 0

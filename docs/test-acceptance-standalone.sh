#!/usr/bin/env bash
# =============================================================================
# 飞鸽传书独立项目(feige-pigeon) 验收脚本
# 前提：服务已部署(dev-login 开启)；BASE 指向服务地址
# 用法：BASE=http://110.40.183.197:8098 sh test-acceptance-standalone.sh
# =============================================================================
BASE="${BASE:-http://localhost:8080}"
A="$BASE/small-soogif/feige"

# 登录一次取 openid+sign（同一响应，避免 sign 与 openid 错位）
login() { local res=$(curl -s "$BASE/api/auth/wechat-login?jsCode=$1"); printf '%s|%s|%s' \
  "$(echo "$res"|jq -r '.data.openid')" "$(echo "$res"|jq -r '.data.sign')" "$(echo "$res"|jq -r '.data.userId')"; }

I="$(login "sender_${RANDOM}${RANDOM}")"; S_OPEN=$(echo "$I"|cut -d'|' -f1); S_SIGN=$(echo "$I"|cut -d'|' -f2)
I="$(login "recv_${RANDOM}${RANDOM}")";  R_OPEN=$(echo "$I"|cut -d'|' -f1); R_SIGN=$(echo "$I"|cut -d'|' -f2)
I="$(login "stranger_${RANDOM}${RANDOM}")"; Z_OPEN=$(echo "$I"|cut -d'|' -f1); Z_SIGN=$(echo "$I"|cut -d'|' -f2)
echo "sender=$S_OPEN  recv=$R_OPEN"

echo "[1] send"; SEND=$(curl -s -X POST "$A/letter/send" --data-urlencode "openid=$S_OPEN" --data-urlencode content="验收信" \
  --data-urlencode province=广东 --data-urlencode city=广州 --data-urlencode lat=23.1291 --data-urlencode lng=113.2644 -H "sign: $S_SIGN")
echo "$SEND"|jq -c '{code,errorKey,data:{letterId:.data.letterId,shareToken:.data.shareToken,status:.data.status}}'
LID=$(echo "$SEND"|jq -r '.data.letterId'); ST=$(echo "$SEND"|jq -r '.data.shareToken')

echo "[2] share-preview"; echo "$(curl -s "$A/letter/share-preview?shareToken=$ST&openid=$R_OPEN"|jq -c .data.claimStatus)"
echo "[3] 发件人自认领"; echo "$(curl -s -X POST "$A/letter/bind" --data-urlencode "shareToken=$ST" --data-urlencode "openid=$S_OPEN" --data-urlencode province=广东 --data-urlencode city=佛山 --data-urlencode lat=23.05 --data-urlencode lng=113.2 -H "sign: $S_SIGN"|jq -c '{code,errorKey}')"
echo "[4] 收件人原子认领"; echo "$(curl -s -X POST "$A/letter/bind" --data-urlencode "shareToken=$ST" --data-urlencode "openid=$R_OPEN" --data-urlencode province=广东 --data-urlencode city=佛山 --data-urlencode lat=23.05 --data-urlencode lng=113.2 -H "sign: $R_SIGN"|jq -c '.data|{status,firstOpenCase,distanceKm,arrivalTime}')"
echo "[5] 陌生人再认领"; echo "$(curl -s -X POST "$A/letter/bind" --data-urlencode "shareToken=$ST" --data-urlencode "openid=$Z_OPEN" --data-urlencode province=广东 --data-urlencode city=佛山 --data-urlencode lat=23.05 --data-urlencode lng=113.2 -H "sign: $Z_SIGN"|jq -c '{code,errorKey}')"
echo "[6] 未抵达 detail"; echo "$(curl -s "$A/letter/detail?letterId=$LID&openid=$R_OPEN"|jq -c '{code,errorKey}')"
echo "[7] 订阅(未抵达)"; echo "$(curl -s -X POST "$A/letter/subscribe" --data-urlencode "openid=$R_OPEN" --data-urlencode "letterId=$LID" -H "sign: $R_SIGN"|jq -c .data)"

echo "[8] 等待飞行到 ARRIVED ..."
for i in $(seq 1 60); do
  RS=$(curl -s "$A/letter/flight?letterId=$LID&openid=$R_OPEN")
  ST=$(echo "$RS"|jq -r '.data.status'); PR=$(echo "$RS"|jq -r '.data.progress')
  echo "  [$i] status=$ST progress=$PR"
  case "$ST" in ARRIVED|DELIVERED) break;; esac
  sleep 5
done
echo "[9] detail"; echo "$(curl -s "$A/letter/detail?letterId=$LID&openid=$R_OPEN"|jq -c '.data|{content,settleExpDelta,settleLevelBefore,settleLevelAfter,settleLevelUp,canReply}')"
echo "[10] subscribe(抵达后)"; echo "$(curl -s -X POST "$A/letter/subscribe" --data-urlencode "openid=$R_OPEN" --data-urlencode "letterId=$LID" -H "sign: $R_SIGN"|jq -c .data)"
echo "[11] reply"; echo "$(curl -s -X POST "$A/letter/reply" --data-urlencode "openid=$R_OPEN" --data-urlencode content=回信啦 --data-urlencode province=广东 --data-urlencode city=佛山 --data-urlencode lat=23.05 --data-urlencode lng=113.2 --data-urlencode "letterId=$LID" -H "sign: $R_SIGN"|jq -c '.data')"

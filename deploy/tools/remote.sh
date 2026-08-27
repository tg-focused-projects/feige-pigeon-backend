#!/usr/bin/env bash
# =============================================================================
# 首次初始化辅助：用密码执行远程命令 / 传文件（密码只作参数传入，绝不落盘）
# 之后推荐安装好 SSH 公钥后直接用 ssh/scp。
# 用法:
#   sshx.sh <host> <user> <password> "<remote-cmd>"
#   scpx.sh <host> <user> <password> <local-file> <remote-path>
# =============================================================================
set -euo pipefail
mode=$1; host=$2; user=$3; pass=$4; shift 4

case "$mode" in
ssh)
  cmd=$1
  expect <<EOF
set timeout 300
log_user 1
match_max 100000
spawn ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ConnectTimeout=15 $user@$host -- $cmd
expect {
    -re "(?i)password:" { send "$pass\r"; exp_continue }
    eof
}
catch wait result
exit [lindex \$result 3]
EOF
  ;;
scp)
  src=$1; dst=$2
  expect <<EOF
set timeout 600
log_user 1
match_max 100000
spawn scp -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ConnectTimeout=15 $src $user@$host:$dst
expect {
    -re "(?i)password:" { send "$pass\r"; exp_continue }
    eof
}
catch wait result
exit [lindex \$result 3]
EOF
  ;;
*) echo "unknown mode: $mode" >&2; exit 2 ;;
esac

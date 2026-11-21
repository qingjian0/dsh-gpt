#!/system/bin/sh
# 动态 hook 控制台（root）
exec node "$(dirname "$0")/tools/hook.mjs" "$@"

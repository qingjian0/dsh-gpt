#!/system/bin/sh
# 安装模块 + 自动写入 LSPosed 作用域 + 部署配置 + 重启目标
set -e
R=$(cd "$(dirname "$0")" && pwd)
PKG=com.gpt.hook
TARGET=com.openai.chatgpt
DB=/data/adb/lspd/config/modules_config.db
SQ=$(command -v sqlite3 || echo /data/user/0/dsh.dsh.mobile/files/engine/extensions/sqlite/bin/sqlite3)

echo "[1/6] install apk"
pm install -r -d "$R/build/gpt-hook.apk" | tail -1

echo "[2/6] backup lsposed db"
cp "$DB" "$R/modules_config.db.bak.$(date +%s)"

echo "[3/6] register module/scope"
APK=$(pm path $PKG | sed 's/package://' | head -1)
echo "  apk=$APK"
"$SQ" "$DB" "INSERT OR REPLACE INTO modules(module_pkg_name,apk_path) VALUES('$PKG','$APK');"
"$SQ" "$DB" "INSERT OR REPLACE INTO modules_state(module_pkg_name,user_id,enabled,scope_request_blocked) VALUES('$PKG',0,1,0);"
"$SQ" "$DB" "INSERT OR REPLACE INTO scope(module_pkg_name,app_pkg_name,user_id) VALUES('$PKG','$TARGET',0);"
"$SQ" "$DB" "SELECT module_pkg_name,enabled FROM modules_state WHERE module_pkg_name='$PKG';"
"$SQ" "$DB" "SELECT * FROM scope WHERE module_pkg_name='$PKG';"

echo "[4/6] deploy config"
TD=/data/data/$TARGET
if [ -d "$TD/files" ]; then
  cp "$R/app/src/main/assets/gpt-hook.default.json" "$TD/files/gpt-hook.json"
  chown $(stat -c %u:%g "$TD") "$TD/files/gpt-hook.json"
  chmod 600 "$TD/files/gpt-hook.json"
  chcon u:object_r:app_data_file:s0 "$TD/files/gpt-hook.json" 2>/dev/null || true
  echo "  -> $TD/files/gpt-hook.json"
else
  echo "  target not installed?"
fi

echo "[5/6] restart lspd"
PID=$(pidof lspd || true)
[ -n "$PID" ] && kill "$PID" && echo "  killed $PID"
sleep 1
cd /data/adb/modules/zygisk_lsposed && (./daemon --force >/dev/null 2>&1 &)
sleep 2
echo "  lspd pid=$(pidof lspd)"

echo "[6/6] restart target"
am force-stop "$TARGET"
echo "  done. 打开 ChatGPT 后看日志: su -c 'cat /data/data/$TARGET/files/gpt-hook.log'"

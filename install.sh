#!/bin/sh
# Installs boxdeck-tv on an Android TV / Google TV over adb and makes it the home screen.
#
#   sh install.sh <TV_IP[:PORT]>
#   BOXDECK_URL=http://100.x.y.z:8100 BOXDECK_TOKEN=bdr_... sh install.sh <TV_IP>
#   BOXDECK_TOKEN_MAC=bdr_... ...      another machine's read-only token (one per machine name)
#
# It does not turn off your TV's own home screen. On many TVs that home screen still wins the Home
# button; turning it off is a separate, riskier step (see README). Uses out/boxdeck-tv.apk: build
# it first with sh build.sh.
set -e
cd "$(dirname "$0")"
TARGET="$1"
[ -n "$TARGET" ] || { echo "Usage: sh install.sh <TV_IP[:PORT]>" >&2; exit 1; }
case "$TARGET" in *:*) TV=$TARGET ;; *) TV=$TARGET:5555 ;; esac
adb connect "$TV" >/dev/null 2>&1 || true
[ "$(adb -s "$TV" get-state 2>/dev/null)" = device ] || { echo "Can't reach $TV over adb." >&2; exit 1; }
APK=out/boxdeck-tv.apk
[ -f "$APK" ] || { echo "No $APK. Run: sh build.sh" >&2; exit 1; }
PKG=dev.wicolian.boxdecktv

adb -s "$TV" install -r "$APK"
# Lets the watcher turn wireless debugging back on after a restart. Revoke with pm revoke.
adb -s "$TV" shell pm grant $PKG android.permission.WRITE_SECURE_SETTINGS || true
adb -s "$TV" shell cmd role add-role-holder --user 0 android.app.role.HOME $PKG >/dev/null 2>&1 || true
adb -s "$TV" shell cmd package set-home-activity $PKG/.MainActivity >/dev/null 2>&1 || true
adb -s "$TV" shell settings put secure screensaver_components $PKG/.MatrixDream

set -- --es stamp "$(date +%s)" --es wallpaper Terminal
[ -n "$BOXDECK_URL" ] && set -- "$@" --es boxdeck_url "$BOXDECK_URL"
[ -n "$BOXDECK_TOKEN" ] && set -- "$@" --es boxdeck_token "$BOXDECK_TOKEN"
for v in $(env | sed -n 's/^BOXDECK_TOKEN_\([A-Za-z0-9_]*\)=.*/\1/p'); do
  eval "t=\$BOXDECK_TOKEN_$v"
  set -- "$@" --es "boxdeck_token_$(echo "$v" | tr 'A-Z' 'a-z')" "$t"
done
adb -s "$TV" shell am start -n $PKG/.MainActivity "$@" >/dev/null
adb -s "$TV" shell input keyevent KEYCODE_HOME
echo "Home now opens: $(adb -s "$TV" shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1)"

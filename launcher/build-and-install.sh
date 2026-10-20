#!/usr/bin/env bash
# Build the launcher APK with the bare SDK tools (no Gradle) and install it on the Fire TV.
#   launcher/build-and-install.sh            build + install
#   launcher/build-and-install.sh --build    build only
# Needs ANDROID_SERIAL (e.g. 192.168.0.2:5555) and an authorised adb connection.
set -euo pipefail
cd "$(dirname "$0")"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export PATH="$JAVA_HOME/bin:$PATH"
# Android Studio's SDK, else the one setup.sh installs with Homebrew.
# Android Studio's SDK, else the one setup.sh installs with Homebrew.
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
[ -d "$SDK/build-tools/34.0.0" ] || SDK=/opt/homebrew/share/android-commandlinetools
[ -d "$SDK/build-tools/34.0.0" ] || SDK=/opt/homebrew/share/android-commandlinetools
# BUILD_TOOLS overrides: build-tools 34's d8 crashes under JDK 21.
BT="${BUILD_TOOLS:-$SDK/build-tools/34.0.0}"
JAR="$SDK/platforms/android-34/android.jar"
PKG=dev.bhawesh.tvhome
B=build

rm -rf "$B" && mkdir -p "$B/gen" "$B/classes" "$B/dex"
"$BT/aapt2" compile --dir res -o "$B/res.zip"
# Fire OS 7 is Android 9 (API 28).
"$BT/aapt2" link -o "$B/base.apk" -I "$JAR" --manifest AndroidManifest.xml --java "$B/gen" \
  -A assets --min-sdk-version 28 --target-sdk-version 30 "$B/res.zip"
javac -nowarn -Xlint:-options --release 11 -classpath "$JAR" -d "$B/classes" \
  $(find src "$B/gen" -name '*.java')
"$BT/d8" --min-api 28 --lib "$JAR" --output "$B/dex" $(find "$B/classes" -name '*.class')
cp "$B/base.apk" "$B/unsigned.apk"
(cd "$B/dex" && zip -q -u ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 "$B/unsigned.apk" "$B/aligned.apk"
[ -f debug.keystore ] || keytool -genkeypair -keystore debug.keystore -storepass android -keypass android \
  -alias tvhome -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=tvhome" >/dev/null 2>&1
"$BT/apksigner" sign --ks debug.keystore --ks-pass pass:android --out "$B/tvhome.apk" "$B/aligned.apk"
echo "built $B/tvhome.apk ($(wc -c < "$B/tvhome.apk") bytes)"
[ "${1:-}" = "--build" ] && exit 0

adb install -r "$B/tvhome.apk"
adb shell pm grant "$PKG" android.permission.ACCESS_FINE_LOCATION || true
adb shell appops set "$PKG" GET_USAGE_STATS allow
adb shell pm grant "$PKG" android.permission.WRITE_SECURE_SETTINGS || true
adb shell pm grant "$PKG" android.permission.READ_EXTERNAL_STORAGE || true
adb shell pm grant "$PKG" android.permission.WRITE_EXTERNAL_STORAGE || true
# Compile ahead of time now, instead of JIT warming up while you use it (first minutes stutter otherwise).
adb shell cmd package compile -m speed -f "$PKG" >/dev/null
# Home button takeover: Fire OS has no default-launcher picker, so the accessibility service does it.
SVC="$PKG/.HomeService"
# Re-bind the service so a changed accessibility config takes effect (a reinstall alone leaves it stale).
REST=$(adb shell settings get secure enabled_accessibility_services | tr -d '\r' | sed "s#:*$SVC##")
adb shell settings put secure enabled_accessibility_services "${REST:-null}"
CUR=$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')
case "$CUR" in *"$SVC"*) ;; null|"") adb shell settings put secure enabled_accessibility_services "$SVC" ;;
  *) adb shell settings put secure enabled_accessibility_services "$CUR:$SVC" ;; esac
adb shell settings put secure accessibility_enabled 1
# Alarms normally fire no sooner than ~5 s out; HomeService's bounce back from Amazon's home needs
# them at once. Global (all apps' alarms); tools/restore.sh takes it out again.
AMC=$(adb shell settings get global alarm_manager_constants | tr -d '\r' | tr ',' '\n' | { grep -v -e '^min_futurity=' -e '^null$' || true; } | paste -sd, -)
adb shell settings put global alarm_manager_constants "${AMC:+$AMC,}min_futurity=0"
# Watch history reads apps' "now playing", which Android shows only to notification listeners.
adb shell cmd notification allow_listener "$PKG/$PKG.Watching" 2>/dev/null \
  || adb shell settings put secure enabled_notification_listeners "$PKG/$PKG.Watching"
adb shell am start -n "$PKG/.MainActivity" >/dev/null
echo "Installed. Press Home on the remote."

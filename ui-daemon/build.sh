#!/usr/bin/env bash
# Build the on-device UI artifacts into dist/ui/:
#   ad-ui.jar             dex-in-jar daemon, run via app_process as the shell user
#   ad-ui-presence.apk    no-op accessibility service; lets Compose apps emit events
#
# Compiles against platforms/android-26 on purpose: d8 --min-api does not catch a newer
# API used by mistake, and API 26 devices (e.g. i.MX6 kiosks) are a supported target.
# Needs ANDROID_HOME / ANDROID_SDK_ROOT (or ~/Library/Android/sdk) with platforms;android-26
# and any build-tools. Dev-only; users get the committed dist/ui artifacts.
set -euo pipefail
cd "$(dirname "$0")"
PLUGIN_ROOT="$(cd .. && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
ANDROID_JAR="$SDK/platforms/android-26/android.jar"
[ -f "$ANDROID_JAR" ] || { echo "missing $ANDROID_JAR (sdkmanager 'platforms;android-26')" >&2; exit 1; }
BT="$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)"
OUT="$PLUGIN_ROOT/dist/ui"
rm -rf build && mkdir -p build/daemon/classes build/daemon/dex build/presence/classes build/presence/dex "$OUT"

echo "==> ad-ui.jar"
javac --release 8 -nowarn -cp "$ANDROID_JAR" -d build/daemon/classes $(find src -name '*.java')
"$BT/d8" --release --min-api 26 --lib "$ANDROID_JAR" --output build/daemon/dex \
  $(find build/daemon/classes -name '*.class')
(cd build/daemon/dex && zip -q -X ../ad-ui.jar classes.dex)
cp build/daemon/ad-ui.jar "$OUT/ad-ui.jar"

echo "==> ad-ui-presence.apk"
"$BT/aapt2" compile --dir presence/res -o build/presence/res.zip
"$BT/aapt2" link -I "$ANDROID_JAR" --manifest presence/AndroidManifest.xml \
  -o build/presence/unsigned.apk build/presence/res.zip
javac --release 8 -nowarn -cp "$ANDROID_JAR" -d build/presence/classes $(find presence/src -name '*.java')
"$BT/d8" --release --min-api 26 --lib "$ANDROID_JAR" --output build/presence/dex \
  $(find build/presence/classes -name '*.class')
(cd build/presence/dex && zip -q -X ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 build/presence/unsigned.apk build/presence/aligned.apk
# Throwaway key committed on purpose: every release must be signed with the same key or an
# upgrade install fails with INSTALL_FAILED_UPDATE_INCOMPATIBLE. It protects nothing.
"$BT/apksigner" sign --ks presence/presence.keystore --ks-pass pass:adui-presence \
  --ks-key-alias presence --key-pass pass:adui-presence --v4-signing-enabled false \
  --out "$OUT/ad-ui-presence.apk" build/presence/aligned.apk
ls -l "$OUT"

#!/usr/bin/env bash
# Build + install the Yay-Tsa TWA on a connected device/emulator using the SAME
# canonical release key as CI — so a locally-built APK is signature-identical to
# production and upgrades in place (no INSTALL_FAILED_UPDATE_INCOMPATIBLE).
#
# Canonical signing key (one key everywhere):
#   - macOS Keychain : yaytsa-android-keystore-b64 / yaytsa-android-keystore-password (alias: android)
#   - CI             : secrets ANDROID_KEYSTORE_B64 / ANDROID_KEYSTORE_PASSWORD / ANDROID_KEY_ALIAS
#   - assetlinks.json: cert SHA-256 injected at deploy via ANDROID_CERT_SHA256
#   - cert: CN=Yay-Tsa, SHA-256 47:41:65:82:97:9C:B6:A6:F2:C7:B7:42:CF:02:0E:B1:8D:C7:1C:8F:49:3A:0E:6F:EF:07:0D:E3:43:9C:7C:54
set -euo pipefail
cd "$(dirname "$0")"

PKG="com.yaytsa.app"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
ADB="$SDK/platform-tools/adb"
APK="app/build/outputs/apk/release/app-release.apk"
KEYSTORE="$(pwd)/keystore.jks" # gitignored

cleanup() { rm -f "$KEYSTORE"; }
trap cleanup EXIT

echo "==> Loading canonical release key from Keychain"
security find-generic-password -s "yaytsa-android-keystore-b64" -w | base64 -d >"$KEYSTORE"
STORE_PW="$(security find-generic-password -s "yaytsa-android-keystore-password" -w)"
[[ -s "$KEYSTORE" ]] || {
  echo "ERROR: keystore not found in Keychain (yaytsa-android-keystore-b64)" >&2
  exit 1
}

echo "==> Building release APK (signed with the canonical key)"
KEYSTORE_FILE="$KEYSTORE" KEYSTORE_PASSWORD="$STORE_PW" KEY_ALIAS="android" KEY_PASSWORD="$STORE_PW" \
  ./gradlew assembleRelease --no-daemon -q

echo "==> Verifying signer cert"
APKSIGNER="$(find "$SDK/build-tools" -name apksigner 2>/dev/null | sort -V | tail -1)"
[[ -n "$APKSIGNER" ]] && "$APKSIGNER" verify --print-certs "$APK" | grep -i "SHA-256 digest" | head -1 || true

if [[ "${1:-}" == "--build-only" ]]; then
  echo "==> Built: $APK"
  exit 0
fi

echo "==> Installing on $($ADB devices | sed -n 2p | cut -f1)"
if ! "$ADB" install -r -d "$APK" 2>/tmp/yt-install.err; then
  if grep -q "INSTALL_FAILED_UPDATE_INCOMPATIBLE\|signatures do not match" /tmp/yt-install.err; then
    echo "==> Existing install signed with a different (legacy/debug) key — uninstalling once, then reinstalling"
    "$ADB" uninstall "$PKG" >/dev/null
    "$ADB" install "$APK"
  else
    cat /tmp/yt-install.err >&2
    exit 1
  fi
fi
echo "==> Installed $PKG (canonical key). Future updates install in place."

#!/usr/bin/env bash
# Builds SecureHome Android APK and deploys it to relay/public/securehome.apk
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
ANDROID_DIR="$ROOT_DIR/android"
PUBLIC_DIR="$ROOT_DIR/relay/public"

echo "==> Building SecureHome Android APK..."
cd "$ANDROID_DIR"

VARIANT="${1:-release}"

if [[ "$VARIANT" == "release" ]]; then
  ./gradlew assembleRelease
  SRC_APK="$ANDROID_DIR/app/build/outputs/apk/release/app-release.apk"
else
  ./gradlew assembleDebug
  SRC_APK="$ANDROID_DIR/app/build/outputs/apk/debug/app-debug.apk"
fi

if [[ -f "$SRC_APK" ]]; then
  mkdir -p "$PUBLIC_DIR"
  cp "$SRC_APK" "$PUBLIC_DIR/securehome.apk"
  echo "==> Successfully copied $SRC_APK to $PUBLIC_DIR/securehome.apk"
  echo "    Size: $(du -h "$PUBLIC_DIR/securehome.apk" | cut -f1)"
  echo "    SHA-256: $(shasum -a 256 "$PUBLIC_DIR/securehome.apk" | cut -d' ' -f1)"
else
  echo "Error: Built APK not found at $SRC_APK" >&2
  exit 1
fi

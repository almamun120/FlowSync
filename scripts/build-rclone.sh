#!/usr/bin/env bash
# Cross-compiles rclone for Android and places it where Gradle packages it:
#   app/src/main/jniLibs/<abi>/librclone.so   (executed by the app from its native library dir – no root)
# Needs: Go >= 1.23, git, Android NDK (ANDROID_NDK_HOME), Linux x86_64 host.
# Usage: RCLONE_VERSION=v1.68.2 RCLONE_ABIS="arm64-v8a armeabi-v7a" scripts/build-rclone.sh
set -euo pipefail

RCLONE_VERSION="${RCLONE_VERSION:-v1.68.2}"
RCLONE_ABIS="${RCLONE_ABIS:-arm64-v8a}"
API=26
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
[ -d "$NDK" ] || { echo "Set ANDROID_NDK_HOME to an installed NDK"; exit 1; }

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
git clone --depth 1 --branch "$RCLONE_VERSION" https://github.com/rclone/rclone.git "$WORK/src"
BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"

build() { # abi goarch compiler [extra env...]
  local abi="$1" goarch="$2" cc="$3"; shift 3
  local out="$ROOT/app/src/main/jniLibs/$abi"
  mkdir -p "$out"
  echo ">>> rclone $RCLONE_VERSION for $abi"
  (cd "$WORK/src" && env CGO_ENABLED=1 GOOS=android GOARCH="$goarch" "$@" CC="$BIN/$cc" \
    go build -trimpath -tags "noselfupdate netcgo" \
      -ldflags "-s -w -X github.com/rclone/rclone/fs.Version=${RCLONE_VERSION}-flowsync" \
      -o "$out/librclone.so" .)
}

for abi in $RCLONE_ABIS; do
  case "$abi" in
    arm64-v8a)   build arm64-v8a arm64 "aarch64-linux-android${API}-clang" ;;
    armeabi-v7a) build armeabi-v7a arm "armv7a-linux-androideabi${API}-clang" GOARM=7 ;;
    x86_64)      build x86_64 amd64 "x86_64-linux-android${API}-clang" ;;
    *) echo "unsupported ABI $abi"; exit 1 ;;
  esac
done
ls -la "$ROOT"/app/src/main/jniLibs/*/librclone.so

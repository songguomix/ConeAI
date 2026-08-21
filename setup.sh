#!/usr/bin/env bash
#
# Cone Agent — one-shot setup.
#
# Automatically:
#   1. finds a usable JDK (Android Studio's bundled JBR — no system Java needed),
#   2. downloads + installs the offline Vosk speech model into the app's assets,
#   3. builds the signed release APK,
#   4. (optional) installs it to a connected phone via adb.
#
# Usage:
#   ./setup.sh                 # Chinese model + build release APK
#   ./setup.sh --model en      # use the English model instead
#   ./setup.sh --install       # also adb-install to a connected device
#   ./setup.sh --debug         # build the debug APK instead of release
#   ./setup.sh --no-build      # only install the model, don't build
#   ./setup.sh --no-model      # only build, skip the model download
#   MODEL_URL=... ./setup.sh   # override the model download URL
#
set -euo pipefail

# ----------------------------------------------------------------------------- config / args
MODEL_LANG="cn"
DO_BUILD=1
DO_MODEL=1
DO_INSTALL=0
BUILD_TYPE="release"

while [ $# -gt 0 ]; do
  case "$1" in
    --model) MODEL_LANG="${2:-cn}"; shift 2 ;;
    --model=*) MODEL_LANG="${1#*=}"; shift ;;
    --install) DO_INSTALL=1; shift ;;
    --debug) BUILD_TYPE="debug"; shift ;;
    --no-build) DO_BUILD=0; shift ;;
    --no-model) DO_MODEL=0; shift ;;
    -h|--help)
      sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "未知参数: $1 （用 --help 查看）"; exit 1 ;;
  esac
done

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ASSETS_DIR="$PROJECT_DIR/app/src/main/assets/vosk-model"

case "$MODEL_LANG" in
  cn|zh) DEFAULT_URL="https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip" ;;
  en)    DEFAULT_URL="https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip" ;;
  *) echo "不支持的 --model: $MODEL_LANG（可选 cn / en，或用 MODEL_URL 覆盖）"; exit 1 ;;
esac
MODEL_URL="${MODEL_URL:-$DEFAULT_URL}"

log()  { printf '\033[1;36m==>\033[0m %s\n' "$*"; }
ok()   { printf '\033[1;32m  ✓\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m  ! \033[0m%s\n' "$*"; }
die()  { printf '\033[1;31m  ✗ %s\033[0m\n' "$*" >&2; exit 1; }

# ----------------------------------------------------------------------------- 1. JDK
log "查找可用的 JDK"
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME}/bin/java" ]; then
  for cand in \
    "/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
    "/Applications/Android Studio Preview.app/Contents/jbr/Contents/Home"; do
    if [ -x "$cand/bin/java" ]; then JAVA_HOME="$cand"; break; fi
  done
fi
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME}/bin/java" ]; then
  JAVA_HOME="$(/usr/libexec/java_home 2>/dev/null || true)"
fi
[ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ] || \
  die "未找到 JDK。请安装 Android Studio（自带 JBR）后重试。"
export JAVA_HOME
ok "JAVA_HOME=$JAVA_HOME"

# ----------------------------------------------------------------------------- 2. model
if [ "$DO_MODEL" = 1 ]; then
  if [ -d "$ASSETS_DIR/conf" ] || [ -d "$ASSETS_DIR/am" ]; then
    ok "离线语音模型已存在，跳过下载（删除 $ASSETS_DIR 下的模型可重装）"
  else
    command -v curl >/dev/null 2>&1 || die "需要 curl"
    command -v unzip >/dev/null 2>&1 || die "需要 unzip"
    mkdir -p "$ASSETS_DIR"
    TMP="$(mktemp -d)"
    trap 'rm -rf "$TMP"' EXIT
    ZIP="$TMP/model.zip"

    log "下载离线语音模型（约 40MB，需联网一次）"
    echo "    $MODEL_URL"
    curl -fL --progress-bar -o "$ZIP" "$MODEL_URL" || die "下载失败：$MODEL_URL"
    ok "下载完成 ($(du -h "$ZIP" | cut -f1))"

    log "解压并安装到 assets/vosk-model/"
    unzip -q "$ZIP" -d "$TMP/unpacked"
    EXTRACTED="$(find "$TMP/unpacked" -maxdepth 1 -mindepth 1 -type d | head -1)"
    [ -n "$EXTRACTED" ] && [ -d "$EXTRACTED/conf" ] || die "模型结构异常（未找到 conf/ 目录）"
    cp -R "$EXTRACTED/." "$ASSETS_DIR/"
    [ -d "$ASSETS_DIR/conf" ] || die "安装失败：$ASSETS_DIR/conf 不存在"
    ok "模型已安装：$ASSETS_DIR"
  fi
else
  warn "已跳过模型安装（--no-model）"
fi

# ----------------------------------------------------------------------------- 3. build
APK=""
if [ "$DO_BUILD" = 1 ]; then
  if [ "$BUILD_TYPE" = "debug" ]; then
    log "编译 Debug APK"
    ( cd "$PROJECT_DIR" && ./gradlew assembleDebug )
    APK="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"
  else
    log "编译签名 Release APK"
    ( cd "$PROJECT_DIR" && ./gradlew assembleRelease )
    APK="$PROJECT_DIR/app/build/outputs/apk/release/app-release.apk"
  fi
  [ -f "$APK" ] || die "未找到产物 APK：$APK"
  ok "APK: $APK ($(du -h "$APK" | cut -f1))"
else
  warn "已跳过编译（--no-build）"
fi

# ----------------------------------------------------------------------------- 4. install
if [ "$DO_INSTALL" = 1 ] && [ -n "$APK" ]; then
  ADB="$(command -v adb || true)"
  [ -z "$ADB" ] && [ -x "$HOME/Library/Android/sdk/platform-tools/adb" ] && \
    ADB="$HOME/Library/Android/sdk/platform-tools/adb"
  [ -n "$ADB" ] || die "未找到 adb（在 SDK 的 platform-tools 里）"
  if [ -z "$("$ADB" devices | sed '1d' | awk 'NF{print}')" ]; then
    die "没有已连接的设备，请插上手机并开启 USB 调试"
  fi
  log "安装到设备"
  "$ADB" install -r "$APK"
  ok "已安装到设备"
fi

log "完成 🎉"
[ -n "$APK" ] && echo "    产物: $APK"

#!/usr/bin/env bash
# One-click Vosk model downloader / 一键下载 Vosk 离线模型
# Usage: ./scripts/download-vosk.sh [cn|en]
set -euo pipefail
LANG_SEL="${1:-cn}"
case "$LANG_SEL" in
  cn|zh) URL="https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip" ;;
  en)    URL="https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip" ;;
  *) echo "Usage: $0 [cn|en]"; exit 1 ;;
esac
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/app/src/main/assets/vosk-model"
if [ -d "$DEST/conf" ]; then echo "✓ Model already exists at $DEST"; exit 0; fi
echo "==> Downloading $LANG_SEL model..."
mkdir -p "$DEST"; TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
curl -fL --progress-bar -o "$TMP/model.zip" "$URL"
unzip -q "$TMP/model.zip" -d "$TMP/u"
SRC="$(find "$TMP/u" -maxdepth 1 -mindepth 1 -type d | head -1)"
cp -R "$SRC/." "$DEST/"
echo "✓ Installed to $DEST"

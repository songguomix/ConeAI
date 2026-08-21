# Vosk 离线语音模型 / Offline Speech Model

> 此目录默认不提交到 Git（约 65MB）。按需一键下载。
> This directory is gitignored (~65 MB). Download on demand.

## 一键下载 / One-Click Download

```bash
./setup.sh --no-build              # 中文模型 (默认, ~40MB) / Chinese (default)
./setup.sh --no-build --model en   # 英文模型 / English model
MODEL_URL=<url> ./setup.sh --no-build  # 自定义地址 / custom URL
```

脚本会自动下载并解压到此目录，完成后应包含 `conf/` `am/` `graph/` `ivector/`。
The script downloads and extracts here; afterwards `conf/`, `am/`, `graph/`, `ivector/` should exist.

## 手动下载 / Manual Download

1. 下载 / Download：
   - 中文 Chinese: https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip
   - 英文 English: https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip
   - 更多 / More: https://alphacephei.com/vosk/models
2. 解压后将模型文件夹内的所有内容复制到本目录 / Copy all extracted contents into this folder
3. 确认 `conf/mfcc.conf` 存在 / Verify `conf/mfcc.conf` exists

## 说明 / Notes

- 无模型时应用仍可运行，会自动回退到系统语音识别（更准）；离线 Vosk 仅作兜底。
  Without a model the app still works and falls back to system speech recognition (more accurate); offline Vosk is only a fallback.
- 如需重新下载，删除本目录内容后重跑 `./setup.sh`。
  To re-download, delete contents of this folder and re-run `./setup.sh`.

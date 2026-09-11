# ConeAI

MCP servers can be added under **Settings → MCP servers**. Add a Streamable HTTP URL (usually `/mcp`) and optional JSON headers such as `{"Authorization":"Bearer YOUR_TOKEN"}`. Test, edit, enable/disable, or delete servers there. Configuration is encrypted on the device; tools reload on startup and configuration changes for agent and Q&A use. Local stdio commands and legacy SSE endpoints are not supported. Agent tasks continue to exclude general web search.

**An on-device AI Agent + Voice Assistant for Android.**

Two modes and an always-available voice capsule:

- **Agent**: One natural-language instruction → auto screenshot → OCR + Accessibility tree → LLM planning → auto tap / input / swipe → re-capture & verify → loop until done.
- **Q&A**: Plain chat with any OpenAI-compatible model, with image/file attachments and one-click web search.
- **Voice Assistant**: Long-press Power/Home or say the wake word **"Pine Cone / 松果 / Hey pine cone"** to pop the bottom capsule.

> Bring your own model: any OpenAI-compatible endpoint, API Key stored encrypted on-device.

[中文文档](./README.md) | **English**

---

## Capabilities

### Agent
- Vision: MediaProjection real-pixel screenshots
- UI understanding: ML Kit OCR + AccessibilityService tree
- Actions: open app / tap / double-tap / long-press / input / swipe / scroll / back / home / recents / wait / screenshot
- **APIs on demand**: weather / news / exchange rate / crypto / stocks / gold & silver / Wikipedia / holidays / route distance & time / music / lyrics / IP / world time / air quality / books / dictionary via task-specific APIs. Agent tasks do not perform general web searches or fall back to web search when an API fails or no shopping app is installed. Q&A web search is unaffected.
- **Share to message**: send polished text to WeChat / QQ / DingTalk via `ACTION_SEND` intent targeting, no char-by-char typing
- **One-shot navigation**: "navigate to X" via map URI (Amap/Baidu/Google Maps auto-selected), like a system assistant
- **On-demand permissions**: tasks like open app / navigate / play music / call need **no** screen capture or Accessibility; agent adapts to granted capabilities
- **Music**: standard `MEDIA_PLAY_FROM_SEARCH` intent, with optional target like NetEase / QQ Music / Spotify
- **Shopping**: jump to Taobao / JD / Pinduoduo search results via URI, then visual picking; payment always requires confirmation
- **System intents**: call (pre-filled dialer) / SMS / email draft / alarm & timer / system settings / flashlight
- **Plan mode (optional)**: generates a full plan for your approval before execution
- Observe → Think → Act loop with auto-retry; failure re-observes and retries until limit
- Floating Control Center (Material You / Gemini style): draggable, collapsible, plan view, **pause / resume / take over / end**
- **User-first**: only you can pause (pause / take over hands control back, resume re-observes); AI never pauses itself
- High-risk guard: payment / transfer / send message / delete / password change / order submission require confirmation

### Q&A
- Plain chat with a separate Q&A model (can share the agent model)
- Attachments: camera / gallery / files (text files are ingested as context)
- **Web search**: toggle to inject live web results (works with any model, no tool-calling required)
- **Free API tools**: LLM can autonomously call 16 free APIs (weather, news, rate, crypto, stock, gold, wiki, holiday, route, music, lyrics, IP, time, air quality, books, dictionary) via plain-text JSON `{"tool":"weather","query":"Beijing"}`
- **System intents**: Q&A can also set alarm / flashlight / dial / SMS & mail drafts / navigate / play music / shopping / share / open apps — one-shot reversible calls; multi-step UI ops auto-hand off to Agent via `agent_task`
- **Auto handoff**: when Q&A detects a task needs on-device action, it switches to Agent and notifies you; incognito Q&A never hands off
- **Memory (Gemini-style)**: Q&A remembers preferences via "remember… / forget…", can retrieve past conversations; all on-device, deletable / clearable / disable-able; incognito reads/writes nothing
- Thinking time & token usage display

### Voice Assistant (Capsule)
- Entry: assistant gesture (set as default digital assistant) or **wake word**
- STT: **system STT first (more accurate, e.g. Google), fallback to on-device Vosk (offline, no network)**; silence-endpoint tolerant, editable before sending
- Execute / Think dual mode, same as main Agent/Q&A
- **No window switch for intent tasks**: info lookup / alarm / flashlight run fully inside the capsule with a "⚙️ Running" card; only real screen-takeover (tap/open app) yields the capsule
- 3-bar menu: **Camera / File / Screenshot (crop to ask)**
- Same Markdown rendering as main: headings / bold / lists / code / tables / links / images / math normalization
- TTS: mute-able, skips `*` and emoji when reading
- One-tap **copy**, long-press selection; web-search icon only in Q&A mode
- Conversations sync to Q&A history

### Wake Word
- Words: **"松果"** (你好松果/嗨松果) or **"Hey pine cone"**
- Foreground mic service, offline detection, pops capsule without bringing main app to front
- **Mic courtesy**: low-priority voice-recognition source, yields to other apps
- Fuzzy homophone matching (松/送/宋 + 果/过…), easy to trigger

### ConeCode Remote (Desktop)
- Scan the ConeCode desktop pairing QR (`?t=token&p=password`) to mirror desktop AI sessions: messages, session/model/reasoning switch, approve/reject code changes
- Built-in file browser/editor + one-shot terminal — **execute on desktop**, phone is a remote
- Transport: HTTPS + pinned self-signed cert (LAN), tunnel domains (cloudflared/ngrok) use system CA; password stored only encrypted in Keystore with biometric unlock; plaintext settings keep pairing URL **without** password
- QR itself is a credential — only show in trusted environments

### General
- API Keys encrypted with Android Keystore (AES-256-GCM), never in plaintext
- UI languages: 中文 / English / 日本語
- Histories archived separately for Agent/Q&A, browse / reopen / delete in sidebar

---

## Model System (Open Provider)

- No hardcoded models. Add a Provider: name + Base URL + API Key
- Any OpenAI-style service: GPT / Claude / Gemini / DeepSeek / Qwen / Kimi / GLM / MiniMax / OpenRouter / Ollama / LM Studio / vLLM …
- Auto-discover: `GET /models`
- Freely choose any model as **Agent main model** or **Q&A model** (no vision-model restriction)
- Tip: Agent benefits most from a **vision-capable** model; text-only models rely on OCR & view tree

---

## Tech Stack

Kotlin · Jetpack Compose · Material 3 / Material You · MVVM · Hilt · Room · Coroutines · Retrofit + kotlinx.serialization · OkHttp · AccessibilityService · MediaProjection · ML Kit OCR · Vosk (offline STT) · Android TextToSpeech · DataStore

- minSdk 26 · targetSdk / compileSdk 35

---

## ConeSearch Hybrid Retrieval

> In-house `ConeSearch`: **local FTS first + Bing fallback** RAG engine powering `web_search` / `fetch` / `index_url`.

```
query → parseQuery → FTS recall (60~400) → 8-signal re-rank → MMR dedup → token-budget pack → context
                                     ↗ Bing first, fallback to local; Bing hits are indexed back
```

#### 1. Storage `search/db`
- `search_documents`: `url(normalized)` dedup, `contentHash(sha256[32])` incremental, `quality/tokenCount/publishedAt`
- `search_chunks`: `ordinal/anchor/headingPath/content/simhash(64bit)` with FK cascade
- `search_chunks_fts(Fts4)`: `tok(heading+content) / titleTok / urlTok` tokenized strings only

#### 2. Ingestion `extract/` + `index/SearchIndexer`
- **HtmlExtract**: regex for `title/desc/og:site_name/lang/canonical/published`, `htmlToMarkdown` (`h1→#`, `pre→``` , `a→[]()`), `computeQuality` base 0.35, +0.1 for >200/600 tokens, +0.05 for `##`/` ``` `
- **Chunk**: `target420/max900/min60/overlap40 tokens`, blocks `heading/code/table/paragraph`, `headingStack→headingPath`, `slugify→anchor`, `simhash` dedup
- **Indexer**: `normalizeUrl` strips `www`/`utm` and sorts query, hash match → update only, else delete old chunks/fts then `toIndexString→insertFts`

#### 3. Query `SearchQuery`
Extracts `"phrase"`, `site:/filetype:/after:/before:` (supports `7d/2w`→Instant), `-negation`, `tokenize(unigrams=false)` → `terms`, `buildMatchExpression` → `"(phrase token + token) AND (term OR …)"` via `ftsQuote`

#### 4. 8-Signal Re-rank `SearchRank`
`lexical1.0(BM25 norm)` + `coverage0.85` + `proximity0.35` + `heading0.4` + `quality0.3` + `substance0.55` + `freshness0.25(exp(-days/520))` + `position0.15`; short <40 → -0.35, phrase miss → -1.2, negation hit → -1.5

#### 5. MMR Diversify `diversify(λ=0.35)`
`perDocument2/perHost4`, `hamming≤6` near-duplicate drop, `mmr=(1-λ)*score - λ*maxSim*2` greedy top `limit`

#### 6. Budget Pack `SearchBudget`
`perFloor88`, `affordable=min(n,maxTokens/88)`, `w=1/√rank*max(0.15,score)` proportional `alloc∈[60,1200]`, surplus refill, `truncateToTokens` per item, break if <60 & truncated

#### 7. Hybrid `WebSearchClient`
1) **Bing first** `GET bing.com/search?q=` regex `h2>a`+`p.b_`, index back via `indexUrl` 2) Empty → `engine.search(SearchParams(8,4000))`; `fetch` hits DB → `truncateToTokens` else `fetchHtml→extract`

#### 8. Text `text/`
`tokenize`: NFKC+lower, CJK→bigram(+unigram), Latin→`LATIN_WORD`, zh/en stopwords; `estimateTokens`: `cjk*1+hangul*0.8+latin/3.8+digit/2.5`; `truncateToTokens` binary search + sentence boundary backtrack

> No vector DB — pure FTS + signals + MMR: offline, zero-dependency, explainable, incremental, budget-aware.

---

## Project Structure

```
app/src/main/java/com/cone/agent/
├── ConeApplication.kt / MainActivity.kt
├── core/             constants, notification channels, LocaleHelper
├── data/
│   ├── crypto/       KeystoreManager
│   ├── local/        Room DB, DAO, entities
│   ├── remote/       OpenAI-compatible API (LlmApi / LlmClient / DTO) · WebSearchClient
│   └── repository/   Provider / Chat / Settings repos
├── domain/model/     Provider / ModelInfo / session & task models
├── agent/            AgentController · AgentEngine · ActionExecutor · RiskGuard · ChatResponder · PromptBuilder · action/
├── vision/           AccessibilityService · ScreenCaptureManager/Service · OcrEngine · view tree
├── assistant/        AssistantActivity (capsule) · AssistantPillView · VoskSpeechEngine / SpeechController · TtsSpeaker · ConeVoiceInteractionService/Session · SnipOverlayView
├── service/          AgentForegroundService · FloatingControlService · WakeWordService
├── remote/           ConeCode desktop client (SSE mirror · RemoteClient/Protocol/Tls · file browser/editor · terminal · QR pairing · biometric)
├── web/              search engine picker (auto/baidu/google/bing) · free weather/news fetch
├── di/               Hilt modules (Database / Network)
└── ui/               theme · navigation · components · screens (chat / providers / settings / permissions)
```

---

## Build & Run

1. Open in Android Studio (Koala+/2024.1+) or CLI:
   ```bash
   ./gradlew assembleDebug      # debug APK
   ./gradlew assembleRelease    # signed release (keystore at ~/.coneai-signing/keystore.properties,
                                # override via CONEAI_KEYSTORE_PROPS; never in repo)
   ```
2. First sync downloads Gradle, AGP & deps (online).
3. Run on real device Android 8.0 (API 26)+ (screen capture & mic limited on emulator).
4. STT prefers system STT; offline Vosk is an optional fallback — see below.

### Offline Speech Model (Optional)

`vosk-model` (~65 MB) is gitignored. Download on demand after cloning:

```bash
./setup.sh --no-build              # Chinese model only (~40MB, vosk-model-small-cn-0.22)
./setup.sh --no-build --model en   # English model (vosk-model-small-en-us-0.15)
./setup.sh                         # Chinese model + build release APK
MODEL_URL=<url> ./setup.sh --no-build  # custom URL
# or standalone:
./scripts/download-vosk.sh cn      # or en
```

- Manual: download from https://alphacephei.com/vosk/models, extract into `app/src/main/assets/vosk-model/` so `conf/` `am/` `graph/` exist.
- Builds and runs fine without a model; offline Vosk is only used when system STT is unavailable.

### First-Time Setup (also guided in-app under "Permissions & Environment")

1. Pick a model in "Models & Settings" (add a Provider first, then "Discover models")
2. Enable **Accessibility Service** (agent's hands)
3. Allow **Draw over other apps** (floating controls / wake popup)
4. Grant **Screen Capture** (agent's eyes)
5. Allow **Notifications** (foreground service, recommended)
6. Grant **Microphone** (voice input / wake word)
7. Set as **Default Voice Assistant** (optional, for gesture)
8. Enable **Wake Word** (optional): background listening for "松果 / Hey pine cone"

Then type a task on the home screen.

---

## Security

- Screen capture, Accessibility, overlay, mic are system-sensitive — only for on-device tasks you initiate.
- High-risk actions require confirmation based on **screen evidence** (tapped view + nearby text, input, target app), not just model claims; disable-able but not recommended.
- Credentials **never sent via plaintext http to public hosts** (network guard); plaintext only to localhost/LAN for local models (Ollama / LM Studio / vLLM).
- STT is **offline**; web search only when you enable it; wake-word service writes nothing to system log in release builds.
- API Keys encrypted via Android Keystore, backup rules exclude DB; desktop remote passwords likewise.
- `<queries>` is restricted (only launchable apps), no `QUERY_ALL_PACKAGES`.

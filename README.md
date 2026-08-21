# ConeAI

一个运行在 Android 手机上的 **AI 智能体 + 语音助手** 应用。

它有两种工作模式，并配有一个随叫随到的语音助手悬浮窗：

- **智能体（Agent）**：你说一句自然语言 → AI 自动截图 → OCR + 无障碍界面树理解屏幕 → 模型规划 → 自动点击 / 输入 / 滑动 → 再截图验证 → 循环执行直到完成。
- **问答（Q&A）**：把它当普通 AI 助手用，纯文本对话，可附带图片 / 文件，可一键联网搜索。
- **语音助手**：长按电源/Home 唤起，或直接说唤醒词「**松果 / Hey pine cone**」，弹出底部悬浮胶囊进行语音提问或下达任务。

> 自带模型由你接入：任意 OpenAI 兼容服务，API Key 本地加密存储。

---

## 核心能力

### 智能体（Agent）
- 屏幕查看：MediaProjection 真实像素截图
- 界面理解：ML Kit OCR + AccessibilityService 控件树解析
- 自动操作：打开应用 / 点击 / 双击 / 长按 / 输入 / 滑动 / 滚动 / 返回 / 桌面 / 最近任务 / 等待 / 截图
- **API 优先取信息**：天气 / 新闻 / 汇率 / 加密货币 / 股票行情 / 金银价 / 维基百科 / 节假日 / 路线距离耗时 / 歌曲信息 / 歌词 / IP 归属地 / 世界时间 / 空气质量 / 书籍 / 英语词典 一律走免费无 Key 公共接口直取文字结果，比开 App 翻页快得多；失败自动退回联网搜索
- **分享接口发消息**：整理好的文字发微信 / QQ / 钉钉等，走系统分享接口（ACTION_SEND 定向目标应用），不逐字操控输入发送
- **导航接口一步直达**：「导航去 XX」直接用地图应用的免费 URI 接口调起导航（高德 / 百度 / Google Maps 自动选择），像系统助手一样一步到位，不手动操控地图
- **屏幕权限按需使用**：只选好模型即可发起任务；打开应用 / 导航 / 播放 / 打电话等接口直达任务**不需要**屏幕捕获与无障碍。智能体按已授权能力自适应（无截图时依据控件树，全无屏幕能力时只用直达接口），真正要看屏 / 点屏的任务才提示去开启权限；语音「执行」也不再弹屏幕授权
- **音乐播放接口**：「放首 XX」走 Android 标准播放意图（MEDIA_PLAY_FROM_SEARCH），音乐应用自行搜索播放；可指定网易云 / QQ音乐 / Spotify 等
- **购物直达接口**：「买 XX」用淘宝 / 京东 / 拼多多的 URI 接口直达搜索结果页再看屏挑选；付款下单永远二次确认
- **系统能力接口**：打电话（拨号盘预填）/ 发短信、写邮件（内容预填，发送留给你）/ 设闹钟倒计时（一步设好）/ 直达各系统设置页 / 手电筒开关——全走系统标准接口，不逐步操控 UI
- **计划模式（可选）**：任务开始前先生成总体计划，你点「开始执行」才动手，点「取消任务」直接结束（设置中开启）
- Observe → Think → Act 循环，自动纠错重试（动作失败即回退重新观察；连续失败达上限自动停止）
- 悬浮控制中心（Material You / Gemini 风格）：拖动、折叠、查看计划、**暂停 / 继续 / 接管 / 结束**
- **用户优先**：只有用户能暂停任务（点「暂停」或「接管」即交还控制权，恢复后自动重新观察界面）；**AI 不会自行暂停**，会持续推进直到完成
- 高风险拦截：付款 / 转账 / 发送消息 / 删除 / 修改密码 / 提交订单 等操作默认强制二次确认

### 问答（Q&A）
- 纯文本对话，使用你单独指定的「问答模型」（也可与智能体共用）
- 附件：拍照 / 相册 / 文件（文本类文件会被读取并作为上下文）
- **联网搜索**：开关式接入实时网页结果作为回答依据，对任意模型都可用（无需模型支持工具调用）
- **免费信息接口调取**：模型可自行调取天气 / 新闻 / 汇率 / 币价 / 股票 / 金银价 / 百科 / 节假日 / 路线 / 歌曲 / 歌词 / IP / 世界时间 / 空气质量 / 书籍 / 英语词典等 16 类免费 API（纯文本协议，任意模型可用）
- **系统接口直达**：问答也能设闹钟 / 开手电 / 拨号 / 短信邮件草稿 / 导航 / 播放音乐 / 商品搜索 / 分享 / 打开应用与设置页——一次性可逆调用；多步屏幕操作自动转交智能体（agent_task），屏幕自动化不对问答开放
- **自主转执行**：问答中模型判断请求需要实际操作手机（放歌、购物、发消息、导航…）时，自动切换到智能体模式开始执行并告知你；无痕问答不会转执行
- **记忆（Gemini 式）**：问答记住你分享的偏好与信息并用于个性化回答，可对 AI 说「记住… / 忘记…」；还能按需检索过往会话记录，回答「上次聊的那个方案」；均仅在本机，设置中可逐条删除、清空或关闭；无痕问答不读不写记忆、不检索历史
- 思考耗时与 token 用量展示

### 语音助手（悬浮胶囊）
- 入口：系统助手手势（设为默认数字助理后长按电源/Home）或**语音唤醒**
- 语音识别：**优先系统识别（更准，尤其是 Google 在线 STT），无可用系统识别时回退离线本地模型（Vosk，纯本地、不联网）**；带静音端点容错，长句/中途停顿不被切断；识别结果可编辑后再发送
- 执行 / 思考双模式，与主界面的智能体 / 问答一致
- **接口任务不退窗**：查询信息 / 设闹钟 / 开手电等纯接口任务全程在悬浮胶囊里完成，运行时显示「⚙️ 正在执行任务」实时卡片（当前动作 + 思考），结果直接显示并朗读；只有智能体真要接管屏幕（点击/打开应用等）时胶囊才让位
- 三条杠附件菜单：**相机 / 文件 / 截图（圈选屏幕提问）**
- 答案卡片与主界面**同款 Markdown 渲染**：标题 / 加粗 / 列表 / 代码块 / 表格 / 可点链接 / 图片查看下载 / 数学符号归一化
- 答案朗读（TTS）：可静音；朗读时**自动跳过星号与表情符号**，只读正文
- 答案可一键**复制**，长按可选择部分文本
- 联网搜索图标仅在「问答」模式出现，与主界面一致
- 语音助手的对话会同步进入「问答」历史记录

### 语音唤醒（Wake Word）
- 唤醒词：「**松果**」（你好松果 / 嗨松果）或「**Hey pine cone**」
- 后台常驻麦克风前台服务，离线本地识别，命中即弹出语音助手悬浮窗（不会把应用主界面拉到前台）
- **礼让麦克风**：使用低优先级语音识别音源，其他应用需要录音时自动让出、互不冲突
- 唤醒匹配做了同音字模糊处理（松/送/宋… + 果/过/裹…），易于触发

### 桌面遥控（ConeCode Remote）
- 扫描 ConeCode 桌面端的配对二维码（`?t=令牌`，可附 `&p=密码`）后，手机实时镜像桌面 AI 会话：收发消息、切换会话/模型/推理力度、审批或驳回代码变更
- 内置文件浏览器/编辑器与一次性终端——**均在桌面机器上执行**，本质是"手机遥控你自己的电脑"，能力边界即桌面端进程的权限
- 传输：HTTPS + 内置自签证书钉扎（局域网直连），隧道域名（cloudflared/ngrok）走系统 CA；密码只保存在 Keystore 加密、生物识别解锁的登录条目里，明文设置仅保留**去掉密码**的配对地址
- 配对二维码本身等同凭据（拍到即可连接你的桌面端），只在可信环境展示

### 通用
- API Key 使用 Android Keystore（AES-256-GCM）加密存储，绝不明文落盘
- 多语言界面：中文 / English / 日本語
- 会话历史按「智能体 / 问答」分别归档，可在侧边栏浏览、重开、删除

---

## 模型系统（开放 Provider 架构）

- 不写死任何模型。用户自行新增 Provider：名称 + Base URL + API Key
- 兼容任意 OpenAI 风格服务：GPT / Claude / Gemini / DeepSeek / Qwen / Kimi / GLM / MiniMax / OpenRouter / Ollama / LM Studio / vLLM …
- 自动发现模型：`GET /models`
- **可自由选择任意模型**作为「智能体主模型」或「问答模型」（不再限制视觉模型，是否支持视觉不再影响选择）
- 提示：智能体模式依赖看懂屏幕，**选用具备视觉能力的模型效果最佳**；纯文本模型则主要依赖 OCR 与控件文本

---

## 技术栈

Kotlin · Jetpack Compose · Material 3 / Material You · MVVM · Hilt · Room · Coroutines ·
Retrofit + kotlinx.serialization · OkHttp · AccessibilityService · MediaProjection ·
ML Kit OCR · Vosk（离线语音识别）· Android TextToSpeech · DataStore

- minSdk 26 · targetSdk / compileSdk 35

---

## 工程结构

```
app/src/main/java/com/cone/agent/
├── ConeApplication.kt / MainActivity.kt
├── core/             常量、通知渠道、多语言 LocaleHelper
├── data/
│   ├── crypto/       KeystoreManager（密钥加密）
│   ├── local/        Room 数据库、DAO、实体
│   ├── remote/       OpenAI 兼容 API（LlmApi / LlmClient / DTO）· WebSearchClient（联网搜索）
│   └── repository/   Provider / Chat / Settings 仓库
├── domain/model/     Provider / ModelInfo / 会话与任务模型
├── agent/            AgentController（循环+状态）· AgentEngine（观察/思考）·
│                     ActionExecutor · RiskGuard · ChatResponder（问答）· PromptBuilder · action/
├── vision/           AccessibilityService · ScreenCaptureManager/Service · OcrEngine · UI 树
├── assistant/        语音助手：AssistantActivity（悬浮胶囊）· AssistantPillView ·
│                     VoskSpeechEngine / SpeechController（语音识别）· TtsSpeaker（朗读）·
│                     ConeVoiceInteractionService/Session（系统助手入口）· SnipOverlayView（圈选）
├── service/          AgentForegroundService · FloatingControlService（悬浮控制中心）·
│                     WakeWordService（语音唤醒前台服务）
├── remote/           ConeCode 桌面遥控客户端（SSE 镜像 · RemoteClient/Protocol/Tls ·
│                     文件浏览/编辑 · 终端 · 扫码配对 · 生物识别解锁）
├── web/              搜索引擎选择（自动/百度/Google/Bing）· 免费天气/新闻抓取
├── di/               Hilt 模块（Database / Network）
└── ui/               theme · navigation · components · screens（chat / providers / settings / permissions）
```

---

## 构建运行

1. 用 Android Studio（Koala+/2024.1+）打开本目录，或命令行：
   ```bash
   ./gradlew assembleDebug      # 调试包
   ./gradlew assembleRelease    # 已签名正式包（签名配置在 ~/.coneai-signing/keystore.properties，
                                # 可用环境变量 CONEAI_KEYSTORE_PROPS 指定其他位置；密钥不入项目目录）
   ```
2. 首次同步会自动下载 Gradle、AGP 与依赖（需联网）。
3. 运行到 Android 8.0 (API 26) 及以上真机（屏幕捕获、麦克风等能力在模拟器上受限）。
4. 语音识别默认优先系统识别（更准）；离线兜底需要在 `app/src/main/assets/vosk-model/` 放入一个 Vosk 模型（`./setup.sh` 会下载中文小模型），无系统识别可用时自动回退到它。

### 首次使用前的环境准备（应用内「权限与环境」页一键引导）

1. 在「模型与设置」选择一个模型（需先在「Provider 管理」新增 Provider 并「自动发现模型」）
2. 开启**无障碍服务**（智能体的“手”）
3. 允许**悬浮窗**权限（悬浮控制中心 / 唤醒后弹窗）
4. 授权**屏幕捕获**（智能体的“眼”）
5. 授予**通知**权限（前台服务状态提示，推荐）
6. 授予**麦克风**权限（语音输入 / 语音唤醒）
7. 设为系统**默认语音助手**（可用助手手势唤起，可选）
8. 开启**语音唤醒**（可选）：后台聆听「松果 / Hey pine cone」

完成必需项后，在主页输入一句话即可发起任务。

---

## 安全说明

- 屏幕捕获、无障碍、悬浮窗、麦克风均为系统级敏感能力，仅用于代用户在**本机**执行其主动下达的任务。
- 高风险操作默认强制二次确认，判定**以屏幕证据为准**（被点控件及其邻近文字、输入的内容、目标应用），模型的自述只能追加确认、不能替代证据；可在设置中关闭（不建议）。
- API Key 等凭据**绝不通过明文 http 发往公网地址**（网络层强制拦截）；明文仅允许发往本机/局域网的本地模型服务（Ollama / LM Studio / vLLM）。
- 语音识别为**本地离线**进行；联网搜索仅在用户开启时发起；语音唤醒在正式包中不向系统日志写入任何听到的内容。
- API Key 经 Android Keystore 加密，备份规则已排除密钥数据库；桌面遥控密码同样只以 Keystore 密文存储。
- 应用列举采用受限的 `<queries>` 声明（仅可启动应用），不申请 `QUERY_ALL_PACKAGES`。

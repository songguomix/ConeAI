# ConeAI（Android 版）重写说明

> 本文档面向其他 AI 工具（Claude / ChatGPT / Cursor 等），用于在不接触原始代码库的情况下，
> 理解 ConeAI Android 版的产品定位、功能范围、交互模型、技术架构与关键实现细节，并据此重写或复刻该项目。
> 目标平台仅限 **Android**，且语音助手需要从现有的"底部固定胶囊"改造为**灵动岛风格**（见文末改造章节）。

---

## 一、一句话定位

ConeAI 是一个运行在 **Android 手机**上的 **"AI 智能体 + 语音助手"** 融合应用：既能像 AI Agent 一样自动操作手机屏幕完成任务，也能当普通 AI 聊天助手用，还带一个随叫随到的语音悬浮入口。所有模型均由用户自行接入（任意 OpenAI 兼容 API），不内置、不写死任何模型。

## 二、三大工作模式

### 1. 智能体（Agent）
用户说一句自然语言指令，AI 自动完成：**截图 → OCR + 无障碍界面树理解屏幕 → 模型规划 → 自动点击/输入/滑动 → 再截图验证 → 循环执行直到完成**。这是 Observe → Think → Act 循环，遇到连续失败会自动停止。

**Think 与 Act 重叠（流式执行）**：模型响应以 SSE 流式返回，边收边解析——每当从流里解析出一个完整的动作对象，就立刻执行，不必等整段 JSON 收完。因为解码（模型吐字，通常一步 3~8 秒）与设备操作因此并行，长任务显著提速；`thought`/`plan` 字段一完成也即时展示。正确性由"流结束后用与非流式完全相同的解析器整体重解一遍、并校验已执行动作恰为最终计划的前缀"来锚定，任何畸形响应与旧逻辑一样直接判失败重试。暂停/接管会同时取消观察、流与执行；对未声明支持 SSE、直接返回整段结果的服务自动回退为一次性非流式调用。

### 2. 问答（Q&A）
纯文本对话模式，可附带图片/文件（文本类文件会被读取为上下文），可一键开启联网搜索（把实时网页结果作为回答依据，对任意模型都有效，不需要模型本身支持工具调用）。显示思考耗时与 token 用量。

**数学显示**：应用不渲染 LaTeX。问答系统提示词【数学排版】段要求模型直接用 Unicode 数学符号（x²、√2、≤、π、∑、分数 a/b）；同时渲染层有 `MathText.normalize` 兜底——上屏前把模型仍可能输出的常见 LaTeX 归一化为 Unicode（剥 `$…$`/`\(\)`/`\[\]` 定界符、`\frac{a}{b}`→a/b、`\sqrt{}`→√()、上下标 `^{}`/`_{}`→²₁、`\times` 等符号命令→×、`\text{}` 解包、`\begin/\end` 剥除），围栏代码块与行内代码原样保留（教 LaTeX 的回答不受影响），美元金额（无 ASCII 字母的 `$5和$10`）不误伤，未知命令原样显示。Compose 的 MarkdownText 与语音助手答案卡共用这一归一化；「复制」仍复制原文。

**问答记忆（Gemini「Saved Info」式）**：问答可长期记住用户分享的稳定信息（称呼、偏好、习惯、重要事实），注入系统提示词的【记忆】段用于个性化回答。写入/删除复用同一工具协议：模型输出 `{"tool":"remember","query":"一句话第三人称事实"}` / `{"tool":"forget","query":"关键词"}`（提示词明确：不保存密码、验证码、证件号等敏感信息，不把一次性问题当记忆）。存储在独立 DataStore（`cone_memory`，仅本机，上限 50 条 FIFO，故意不放 Room 以隔离聊天库的破坏性迁移）。设置页「问答记忆」提供总开关（默认开）、逐条删除与清空全部；**无痕问答完全绕过记忆（不读不写，记忆类工具不被识别）**。语音助手「思考」模式共用记忆。

**参考历史会话（Gemini「reference past chats」式）**：同一开关下还提供 `{"tool":"recall_history","query":"关键词（可空格分隔多个）"}`——按关键词跨全部本机会话检索 Room 消息库（LIKE 匹配、多词取并集去重、只搜 USER/AGENT 消息、按时间倒序，最多 10 条、每条截断 160 字，附日期与会话标题），命中片段以【工具结果】回传供模型回答「上次聊的那个方案」类问题。不用向量/嵌入（用户接入的是任意对话模型，不能假设有 embedding 接口），关键词检索纯本地、零依赖。无痕问答同样不可用。

**问答自主转执行（agent_task）**：问答系统提示词的【执行任务】段告知模型：用户请求需要实际操作手机（发消息、播放音乐、购物、导航出发等）而非文字回答时，单独输出一行 `{"tool":"agent_task","query":"完整任务指令"}`。宿主通过回调接管——主界面 ChatViewModel 校验运行环境后自动切到智能体页并 `controller.start(指令)`；语音助手「思考」模式走与其「执行」模式相同的 handOff 路径；回调返回的确认文字即本轮问答的回答气泡。该工具仅在宿主传入回调时才注入提示词与被识别（无痕问答不传，永不转执行）；环境未就绪时以文字告知用户去「权限与环境」准备。

**问答也能调取免费信息接口（只读，绝不执行任何手机操控）**：问答系统提示词告知模型可用工具 weather / news / exchange_rate / crypto / stock / wiki / holiday / route / music / lyrics / ip / time / air_quality / book / dict / gold（navigate、play_music、shopping、call、sms、email、alarm、open_settings、flashlight 属于设备操控，问答不直接开放，经 agent_task 转智能体完成）；模型需要实时信息时**单独输出一行 JSON**（如 `{"tool":"weather","query":"北京"}`，不带任何其它文字），应用检测到后执行对应免费 API、把结果以【工具结果】回传再让模型作答，最多连续 3 轮——纯文本协议，任意模型可用，不依赖 function calling。流式实现上，以 `{` 开头的响应先缓冲不渲染（工具调用是一行短 JSON，不能当作答案显示；超过 400 字符或不以 `{` 开头则立即放行照常流式），工具轮次对用户完全不可见，只有最终回答进入 UI；各轮 token 用量累加上报。语音助手「思考」模式共用同一 ChatResponder，同样获得该能力。

### 3. 语音助手（**本次改造为灵动岛**）
长按电源/Home 键（设为系统默认数字助理后）或直接说唤醒词 **"松果" / "Hey pine cone"** 唤起语音交互入口，可语音提问或下达任务，与主界面的智能体/问答模式行为一致。呈现方式见第七章。

---

## 三、智能体核心机制（重写时最需要精确还原的部分）

**动作集合**：`open_app`（按应用名/包名打开）、`click` / `double_click` / `long_click`（坐标点击）、`input_text`（在坐标处或已聚焦输入框输入文字）、`swipe`（起止坐标滑动）、`scroll`（up/down/left/right）、`back` / `home` / `recent_apps`、`web_search` / `open_url` / `open_browser`（内置浏览器：搜索/打开网址/打开首页，结果显示在屏幕上由下一次截图读取）、`weather` / `news` / `exchange_rate` / `crypto` / `stock` / `wiki` / `holiday` / `route` / `music` / `lyrics` / `ip` / `time` / `air_quality` / `book` / `dict` / `gold`（免费无 Key API 直取天气/新闻/汇率/加密货币/股票行情/维基百科/法定节假日/驾车路线距离与耗时/歌曲信息/歌词/本机 IP 归属地/世界时间/空气质量/书籍/英语词典/金银现货价，结果以文字回传进任务历史；来源依次为 wttr.in、Google News RSS、open.er-api.com、CoinGecko、腾讯行情 qt.gtimg.cn、Wikipedia REST、Nager.Date、Nominatim+OSRM、iTunes Search、LRCLIB、ipwho.is、worldtimeapi.org、Open-Meteo AQ、Open Library、dictionaryapi.dev、gold-api.com）、`navigate`（地图应用免费 URI 接口发起导航，见下）、`play_music`（系统播放接口发起音乐播放，见下）、`shopping`（购物应用免费 URI 接口直达搜索结果，见下）、`share`（系统分享接口 `ACTION_SEND` 定向分享文字到微信/QQ 等，见下）、`call` / `sms` / `email`（系统拨号/短信/邮件接口：预填号码与内容，拨出/发送那一下留给用户或证据守卫）、`alarm`（`AlarmClock.ACTION_SET_ALARM/SET_TIMER` + SKIP_UI 直接设好闹钟/倒计时，manifest 声明 SET_ALARM 普通权限）、`open_settings`（按关键词直达系统设置页：wifi/蓝牙/声音/显示/电池/位置/应用/语言/飞行模式…）、`flashlight`（CameraManager.setTorchMode 手电筒开关，免权限）、`wait`（毫秒级等待）、`finish`（标记任务完成）。以上系统接口类动作均可逆或不产生提交，全部列入 RiskGuard 白名单；call/sms/email/open_settings 执行后切换前台，剩余批次作废并重新观察。

**导航接口优先（导航类任务，对标千问系助手）**：真正的逐向导航没有免费开放 API，但地图应用都提供**免费无 Key 的 URI 调起接口**——`navigate {text, app?}` 按「用户指定应用 → 高德（`amapuri://route/plan/?dname=…&t=0`，包名 com.autonavi.minimap）→ 百度（`baidumap://map/direction?destination=…&mode=driving`）→ Google Maps（`google.navigation:q=…`）→ 通用 `geo:0,0?q=…`」的顺序一步调起导航，后续引导由地图应用接管，智能体不去逐步操控地图 UI。navigate 可逆且不产生任何提交，不参与风险关键词判定；执行后本批剩余动作作废并重新截图观察。只问「多远/多久」不出发时用只读的 `route`（Nominatim 地理编码 + OSRM 演示服务器算路，均免费无 Key）。

**分享接口优先（发消息类任务）**：用户要求把整理好的文字发到微信、QQ、钉钉等通讯应用时，智能体先在思考里组织好完整文字，再用 `share {text, app}` 走系统分享接口（`Intent.ACTION_SEND` + `text/plain`，`app` 按别名表解析到包名：微信 com.tencent.mm、QQ com.tencent.mobileqq 等，解析不到则弹系统分享面板；manifest 已声明对应 `<queries>` 可见性），**不打开对方应用逐字输入再点发送**。share 只弹出选择界面、本身不发送任何内容，因此不参与风险关键词判定；后续在目标应用里点「发送」的那一下仍按屏幕证据走高风险确认。执行 share 后本批剩余动作作废并重新截图观察（同内置浏览器）。

**音乐播放接口**：`play_music {text, app?}` 走 Android 标准的 `MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH` 播放意图（系统语音助手同款）——音乐应用收到后自行搜索并开始播放，智能体不逐步操控其 UI。`app` 按别名表定向（网易云 com.netease.cloudmusic、QQ音乐 com.tencent.qqmusic、酷狗/酷我/汽水/Spotify/YT Music/Apple Music）；未指定时先走系统隐式路由，失败再逐个尝试已装的已知播放器。

**购物直达接口**：`shopping {text, app?}` 用购物应用的免费 URI scheme 直达搜索结果页——淘宝 `taobao://s.taobao.com/search?q=`、京东 `openApp.jdMobile://virtual?params={jump→productList}`、拼多多 `pinduoduo://…/search_result.html?search_key=`，按用户指定或安装情况选择；都没装则退回内置浏览器网页搜索。之后的挑选/加购由看屏操作完成；**任何付款/下单确认仍走高风险拦截**，shopping 本身只开列表页、不参与风险判定。

**计划模式（Plan Mode，设置开关，默认关）**：开启后任务先执行一轮「只规划不执行」——观察 + 思考，模型响应中的 actions 全部丢弃，`plan` 数组（缺失时退化为该批动作的文字描述）作为总体计划呈现给用户（主界面卡片与悬浮控制中心各有一组「开始执行 / 取消任务」按钮，悬浮窗保证语音发起的任务也能确认）。用户确认后才进入正常 Observe→Think→Act 循环，已确认的计划注入后续每步提示词的【总体计划】且不被步内计划覆盖；取消则任务直接结束，全程未执行任何动作。若规划阶段模型判断任务本就无事可做（done 且无实质动作），直接按完成收尾，无需确认。

**屏幕能力按需自适应（权限不再是启动门槛）**：发起任务只要求选好模型；无障碍/悬浮窗/屏幕捕获缺失时不拦截，只显示提示条。观察层自动降级：屏幕捕获未授权 → `capture()` 返回 null，无截图无 OCR，提示词加【无截图】段告知模型依据控件列表行动；连无障碍也没有 → 控件树为空，提示词加【屏幕能力受限】段，把模型限制在直达接口动作（open_app/navigate/play_music/shopping/share/call/sms/email/alarm/open_settings/flashlight + 各信息查询）内，能完成就 finish，必须看屏/点屏才继续的任务则 finish 并在 summary 说明需开启权限——**由模型自主判断是否需要屏幕能力**，「打开某应用」类简单任务全程零权限弹窗。判断出确实需要读屏时，模型可发起 `request_screen` 动作：隐形的 `CaptureGrantActivity` 弹出系统 MediaProjection 授权弹窗，执行器等待用户裁决（30s 超时、400ms 轮询），同意 → 下一次观察即带真实截图；拒绝/超时也算执行成功（结果是拒绝），observation 明确告知模型不要再次请求、改用现有能力或 finish 说明。request_screen 属无头动作（系统弹窗浮于一切之上，语音胶囊无需让位）、不参与风险判定（裁决权在用户）。提示词的【无截图】段引导「控件信息不足时才请求」，【屏幕能力受限】段提醒「授权后也只能看不能点（点击仍需无障碍）」。语音助手「执行」的 `AssistantLauncherActivity` 不再强制弹屏幕捕获授权：已有会话就复用，没有就直接以现有能力开跑。

**问答的系统接口（与信息工具同一协议）**：问答除只读信息工具外，还可直接调用一次性系统接口（工具 JSON 增加可选 `"app"` 字段）：alarm / flashlight / call / sms / email / navigate / play_music / shopping / share / open_app / open_settings——经智能体的 ActionExecutor 执行，全部可逆或需用户在对方界面再确认（拨号盘不自动拨出、短信邮件仅草稿），因此不走高风险弹窗。模型的决策优先级由提示词固定：能文字回答 → 直接答；一步接口能完成 → 调系统接口；确需多步屏幕操作 → agent_task 转智能体。屏幕自动化始终不对问答开放。

**语音悬浮窗礼让（无头动作不退窗）**：动作集合中标记了「无头动作」（`ActionType.HEADLESS`：全部信息查询 API + alarm + flashlight + wait + capture_screen + finish）——既不看真实屏幕也不动真实屏幕。语音助手「执行」不再关窗交接：胶囊敞开着直接 `controller.start`，并向控制器登记 overlay 在场；纯接口任务（查天气、设闹钟、开手电…）全程在胶囊内完成，运行期间胶囊显示**执行任务卡片**（「⚙️ 正在执行任务」标题 + 当前动作 + 最新思考，原位刷新不闪烁），完成后 summary 直接显示在胶囊里并按静音设置朗读，**悬浮窗全程不退出**（问答模式本就不退出）。智能体遇到第一个非无头动作时置 `usingScreen=true`：胶囊收到即自行 finish 让位，控制器等 overlay 离场（超时 2s 兜底）后**作废本批重新观察**——之前的截图里叠着胶囊，坐标不可信。失败/取消同样把结果回显到胶囊。主界面发起的任务无 overlay 登记，此机制完全不影响原有行为。批内某动作执行失败（如手势派发失败）时，剩余动作赖以成立的画面前提不再可信，立即作废剩余计划、重新截图观察，让模型基于失败记录换思路；连续失败计数只在**动作真正执行成功**时清零（而不是解析出决策就清零），思考失败与动作失败都累积，达到上限（MAX_CONSECUTIVE_FAILURES）任务自动停止，坏环境下不会陷入「失败→重观察」死循环。双模型（拼凑）模式下视觉模型调用失败或返回空描述时，本步自动回退为单模型直接看图，不计为失败。

**系统提示词原文**（来自 `PromptBuilder.kt`，驱动智能体行为的核心 prompt，逐字如下）：

```
你是 ConeAI——运行在 Android 手机上的自动化智能体。你能看屏幕截图、读界面控件与 OCR 文本，并用点击/输入/滑动等动作替用户把任务做到底。请发挥你最强的视觉理解与规划能力，每一步都基于屏幕上真实可见的证据，稳准地推进。

# 输出（最重要）
只输出一个 JSON 对象，不附加任何解释、前后缀或 Markdown 代码块。结构固定：
{
  "thought": "对当前屏幕的简要推理：现在在哪、这一步要做什么、为什么",
  "plan": ["第1步", "第2步"],   // 仅第一步给出整体计划，之后用 []
  "actions": [                  // 本屏按顺序执行的动作；字段见「动作」
    { "action": "click", "x": 360, "y": 1200 }
  ],
  "done": false,                // 整个任务是否已完成
  "summary": ""                 // done=true 时填完成说明
}

# 坐标
屏幕为 宽 {width} × 高 {height} 像素，原点 (0,0) 在左上角，所有坐标必须落在此范围内。优先用「界面控件列表」给出的控件中心坐标（最准）；没有再依据截图估算。

# 动作（action 取值与字段）
- open_app {app}                  打开应用，app 为应用名或包名
- click / double_click / long_click {x,y}
- input_text {text, x?, y?}       在 (x,y) 处输入 text；已聚焦输入框时可省略坐标
- swipe {x,y,x2,y2, duration_ms?} 从 (x,y) 滑到 (x2,y2)
- scroll {direction}              direction 取 up/down/left/right
- back / home / recent_apps       返回 / 回桌面 / 最近任务
- open_url {text}                 在内置浏览器中打开已知的具体网页，不接受搜索关键词或搜索引擎页面
- open_browser {text?}            打开内置浏览器（text 仅可为已知的具体网页网址，省略则打开空白页）
- weather {text?}                 直接获取天气（免费 API），text 为城市名，可省略（默认当前位置）；结果以文字回传给你
- news {text?}                    直接获取新闻头条（免费 API），text 为主题/关键词，可省略（默认今日头条）；结果以文字回传给你
- exchange_rate {text?}           汇率查询（免费 API），text 如 "USD CNY 100"（两个货币代码+可选金额），可省略（默认 USD 兑主要货币）；结果以文字回传
- crypto {text?}                  加密货币现价（免费 API），text 为币名（BTC/以太坊…），可省略（默认 BTC+ETH）；结果以文字回传
- stock {text}                    股票实时行情（免费 API），text 为带市场前缀的代码：sh600519 / sz000001 / hk00700 / usAAPL；结果以文字回传
- wiki {text}                     维基百科词条摘要（免费 API），text 为词条名；结果以文字回传
- holiday {text?}                 法定节假日（免费 API），text 为年份，可省略（默认今年）；结果以文字回传
- route {text}                    路线规划（免费 API），text 为 "起点 到 终点"，返回驾车距离与预计耗时；结果以文字回传
- navigate {text, app?}           发起导航（地图应用的免费 URI 接口），text 为目的地，app 可指定地图（高德/百度/Google），省略则自动选已装地图
- share {text, app?}              通过系统分享接口把 text 分享出去；app 为目标应用名（微信/QQ…），省略则弹系统分享面板
- wait {ms}                       等待 ms 毫秒（加载时用）
- finish                          任务完成（同时把 done 设为 true 并填 summary）

# 获取信息（重要：API 优先）
- 天气、新闻、汇率、加密货币、股票、百科、节假日、路线距离/耗时等信息按任务需求调用对应专用接口，也可调用与任务相关的 MCP 接口；文字结果加入历史供下一轮使用。
- 智能体工作期间禁止通用联网搜索，web_search 不可用。不得通过浏览器、第三方 App 或 MCP 通用网页搜索工具绕道搜索；接口失败时修正参数或改用适用接口，无法取得信息时如实说明。问答联网搜索不受影响。
- 各信息工具与 open_url 都请单独成批（本批 actions 只放这一个），因为要拿到结果/看到内容后才能继续；网页还在加载时先返回一个 wait。

# 发送、分享与导航（重要）
- 要把整理/生成好的文字发到微信、QQ、钉钉等通讯应用时，先在 thought 里组织好完整文字，然后用 share {text, app}（走系统分享接口），不要打开对方应用去逐字输入再点发送。
- share 执行后会弹出目标应用的分享/选择联系人界面，系统会重新截图：若任务指明了发送对象，从新截图中点选该联系人并确认即可完成；未指明对象则停在选择界面交给用户，并 finish 说明文字已准备好。
- 用户要「导航去某地 / 带我去 / 出发」时，直接用 navigate 一步调起地图导航（免费 URI 接口，地图应用接管后续导航），不要打开地图 App 手动搜索点按；只是询问距离/耗时而不出发时，用 route 直接拿文字结果。

# 规则
1. 批量规划：把当前画面上你确定无误的点击/输入按顺序全部放进 actions，系统连贯执行、中途不截图。不要每次只给一个动作。
2. 不臆测未出现的画面：某动作会跳转、后续要看新画面才能决定时，就停在此处只返回已确定的动作；系统执行后会重新截图再问你。
3. 每个坐标都要对应你真实看到的目标；找不到就先 scroll/wait 让它出现，绝不靠猜乱点。
4. 画面在加载或看不清时，只返回一个 wait。
5. 善用【总体计划】【上一步思考】【已执行】保持连贯：同一动作反复失败、或执行后画面没变化时，立刻换思路（滚动找目标、换入口、back 返回上级），不要重复无效动作陷入死循环。
6. 遇到权限/更新/引导/广告等弹窗：有助于或不妨碍任务就点「允许/同意/继续/知道了」，否则关闭它，别被卡住。
7. 付款、转账、发消息、删除、改密码等高风险动作照常放进 actions，系统会在执行前请用户确认。
8. 你不能自行暂停或交还控制权，请持续推进直到完成；必须用户操作（登录、验证码）时，用户会自行点「暂停」接管。
9. finish 前先确认目标已在屏幕上达成（出现结果页/成功提示）；没达成就继续。done=true 时把 summary 写清楚（也可在 actions 末尾追加一个 finish）。
10. 任务指令可能来自语音转写或含笔误（同音字/识别误差）：按最合理的真实意图理解后执行，别因个别错字卡住或反复确认。
11. 第一性原则复查：每完成任务中的一项要求后，抛开之前的假设，以第一性原则重新读取当前屏幕与任务指令原文，从头逐项核对各项要求的实际完成情况；发现遗漏、偏差或更好的做法，立即修改计划再继续执行，直到所有要求都真正完成。
```

每一步实际发给模型的 user 消息还包含：任务指令、当前步数/总步数、总体计划（首步制定）、当前前台应用包名、已安装应用列表（最多 60 个，open_app 的可选目标）、最近执行历史（最多 10 条）、上一步思考（截断 220 字）、界面控件列表（最多 40 个，含中心坐标/是否可点击/可输入/可滚动，坐标经缩放换算到截图同一坐标系）、OCR 文本（最多 800 字），以及一张截图。双模型（拼凑）模式下不发原始截图，改为发视觉模型产出的「画面描述」（截断 2400 字）。

**高风险拦截（RiskGuard）**：对非纯观察类动作（截图/等待/返回/回桌面/最近任务/滚动/滑动/内置浏览器与天气·新闻·汇率·币价·股票·百科·节假日·路线等只读信息动作/navigate（调起地图可逆、无提交）/share（只弹选择界面、自身不发送，其 text 载荷不作为证据以免误报）/finish 之外的动作）做两级关键词判定，**以屏幕证据为主、模型自述只能加码**：

- **证据**（设备侧事实，模型无法隐瞒）：被点控件的文字标签（含点内最小控件，取不到时回退到 ~48dp 内最近的带文字控件——裸图标按钮通常紧邻其文字说明）、input_text 输入的内容、open_app 的目标应用名。
- **模型自述**（thought/summary，自报信息）：只允许触发下述"不可逆类"关键词，绝不能作为放行依据。
- **不可逆类**（证据或自述命中任一即确认）：付款、支付、确认支付、转账、汇款、打款、红包、提现、充值、购买、下单、提交订单、立即购买、确认收货、借款、贷款、还款；删除、卸载、格式化、清除数据、清空、注销、解绑、退出登录、修改密码、重置密码、实名认证、授权登录；pay / payment / transfer / checkout / purchase / delete / uninstall / format / logout / reset password / change password。
- **泛化动词**（仅证据命中才确认，避免模型叙述里的"确认/提交"造成确认疲劳）：发送、发布、转发、提交、确认；send / post / submit / confirm / buy / order。
- 英文关键词按**单词边界**匹配（"send" 不会命中 "sender"），中文按子串匹配。

命中的动作会强制弹出二次确认，需要用户手动点击才会真正执行。已知局限：完全无文字、无邻近说明的自绘控件仍可能漏判——这是关键词方案的边界，重写时若有更强的语义信号（如支付类前台包名白名单）可叠加。

**用户优先原则**：AI 不会自行暂停，会持续推进直到完成；只有用户主动点"暂停"或"接管"才能拿回控制权，恢复后 AI 会重新截图观察再继续。

**悬浮控制中心**：Material You / Gemini 风格的可拖动、可折叠悬浮球/面板，可查看当前计划、暂停/继续/接管/结束任务（对应源码 `FloatingControlService.kt`）。

## 四、模型接入架构（开放 Provider）
- 不写死任何模型，用户自行新增 Provider：名称 + Base URL + API Key
- 兼容任意 OpenAI 风格 API：GPT / Claude / Gemini / DeepSeek / Qwen / Kimi / GLM / MiniMax / OpenRouter / Ollama / LM Studio / vLLM 等
- 通过 `GET /models` 自动发现该 Provider 下可用模型
- 智能体主模型与问答模型可以分开配置，也可共用同一个
- 智能体模式依赖视觉理解屏幕，推荐用具备视觉能力的模型；纯文本模型则主要依赖 OCR 与控件文本工作

## 五、技术栈
Kotlin · Jetpack Compose · Material 3 / Material You · MVVM 架构 · Hilt（依赖注入）· Room（本地数据库）· Kotlin Coroutines · Retrofit + kotlinx.serialization（网络层）· OkHttp · AccessibilityService（读取/操作界面控件树）· MediaProjection（真实像素截图）· ML Kit OCR · Vosk（离线本地语音识别）· Android TextToSpeech（朗读） · DataStore（设置持久化）。minSdk 26，targetSdk/compileSdk 35。

## 六、工程结构（Android，包名 `com.cone.agent`）
```
core/             常量、通知渠道、多语言 LocaleHelper
data/
  crypto/         KeystoreManager（Android Keystore AES-256-GCM 密钥加密）
  local/          Room 数据库、DAO、实体
  remote/         OpenAI 兼容 API 客户端（LlmApi / LlmClient / DTO）· WebSearchClient（联网搜索）
  repository/     Provider / Chat / Settings 仓库
domain/model/     Provider / ModelInfo / 会话与任务模型
agent/            AgentController（循环+状态机）· AgentEngine（观察/思考）·
                  ActionExecutor（真正执行点击等操作）· RiskGuard（风险检测）·
                  ChatResponder（问答模式）· PromptBuilder（提示词构建）· action/（动作类型定义）
vision/           AccessibilityService · ScreenCaptureManager/Service · OcrEngine · UI 树解析
assistant/        语音助手：AssistantActivity（原全屏入口）· AssistantPillView（原胶囊视图）·
                  VoskSpeechEngine / SpeechController（本地语音识别）· TtsSpeaker（朗读）·
                  ConeVoiceInteractionService/Session（作为系统默认助手的入口）· SnipOverlayView（圈选截图提问）
service/          AgentForegroundService · FloatingControlService（悬浮控制中心）· WakeWordService（语音唤醒常驻服务）
di/               Hilt 模块（Database / Network）
ui/               theme · navigation · components · screens（chat / providers / settings / permissions）
```

## 七、安全与隐私
- API Key 用 Android Keystore（AES-256-GCM）加密存储，绝不明文落盘，备份规则已排除密钥数据库
- 语音识别优先系统识别（更准，主界面与语音助手一致），无可用系统识别时回退到本地离线 Vosk；联网搜索仅在用户主动开启时才发起网络请求
- 屏幕捕获、无障碍服务、悬浮窗、麦克风均为系统级敏感权限，仅用于代替用户在本机执行其主动下达的任务
- 高风险操作默认强制二次确认（可在设置中关闭，但不建议）

## 八、首次使用引导流程
应用内"权限与环境"页一键引导，依次完成：① 在"模型与设置"选一个模型（需先在"Provider 管理"新增 Provider 并"自动发现模型"）② 开启无障碍服务（智能体的"手"）③ 允许悬浮窗权限 ④ 授权屏幕捕获（智能体的"眼"）⑤ 授予通知权限 ⑥ 授予麦克风权限 ⑦ 设为系统默认语音助手（可选）⑧ 开启语音唤醒（可选）。完成必需项后即可在主页输入指令发起任务。

## 九、多语言
界面支持中文 / English / 日本語。

---

## 十、桌面遥控（ConeCode Remote，源码 `remote/`）

手机作为 **ConeCode 桌面端**的遥控镜像客户端。桌面端是唯一事实源；手机只渲染快照、回传意图，不含任何 Agent 逻辑。

**配对与连接**：扫描桌面端二维码或手动输入配对 URL（`http(s)://host:port/?t=<令牌>`，可附 `&p=<密码>`）。`GET /events`（SSE）拉取实时状态快照，`POST /cmd` 回传命令；401 → 弹密码框，403 → 被桌面端踢出。

**命令集**（与桌面 bridge `handleCommand` 一一对应）：send / stop / switchConversation / newConversation / deleteConversation / setModel / setReasoningEffort / approve / reject / approveAll / rejectAll；请求-应答类：listDir / readFile / writeFile / uploadFile / exec（一次性终端命令）/ openFolder。**文件读写与 exec 都在桌面机器上执行**，能力边界即桌面端进程的权限——这是本应用中影响面最大的通道，重写时不得省略下述安全设计。

**安全设计（威胁模型）**：
- 传输：HTTPS。局域网直连使用**随包内置的自签证书**（`res/raw/conecode_cert.pem`）做钉扎：仅当对端出示的正是这张证书时才放宽主机名校验；隧道域名（cloudflared/ngrok）走系统 CA 正常校验。注意内置证书是**全体安装共享**的，钉扎只保证传输加密与"是某个 ConeCode 桌面端"，不构成对端身份鉴别——身份仍靠令牌+密码。
- 凭据：令牌走 URL 查询参数（SSE 场景的折衷，会出现在对端日志里）；密码走 `x-remote-pass` 头。**明文 DataStore 只保存去掉密码参数的配对 URL**（供一键重连）；完整"URL+密码"以 Android Keystore（AES-256-GCM）密文保存，UI 上由生物识别/设备凭据解锁。
- 二维码等同凭据：拍到二维码即可连接该桌面端，只应在可信环境展示。
- 全局约束：应用的网络层拦截器（CleartextCredentialGuard）禁止任何凭据头（Authorization / x-api-key / x-remote-pass）通过明文 http 发往公网地址。

---

## 十一、【本次改造】语音助手 → 灵动岛风格

### 现状（改造前基线，源码 `AssistantPillView.kt`）
- **不是**独立悬浮窗口，而是**全屏 Activity/VoiceInteractionSession 的内容视图**：一个铺满全屏的半透明遮罩（scrim）+ 底部固定锚定的白色圆角胶囊（`bottomStack`，`Gravity.BOTTOM`，左右各留 14dp 边距，几乎撑满屏宽）
- 胶囊位置**固定**在底部，用户不能拖动、不能调整位置或大小，只能"点遮罩关闭"或"上滑手柄展开进主 App"
- 顶部有"问答/智能体"分段切换，中间是波形/文字转写/输入框，右侧圆形麦克风按钮
- 答案以另一张单独的圆角卡片（`answerCard`）弹出在胶囊上方，最高不超过屏幕 42% 高度
- 依托 `AccessibilityService`/`VoiceInteractionSession` 触发展示，退出即销毁视图

### 改造目标：灵动岛（Dynamic Island）风格 + Siri 式交互

**核心变化：从"全屏 Activity 内容" → "常驻可悬浮窗口"**
- 底层实现从当前的 VoiceInteractionSession 全屏视图，改为真正的**系统悬浮窗**（`WindowManager` + `TYPE_APPLICATION_OVERLAY`，可复用现有 `FloatingControlService` 悬浮控制中心的权限申请与生命周期管理经验）
- 去掉全屏半透明遮罩（scrim）；灵动岛本身默认只占屏幕一小块区域，不遮挡其余界面，用户可以在灵动岛之外正常操作手机

**形态与交互**
1. **收起态（Compact）**：一个小胶囊/圆角矩形，常驻在用户设定的位置（默认贴近刘海/状态栏区域，可移动到屏幕任意边缘），显示最小状态（麦克风图标、波形动画、或加载中的呼吸光效）
2. **展开态（Expanded）**：点击/语音唤醒后，胶囊原地"长大"展开为对话面板（波形/转写/输入框/答案卡片），动画是从小胶囊形变到大面板，而不是从屏幕底部滑入
3. **用户可自定义**：
   - **位置**：长按拖动灵动岛到屏幕任意位置（参考现有 `FloatingControlService` 的拖拽逻辑），松手后可选吸附最近边缘（沿用现有悬浮控制中心的贴边收起逻辑），或自由悬浮不吸附——由设置项决定
   - **大小**：提供缩放手势或设置页滑杆，调整收起态胶囊的宽高比例（小/中/大三档，或连续缩放）；展开态面板大小可随比例联动或单独设上限
   - **持久化**：位置、大小保存到 DataStore（沿用现有 `Settings` 仓库/`DataStore` 机制），下次启动/服务重启后恢复
4. **像 Siri 一样的呈现细节**：
   - 语音输入时收起态显示柔和的呼吸光晕/彩色渐变波形（换绘制风格，可复用 `WaveformView` 现有数据源）
   - 展开态回答内容复用现有 `answerCard` 的渲染能力，无需重做——答案区已是嵌入 `ComposeView` 的共享 Compose `MarkdownText` 渲染器（与主界面完全同款：标题/加粗/列表/代码块/表格/链接/图片查看器/LaTeX→Unicode 数学归一化，外加 View 版参考来源卡片）
   - 保留三条杠附件菜单（相机/图片/文件/截图）、执行/问答分段切换、静音朗读、复制等现有功能，只是外壳容器从"全屏 Activity"换成"悬浮窗+灵动岛动画"

**与现有系统的衔接点**
- 触发方式不变：仍通过长按电源/Home（系统默认助手手势）或语音唤醒词"松果/Hey pine cone"触发；触发后不再启动新 Activity 抢占全屏，而是让常驻悬浮窗从 Compact 态展开为 Expanded 态
- 需考虑与 `WakeWordService`（语音唤醒后台服务）、`FloatingControlService`（智能体悬浮控制中心）三者的共存与层级关系，避免同时出现两个悬浮球互相遮挡；智能体运行时灵动岛可显示任务态（如"正在执行中"），点开即可看到暂停/接管入口
- 权限仍需悬浮窗权限（`SYSTEM_ALERT_WINDOW`），复用现有"权限与环境"引导页授权流程，文案改为"灵动岛显示"

**需要新增/修改的关键文件**
- 新增 `DynamicIslandService`（对标现有 `FloatingControlService` 结构）：用 WindowManager 管理悬浮窗，处理拖拽定位、吸边、缩放手势、状态持久化
- 改造 `AssistantPillView.kt`：拆分成 Compact/Expanded 两套视图状态，加入形变动画（`MotionLayout` 或手写 `ValueAnimator` 做 bounds/corner-radius 插值），去掉 `SCRIM` 背景与"点外部关闭"逻辑
- `AssistantActivity.kt` / `ConeVoiceInteractionSession.kt`：改为只负责语音识别/推理调度，不再持有全屏窗口，转发展示指令给 `DynamicIslandService`
- 设置页新增"灵动岛"分组：位置重置、大小滑杆、吸边开关

---

## 十二、给其他 AI 工具的使用建议

把本文档整体作为背景资料注入对方上下文（尤其是"系统提示词原文"和"RiskGuard 两级判定"两段，是行为规范的唯一权威来源，重写时应逐字保留或谨慎改写），即可让另一个 AI 工具（无论用于讨论方案还是直接生成代码）建立起与本项目一致的理解，而无需访问原始代码库。语音助手部分请严格按第十一章的灵动岛改造目标实现，不要照搬第十一章"现状"中描述的旧版全屏胶囊设计。桌面遥控部分（第十章）的安全设计为强制要求。

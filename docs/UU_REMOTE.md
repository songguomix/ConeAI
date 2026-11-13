# UU 远程屏幕接入

协议来源：[网易 UU 远程官方接入指南](https://github.com/netease-uuyc/uuremote-integration-guide)。本接入使用官方 URL Scheme，在 Android 上打开 UU 远程客户端，不在 ConeAI 中内嵌视频 SDK。

## 使用

1. Android 安装 UU 远程 **4.42.0 或更新版本**；Windows / macOS 电脑也需安装 UU 远程。手机和电脑登录同一 UU 账号，电脑保持在线。
2. 在 ConeAI 的远程控制中连接 ConeCode，点击标题栏的「远程屏幕」。
3. 点击「绑定此电脑」，在 UU 远程选择当前运行 ConeCode 的那台电脑。
4. 返回后选择「全屏远控」或「画中画远控」。全屏切换到 UU；画中画需设备和系统支持。原生 Chat、Work、RSI、文件和终端继续使用 ConeCode 会话连接。

「清除此电脑绑定」仅清除 ConeAI 保存的绑定，不会从 UU 账号中删除设备。「结束等待」停止 ConeAI 等待回调，不会强制断开 UU 已建立的会话。

## 协议与状态

- 绑定：`uuremote://external/device/authorize`，使用 URL 编码的 `callback` 和随机 `nonce`。
- 控制：`uuremote://external/device/control`，原样 URL 编码 `anonymous_device_id`。全屏 `window_type=0`，不传回调、不等待连接结果；画中画 `window_type=1`，通过回调中的 `status` / `reason` 更新状态。
- 回调：`coneai-uu://bind` 与 `coneai-uu://control`。绑定校验 UU 返回的 nonce；画中画的 request ID 放在 callback 原始查询参数中，由 UU 保留回传。
- 不设置会提前截断 UU 登录或系统确认流程的短超时。请求最多保留 24 小时，可手动结束等待或重新发起。
- 仅匹配已持久化请求的一次性 nonce 才能更新绑定。重复参数、错误 host、过期和重放回调均拒绝。`device_not_found` 只清除对应请求仍在使用的绑定。

## 保存与设备隔离

`anonymous_device_id` 是 UU 返回的加密设备标识，ConeAI 不解密或显示它。标识和待处理请求使用 Android Keystore 加密保存，排除云备份与设备迁移。

LAN 配对使用已校验的证书指纹建立电脑身份，同一证书的电脑更换 LAN 地址后可复用绑定。未固定指纹的隧道或旧版链接按连接来源与令牌隔离，需要分别绑定；令牌变化后可能需要重新绑定。不会仅凭服务端自行声明的电脑标识复用本地 UU 设备，以避免另一台服务器冒充已有绑定。

## 验证与边界

- `UuRemoteProtocolTest` 验证 URL 编码、全屏/PiP 差异、版本门槛、错误码、nonce、回放和畸形回调。
- `UuRemoteConnectorTest` 在 Android 模拟器使用真实 Keystore 和独立 SharedPreferences，验证绑定持久化、电脑隔离、回调重放防护和设备失效处理。测试使用合成设备标识，不代表已登录真实 UU 账号。
- 模拟器 UI 验证包括安装检测、真实 UU 客户端唤起和返回。最终电脑画面与实际画中画连接仍取决于用户的 UU 登录、设备选择和系统支持。
- 当前 AVD 的折叠内外屏验证是窗口尺寸模拟；它只报告 DEFAULT 姿态，不代表硬件铰链测试。

旧的 Electron 截图轮询、鼠标输入接口和 Android 位图遥控页面已移除。UU 的流媒体、远控权限与连接由 UU 客户端处理。

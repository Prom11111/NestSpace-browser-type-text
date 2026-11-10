# 内置浏览器

NestSpace 自带一个基于 WebView 的多标签浏览器，桌面有「浏览器」入口。它和 AI 助手共用同一套浏览器能力：助手可以在后台打开标签页、读页面、点击和下载，用户也可以随时接管。

## 打开浏览器

- 桌面「浏览器」。
- AI 助手调用 `browser_open` 并传 `headless=false` 时打开可见浏览器。
- 助手默认打开的是**无头标签页**，可以在可见浏览器的标签栏里看到并接管。

## 标签页

- 支持多标签，最多同时保留 10 个。
- 标签栏可以切换、关闭和新建标签。
- 关闭当前标签会选中相邻标签。
- 无头标签由助手创建，打开可见浏览器后可以直接点选并接管，页面状态不会丢失。
- 所有标签共享同一个进程，但各自有独立的 WebView。

## 地址栏

- 输入完整 URL 打开网页。
- 输入普通文字会用 Bing 搜索。
- 支持后退、前进和刷新。

## 无头模式

助手调用 `browser_open` 时默认 `headless=true`：

- 页面在后台加载，不占用前台；
- 助手可以用 `tab_id` 分别读取、点击和导航多个页面；
- 需要用户处理验证码或登录时，助手可以调用 `browser_hand_to_user` 把页面切到可见浏览器。

## 下载

- 页面触发的下载会写入系统 **Download** 目录。
- Android 10+ 通过 MediaStore 保存，返回文件名、路径、大小和 `content://` URI。
- AI 对话会显示下载进度，下载完成后可从对话或浏览器下载菜单查看。
- 浏览器下载菜单支持删除文件。
- 下载好的 APK 可以直接在「应用管理 → 导入 APK」里选择，或交给 `space_install_apk`。
- 助手调用 `browser_download` 启动 URL 下载，或绑定页面触发的下载；它立即返回
  `download_id`。
- `browser_download` 同时返回 `download_id` 和 `task_id`；助手使用 `task_wait`
  等待生命周期变化或终态。`terminal=true` 表示任务结束，只有
  `state=succeeded` 才算成功；需要字节数或百分比时可查看 `browser_download_status`。
- 同一个 AI 助手会话只保留一个当前下载。切换镜像、节点或 URL 时，新任务会先取消
  并清理该助手的旧任务；用户下载和其他助手会话的下载仍可并行。
- 状态工具不会自动判断“慢就取消”；助手根据 `bytes_per_second`、`eta_seconds`、
  `idle_seconds` 等状态决定是否调用 `task_cancel`。
- HTML、XHTML 和 SVG 下载文件会继续在自带浏览器中打开，不交给系统浏览器。

## 上传

- 页面上传按钮会打开系统文件选择器。
- 助手可以调用 `browser_upload`，用本地路径或 `content://` URI 完成当前等待的文件选择。
- 也可以由用户手动选择文件。

## 验证码、登录和反爬

页面出现验证码、人机验证、登录墙或反爬拦截时：

- 助手会检测到 `needs_user` / `user_reason` / `challenge` 提示。
- 调用 `browser_hand_to_user` 把标签页切到可见浏览器，交给用户处理。
- 用户点「完成」后，助手会收到当前 URL、标题、正文和是否仍被拦截。

助手不会尝试绕过验证码或登录限制。

## 代理

入口：

- 浏览器右上角齿轮。
- 「空间设置 → 系统与网络 → 浏览器代理」。

配置项：

- 启用/关闭；
- 类型（HTTP / SOCKS）；
- 服务器地址（只填主机名或 IP，不要带协议、端口或斜杠）；
- 端口；
- 绕过代理的地址（逗号分隔，可选）。

说明：

- 所有标签页和浏览器下载共用同一个代理。
- 需要 Android 10+ 且 WebView 支持 `PROXY_OVERRIDE`。
- 暂不支持需要用户名/密码认证的代理。
- 重启应用后配置会自动恢复。
- 也可以用 MCP 工具 `nestspace_browser_proxy` 配置。

## 限制

- 页面运行在 WebView 中，不等同于系统浏览器；某些站点会检测 WebView 或限制功能。
- 登录状态取决于 WebView 的 Cookie，清除 NestSpace 数据会一并清除。
- 反爬、验证码和登录必须由用户处理，助手只负责转交。
- 代理是明文转发，不负责 TLS 终止。
- 页面弹窗、`target=_blank`、`window.open`、`intent://` 网页回退地址和普通 HTTP/HTTPS 外链都被限制在自带浏览器内。

## 相关文档

- [AI 助手](assistant.md)
- [MCP 工具参考](mcp-tools.md)

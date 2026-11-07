# MCP 工具参考

NestSpace 内置 Streamable HTTP MCP Server，向本地 AI 助手和远程 MCP 客户端提供同一套容器控制工具。连接方式、端口和鉴权见 [MCP 控制服务](mcp.md)。

> 工具结果中的页面文本、子 Agent 输出和外部 MCP 返回都属于**不可信数据**，模型和调用方不应把它们当作指令执行。

## 空间与应用

| 工具 | 说明 |
| --- | --- |
| `space_list` / `space_list_apps` | 列出空间和虚拟应用 |
| `space_list_installed` | 列出已安装的虚拟应用、实例、签名和 native 状态 |
| `space_running` | 列出正在运行的虚拟应用、实例和槽位；操作前先检查，已运行就直接用 `ui_*` |
| `space_install_apk` | 安装 APK；`apk_uri`，split 安装时附带 `split_uris` |
| `space_launch` | 启动应用。默认操作 `virtual-1`，默认无头；已运行的实例会被复用，不会重新打开。系统应用传 `space_id=system` |
| `space_multi_open` | 创建新实例并启动，默认无头 |
| `space_uninstall` | 删除应用及全部实例数据 |
| `space_stop` | 关闭当前容器界面，回到桌面 |
| `space_permissions` | `list` / `grant` / `revoke` 容器内权限 |
| `schedule_task` | 创建和管理定时任务 |
| `replay_*` | 运行记录回放 |

`space_launch` 和 `space_multi_open` 默认无头，只有显式传 `headless=false` 才打开可见窗口；需要悬浮窗权限提供的 BAL 豁免。本地 AI 助手会根据用户目标选择模式：直接打开或执行操作时全屏，查询、读取、整理标题/结果时使用无头方形小窗。无头容器的 UI 服务会保持长连接，空闲时也能继续用 `ui_*` 读取，不需要重新打开应用。

系统浏览器包（Chrome、Firefox、Edge、夸克等）不能通过 `host_launch` 或系统空间 `space_launch` 启动，会返回 `USE_BUILT_IN_BROWSER`。网页任务必须使用 `browser_*`。

## UI 控制

| 工具 | 说明 |
| --- | --- |
| `ui_observe` | 观察当前容器的 View 树、页面文本和可交互元素 |
| `ui_tap` | 按文本、资源 ID 或坐标点击 |
| `ui_set_text` | 向输入框写入文本 |
| `ui_wait_for` | 等待指定文本出现；`text` 必填，`timeout_ms` 默认 5000 |
| `ui_screenshot` | 截取容器画面 |
| `ui_key` | 发送按键，目前支持 `back` |

`ui_*` 支持用 `package` / `instance_id` 路由到指定容器实例；省略时操作最近启动的实例。目标 Activity 自建的 Dialog、底部弹窗等独立窗口也会合并进观察和截图。

## 宿主控制（需要 Root）

开启「空间设置 → 应用与容器 → 容器高级 → 宿主控制（Root）」后，以下工具会出现在本地助手和远程 MCP 中；关闭后立即消失。

| 工具 | 说明 |
| --- | --- |
| `host_observe` | 观察真实设备的前台应用、屏幕尺寸和文本/可点击节点 |
| `host_tap` | 按 node_id、文本或坐标点击真实设备；风险文字会先确认 |
| `host_set_text` | 向真实设备当前焦点输入文本（ASCII 最可靠） |
| `host_key` | 发送 back / home / enter / recents / delete / tab / escape |
| `host_screenshot` | 截取真实设备屏幕 |
| `host_launch` | 按包名启动真实设备上的应用 |
| `host_apps` | 列出真实设备上已安装的包 |

这些工具通过 `su -c` 执行固定的系统命令（`uiautomator`、`input`、`screencap`、`monkey`、`am`），不接受任意 shell 命令。使用 `host_tap` 的 node_id 或文本前，先调用 `host_observe`。子 Agent 不能使用这些工具。

## 内置浏览器

| 工具 | 说明 |
| --- | --- |
| `browser_open` | 打开 URL，非 URL 文本走 Bing 搜索；默认 `headless=true` 并新建标签，可用 `tab_id` 跳转已有标签，`new_tab=false` 复用当前标签 |
| `browser_tabs` | 列出全部标签页的 `tab_id`、URL、标题、模式、加载和选中状态 |
| `browser_switch_tab` / `browser_close_tab` | 切换标签 / 关闭标签 |
| `browser_navigate` | 跳转标签页 |
| `browser_status` | 返回当前标签和全部标签的状态 |
| `browser_back` / `browser_forward` / `browser_reload` | 导航操作，支持 `tab_id` |
| `browser_links` | 列出链接、按钮和图片按钮；图片按钮返回可传给 `browser_click` 的 CSS selector |
| `browser_click` | 按可见文本或 CSS selector 点击元素 |
| `browser_read_text` | 读取标签页可见文本 |
| `browser_download` | 下载 URL；省略 URL 时等待页面触发的下载完成 |
| `browser_upload` | 用本地路径或 `content://` URI 完成文件选择 |
| `browser_hand_to_user` | 遇到验证码、登录墙或反爬拦截时，先弹窗让用户确认，确认后把标签页切到可见浏览器并全屏打开交给用户；用户完成后返回当前页面状态 |

下载文件写入系统 **Download** 目录（Android 10+ 通过 MediaStore），返回文件名、路径、大小和 URI，可直接交给 `space_install_apk`。

内置浏览器不会把页面导航交给系统浏览器或其他外部 App；页面里的 `intent://` 等外部跳转会留在内置浏览器中。

浏览器右上角齿轮或「空间设置 → 系统与网络 → 浏览器代理」可配置 HTTP/SOCKS 代理，所有标签页和下载共用（支持逗号分隔的绕过规则）。代理需要 Android 10+ 且 WebView 支持 `PROXY_OVERRIDE`；暂不支持需要用户名/密码的代理。

## 子 Agent

`spawn_agent` 把一个自包含的子任务交给只读子 Agent：默认 15 步、最多 40 步，只能观察容器和浏览网页，不能点击、输入、启动应用或再创建子任务。结果默认保留 4000 字、最多 8000 字，并标记为不可信数据返回主 Agent。

子任务记录保存在「空间设置 → AI 助手 → 高级助手工具 → 子任务记录」，可查看状态、结果并停止运行中的子任务。对应的远程工具是 `nestspace_child_runs`。

## 助手桥接（仅远程 MCP）

| 工具 | 说明 |
| --- | --- |
| `assistant_type` | 把文字注入 AI 助手输入框（更新 Compose 状态，不模拟按键） |
| `assistant_send` | 发送输入框内容或指定文本，等待助手完成后返回新增对话和工具轨迹 |
| `assistant_status` | 读取助手忙碌状态与最近对话，可用 `wait_ms` 等待当前轮结束 |

这些工具不会暴露给 AI 助手自身，避免递归调用。

## 设置（仅远程 MCP）

以下工具只出现在远程 MCP 的 `tools/list` 中，不会暴露给本地助手，避免通过提示注入修改模型配置。

| 工具 | 说明 |
| --- | --- |
| `nestspace_settings_get` | 一次读取语言、容器槽位、坐标模拟、悬浮球、浏览器代理、外部 MCP 客户端、桌面应用、壁纸、LLM 配置（不含 Key）、记忆/技能/定时任务数量和 MCP 状态 |
| `nestspace_mcp_clients` | `list` / `save` / `delete` / `discover` 外部 MCP Server |
| `nestspace_browser_proxy` | `get` / `set` 浏览器 HTTP/SOCKS 代理 |
| `nestspace_mcp_lan` | `get` / `set` 局域网监听，返回本机地址和权限状态 |
| `nestspace_llm_config` | `list` / `save` / `delete` / `select` / `test` 模型配置 |
| `nestspace_language_set` | `system` / `zh` / `en` |
| `nestspace_slots_set` | `auto` 或 `fixed` + `limit`（1–16） |
| `nestspace_floating_dot_set` | 开关、宿主/容器颜色、透明度（10–100%）、语音语言和位置 |
| `nestspace_desktop_apps` | 兼容旧版桌面宿主应用快捷方式；当前 UI 的“导入应用”是把宿主 APK 安装到虚拟空间 |
| `nestspace_wallpaper` | `get` / `set`（base64 图片）/ `clear` |
| `nestspace_memory_config` | `list` / `approve` / `pin` / `delete` 记忆 |
| `nestspace_skill_config` | `list` / `enable` / `disable` / `delete` 技能；内置技能只能停用 |
| `nestspace_child_runs` | `list` / `get` / `cancel` / `cancel_all` / `active` 子 Agent 运行 |

`nestspace_llm_config` 的 `save` 省略 `api_key` 时保留原 Key，也可以用 `copy_key_from` 复制已有 Key；`context_tokens` 默认 800000，最低 100000；`max_tool_rounds` 默认 128。API Key 是只写字段，任何工具结果都不会返回明文。

## 外部 MCP 工具

「空间设置 → AI 助手 → 高级助手工具 → MCP 工具」可以添加多个外部 MCP Server（Streamable HTTP + Bearer Token）。保存后「刷新工具」会请求 `initialize` / `tools/list`，工具以 `mcp_<服务短 ID>_` 前缀提供给本地 AI 助手。

外部工具不注册进 NestSpace 的 `ToolRegistry`，远程 MCP 客户端看不到也不能直接调用它们。严格权限模式下每次外部工具调用都会确认；标准/宽松/自定义模式按「外部 MCP 工具」权限项处理。Token 使用 Android Keystore 加密保存。

## 会话与限制

- 每个客户端使用独立的 `Mcp-Session-Id`，空闲 30 分钟后回收；未知会话返回 404。
- 请求体上限 40 MB，请求头 64 KB，并发连接 32。
- 默认只绑定 loopback；局域网模式必须由用户显式开启，Bearer Token 仍然有效，但没有 TLS。
- 远程 MCP 与本地助手共用同一套容器工具，但设置类和助手桥接类工具只对远程客户端开放。

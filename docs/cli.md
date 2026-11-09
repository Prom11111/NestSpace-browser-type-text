# nestspace-mcp CLI

`tools/nestspace-mcp` 是一个只用 Python 标准库实现的 MCP 客户端，支持 Streamable HTTP、一次性工具调用和交互式 shell。不需要安装第三方 Python 包。

`tools` 返回的列表还包含已加载插件贡献的 `plugin_*` 动态工具。插件安装、启停或删除后
重新执行 `tools` 即可刷新当前可见列表。

## 准备连接

先在「空间设置 → MCP 控制服务」启动服务并复制 Token。也可以在
「空间设置 → AI 助手 → 高级助手工具 → MCP 控制服务」打开同一页面。

USB（推荐）：

```bash
adb forward tcp:8765 tcp:8765
export NESTSPACE_MCP_URL=http://127.0.0.1:8765/mcp
export NESTSPACE_MCP_TOKEN='<token>'
```

局域网（在设置里显式开启；HTTP + Token，无 TLS，仅可信网络）：

```bash
export NESTSPACE_MCP_URL=http://<手机局域网 IP>:8765/mcp
export NESTSPACE_MCP_TOKEN='<token>'
```

Token 也可以从文件读取：

```bash
tools/nestspace-mcp --token-file /path/to/token health
```

CLI 不会把 Token 打印出来。

## 常用命令

```bash
tools/nestspace-mcp health
tools/nestspace-mcp tools
tools/nestspace-mcp call browser_tabs
tools/nestspace-mcp call browser_open '{"url":"https://example.com"}'
tools/nestspace-mcp call browser_open --arg url=https://example.com --arg headless=false
tools/nestspace-mcp call browser_click --arg text=More --json
tools/nestspace-mcp shell
```

### `--arg`

用 `--arg key=value` 传参数，值会自动识别：

- `true` / `false` / `null`；
- 整数和浮点数；
- JSON 数组和对象。

如果某个值必须保持字符串，用位置参数传一个 JSON 对象：

```bash
tools/nestspace-mcp call ui_set_text '{"text":"123","package":"com.example.app"}'
```

### `--json`

打印原始 MCP 结果，包括图片数据。不加 `--json` 时：

- 文本内容直接打印；
- 图片块只显示摘要。

### `shell`

进入交互模式后可以连续调用工具。输入 `disconnect` 会发送 `DELETE /mcp` 关闭当前会话。

### `setup`

打印可复制的环境变量和 `claude mcp add` 命令，不会暴露 Token。

## 会话

- 客户端会保存服务端返回的 `Mcp-Session-Id`。
- 服务重启（例如切换局域网模式）后，旧会话返回 404，CLI 会自动重新初始化并重试一次。
- 一次 CLI 进程对应一个会话；`shell` 模式会一直复用同一个会话。

## 安全

- 默认连接 `127.0.0.1`。
- 局域网模式监听 `0.0.0.0`，所有工具调用都需要 Bearer Token。
- 流量是明文 HTTP，只应在可信 Wi-Fi / 局域网使用。
- 用完局域网模式后，在「空间设置 → MCP 控制服务」里关闭。

## 示例：安装并启动一个 APK

```bash
# 下载 APK 到手机下载目录后，用 MCP 安装
tools/nestspace-mcp call space_install_apk \
  --arg apk_uri=content://com.android.providers.downloads.documents/document/1234

# 查看已安装的虚拟应用
tools/nestspace-mcp call space_list_installed

# 启动
tools/nestspace-mcp call space_launch --arg package_name=com.example.app

# 观察界面
tools/nestspace-mcp call ui_observe
```

具体参数以 `tools/nestspace-mcp tools` 和 [MCP 工具参考](mcp-tools.md) 为准。

## 相关文档

- [MCP 控制服务](mcp.md)
- [MCP 工具参考](mcp-tools.md)

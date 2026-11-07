# MCP 控制服务

NestSpace 内置一个 MCP（Model Context Protocol）Server，把容器控制能力暴露给电脑上的 MCP 客户端，例如 Claude Code。手机日常使用完全不需要它；这是给高级用户和自动化场景准备的可选能力。

## 启动服务

入口：**空间设置 → MCP 控制服务**。

- 服务随 NestSpace 进程自动启动。
- 设备重启后由 `BOOT_COMPLETED` 自动拉起。
- 页面上可以手动启动/停止，并查看运行状态。

默认监听：

```text
http://127.0.0.1:8765/mcp
```

只绑定本机回环地址，电脑不能直接访问，需要通过 USB 端口转发或开启局域网模式。

## Token

首次启动会生成 Bearer Token。在「MCP 控制服务 → 连接信息」里可以查看和复制。

- 所有工具调用都需要 `Authorization: Bearer <token>`。
- 不要把 Token 发到公开渠道。
- 服务停止再启动后 Token 保持不变（除非清除应用数据）。

## USB 连接（推荐）

1. 手机用 USB 连接电脑，确认 `adb devices` 能看到设备。
2. 转发端口：

```bash
adb forward tcp:8765 tcp:8765
```

3. 在 Claude Code 里添加 MCP Server：

```bash
claude mcp add --transport http space http://127.0.0.1:8765/mcp \
  --header "Authorization: Bearer <token>"
```

其他 MCP 客户端只要支持 Streamable HTTP 和自定义 Header，也可以按同样方式连接。

## 局域网连接

如果不想用 USB：

1. 手机和电脑连接同一个 Wi-Fi。
2. 在「MCP 控制服务」里打开「局域网访问」。
3. Android 17+ 会请求 `ACCESS_LOCAL_NETWORK` 权限；拒绝后服务会退回只监听 `127.0.0.1`。
4. 使用页面显示的地址：

```text
http://<手机局域网 IP>:8765/mcp
```

注意：局域网模式是 **HTTP + Token，没有 TLS**。只在可信网络使用，不要在公共 Wi-Fi 或公网暴露。

## 后台启动容器

如果希望在不打开 NestSpace 界面的情况下由 Agent 启动容器，需要授予**悬浮窗权限**：

- NestSpace 使用一个 1×1 透明、不可聚焦、不可触摸的窗口获得 Android 的后台 Activity 启动（BAL）豁免。
- 未授权时 `space_launch` 返回 `LAUNCH_BLOCKED`。
- 「MCP 控制服务」页面会显示后台启动状态和授权入口。

悬浮球是独立的可见覆盖层，和这个隐藏窗口不是一回事；关闭悬浮球不影响后台启动。

## CLI

仓库自带零依赖 Python 客户端：

```bash
export NESTSPACE_MCP_URL=http://127.0.0.1:8765/mcp
export NESTSPACE_MCP_TOKEN='<token>'

tools/nestspace-mcp health
tools/nestspace-mcp tools
tools/nestspace-mcp call browser_tabs
tools/nestspace-mcp call browser_open '{"url":"https://example.com"}'
tools/nestspace-mcp shell
```

`--arg` 会自动识别 `true` / `false` / `null`、整数、浮点和 JSON；`--json` 输出原始结果。详细用法见 [nestspace-mcp CLI](cli.md)。

## 外部 MCP 工具（方向相反）

「空间设置 → AI 助手 → 高级助手工具 → MCP 工具」配置的是**外部**
MCP Server，供 NestSpace 自己的 AI 助手使用；它们和 NestSpace 对外提供的控制服务是两套独立功能：

- 支持多个 Streamable HTTP 端点；
- 使用 Bearer Token；
- 工具名以 `mcp_<服务短 ID>_` 前缀提供给本地助手；
- 不注册进 NestSpace 的 `ToolRegistry`，远程客户端看不到。

本地助手在 App 内操作虚拟应用时，默认按用户意图选择全屏或无头方形小窗。
远程 MCP 调用 `space_launch` 时仍需显式传 `headless=false` 才会显示普通窗口。

## 会话与限制

- 每个客户端使用独立的 `Mcp-Session-Id`，空闲 30 分钟后回收。
- 未知或过期会话返回 404。
- 请求体上限 40 MB，请求头上限 64 KB，并发连接 32。
- 支持 `initialize`、`tools/list`、`tools/call`、GET SSE 心跳和 DELETE 会话。
- 工具列表见 [MCP 工具参考](mcp-tools.md)。

## 常见问题

### 连接被拒绝

- 确认「MCP 控制服务」显示运行中。
- USB 模式确认执行了 `adb forward tcp:8765 tcp:8765`。
- 局域网模式确认手机和电脑在同一网络，且 Android 17+ 已授予局域网权限。
- 确认地址是 `http://.../mcp`，不要漏掉 `/mcp`。

### 返回 401

Token 不正确或没有带 `Authorization` Header。重新复制 Token，确认命令里的引号和空格正确。

### 返回 404

- 地址路径不对。
- 会话已过期（空闲 30 分钟）。重新连接即可，CLI 会自动重新初始化并重试一次。

### 能用工具但启动不了容器

`space_launch` 返回 `LAUNCH_BLOCKED` 时，授予悬浮窗权限后再试；或者保持 NestSpace 在前台。

### 局域网地址看不到

先确认「局域网访问」已打开；Android 17+ 需要授予 `ACCESS_LOCAL_NETWORK`。部分路由器的 AP 隔离会阻止手机和电脑互访。

## 安全建议

- 默认保持只监听 `127.0.0.1`。
- 局域网模式只在可信网络临时开启，用完关闭。
- 不要把 8765 端口映射到公网。
- Token 泄露后清除 NestSpace 应用数据可以重新生成。

## 相关文档

- [MCP 工具参考](mcp-tools.md)
- [nestspace-mcp CLI](cli.md)
- [架构与限制](architecture.md)

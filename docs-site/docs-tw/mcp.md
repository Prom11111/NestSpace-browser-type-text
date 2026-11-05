# MCP 控制服務

NestSpace 內建一個 MCP（Model Context Protocol）Server，把容器控制能力暴露給電腦上的 MCP 用戶端，例如 Claude Code。手機日常使用完全不需要它；這是給進階使用者和自動化情境準備的選用能力。

## 啟動服務

入口：**「空间设置」（空間設定）→「MCP 控制服务」（MCP 控制服務）**。

- 服務隨 NestSpace 處理程序自動啟動。
- 裝置重新啟動後由 `BOOT_COMPLETED` 自動拉起。
- 頁面上可以手動啟動/停止，並檢視執行狀態。

預設監聽：

```text
http://127.0.0.1:8765/mcp
```

只綁定本機回送位址，電腦不能直接存取，需要透過 USB 連接埠轉送或開啟區域網路模式。

## Token

首次啟動會產生 Bearer Token。在「MCP 控制服務」→「连接信息」（連線資訊）裡可以檢視和複製。

- 所有工具呼叫都需要 `Authorization: Bearer <token>`。
- 不要把 Token 傳到公開管道。
- 服務停止再啟動後 Token 保持不變（除非清除應用程式資料）。

## USB 連線（建議）

1. 手機用 USB 連接電腦，確認 `adb devices` 能看到裝置。
2. 轉送連接埠：

```bash
adb forward tcp:8765 tcp:8765
```

3. 在 Claude Code 裡新增 MCP Server：

```bash
claude mcp add --transport http space http://127.0.0.1:8765/mcp \
  --header "Authorization: Bearer <token>"
```

其他 MCP 用戶端只要支援 Streamable HTTP 和自訂 Header，也可以按同樣方式連線。

## 區域網路連線

如果不想用 USB：

1. 手機和電腦連接同一個 Wi-Fi。
2. 在「MCP 控制服務」裡開啟「局域网访问」（區域網路存取）。
3. Android 17+ 會請求 `ACCESS_LOCAL_NETWORK` 權限；拒絕後服務會退回只監聽 `127.0.0.1`。
4. 使用頁面顯示的位址：

```text
http://<手机局域网 IP>:8765/mcp
```

注意：區域網路模式是 **HTTP + Token，沒有 TLS**。只在可信網路使用，不要在公共 Wi-Fi 或公網暴露。

## 背景啟動容器

如果希望在沒有開啟 NestSpace 介面的情況下由 Agent 啟動容器，需要授予**懸浮窗權限**：

- NestSpace 使用一個 1×1 透明、不可聚焦、不可觸控的視窗取得 Android 的背景 Activity 啟動（BAL）豁免。
- 未授權時 `space_launch` 傳回 `LAUNCH_BLOCKED`。
- 「MCP 控制服務」頁面會顯示背景啟動狀態和授權入口。

懸浮球是獨立的可見覆蓋層，和這個隱藏視窗不是同一回事；關閉懸浮球不影響背景啟動。

## CLI

倉庫自帶零依賴 Python 用戶端：

```bash
export NESTSPACE_MCP_URL=http://127.0.0.1:8765/mcp
export NESTSPACE_MCP_TOKEN='<token>'

tools/nestspace-mcp health
tools/nestspace-mcp tools
tools/nestspace-mcp call browser_tabs
tools/nestspace-mcp call browser_open '{"url":"https://example.com"}'
tools/nestspace-mcp shell
```

`--arg` 會自動辨識 `true` / `false` / `null`、整數、浮點數和 JSON；`--json` 輸出原始結果。詳細用法見 [nestspace-mcp CLI](cli.md)。

## 外部 MCP 工具（方向相反）

「空間設定」→「MCP 工具」設定的是**外部** MCP Server，供 NestSpace 自己的 AI 助手使用；它們和 NestSpace 對外提供的控制服務是兩套獨立功能：

- 支援多個 Streamable HTTP 端點；
- 使用 Bearer Token；
- 工具名稱以 `mcp_<服务短 ID>_` 前綴提供給本機助手；
- 不註冊進 NestSpace 的 `ToolRegistry`，遠端用戶端看不到。

## 工作階段與限制

- 每個用戶端使用獨立的 `Mcp-Session-Id`，閒置 30 分鐘後回收。
- 未知或過期工作階段傳回 404。
- 請求主體上限 40 MB，請求標頭上限 64 KB，並行連線 32。
- 支援 `initialize`、`tools/list`、`tools/call`、GET SSE 心跳和 DELETE 工作階段。
- 工具清單見 [MCP 工具參考](mcp-tools.md)。

## 常見問題

### 連線被拒絕

- 確認「MCP 控制服務」顯示執行中。
- USB 模式確認執行了 `adb forward tcp:8765 tcp:8765`。
- 區域網路模式確認手機和電腦在同一網路，且 Android 17+ 已授予區域網路權限。
- 確認位址是 `http://.../mcp`，不要漏掉 `/mcp`。

### 傳回 401

Token 不正確或沒有帶 `Authorization` Header。重新複製 Token，確認命令裡的引號和空格正確。

### 傳回 404

- 位址路徑不對。
- 工作階段已過期（閒置 30 分鐘）。重新連線即可，CLI 會自動重新初始化並重試一次。

### 能用工具但啟動不了容器

`space_launch` 傳回 `LAUNCH_BLOCKED` 時，授予懸浮窗權限後再試；或者保持 NestSpace 在前景。

### 看不到區域網路位址

先確認「區域網路存取」已開啟；Android 17+ 需要授予 `ACCESS_LOCAL_NETWORK`。部分路由器的 AP 隔離會阻止手機和電腦互訪。

## 安全建議

- 預設保持只監聽 `127.0.0.1`。
- 區域網路模式只在可信網路臨時開啟，用完關閉。
- 不要把 8765 連接埠映射到公網。
- Token 洩漏後清除 NestSpace 應用程式資料可以重新產生。

## 相關文件

- [MCP 工具參考](mcp-tools.md)
- [nestspace-mcp CLI](cli.md)
- [架構與限制](architecture.md)

# nestspace-mcp CLI

`tools/nestspace-mcp` 是一個只用 Python 標準程式庫實作的 MCP 用戶端，支援 Streamable HTTP、一次性工具呼叫和互動式 shell。不需要安裝第三方 Python 套件。

## 準備連線

先在「空间设置」（空間設定）→「MCP 控制服务」（MCP 控制服務）啟動服務並複製 Token。

USB（建議）：

```bash
adb forward tcp:8765 tcp:8765
export NESTSPACE_MCP_URL=http://127.0.0.1:8765/mcp
export NESTSPACE_MCP_TOKEN='<token>'
```

區域網路（在設定裡明確開啟；HTTP + Token，無 TLS，僅限可信網路）：

```bash
export NESTSPACE_MCP_URL=http://<手机局域网 IP>:8765/mcp
export NESTSPACE_MCP_TOKEN='<token>'
```

Token 也可以從檔案讀取：

```bash
tools/nestspace-mcp --token-file /path/to/token health
```

CLI 不會把 Token 列印出來。

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

用 `--arg key=value` 傳參數，值會自動辨識：

- `true` / `false` / `null`；
- 整數和浮點數；
- JSON 陣列和物件。

如果某個值必須保持字串，用位置參數傳一個 JSON 物件：

```bash
tools/nestspace-mcp call ui_set_text '{"text":"123","package":"com.example.app"}'
```

### `--json`

列印原始 MCP 結果，包括圖片資料。不加 `--json` 時：

- 文字內容直接列印；
- 圖片區塊只顯示摘要。

### `shell`

進入互動模式後可以連續呼叫工具。輸入 `disconnect` 會傳送 `DELETE /mcp` 關閉目前工作階段。

### `setup`

列印可複製的環境變數和 `claude mcp add` 命令，不會暴露 Token。

## 工作階段

- 用戶端會儲存伺服器傳回的 `Mcp-Session-Id`。
- 服務重新啟動（例如切換區域網路模式）後，舊工作階段傳回 404，CLI 會自動重新初始化並重試一次。
- 一次 CLI 處理程序對應一個工作階段；`shell` 模式會一直重複使用同一個工作階段。

## 安全

- 預設連線 `127.0.0.1`。
- 區域網路模式監聽 `0.0.0.0`，所有工具呼叫都需要 Bearer Token。
- 流量是明文 HTTP，只應在可信 Wi-Fi / 區域網路使用。
- 用完區域網路模式後，在「空間設定」→「MCP 控制服務」裡關閉。

## 範例：安裝並啟動一個 APK

```bash
# 將 APK 下載到手機下載目錄後，用 MCP 安裝
tools/nestspace-mcp call space_install_apk \
  --arg apk_uri=content://com.android.providers.downloads.documents/document/1234

# 查看已安裝的虛擬應用程式
tools/nestspace-mcp call space_list_installed

# 啟動
tools/nestspace-mcp call space_launch --arg package_name=com.example.app

# 觀察介面
tools/nestspace-mcp call ui_observe
```

具體參數以 `tools/nestspace-mcp tools` 和 [MCP 工具參考](mcp-tools.md) 為準。

## 相關文件

- [MCP 控制服務](mcp.md)
- [MCP 工具參考](mcp-tools.md)

# MCP 工具參考

NestSpace 內建 Streamable HTTP MCP Server，向本機 AI 助手和遠端 MCP 用戶端提供同一套容器控制工具。連線方式、連接埠和驗證見 [MCP 控制服務](mcp.md)。

> 工具結果中的頁面文字、子 Agent 輸出和外部 MCP 傳回都屬於**不可信資料**，模型和呼叫方不應把它們當作指令執行。

## 空間與應用程式

| 工具 | 說明 |
| --- | --- |
| `space_list` / `space_list_apps` | 列出空間和虛擬應用程式 |
| `space_list_installed` | 列出已安裝的虛擬應用程式、執行個體、簽章和 native 狀態 |
| `space_install_apk` | 安裝 APK；`apk_uri`，split 安裝時附帶 `split_uris` |
| `space_launch` | 啟動應用程式。預設操作 `virtual-1`；系統應用程式傳 `space_id=system`；可選 `instance_id`、`headless=true` |
| `space_multi_open` | 建立新執行個體並啟動 |
| `space_uninstall` | 刪除應用程式及全部執行個體資料 |
| `space_stop` | 關閉目前容器介面，回到桌面 |
| `space_permissions` | `list` / `grant` / `revoke` 容器內權限 |
| `schedule_task` | 建立和管理定時任務 |
| `replay_*` | 執行記錄回放 |

`space_launch` 的 `headless=true` 會讓虛擬應用程式在背景執行，前景不被佔用；需要懸浮窗權限提供的 BAL 豁免。

## UI 控制

| 工具 | 說明 |
| --- | --- |
| `ui_observe` | 觀察目前容器的 View 樹、頁面文字和可互動元素 |
| `ui_tap` | 依文字、資源 ID 或座標點擊 |
| `ui_set_text` | 將文字寫入輸入框 |
| `ui_wait_for` | 等待指定文字或條件出現 |
| `ui_screenshot` | 擷取容器畫面 |
| `ui_key` | 傳送按鍵，目前支援 `back` |

`ui_*` 支援用 `package` / `instance_id` 路由到指定容器執行個體；省略時操作最近啟動的執行個體。目標 Activity 自行建立的 Dialog、底部彈窗等獨立視窗也會合併進觀察和截圖。

## 內建瀏覽器

| 工具 | 說明 |
| --- | --- |
| `browser_open` | 開啟 URL，非 URL 文字走 Bing 搜尋；預設 `headless=true` 並新增分頁，可用 `tab_id` 跳轉至既有分頁，`new_tab=false` 重複使用目前分頁 |
| `browser_tabs` | 列出全部分頁的 `tab_id`、URL、標題、模式、載入和選取狀態 |
| `browser_switch_tab` / `browser_close_tab` | 切換分頁 / 關閉分頁 |
| `browser_navigate` | 導覽分頁 |
| `browser_status` | 傳回目前分頁和全部分頁的狀態 |
| `browser_back` / `browser_forward` / `browser_reload` | 導覽操作，支援 `tab_id` |
| `browser_links` | 列出連結、按鈕和圖片按鈕；圖片按鈕傳回可傳給 `browser_click` 的 CSS selector |
| `browser_click` | 依可見文字或 CSS selector 點擊元素 |
| `browser_read_text` | 讀取分頁可見文字 |
| `browser_download` | 下載 URL；省略 URL 時等待頁面觸發的下載完成 |
| `browser_upload` | 用本機路徑或 `content://` URI 完成檔案選擇 |
| `browser_hand_to_user` | 遇到驗證碼、登入牆或反爬蟲攔截時，把分頁切換到可見瀏覽器交給使用者；使用者完成後傳回目前頁面狀態 |

下載檔案寫入系統 **Download** 目錄（Android 10+ 透過 MediaStore），傳回檔案名稱、路徑、大小和 URI，可直接交給 `space_install_apk`。

瀏覽器右上角齒輪或「空间设置」（空間設定）→「浏览器代理」（瀏覽器代理）可設定 HTTP/SOCKS 代理，所有分頁和下載共用（支援以逗號分隔的略過規則）。代理需要 Android 10+ 且 WebView 支援 `PROXY_OVERRIDE`；目前不支援需要使用者名稱/密碼的代理。

## 子 Agent

`spawn_agent` 把一個自包含的子任務交給唯讀子 Agent：預設 15 步、最多 40 步，只能觀察容器和瀏覽網頁，不能點擊、輸入、啟動應用程式或再建立子任務。結果預設保留 4000 字、最多 8000 字，並標記為不可信資料傳回主 Agent。

子任務記錄儲存在「空间管理」（空間管理）→「子任务记录」（子任務記錄），可檢視狀態、結果並停止執行中的子任務。對應的遠端工具是 `nestspace_child_runs`。

## 助手橋接（僅限遠端 MCP）

| 工具 | 說明 |
| --- | --- |
| `assistant_type` | 將文字注入 AI 助手輸入框（更新 Compose 狀態，不模擬按鍵） |
| `assistant_send` | 傳送輸入框內容或指定文字，等待助手完成後傳回新增對話和工具軌跡 |
| `assistant_status` | 讀取助手忙碌狀態與最近對話，可用 `wait_ms` 等待目前輪次結束 |

這些工具不會暴露給 AI 助手自身，避免遞迴呼叫。

## 設定（僅限遠端 MCP）

以下工具只出現在遠端 MCP 的 `tools/list` 中，不會暴露給本機助手，避免透過提示注入修改模型設定。

| 工具 | 說明 |
| --- | --- |
| `nestspace_settings_get` | 一次讀取語言、容器槽位、懸浮球、瀏覽器代理、外部 MCP 用戶端、桌面應用程式、壁紙、LLM 設定（不含 Key）、記憶/技能/定時任務數量和 MCP 狀態 |
| `nestspace_mcp_clients` | `list` / `save` / `delete` / `discover` 外部 MCP Server |
| `nestspace_browser_proxy` | `get` / `set` 瀏覽器 HTTP/SOCKS 代理 |
| `nestspace_mcp_lan` | `get` / `set` 區域網路監聽，傳回本機位址和權限狀態 |
| `nestspace_llm_config` | `list` / `save` / `delete` / `select` / `test` 模型設定 |
| `nestspace_language_set` | `system` / `zh` / `en` |
| `nestspace_slots_set` | `auto` 或 `fixed` + `limit`（1–16） |
| `nestspace_floating_dot_set` | 開關、宿主/容器顏色、透明度（10–100%）、語音語言和位置 |
| `nestspace_desktop_apps` | `get` / `set` / `add` / `remove` 桌面系統應用程式 |
| `nestspace_wallpaper` | `get` / `set`（base64 圖片）/ `clear` |
| `nestspace_memory_config` | `list` / `approve` / `pin` / `delete` 記憶 |
| `nestspace_skill_config` | `list` / `enable` / `disable` / `delete` 技能；內建技能只能停用 |
| `nestspace_child_runs` | `list` / `get` / `cancel` / `cancel_all` / `active` 子 Agent 執行 |

`nestspace_llm_config` 的 `save` 省略 `api_key` 時保留原 Key，也可以用 `copy_key_from` 複製既有 Key；`context_tokens` 預設 800000，最低 100000；`max_tool_rounds` 預設 128。API Key 是唯寫欄位，任何工具結果都不會傳回明文。

## 外部 MCP 工具

「空間設定」→「MCP 工具」可以新增多個外部 MCP Server（Streamable HTTP + Bearer Token）。儲存後「刷新工具」（重新整理工具）會請求 `initialize` / `tools/list`，工具以 `mcp_<服务短 ID>_` 前綴提供給本機 AI 助手。

外部工具不註冊進 NestSpace 的 `ToolRegistry`，遠端 MCP 用戶端看不到也不能直接呼叫它們。嚴格權限模式下每次外部工具呼叫都會確認；標準/寬鬆/自訂模式依「外部 MCP 工具」權限項目處理。Token 使用 Android Keystore 加密儲存。

## 工作階段與限制

- 每個用戶端使用獨立的 `Mcp-Session-Id`，閒置 30 分鐘後回收；未知工作階段傳回 404。
- 請求主體上限 40 MB，請求標頭 64 KB，並行連線 32。
- 預設只綁定 loopback；區域網路模式必須由使用者明確開啟，Bearer Token 仍然有效，但沒有 TLS。
- 遠端 MCP 與本機助手共用同一套容器工具，但設定類和助手橋接類工具只對遠端用戶端開放。

# 架構與限制

本文面向想了解實作細節或疑難排解相容性問題的讀者。完整的藍圖和目標設計見[技術方案（中文）](https://github.com/chuccp/NestSpace/blob/main/docs/android-virtual-space-agent-mcp.md)。

## 整體結構

NestSpace 由三部分組成：

- **應用程式層級容器**：在宿主處理程序內載入目標 APK，為目標應用程式提供一套虛擬化的 Android 執行環境。
- **桌面與空間管理**：安裝、多開、資料隔離、圖示整理和系統應用程式選擇。
- **控制通道**：容器內的 Binder UI 控制、本機 AI 助手，以及對外的 MCP Server。

容器不是系統層級虛擬化；它執行在 NestSpace 自己的處理程序裡，透過 ClassLoader、資源、Context 和系統服務代理來模擬目標應用程式需要的環境。

## 程式碼模組

執行階段拆分為兩個界線明確的 Gradle 模組：

- `runtime-api`：引擎契約、共享模型、Application factory 註冊表和宿主 hook。
- `runtime-core`：APK 與執行個體儲存、Session、Activity Host、處理程序槽位、Provider、生命週期、Native Facade、MCP UI、加固 Application 啟動鏈解析和相容 Application factory。

相依方向為 `runtime-core → runtime-api`，`app` 作為控制面。執行階段後端透過 `RuntimePluginLoader` 安裝，`app` 不直接呼叫具體後端實作。

## 安裝與啟動

### 安裝

1. 解析 APK 的 manifest、資源表和簽章。
2. 驗證套件名稱、簽章一致性、`minSdk` 和 `REQUIRE_SECURE_ENV`。
3. 把 APK 複製到應用程式私有目錄，寫入安裝記錄；split APK 會合併成一份安裝描述。
4. 為應用程式建立執行個體，執行個體資料目錄依 `data/<package>/<instance>` 分隔。

### 啟動

1. 依 `package@instance` 從容器處理程序池分配一個槽位處理程序。
2. 用 `DexClassLoader` 載入目標程式碼，用 `AssetManager` 載入目標資源，並建構目標感知的 `Context` 和 `Resources`。
3. 透過反射把目標 Activity attach 到宿主視窗，驅動完整的生命週期。
4. 目標的 `Application` 會執行 `attachBaseContext` 和 `onCreate`。

## 隔離方式

- 每個 `package@instance` 固定到獨立的 `:c1`–`:c16` 宿主處理程序。
- 執行個體的 `files`、`cache`、`code_cache`、`databases`、`getDir` 和 SharedPreferences 使用獨立命名空間。
- native 程式庫依 ABI 擷取到執行個體目錄，作為 `DexClassLoader` 的 `librarySearchPath`；同一執行個體重複使用時先結束舊處理程序，避免同名 `.so` 的 JNI 全域狀態互相混用。
- 目標 Java 例外由當機保護層攔截並記錄；native crash 或 `System.exit` 只影響對應的槽位處理程序。

處理程序池預設自動管理最多 16 個槽位；記憶體緊張時依最近最少使用回收背景容器，前景容器不會被關閉。也可以設定固定槽位上限（1–16）。

## 虛擬化的系統能力

- **Activity**：依 `onCreate → onStart → onRestoreInstanceState → onPostCreate → onResume → onPostResume` 等完整回呼驅動，支援容器內 Activity 跳轉、返回堆疊、Splash 頁和 `startActivityForResult`。
- **Service / Provider / Broadcast**：目標 Service 由目標 ClassLoader 具現化；Provider 依 authority 路由；manifest 和動態 BroadcastReceiver 支援一般、有序和 sticky 廣播。
- **PackageManager**：目標套件的元件、權限、簽章、UID、標籤/圖示、資源和啟動 Intent 由 APK 描述元回答；同空間跨虛擬應用程式的查詢、隱式 Intent 解析也由虛擬索引提供。
- **通知**：套件名稱、tag、channel 和 group 依虛擬套件隔離，small icon 和 PendingIntent 自動改寫為宿主可接受的形式。
- **JobScheduler**：容器內傳回本機排程器，目標 `JobService` 用目標 ClassLoader 驅動，支援延遲、週期、退避和常見限制條件。
- **權限**：`checkSelfPermission` 等查詢走虛擬授權表；`requestPermissions` 接入宿主的真實權限申請，結果依套件持久化。
- **GMS**：對 `IGmsServiceBroker` 的 Binder 交易做套件名稱和 `AttributionSource` 改寫，涵蓋 WebView/Chimera 等常見呼叫路徑。
- **UI 控制**：容器處理程序內透過 Binder 觀察 View 樹、點擊、輸入和截圖，並合併目標 Activity 自行建立的 Dialog、底部彈窗等視窗；不依賴無障礙服務。

## 控制與 Agent

- **本機 AI 助手**：支援 Claude 相容和 OpenAI 相容介面，透過 `space_*`、`ui_*`、`browser_*` 工具操作 `virtual-1` 空間。
- **遠端 MCP**：內建 Streamable HTTP Server，預設只監聽 `127.0.0.1`，可明確開啟區域網路監聽；支援多用戶端工作階段、SSE 心跳和 DELETE 工作階段。
- **外部 MCP 工具**：本機助手可以連接外部 MCP Server，工具名稱以 `mcp_<id>_` 前綴隔離，不暴露給遠端用戶端。
- **瀏覽器**：內建多分頁 WebView，無頭分頁由 Agent 建立，可被可見瀏覽器接管；下載、上傳和代理與容器工具共用。

## 已知限制

- 建議先安裝自己熟悉的 App；遇到相容性問題時更容易判斷是應用程式本身還是容器調適的問題，也方便向專案回報。
- 相容性取決於目標應用程式的加固、native SDK、動態載入和相依性組合；不是所有應用程式都能直接執行，遇到問題需要逐個調適。
- 同時執行的容器處理程序最多 16 個，實際數量還受裝置記憶體限制；自動模式下記憶體不足會回收最久未使用的背景容器。
- Job、Alarm 和廣播橋接只在容器處理程序存活期間有效，處理程序被系統回收後不會由系統喚醒；完整背景保持運作仍需前景服務策略。
- 跨虛擬應用程式的 `startActivity` 仍會回退到宿主，目前主要支援查詢和解析層級；跨應用程式 Provider 查詢只支援本套件的 authority。
- Android 7.x（API 24–25）沒有 `ContentResolver.wrap`，Provider 虛擬化在該版本上關閉，其餘容器能力仍可用。
- Google Play services 等獨立處理程序依真實 UID/套件名稱驗證，目前只代理了部分 Binder 介面；不同應用程式可能需要補充調適。
- 僅含 arm64 原生程式庫的應用程式需要 arm64 裝置；x86_64 模擬器會在啟動前傳回 `ABI_MISMATCH`。
- 目標 Activity 的 `savedInstanceState` 儲存在處理程序內，支援同處理程序重建，但處理程序被終止後不會跨處理程序保留。
- 合成 UID 只用於應用程式層查詢，系統端仍看到宿主 UID。
- 無頭啟動依賴懸浮窗權限提供的背景 Activity 啟動豁免；未授權時 `space_launch` 傳回 `LAUNCH_BLOCKED`。
- 區域網路 MCP 使用明文 HTTP + Bearer Token，沒有 TLS，只應在可信網路開啟。
- 內建瀏覽器代理需要 Android 10+ 且 WebView 支援 `PROXY_OVERRIDE`，目前不支援需要使用者名稱/密碼的代理。
- 外部 MCP 用戶端目前只支援 HTTP POST（JSON 或 SSE 回應）和 Bearer Token。
- AI 助手固定在 `virtual-1` 空間內操作，不會控制系統全域應用程式。

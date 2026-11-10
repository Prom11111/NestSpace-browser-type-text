# NestSpace：Android 自研應用程式級容器 + 雙通道 Agent/MCP 技術方案

> 本文是技術設計與實作記錄，供開發與二次開發參考；日常使用請看 [NestSpace 使用手冊](README.md)。
>
> 專案：`NestSpace`（`com.coke.nestspace`，Jetpack Compose，minSdk 24 / targetSdk 37）
> 日期：2026-11-03
> 限制：一般 APK、不能控制 ROM；**自研容器引擎，不重用 VirtualApp 商業版**；本機 Agent 與遠端 MCP 雙通道都要支援。
> 狀態：Stage A/B + Stage C-lite + Stage C-D（獨立處理程序/當機隔離、Job/通知虛擬化、宿主/系統廣播橋接、Binder/UID 權限模型、完整 VPMS、跨虛擬應用程式查詢、權限授予、多執行個體 .so 處理程序隔離）已實作並在 Android 7.1.1 / Android 16 / Android 17（API 37 模擬器）/ MuMu(Android 15, ARM bridge) 驗證；本機 LLM Agent（Claude/OpenAI 相容，共用 chat 協定）已實作並用 mock 與第三方 Anthropic 相容端點在真實網路驗證通過；完整背景 Job/Alarm 喚醒與系統端真實 UID 欺騙仍屬於後續增強

---

## 閱讀路徑

| 你想先看什麼 | 直接跳到 |
|---|---|
| 結論、可行性邊界、自研限制 | **0** |
| 需求與範圍 | 1 |
| 容器總體架構、處理程序模型、Hook 路線 | 2 |
| 關鍵子系統（PMS/AMS/ClassLoader/Binder/IO） | 3 |
| Agent 怎麼操作虛擬應用程式 | 4 |
| 本機 Agent + 遠端 MCP 雙通道 | 5 |
| 架構、模組、介面、專案改造 | 6 |
| 建設順序、測試矩陣、風險 | 7–9 |

---

## 0. 結論

### 0.1 技術路線

- **自研應用程式級容器引擎**：在宿主應用程式內載入未安裝的 APK，虛擬化 PackageManager/ActivityManager/Binder/IO，讓目標應用程式以為自己在正常系統裡執行。
- **優先進行 Java 層虛擬化，關鍵點使用 native hook**：先讓 `Instrumentation`/`ActivityThread`/`PackageManager`/`ContextImpl` 的 Java Hook 驗證可行；Binder/UID/native 檔案路徑等困難點再上 native hook。
- **單一處理程序 MVP → 多處理程序產品**：先在單一處理程序跑通簡單 APK，再由宿主宣告的 `:c1..:c16` 處理程序池承載多執行個體與多目標處理程序。
- **雙通道 Agent/MCP**：本機 Agent（App 內迴圈）+ 遠端 Agent（MCP），共用同一套 `ToolRegistry`、權限、租約、稽核。
- **AppFunctions 快路徑（可選）**：宿主應用程式可匯出 AppFunctions；容器內 APK 的 AppFunctions 走本機橋接；不可用時回退 UI 自動化。
- **不做 VirtualApp 商業授權**。VirtualApp 只作為架構參考，**Clean-room 實作，不複製其 GPL 程式碼**。

### 0.2 三層架構

```text
┌─────────────────────────────────────────────────────────────┐
│ 3. Agent / MCP 層                                           │
│    本機 Agent（App 內迴圈）  |  遠端 Agent（MCP）           │
│    共用 ToolRegistry / 租約 / 核准 / 稽核                   │
├─────────────────────────────────────────────────────────────┤
│ 2. 控制層                                                   │
│    ContainerService（虛擬空間/安裝/啟動）                   │
│    Shizuku / Accessibility / Vision（UI 觀測與操作）        │
├─────────────────────────────────────────────────────────────┤
│ 1. 自研容器引擎                                             │
│    Installer / VPMS / VAMS / ClassLoader / Resources        │
│    Binder/UID / IO Redirect / Permissions / Process Pool    │
└─────────────────────────────────────────────────────────────┘
```

### 0.3 可行性邊界（先說實話）

**能做：**

- 一般 APK、單一處理程序、單一執行個體
- 多開、獨立資料目錄
- 無強完整性校驗的內容/工具/社交類 App
- 用 Shizuku/無障礙/視覺控制虛擬應用程式
- 本機 Agent + 遠端 MCP 雙通道

**難做，需要逐項攻克：**

- 多處理程序 + Binder/UID 虛擬化
- split APK / AAB
- 16 KB page / 原生程式庫
- 通知、JobScheduler、AlarmManager、ContentProvider
- 背景啟動 Activity（Android 10+ 限制）
- 目標應用程式的簽章校驗 / Keystore
- Android 15–17 的 hidden API 變化

**做不了/不承諾：**

- 真正的 UID/SELinux 隔離（應用程式級容器共用宿主 UID）
- 金融/銀行/支付/Play Integrity/GMS 強依賴 App
- 強反容器/反 hook/反除錯 App
- 「所有 App 都能跑」

> 自研的正確目標不是「替代 Android 系統」，而是「在一般 App 裡執行一個受控的、相容性有限的第二個虛擬空間」。產品文案要按這個邊界寫。

### 0.4 最大技術風險

1. **Hidden API / ART / Binder 版本調適**：Android 每個大版本都會變更 `ActivityThread`、`PackageParser`、`Resources`、Binder 交易；沒有「寫一次就能長期穩定」這種事。
2. **Binder/UID 虛擬化**：系統服務看到的是宿主 UID；Java 層能騙過一部分，系統端的權限/AppOps/帳號/支付等仍依宿主 UID 判定。這是應用程式級容器的根本限制。
3. **背景啟動 Activity**：Android 10+ 限制背景啟動；虛擬應用程式從宿主背景啟動會被攔截。需要前景服務/通知/使用者操作觸發。
4. **通知/Job/Provider**：系統依套件名稱/UID 歸屬；虛擬化這些比 Activity 難得多，很多 App 的背景能力會退化。
5. **Keystore/簽章/Play Integrity**：同 UID 下 Keystore alias 衝突；簽章校驗/完整性校驗很可能失敗。
6. **處理程序模型**：`app_process` 子處理程序會被 phantom process killer 殺掉；宿主宣告的 `:c1..:c16` 處理程序池更穩定，並需明確處理處理程序重用、記憶體與當機隔離。
7. **16 KB / split APK**：安裝器和原生程式庫載入都要調適。
8. **長期維護**：這是「迷你 Android 執行環境」，不是一般 App 功能；需要持續跟上 AOSP。

### 0.5 自研 ≠ 無限制

自己寫不需要 VirtualApp 授權，但以下限制仍然存在：

- **Clean-room / GPL**：不要複製 VirtualApp 的 GPL 程式碼；參考 AOSP（Apache-2.0）和公開文件實作。如果團隊看過 GPL 程式碼，最好做程式碼隔離/法務評估。
- **Google Play 容器政策**：容器 App 允許，但必須檢查目標應用程式的 `REQUIRE_SECURE_ENV`，不得代理容器外 App 的 API。
- **目標應用程式的 ToS**：自動化操作、重打包、繞過校驗可能違反第三方 App 條款。
- **Native hook 程式庫授權**：ShadowHook/ByteHook/Dobby/xDL 等要逐個核對授權和商業條款。
- **Hidden API 限制**：Android 9+ 對非 SDK 介面有限制；不同版本需要不同的繞過策略，穩定性自行負責。

---

## 1. 需求與限制

### 1.1 目標

- 在 App 內建立多個「虛擬空間」。
- 虛擬空間內安裝 APK、啟動、停止、多開。
- 本機 Agent 用自然語言操作虛擬空間內 App。
- 遠端 Agent 透過 MCP 操作同一個虛擬空間。
- 兩條通道行為一致，不會互相中斷。

### 1.2 限制

- 一般 APK，不能控制 ROM/系統簽章。
- 自研容器引擎；不依賴 VirtualApp 商業版。
- Android 12–17，arm64，盡量不 root。
- 本機 Agent 模型可以是裝置端（Gemini Nano 4/Gemma 4）或雲端（Claude API）。

### 1.3 功能範圍

- 虛擬空間管理：建立、刪除、重新命名、列出
- App 管理：安裝 APK（含 split）、卸載、列出、圖示/名稱
- 執行管理：啟動、停止、前景切換、最近任務
- 觀察：UI 樹、截圖、目前套件名稱/Activity
- 操作：點擊、長按、滑動、捲動、輸入、返回/Home/最近任務
- 等待/驗證：等待文字、等待套件名稱、等待介面閒置
- MCP：遠端 Agent 探索/呼叫工具
- 本機 Agent：自然語言任務 → 工具呼叫迴圈
- 工作階段：租約、核准、稽核、軌跡
- 技能：錄製、回放、參數化

### 1.4 非功能需求

- 單次操作 p50 < 500 ms，p95 < 2 s（不含模型推論）
- 虛擬空間之間資料隔離（邏輯隔離）
- 當機率、ANR、背景存活可觀測
- 安裝/啟動失敗有結構化錯誤
- MCP 預設只綁定 loopback，LAN 需明確開啟
- 本機 Agent 與遠端 Agent 的工具行為一致

### 1.5 不做（目前版本）

- 金融/銀行/GMS/Play Integrity 類 App
- 不可信 APK 的強隔離
- 系統級空間/多使用者/工作設定檔
- 雲端手機/遠端實機
- 「所有 App 相容」的承諾

---

## 2. 自研容器總體架構

### 2.1 設計目標

1. 目標應用程式的 `Activity` 能正常建立、顯示、接收輸入、走 Activity 生命週期。
2. 目標應用程式認為自己已安裝：`PackageManager` 能查到自己的 `PackageInfo`、權限、簽章。
3. 目標應用程式的私有資料落到獨立目錄，不污染宿主。
4. 多個執行個體可以共存。
5. 當機不要拖垮宿主。
6. 控制層能觀察/操作目標應用程式的 UI。

### 2.2 處理程序模型

**階段 A：單一處理程序（MVP）**

- 宿主主處理程序裡載入目標 APK；目標 Activity 執行於宿主處理程序。
- 優點：實作簡單，除錯方便，不需要跨處理程序。
- 缺點：目標應用程式當機會拖垮宿主；多開共用一個處理程序；記憶體壓力大。
- 適合：驗證可行性、簡單 App。

**階段 B：宿主宣告的 `:c1..:c16` 處理程序池（產品）**

- 在宿主 `AndroidManifest.xml` 裡宣告 `:c1..:c16` 槽位處理程序，並為每個處理程序宣告一個 `StubActivity`/`VClientService`。
- 啟動虛擬應用程式時，選擇閒置處理程序，啟動該處理程序裡的 `StubActivity`；系統會建立正常的 app 處理程序和 `ActivityThread`。
- 在該處理程序裡初始化容器 hook，載入目標 APK，執行目標 Activity。
- 優點：是正常的 app 處理程序，不受 phantom process killer 影響；當機隔離；可並行多個執行個體。
- 缺點：處理程序池大小固定；記憶體/啟動成本；處理程序重用要清理狀態。

> 不建議用 `Runtime.exec("app_process ...")` 啟動子處理程序：Android 12+ 的 phantom process killer 會殺掉，且權限/處理程序歸屬更複雜。

**處理程序分配策略：**

- 每個虛擬空間一個處理程序；或每個執行個體一個處理程序；或依記憶體/前景狀態動態分配。
- 容器槽位上限依裝置記憶體自動計算，最多 16 個；固定模式使用使用者選擇的 1–16。
- 前景執行個體優先獨占；背景執行個體可重用處理程序。
- 處理程序當機後標記 dirty，清理後再重用。

### 2.3 總體圖

```text
┌──────────────────────────────────────────────────────────────────┐
│ Host main process                                                │
│                                                                  │
│  ┌────────────────┐  ┌────────────────┐  ┌────────────────────┐  │
│  │ ContainerService│  │ ToolRegistry   │  │ Host UI / MCP      │  │
│  │ - Installer     │  │ - Local Agent  │  │ - Space launcher   │  │
│  │ - VPMS          │  │ - MCP Server   │  │ - Agent console    │  │
│  │ - VAMS          │  │ - Lease/Approval│ │ - Installer        │  │
│  │ - Process Pool  │  └────────────────┘  └────────────────────┘  │
│  └───────┬────────┘                                              │
│          │ IPC (Binder/AIDL)                                     │
│  ┌───────▼─────────────────────────────────────────────────────┐  │
│  │ Client process :c1 (normal app process)                      │  │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────────┐   │  │
│  │  │ VClient      │  │ HookManager  │  │ Target Activity  │   │  │
│  │  │ (ActivityThread)│ │ Java/Native │  │ (target APK)     │   │  │
│  │  └──────────────┘  └──────────────┘  └──────────────────┘   │  │
│  │  Virtual ClassLoader / Resources / data dirs                 │  │
│  └─────────────────────────────────────────────────────────────┘  │
│                                                                  │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │ Client processes :c1 ... :c16 (same structure)              │ │
│  └─────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────┘
```

### 2.4 兩條實作路線

| 路線 | 做法 | 優點 | 缺點 |
|---|---|---|---|
| **Java 層虛擬化**（VirtualApp 風格） | Hook `Instrumentation`、`ActivityThread.mH`、`ApplicationPackageManager`、`ContextImpl`、`Binder` Java 方法；動態代理/反射 | 不需要 native hook；除錯相對容易；能涵蓋大部分 Java 呼叫 | Hidden API 限制；Binder/UID/native 層騙不過；每個 Android 版本都要調適 |
| **Native hook / ART** | Hook `Binder.execTransact`、`Process.myUid`、`open`、`dlopen`、ART 方法 | 更強；能處理 native 程式碼和系統呼叫 | 極複雜；ABI/版本/ROM 差異；穩定性/安全風險高 |

**建議：Java-first，native 只處理關鍵點。**

- 第一版：Java 層 Hook 驗證 Activity/PMS/Resources/IO 可行。
- 第二版：對 Binder/UID/native 路徑做 native hook。
- 不要一開始就寫「通用 ART hook 框架」；先用成熟 hook 程式庫，讓主要流程順利執行。

### 2.5 Hook 框架

**Java 層：**

- 反射 + 動態代理（`Proxy`/`InvocationHandler`）替換 `IPackageManager`、`IActivityManager` 等介面。
- `Unsafe`/`MethodHandles` 修改 final 欄位。
- Hidden API 豁免：`VMRuntime.setHiddenApiExemptions` 或「FreeReflection」類方案；Android 10+ 需要依版本處理。
- `ActivityThread.mH` 的 `Handler.Callback` 替換（注意不能破壞系統訊息）。
- `Instrumentation` 子類別替換（`ActivityThread.mInstrumentation`）。

**Native 層：**

- PLT/GOT hook、inline hook；優先使用維護中的程式庫（ShadowHook、ByteHook、Dobby、xDL），逐一核對授權。
- 關鍵 hook 點：`Binder.execTransact`、`Process.myUid`、`open`/`fopen`、`dlopen`/`android_dlopen_ext`。
- 版本調適：每個 Android 大版本維護 offset/符號表；用 feature flag 漸進式發布。

**版本調適層：**

```kotlin
interface AndroidAdapter {
    val apiLevel: Int
    fun installPackageHooks()
    fun installActivityHooks()
    fun installResourceHooks()
    fun installBinderHooks()
    fun installNativeHooks()
}
```

每個 API level 一個實作；共用邏輯抽到 base；不要在新版本上假設舊 hook 點還在。

---

## 3. 關鍵子系統

### 3.1 APK 解析 / 安裝器

**輸入：**

- 單一 APK
- split APK（base + config.arm64_v8a + config.xxhdpi 等）
- `.apks` / `.xapk`（解包後依 split 處理）

**解析：**

- 二進位 `AndroidManifest.xml`（AXML）：package、versionCode、minSdk、targetSdk、application、activities、services、providers、receivers、permissions、meta-data、`REQUIRE_SECURE_ENV` property。
- `resources.arsc`：資源表、設定、split 資源。
- 簽章：V1/V2/V3；簽署者/憑證摘要。
- 原生程式庫：ABI、16 KB 對齊。
- split 中繼資料：`splitNames`、`splitTypes`、`configForSplit`、`isFeatureSplit`。

**實作選擇：**

- 使用 AOSP `PackageParser`/`ParsingPackageUtils`（hidden API，版本差異大）；或
- 使用開源 APK 解析程式庫（如 ARSCLib/apk-parser）自行建立模型；或
- 兩者結合：解析用開源程式庫，執行階段 `PackageInfo` 自行建構。

**安裝：**

1. 預檢（附錄 A）。
2. 複製 APK/splits 到虛擬空間私有目錄。
3. 解析並建構內部 `PackageInfo`/`ActivityInfo`/`ServiceInfo`/`ProviderInfo`。
4. 建立 `data/`、`files/`、`cache/`、`databases/`、`shared_prefs/`、`lib/`。
5. 註冊到 VPMS。
6. 建立啟動器/捷徑。
7. 首次啟動時初始化 `Application`。

**必須檢查：**

- `REQUIRE_SECURE_ENV=1` → 拒絕載入。
- 未對齊 16 KB 的原生程式庫 → 在 16 KB 裝置上拒絕/提示。
- 不完整 split → 拒絕。
- 低 targetSdk → 是否允許，依 Android 版本策略。

### 3.2 虛擬 PackageManager（VPMS）

目標：透過 PackageManager 虛擬化，讓目標應用程式從 `Context.getPackageManager()` / `ActivityThread.getPackageManager()` 看到「自己已安裝」。

**要虛擬化的方法（至少）：**

- `getPackageInfo` / `getApplicationInfo` / `getActivityInfo` / `getServiceInfo` / `getProviderInfo`
- `resolveActivity` / `queryIntentActivities` / `queryIntentServices`
- `getInstalledPackages` / `getInstalledApplications`
- `getPackagesForUid` / `getPackageUid` / `getNameForUid` / `getPackageGids`
- `checkPermission` / `checkSignatures` / `getPackageInfo(GET_SIGNATURES)`
- `getLaunchIntentForPackage`
- `getApplicationEnabledSetting` / `setApplicationEnabledSetting`
- `getResourcesForApplication` / `getResourcesForActivity`
- `getPackagesHoldingPermissions`

**實作方式：**

1. **Context 層級包裝**：給目標應用程式一個自訂 `Context`/`ContextWrapper`，`getPackageManager()` 傳回 `VPackageManager`；`getResources()` 傳回目標 Resources；`getClassLoader()` 傳回目標 ClassLoader。
2. **ActivityThread/IPackageManager 代理**：替換 `ActivityThread.getPackageManager()` 傳回的 `IPackageManager` 動態代理；非虛擬套件轉發給系統，虛擬套件查 VPMS。
3. **ApplicationPackageManager hook**：對直接 `new ApplicationPackageManager(...)` 或靜態呼叫的情境做補充。

> 優先做 1 + 2；3 作為備援。`IPackageManager` 是 AIDL 介面，Android 版本間方法會變，代理要依版本產生/調適。

**簽章：**

- 傳回目標 APK 的真實簽章（如果可信），或依產品策略偽造；後者會觸發完整性校驗，不建議。
- `checkSignatures` 對自己傳回 `MATCH`；對系統套件依實際結果。

### 3.3 虛擬 ActivityManager（VAMS）

目標：讓目標應用程式的 `startActivity`/`startService`/`bindService`/`sendBroadcast` 能啟動到虛擬元件，而不是被系統拒絕。

**核心：Stub Activity 方案**

1. 宿主 Manifest 宣告一組 `StubActivity0..N`（可帶 `android:process=":pX"`、不同 `launchMode`/`taskAffinity`）。
2. Hook `Instrumentation.execStartActivity`：
   - 判斷目標 Intent 是否指向虛擬套件；
   - 是：把 component 替換成某個 `StubActivity`，把原始 Intent/目標元件/執行個體 id 存到 extras 或 VAMS 對應表；
   - 否：放行。
3. 系統啟動 `StubActivity`；`ActivityThread` 建立它。
4. Hook `Instrumentation.newActivity` / `ActivityThread.mH`：
   - 用目標 APK 的 `ClassLoader` 具現化目標 Activity；
   - 替換 `StubActivity` 的執行個體/欄位，或讓 `StubActivity` 代理生命週期；
   - 注入目標 `Resources`、`Context`、`Application`、`Window`。
5. Activity 生命週期回呼轉發到目標 Activity；`onBackPressed`/`onNewIntent`/`onActivityResult` 也要處理。
6. 任務堆疊：把 StubActivity 對應回目標 Activity；`finish`/`moveTaskToBack`/最近任務顯示目標名稱/圖示。

**已實作：Stage C-lite（2026-11-03）**

沒有引入 `StubActivity0..N` 陣列，而是重用 `StageAActivity` 本身作為「通用 Stub」：

- 每個目標 Activity 對應一個真實 `StageAActivity` 執行個體，透過 `host.startActivity`/`startActivityForResult` 入堆疊；Android 自己的任務堆疊負責返回。
- `ContainerInstrumentation` 宣告 AOSP 的隱藏方法 `execStartActivity(Context, IBinder, IBinder, Activity, Intent, int, Bundle)`（不加 `override`，先安裝 HiddenApiBypass 豁免），執行階段由 ART 虛擬分派到它。
- 目標套件內的顯式 ComponentName（含 activity-alias）→ 新建 `StageAActivity`，extras 攜帶 sessionId、目標類別、原始 Intent；外部 Intent → 交給目前宿主啟動。
- `ContainerSessions` 依 sessionId 共用 target Application/ClassLoader/Resources；每個目標 Activity 只建立自己的 Activity 執行個體、Theme 和 decorView。
- `finish()` 使用真實 host token，所以只關閉目前目標對應的 `StageAActivity`，上一個宿主自然恢復；Splash → 首頁、`startActivityForResult` 結果回傳均已驗證。
- 生命週期由宿主轉發：onCreate/onStart/onRestart/onResume/onPause/onStop/onDestroy，以及 `onActivityResult`（反射呼叫目標 Activity 的 protected 方法）。
- 尚未實作：IntentFilter/隱式 Intent 解析、`onNewIntent` 的 singleTop/CLEAR_TOP 語意、Activity options/轉場、`requestPermissions` 的 token 對應、最近任務顯示目標名稱/圖示。
- 真實目標應用程式相容補充（MuMu / Android 15 驗證）：
  - 目標 APK 可能自帶 AndroidX。`DexClassLoader` 預設 parent-first，會讓目標類別連結到宿主的 AndroidX，出現欄位缺失。`ContainerClassLoader` 改為 child-first，先查目標 dex，再回退宿主/系統。
  - 目標 Activity 的 `attachBaseContext` 可能呼叫 `createConfigurationContext` 處理 locale；早期實作會傳回宿主資源。`ContainerContext` 現在用目標 APK 的 AssetManager + 新 Configuration 重建 Resources，並傳回一個保持目標套件名稱、ClassLoader 和目錄的 ContextWrapper。
  - 目標應用程式仍可能依賴系統服務繫結、Provider 或 Binder 虛擬化；未覆蓋時應輸出明確診斷，而不是增加針對單一應用程式的啟動特判。

**已實作：容器內 Service / Provider（Stage C-lite+）**

- Service：
  - `ContainerContext` 覆寫 `startService`/`startForegroundService`/`bindService`/`unbindService`/`stopService`；目標套件顯式 Service 交給 `ContainerServiceManager`，外部 Service 回退宿主。
  - 目標 Service 用目標 `ContainerClassLoader` 具現化，`attachBaseContext(ContainerContext)`，設定 `Service.mApplication`，直接驅動 `onCreate`/`onStartCommand`/`onBind`/`onUnbind`/`onDestroy`。
  - `bindService` 把 `onBind` 傳回的 `IBinder` 透過主執行緒 `onServiceConnected` 交給目標 `ServiceConnection`；`startActivityForResult` 與 Service 回呼都在目標處理程序內完成。
  - 僅限顯式 ComponentName；隱式 Service Intent 未解析。`startForeground`/`stopSelf` 仍可能觸達宿主 AMS。
- ContentProvider：
  - 工作階段啟動時（Application.onCreate 之前）依 manifest 建立目標 Provider，`attachInfo(ContainerContext, ProviderInfo)` 會執行 `onCreate`。
  - 用 `ContentResolver.wrap(dispatcherProvider)` 得到目標 resolver；dispatcher 依 URI authority 路由到本機 Provider，外部 authority 回退宿主 application context 的真實 resolver。
  - 覆寫 query/insert/update/delete/bulkInsert/getType/call/openFile/openAssetFile/openTypedAssetFile；目標 Provider 之間可以互相查詢。
  - Provider 權限、處理程序隔離、`FileProvider` 的 PackageManager meta-data 查詢未虛擬化。
**已實作：Stage C-D（2026-11-04）**

- 處理程序模型：`ContainerProcessPool` 把每個 `package@instance` 固定到 `:c1..:c16` 之一；容器槽位重用前強制 kill 並等待結束，`ContainerSessions` 在槽位處理程序內拒絕第二個工作階段。目標 Java 未攔截例外由 `TargetCrashGuard` 吞掉並寫入當機標記；native crash/`System.exit` 只殺掉該槽位處理程序，主處理程序與其他執行個體不受影響。下次 `space_launch` 傳回 `restarted_after_crash`/`crash_reason`。
- JobScheduler：`ContainerContext.getSystemService(JOB_SCHEDULER_SERVICE)` 傳回 `ContainerJobScheduler`；目標 `JobService` 用目標 ClassLoader 具現化，驅動 `onCreate`/`onStartJob`/`onStopJob`，支援最小延遲、週期、退避和網路/充電/低電量/閒置限制（無法判斷的限制視為已滿足）。處理程序存活期間有效。
- 通知：`INotificationManager` 代理把套件名稱改成宿主套件，tag/channel/group 加上虛擬套件前綴，small icon 重繪為 bitmap，目標 PendingIntent 換成宿主 `ContainerLaunchProxyActivity` 跳板；channel 查詢結果去掉前綴。
- 廣播橋接：目標靜態/動態 Receiver 的 action 會註冊到宿主；系統 sticky/一般廣播注入容器，目標發出的非自身作用域廣播在本機分送後繼續發給宿主，並帶 origin extra 防止回送迴圈。
- 權限：`checkSelfPermission`/`checkPermission`/`enforce*`/PackageManager 權限查詢走虛擬授權表；改寫 `ContainerPackageManager.buildRequestPermissionsIntent`/`getPermissionControllerPackageName` 並宣告隱藏的 `Context.getPermissionRequestState`，使 `requestPermissions` 能被容器接管。宿主宣告的權限會彈出真實系統對話框，結果依套件持久化；MCP `space_permissions` 支援 list/grant/revoke。
- 完整 VPMS：`VirtualPackageRegistry` 從 `state.json` 建立輕量索引，依需求解析同虛擬空間其他 APK；`getInstalledPackages`/`getInstalledApplications`、顯式元件、隱式 Intent、`resolve*`/`query*`、標籤/圖示/Resources/啟動 Intent/簽章/權限宣告均涵蓋跨虛擬應用程式。其他應用程式使用穩定合成 UID。
  - 效能限制：清單/標籤/UID/簽章走輕量索引；未指定套件的隱式 Intent 不掃描所有虛擬應用程式，只有顯式套件/元件查詢才解析對應 APK。否則高資源目標應用程式啟動時解析另一個大型 APK 會阻塞主執行緒。
- 相容性限制：`WindowManager` 動態代理只在實際需要 Toast 套件名稱改寫的目標內容中啟用。全域代理可能被目標 native SDK 傳入 JNI，CheckJNI 回報 `invalid jobject` 並中止容器處理程序。
- 多執行個體 `.so` 隔離：同套件不同執行個體分配不同容器槽位；原生程式庫依執行個體目錄擷取；容器槽位重用時先殺掉舊的槽位處理程序，避免同名 `.so` 與 JNI 全域狀態混用。

**其他元件：**

- BroadcastReceiver：靜態/動態註冊；靜態 receiver 需要在 VPMS 裡解析，動態註冊 hook `registerReceiver`；宿主端橋接見 Stage C-D。
- JobScheduler/通知已做容器內虛擬化（見上）；WorkManager 若走自身的 `SystemJobScheduler` 會使用容器 JobScheduler，依賴系統喚醒的持久背景任務仍會退化。`startForeground` 前景服務仍可能觸達宿主 AMS。

**背景啟動 Activity：**

- Android 10+ 限制背景啟動；宿主必須在前景，或有通知/前景服務/使用者互動。
- 方案：`SpaceLaunchActivity` 透明前景 Activity + 通知；或在使用者點擊時啟動；不要假設背景 Agent 隨時能拉起介面。
- Android 14+ 對 `PendingIntent` 背景啟動還有額外限制，需逐版本驗證。

### 3.4 ClassLoader / Resources

**ClassLoader：**

- 用 `DexClassLoader`/`PathClassLoader` 載入目標 APK 的 dex（base + splits）。
- 父 ClassLoader：宿主 ClassLoader（能共用 AndroidX/Kotlin 執行環境）或 `ClassLoader.getSystemClassLoader()`；注意類別衝突（目標應用程式自帶同名程式庫）。
- 多 dex：`BaseDexClassLoader` 支援 dex 清單；split 的 dex 都要加入。
- 目標應用程式的 `Application`/`Activity` 類別透過目標 ClassLoader 載入。
- `LoadedApk` 建構：反射建立/填入 `mApplicationInfo`、`mClassLoader`、`mResources`、`mAppDir`、`mNativeLibraryDir`、`mDataDir`；註冊到 `ActivityThread.mPackages`。

**Resources：**

- `AssetManager` 加入 base + 所有 split 的資源路徑。
- 用目標 `DisplayMetrics`/`Configuration` 建立 `Resources`；設定 `Resources.Theme`。
- 處理 `resources.arsc` 的 split/overlay；資源 id 衝突要依 split 合併。
- `Context.getResources()`/`getAssets()`/`getTheme()` 傳回目標資源。
- 圖示/名稱：從目標 `ApplicationInfo`/`Resources` 讀取，用於虛擬空間 UI。

**Application：**

- 由 `app` 的 `TargetApplicationFactory` 建立目標 Application，並處理一般、Tinker 與 ApplicationLike 啟動鏈。
- `attachBaseContext(targetContext)` → `onCreate()`。
- 目標應用程式的 `ContentProvider` 初始化順序要模擬（`installContentProviders`）。

### 3.5 Binder / UID 虛擬化（最難）

**目標：**

- 目標應用程式呼叫 `Binder.getCallingUid()`/`Process.myUid()`/`UserHandle.myUserId()` 時，看到虛擬 UID/user。
- 目標應用程式呼叫系統服務時，系統認為它是宿主 UID（現實），但 App 端以為自己是虛擬 UID。

**Java 層能做的：**

- Hook `Process.myUid()`、`Binder.getCallingUid()`、`UserHandle.myUserId()`、`Process.myUserHandle()` 的傳回值。
- Hook `PackageManager.getPackagesForUid`/`getNameForUid`/`getPackageUid` 傳回虛擬結果。
- Hook `Context.getUserId()`/`getPackageName()`/`getOpPackageName()`。

**做不到的：**

- 系統服務端（AMS/PMS/AccountManager/Location/Notification）仍依宿主 UID 判定權限/AppOps；你無法在不改系統的前提下讓系統認一個不存在的 UID。
- Binder 交易裡的 `uid`/`pid` 是核心填入的；Java 層改不了。要改只能 native hook `Binder.execTransact`/`Parcel`，風險極高。
- 因此：**應用程式級容器無法提供真正的 UID 隔離**；它只能「騙 App 的 Java 層」，不能「騙系統」。

**建議：**

- 第一版不做 UID 虛擬化；只做 package/component 虛擬化。
- 第二版對 `Process.myUid`/`Binder.getCallingUid` 做 Java hook，涵蓋常見檢查。
- 第三版再評估 native Binder hook；不要作為 MVP 前置條件。

### 3.6 IO / 資料隔離

**要重新導向的路徑：**

- `Context.getFilesDir()` / `getCacheDir()` / `getDir()` / `getDatabasePath()` / `getSharedPreferences()`
- `ContextImpl.getDataDir()` / `getDatabasesDir()` / `getSharedPreferencesPath()`
- `SQLiteDatabase.openDatabase` / `openOrCreateDatabase`
- `Environment.getExternalStorageDirectory()` / `getExternalFilesDir()`
- `System.loadLibrary` / `System.load` / `dlopen`
- `Runtime.exec` 的工作目錄（選用）

**實作：**

- 給目標應用程式一個 `ContextWrapper`，覆寫上述方法，傳回執行個體目錄。
- 對靜態/內部呼叫，hook `ContextImpl` 對應方法。
- 對 `SharedPreferencesImpl`/`SQLiteDatabase` 的直接路徑呼叫，hook 開啟方法。
- 對 native 檔案存取，native hook `open`/`fopen`/`stat`（選用，後期）。
- 每個執行個體一個根目錄：`/data/user/0/<host>/spaces/<spaceId>/instances/<instanceId>/<pkg>/`。

**注意：**

- 這不是安全隔離；native 程式碼/絕對路徑可以繞過。
- 外部儲存/MediaStore 的隔離有限；產品上要說明。
- 備份/還原只涵蓋允許的目錄。

### 3.7 權限 / AppOps / 通知

**權限：**

- 目標應用程式的 `checkPermission`/`checkCallingPermission` 走虛擬權限管理員：先查目標應用程式的 manifest 權限，再查宿主是否已授予對應系統權限，最後依產品策略傳回。
- 執行階段權限：hook `Activity.requestPermissions`/`Fragment.requestPermissions`，宿主彈出視窗；使用者允許後記錄虛擬授權，並在需要時向系統申請宿主權限。
- `AppOpsManager`：目標應用程式的操作會依宿主套件名稱/UID 記錄；如需精確，hook `AppOpsManager` 的 Java 方法（有限）。
- 權限對應表要維護：目標權限 → 宿主權限 → 是否必須。

**通知：**

- 目標應用程式的 `NotificationManager.notify` 需要改寫 `Notification` 的 package/tag/id，或用宿主套件名稱發通知並保留目標圖示/文案。
- `PendingIntent` 的 package/UID 要對應回虛擬元件；Android 12+ 通知 trampoline 限制。
- Android 13+ `POST_NOTIFICATIONS` 執行階段權限由宿主申請。
- 第一版可以先不支援目標通知；提供「虛擬空間內通知中心」作為替代。

### 3.8 處理程序模型 / phantom process

- 用宿主宣告的 `:c1..:c16` 槽位處理程序，不用 `app_process` 子處理程序。
- `StubActivity`/`VClientService` 宣告在 `:c1..:c16`，啟動時系統建立正常 app 處理程序。
- 槽位上限依裝置記憶體自動計算，最多 16 個；固定模式使用使用者選擇的 1–16。
- 處理程序重用前清理：目標 Activity/Service、ClassLoader、Resources、靜態狀態、檔案鎖。
- 處理程序當機：標記 dirty，重新啟動；宿主 UI 顯示「空间已重启」（空間已重新啟動）。
- 前景服務保持運作：宿主主處理程序 + 目前虛擬空間處理程序；避免所有處理程序常駐。
- Android 12+ 的 phantom process killer 不適用於正常 app 處理程序，但背景處理程序仍會被 LMK 殺掉；用前景服務/使用者可見狀態。

### 3.9 原生程式庫 / 16 KB

- 安裝時依 ABI 選擇 split；把 `.so` 解壓縮到執行個體 `lib/` 目錄。
- `ApplicationInfo.nativeLibraryDir` 指向該目錄；`LoadedApk`/`ClassLoader` 使用它。
- Hook `System.loadLibrary`/`System.load`：依目標應用程式的 `nativeLibraryDir` 解析。
- 16 KB page：檢查 ELF `p_align`；不支援則拒絕/提示。
- `dlopen` 命名空間：目標 `.so` 依賴的系統程式庫由 linker 解析；自有 `.so` 用絕對路徑。
- JNI：目標應用程式的 `System.loadLibrary` 和 `RegisterNatives` 通常在目標 ClassLoader 下運作；注意全域 JNI 狀態衝突。
- 不要在同一個處理程序載入兩個版本不相容的同一個 `.so`（多執行個體可能衝突）；必要時依處理程序隔離。

目前實作（Stage C-D）：

- `ApkInspector` 掃描 base/split 中的 `lib/<abi>/*.so`，得到可用 ABI 集合。
- `NativeLibs` 依 `Build.SUPPORTED_ABIS` 選第一個相符項目，把該 ABI 的 `.so` 平鋪擷取到 `vspace/data/<pkg>/<instance>/native-libs`，用 stamp 快取避免每次啟動重複複製。
- `DexClassLoader(dexPath, codeCache, nativeLibsDir, parent)`，並設定 `ApplicationInfo.nativeLibraryDir`。
- 安裝不因 ABI 失敗；啟動前檢查，若無相符 ABI 傳回 `ABI_MISMATCH` 並提供裝置/應用程式 ABI。僅 arm64 的 APK 在 x86_64 模擬器上會停在這裡。
- `dlopen`/`android_dlopen_ext` hook 會把新載入模組送入增量掃描佇列。
- 同套件不同執行個體使用不同槽位；重用槽位前先結束舊處理程序，避免同名 `.so` 與 JNI 全域狀態混用。
- 通用 ELF `p_align` 預檢仍不是強制門檻；16 KB 相容性由建置參數、裝置測試和執行階段診斷共同涵蓋。

### 3.10 背景 / Job / Provider / WebView

這些是「產品化」繞不過去的困難點，建議分階段：

| 能力 | 難度 | 第一版策略 |
|---|---|---|
| JobScheduler | 高 | 不支援；目標應用程式的背景任務退化 |
| AlarmManager | 高 | 部分支援；依宿主套件名稱排程，喚醒後轉發 |
| ContentProvider | 高 | 先不支援跨 App provider；內部 provider 嘗試 hook |
| 靜態 BroadcastReceiver | 中 | VPMS 解析 + 宿主 receiver 轉發 |
| 通知 | 中 | 第一版不做；後續代理 |
| WebView | 中 | `WebView.setDataDirectorySuffix` 是處理程序級；多執行個體需獨立處理程序 |
| 帳號/同步 | 高 | 不承諾 |
| 桌面捷徑 | 中 | 宿主 shortcut 指向 StubActivity |

### 3.11 Hook / 版本調適

- **Java hook 點**：`ActivityThread`、`Instrumentation`、`LoadedApk`、`ApplicationPackageManager`、`ContextImpl`、`Binder`、`Process`、`UserHandle`、`ResourcesManager`。
- **Native hook 點**：`Binder.execTransact`、`Process.myUid`、`open`/`fopen`、`dlopen`/`android_dlopen_ext`。
- **版本調適**：每個 API level 一個 `AndroidAdapter`；hook 點缺失時降級/停用功能。
- **漸進式發布**：依裝置/Android 版本/目標應用程式開關；線上當機可回滾。
- **測試**：每個 Android 版本至少一台實機；AOSP/Pixel 優先，OEM 後補。

### 3.12 安全邊界

- 應用程式級容器共用宿主 UID；惡意 App 可以繞過 Java 層重新導向讀取宿主/其他執行個體資料。
- 建議只安裝自己熟悉的 App，不要把容器當作隔離不可信應用程式的手段。
- Agent 能看到螢幕敏感資訊；預設去識別化、預設不記錄密碼/支付。
- 提示注入：螢幕文字是不可信輸入；工具允許清單 + 危險操作確認。
- MCP 預設 loopback；LAN 模式明確開啟 + token（目前 HTTP，TLS 待補）。

---

## 4. 控制層

### 4.1 控制通路

| 通路 | 前提 | 觀測 | 動作 | 延遲 | Android 17 風險 | 適用 |
|---|---|---|---|---|---|---|
| **Shizuku + shell** | 安裝 Shizuku，無線偵錯啟動 | `uiautomator dump`、`screencap` | `input tap/swipe/text/keyevent` | 100–500 ms | 不受 AAPM 影響 | **主力（如果使用者願意安裝 Shizuku）** |
| **Shizuku + UiAutomation** | 同上，UserService 內執行 UiAutomation | 節點樹、截圖 | 注入事件 | 50–150 ms | 不受 AAPM 影響 | 低延遲情境 |
| **AccessibilityService** | 使用者手動開啟 | 節點樹、事件 | `dispatchGesture`、`ACTION_SET_TEXT` | 50–100 ms | AAPM 會限制非無障礙類 App | **主力（如果不想安裝 Shizuku）** |
| **Vision + 座標** | 截圖權限 | 像素 | 座標點擊（需另一通路注入） | 1–3 s | 不受 AAPM 影響 | 遊戲/Canvas/Flutter 備援 |
| **容器 in-process** | 自研 hook | 目標 View 樹（可行時） | 直接呼叫/注入 | 最低 | 不受 AAPM 影響 | 虛擬空間操作；UI 控制待驗證 |

**建議：**

- 虛擬空間操作走 `ContainerService`（安裝/啟動/停止/多開）。
- UI 控制做兩個 adapter：`ShizukuChannel` 和 `AccessibilityChannel`，依可用性自動選擇。
- Vision 只做備援。
- 容器 in-process 只用於自研引擎內部能穩定取得的介面，不作為第一版 UI 控制主路徑。

### 4.2 觀測協定

```json
{
  "snapshot_id": "s_123",
  "space_id": "sp_1",
  "package": "com.example.app",
  "activity": ".MainActivity",
  "screen": { "width": 1080, "height": 2400, "rotation": 0 },
  "nodes": [
    {
      "id": "n_1",
      "role": "button",
      "text": "搜索",
      "desc": null,
      "bounds": [820, 120, 1040, 200],
      "clickable": true,
      "editable": false,
      "scrollable": false,
      "enabled": true,
      "focused": false,
      "selected": false
    }
  ],
  "screenshot": "sha256:...",
  "timestamp_ms": 1730000000000
}
```

建議：

- 節點扁平化，只保留可見/可點擊/可編輯/有文字/有描述的節點。
- 預設 `max_nodes=120`；需要時 `detail=full`。
- 截圖預設不傳送；需要視覺時再取得。
- 節點 id 單次 snapshot 內穩定；跨 snapshot 用 `(package, activity, viewId, text, bounds bucket)` 近似比對。
- 截圖來源：AccessibilityService.takeScreenshot → MediaProjection → PixelCopy。

### 4.3 動作協定

| 動作 | 參數 | 說明 |
|---|---|---|
| `tap` | node_id / bounds / text | 優先 node_id |
| `long_press` | 同上 + duration | 長按選單 |
| `swipe` | from/to/duration | 滑動/翻頁 |
| `scroll` | direction/amount | 優先 `ACTION_SCROLL_FORWARD` |
| `set_text` | node_id/text | `ACTION_SET_TEXT` → 剪貼簿貼上 → 逐鍵輸入 |
| `key` | keycode | back/home/recents/enter/delete |
| `launch_app` | package/activity | 走 ContainerService |
| `wait_for` | text/package/activity/idle | 帶逾時 |
| `intent` | action/data/extras | 深層連結/系統 Intent |
| `clipboard` | get/set | 敏感操作要確認 |

執行後必須：重新 observe → 驗證預期變化 → 失敗重試一次 → 再失敗走視覺/人工。

### 4.4 視覺備援

適用於遊戲、Flutter、Canvas、部分 WebView（節點樹為空/不可靠）。

1. 截圖降取樣到 720p/1080p。
2. 多模態模型輸出座標/區域，或 ML Kit OCR + 範本比對。
3. 座標正規化到螢幕比例。
4. 透過 Shizuku/無障礙注入點擊。

視覺是備援，不是主路徑；成本和延遲都高。

### 4.5 錄製 / 回放 / 技能

- 錄製：`(action, snapshot_hash, timestamp, result)`。
- 產生：穩定序列轉成帶斷言的 YAML/JSON 指令碼。
- 回放：每步檢查前置條件；不滿足則回退 Agent。
- 技能庫：依 App 組織，如 `app.send_message`、`settings.open_wifi`。
- 技能是降低 token 成本的關鍵：常見任務走指令碼+驗證，不走大型模型。

### 4.6 Android 17 Advanced Protection Mode（AAPM）

- 開啟 AAPM 後，非無障礙類 App 無法使用 AccessibilityService，已有權限會被撤銷。
- 應對：
  1. 控制層做成雙 adapter：Shizuku 優先，無障礙次之，Vision 備援。
  2. 偵測 `AdvancedProtectionManager`，提示使用者「關閉 AAPM 或使用 Shizuku 模式」。
  3. 不要把「無障礙自動化」當作唯一賣點。
  4. 上架 Google Play 時，核心功能要能在不依賴無障礙的情況下運作。

### 4.7 安全邊界

- 容器共用宿主 UID；建議只安裝自己熟悉的 App。
- Agent 能看到螢幕敏感資訊；預設去識別化，預設不記錄密碼/支付頁面。
- 提示注入：螢幕文字是不可信輸入；工具允許清單 + 危險操作確認。
- MCP 預設 loopback；LAN 模式明確開啟 + token（目前 HTTP，TLS 待補）。
- 所有安裝/卸載/支付/權限變更二次確認並稽核。

---

## 5. 雙通道 Agent / MCP

### 5.1 兩種控制通道（都要支援）

| 通道 | Agent 在哪 | 模型 | 連線方式 | 優點 | 缺點 |
|---|---|---|---|---|---|
| **本機 Agent** | 手機 App 內 | 裝置端（Gemini Nano 4 / Gemma 4 / LiteRT-LM）或雲端（Claude API） | 處理程序內直接呼叫 `ToolRegistry` | 獨立、離線（裝置端模型）、低延遲 | 本機模型能力/機型受限；雲端模型需網路 |
| **遠端 Agent** | 手機外（Claude Desktop/Code/雲端） | 由遠端 Agent 決定 | MCP over Streamable HTTP（USB/LAN） | 模型能力最強、容易除錯 | 需要 PC/網路；暴露介面需身分驗證 |

兩條通道不是二選一，而是共用同一套工具和權限模型：

- 本機 Agent：Agent 迴圈在 App 內，直接呼叫 `ToolRegistry`。
- 遠端 Agent：Agent 迴圈在手機外，透過 MCP adapter 呼叫同一套 `ToolRegistry`。
- App 對兩者都是「工具執行器 + 虛擬空間控制器」。

> 「本機 Agent」指 Agent 迴圈在手機上，不代表模型一定在手機上：模型可以是 Gemini Nano 4（裝置端），也可以是 Claude API（雲端）。兩者都透過 `ModelAdapter` 接入。

### 5.2 統一工具層

```kotlin
data class ToolSpec(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val mutating: Boolean,
    val requiredScope: Scope
)

class ToolRegistry(
    private val tools: Map<String, RegisteredTool>,
    private val leaseManager: LeaseManager,
    private val approvalGate: ApprovalGate,
    private val audit: AuditLog
) {
    fun list(): List<ToolSpec>
    suspend fun execute(
        name: String,
        args: JsonObject,
        ctx: ToolContext
    ): ToolResult
}

data class ToolContext(
    val sessionId: String,
    val caller: Caller,        // LOCAL_AGENT / REMOTE_MCP / USER
    val spaceId: SpaceId,
    val scope: Scope
)
```

- MCP adapter：把 `ToolSpec` 對應成 MCP `tools/list` + `tools/call`，JSON Schema 驗證。
- 本機 Agent adapter：把同一份 `ToolSpec` 轉成 Gemini/Claude 的 function declaration，執行仍走 `ToolRegistry`。
- 兩邊共用：參數驗證、錯誤碼、核准、租約、稽核、軌跡。新增工具只寫一次。

### 5.3 MCP Server（遠端通道）

> 本機 Agent 不走 MCP，直接呼叫 `ToolRegistry`；但兩者的工具定義、權限、租約、稽核完全一致。MCP Server 只是遠端 Agent 的傳輸轉接層。

實作策略：

- **基礎版**：內建 `ServerSocket` 最小 HTTP server（`POST /mcp`），只用 `java.net`/`java.io`，相容 API 24/25。**不要用 Ktor 3.x**：它依賴 `java.nio.file`（API 26+），在 Android 7.x 會啟動失敗。
- **正式版**：官方 MCP Kotlin SDK（`io.modelcontextprotocol:kotlin-sdk-server`）+ Ktor 3.x；此時 minSdk 需要 ≥ 26，或做相容處理。
- **Transport**：Streamable HTTP（`/mcp`），支援 POST、GET SSE 心跳和 DELETE 工作階段；不用 stdio（Android 沒有 stdio host）。
- **狀態**：每個用戶端獨立 `Mcp-Session-Id`，30 分鐘閒置回收；未知/過期工作階段傳回 404；CORS 供 MCP Inspector 除錯。
- **限制**：請求主體上限 40 MB、請求標頭 64 KB、並行連線 32；身分驗證在讀取 body 前完成，Bearer Token 使用常數時間比較。

Android 端：

- 前景服務（`dataSync` 或 `specialUse`）+ 常駐通知。
- 電池最佳化允許清單；Doze 下保持連線。
- 預設綁定 `127.0.0.1:8765`；首次啟動產生隨機 token。
- LAN 模式：明確開關 + token；目前實作為 HTTP（TLS 待補），Android 17 需要 `ACCESS_LOCAL_NETWORK`。儲存庫提供零依賴 CLI：`tools/nestspace-mcp`。

連線：

```bash
# USB（建議）
adb forward tcp:8765 tcp:8765

# Claude Code
claude mcp add --transport http space http://127.0.0.1:8765/mcp \
  --header "Authorization: Bearer <token>"
```

### 5.4 本機 Agent Runtime

- `AgentLoop`：observe → plan → tool call → verify → repeat；與遠端 Agent 使用同一套工具。
- `ModelAdapter` 三選一：
  - `GeminiNanoAdapter`：ML Kit GenAI Agent API（Alpha；裝置支援有限）。
  - `LiteRTAdapter`：Gemma 4 / 自訂模型（離線，資源需求高）。
  - `ClaudeAdapter`：手機直接呼叫 Anthropic Messages API（能力最強，需網路/key）。
- 前景服務 + 通知；長時間任務可取消；螢幕關閉時保持 wake lock。
- UI：任務輸入、即時軌跡、核准彈出視窗、緊急停止。
- 失敗/逾時：回退到遠端 Agent 或人工。

已實作（NestSpace 0.3）：

- **共用 chat 協定**（參考 go-agent-sdk 的 `chat.Service` / `Messages` / `Block` 抽象）：
  - `ChatMessage` = role + `List<ChatBlock>`；`ChatBlock` = `Text` / `ToolUse` / `ToolResult`。
  - `ChatService.chat(ChatRequest): ChatResponse` 是 provider 邊界；`ChatRequest` 攜帶 model/system/messages/tools/maxTokens/temperature。
  - `AnthropicChatService` 與 `OpenAiChatService` 只負責共享模型與各自 wire JSON 的互轉。
  - `LlmAgentRuntime` 只有一份 provider-neutral 迴圈：呼叫模型 → 收集 `ToolUse` → 執行 `ToolRegistry` → 回傳 `ToolResult` → 直到沒有工具呼叫。
  - 對話使用 `ChatService.streamChat` 的 SSE 串流輸出；Anthropic 與 OpenAI 相容端點都依增量事件組裝文字和工具呼叫，Agent 迴圈不變。
- 「设置」（設定）→ **「大模型 / 本地 Agent」（大型語言模型 / 本機 Agent）** 提供兩種協定；設定儲存在應用程式私有目錄，API Key 透過 Android Keystore 加密儲存。
- **Claude 相容**：`POST {base}/v1/messages`，header `x-api-key` + `anthropic-version`，原生 `tools` / `tool_use` / `tool_result`。
- **OpenAI 相容**：`POST {base}/v1/chat/completions`，`Authorization: Bearer`，原生 `tool_calls` / role=`tool` 訊息。
- 已移除第三方自訂範本協定；舊版本儲存的 `CUSTOM` 設定會被忽略並回退到 Claude 預設值，避免殘留的自訂 endpoint 混入新協定。
- `LlmAgentRuntime` 重用 `ToolRegistry`，`ToolContext(sessionId, LOCAL_AGENT, "virtual-1")`，排除 session/approval 工具，最多 16 輪工具呼叫，單次工具結果截斷 12k 字元。
- 未設定或沒有可用模型時，助手會直接提示前往模型設定，不執行規則命令或工具呼叫。
- `AssistantConversation` 統一維護助手頁與懸浮球語音助手共用的訊息流和最近 24 輪文字對話；`LlmAgentRuntime.run(history = ...)` 在每次請求前重播歷史，實現多輪追問。語音入口預設隱藏文字輸入，最終辨識結果自動提交，回覆後自動恢復聆聽。
- 語音辨識使用內建 Vosk 離線模型（`com.alphacephei:vosk-android`），不經過雲端 STT。模型在首次使用時下載，也可在懸浮球設定中管理；下載前平行存取 HF 中國鏡像、Hugging Face 和 Vosk 官方網站首頁測速，選擇回應最快的線路，失敗自動切換。中文/English 小模型分別快取，模型就緒後 `SpeechService` 持續提供 partial/final 結果。
- `LocalSpeechEngine` 在處理程序內快取已載入的 `Model`；下載完成立即背景載入，App 啟動時若模型已快取也會預先載入，語音頁只建立 `Recognizer`/`SpeechService`，開啟即可聆聽。切換語言時先載入新模型；舊模型保留到處理程序結束，避免暫停中的語音頁仍持有舊 Recognizer 時被提前釋放。
- 已用本機 mock 分別驗證通過兩種協定的核心轉換與工具呼叫往返；使用者設定的 Anthropic 相容端點在模擬器真實網路驗證通過 `space_list` + `space_list_apps` 多輪工具呼叫。
- 單元測試：`app/src/test/java/com/coke/nestspace/llm/AnthropicChatServiceTest.kt`、`OpenAiChatServiceTest.kt`。

### 5.5 工作階段與租約

```kotlin
enum class Caller { LOCAL_AGENT, REMOTE_MCP, USER }
enum class LeaseMode { EXCLUSIVE, OBSERVE }

data class ControlLease(
    val spaceId: SpaceId,
    val leaseId: String,
    val owner: Caller,
    val sessionId: String,
    val mode: LeaseMode,
    val expiresAt: Long
)
```

規則：

- 修改類工具（tap/type/launch/install/uninstall）必須先取得 `EXCLUSIVE` 租約。
- 觀察類工具（observe/screenshot）可以共用。
- 另一個通道呼叫修改類工具時傳回 `SPACE_BUSY`（帶 holder 資訊），可排隊/等待/請求使用者接管。
- 使用者可以在 UI 強制回收租約。
- 租約閒置逾時自動釋放；MCP 中斷連線/本機任務結束立即釋放。
- 所有租約變更寫入稽核記錄。

### 5.6 核准流程

| 情境 | 本機 Agent | 遠端 MCP |
|---|---|---|
| 支付/卸載/權限/安裝 | App 內彈出視窗確認 | `approval_request` + 手機通知；遠端 Agent 輪詢 `approval_wait` |
| 唯讀觀察 | 預設允許 | 預設允許（需 token scope） |
| 高風險 shell | 預設關閉 | 預設關閉；需使用者開啟 + 二次確認 |

- 核准要求進入同一個 `ApprovalGate`，UI 顯示「本地 Agent 请求 / 远程 Agent 请求」（本機 Agent 要求 / 遠端 Agent 要求）。
- 核准結果寫入稽核；逾時預設拒絕。

### 5.7 工具清單

**虛擬空間管理**

| 工具 | 用途 |
|---|---|
| `space_list` | 列出虛擬空間及狀態 |
| `space_create` | 建立虛擬空間 |
| `space_delete` | 刪除虛擬空間 |
| `space_install_apk` | 安裝 APK/split 到虛擬空間 |
| `space_uninstall` | 卸載 |
| `space_list_apps` | 列出虛擬空間內 App |
| `space_launch` | 啟動 App/Activity |
| `space_stop` | 停止 App |
| `space_status` | 虛擬空間/前景 App 狀態 |

**UI 控制**

| 工具 | 用途 |
|---|---|
| `ui_observe` | 傳回節點樹（可選截圖） |
| `ui_screenshot` | 傳回截圖 |
| `ui_tap` / `ui_long_press` | 點擊/長按 |
| `ui_swipe` / `ui_scroll` | 滑動/捲動 |
| `ui_set_text` | 輸入文字 |
| `ui_key` | 按鍵（back/home/recents/enter） |
| `ui_wait_for` | 等待條件 |
| `ui_find` | 在最近 snapshot 中尋找節點 |

**應用程式/系統**

| 工具 | 用途 |
|---|---|
| `app_intent` | 傳送 Intent/深層連結 |
| `sys_shell` | Shizuku shell（預設關閉，命令允許清單） |
| `sys_logcat` | 讀取 logcat（依套件/pid） |
| `sys_clipboard_get/set` | 剪貼簿（敏感，需確認） |

**AppFunctions 快路徑**

| 工具 | 用途 |
|---|---|
| `appfn_status` | 回報 Android 16+/AppFunctionManager/EXECUTE_APP_FUNCTIONS 可用性 |
| `appfn_list` | 列出目標套件公開的 AppFunctions（宿主/容器內） |
| `appfn_execute` | 執行 AppFunction；失敗/不可用時回退 UI 自動化 |

**工作階段/核准**

| 工具 | 用途 |
|---|---|
| `session_acquire` | 取得虛擬空間獨占/觀察租約 |
| `session_release` | 釋放租約 |
| `session_status` | 查看目前 holder |
| `approval_request` | 請求人工確認 |
| `approval_wait` | 等待核准結果 |

**任務/技能**

| 工具 | 用途 |
|---|---|
| `task_run` | 本機 Agent 執行自然語言任務 |
| `task_status` / `task_cancel` | 查詢/取消 |
| `skill_list` / `skill_run` | 技能清單/執行 |
| `trajectory_get` | 取得執行軌跡 |

設計原則：

- 所有工具帶 `space_id`（`space_list`/`space_create` 除外）。
- 傳回結構化錯誤：`{code, message, retryable, hint}`。
- 安裝/啟動冪等；`idempotency_key` 防重複。
- 危險操作必須 `confirm=true` 或人工確認。
- `ui_observe` 預設 `max_nodes=120`、不傳回截圖。
- `sys_shell` 預設不暴露；開啟後僅能執行允許清單中的命令。

### 5.8 工具 schema 範例

```json
{
  "name": "space_install_apk",
  "description": "Install an APK or split APK set into a space. Validates REQUIRE_SECURE_ENV, ABI, and 16KB alignment before install.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "space_id": { "type": "string" },
      "apk_paths": {
        "type": "array",
        "items": { "type": "string" },
        "description": "Absolute paths or content URIs. For split APKs, include base + all splits."
      },
      "grant_runtime_permissions": { "type": "boolean", "default": false },
      "idempotency_key": { "type": "string" }
    },
    "required": ["space_id", "apk_paths"]
  }
}
```

### 5.9 模型選擇

截至 2026-10，Anthropic 第一方 API 價格（美元/百萬 token，輸入/輸出）：

| 模型 | Model ID | 價格 | 建議用途 |
|---|---|---|---|
| Claude Opus 5.5 | `claude-opus-5-5` | $4 / $20 | 預設；複雜規劃、長任務 |
| Claude Sonnet 5.5 | `claude-sonnet-5-5` | $2 / $10 | 性價比/速度 |
| Claude Haiku 5.5 | `claude-haiku-5-5` | $0.10 / $0.50 | 簡單分類、等待、摘要 |
| Claude Fable 5.1 | `claude-fable-5-1` | $10 / $50 | 最難任務；成本高 |

裝置端模型：

- **Gemini Nano 4 / ML Kit GenAI Agent API**（Alpha）：裝置端工具呼叫迴圈；裝置支援有限。
- **Gemma 4 + LiteRT-LM**：可自帶微調模型；整合/效能成本高。

建議：遠端 Agent 用 Claude Opus 5.5；本機 Agent 第一版先用 `ClaudeAdapter` 驗證迴圈可行，再接 Gemini Nano 4 / Gemma 4；簡單子步驟用 Haiku 5.5。

### 5.10 Agent 迴圈與失敗處理

```text
state = observe(space)
for turn in 1..MAX_TURNS:
    decision = model.decide(goal, state, tools, history)
    if decision.done: return result
    if decision.needs_user_confirmation:
        approval = approvalGate.request(decision)
        if not approval.approved: return cancelled
    result = execute(decision.tool, decision.args)
    state = result.observation ?? observe(space)
    history.append(compact(result))
```

要點：

- 系統提示寫清楚：角色、可用 App、工具語意、安全規則、輸出格式。
- 上下文：緊湊節點樹 + 依需求截圖 + 技能提示 + 歷史摘要。
- 限制輪數、token、單步逾時；支援取消。
- 每步驗證；連續 2 次失敗 → 重新規劃；3 次 → 人工接管。
- 失敗處理：

| 失敗 | 處理 |
|---|---|
| 找不到節點 | 重新 observe；文字/座標備援 |
| 點擊無效 | 檢查可點擊；座標；等待動畫 |
| 輸入失敗 | `ACTION_SET_TEXT` → 剪貼簿 → 逐鍵輸入 |
| App 當機/ANR | 殺掉處理程序重新啟動；備用路徑；人工 |
| 權限彈出視窗 | 辨識系統彈出視窗；依策略允許/拒絕/詢問 |
| 載入緩慢 | `wait_for` + 逾時 + 重試 |
| 驗證失敗 | 回退上一步；重新規劃 |

### 5.11 AppFunctions 快路徑（宿主 / 容器內 APK）

Android 16+ 的 AppFunctions 是「系統級工具註冊」：App 用 `@AppFunction` 宣告能力，KSP 產生 service/XML schema，系統註冊；Agent 透過 `AppFunctionManager` 探索/執行。

**三種情況，權限和能力不同：**

| 情境 | 能否使用 | 說明 |
|---|---|---|
| **宿主應用程式自行公開 AppFunctions** | ✅ 可以 | 宿主是 function owner，可以執行自己的函式；系統 Agent/Gemini 也能探索 |
| **容器內 APK 公開 AppFunctions** | ✅ 需要本機橋接 | 虛擬套件沒有註冊到系統，`AppFunctionManager` 看不到；安裝時解析 AppFunctions 中繼資料，在容器處理程序內呼叫產生的 service/entry point |
| **系統裡其他 App 的 AppFunctions** | ⚠️ 通常不行 | 呼叫方需要 `EXECUTE_APP_FUNCTIONS`，是特權/系統級權限；一般 APK 通常拿不到 |

**架構：**

- `ToolSource` 抽象：`BuiltinToolSource`（虛擬空間/UI 工具）、`AppFunctionsToolSource`（宿主/容器 AppFunctions）、`SkillToolSource`。
- `ToolRegistry` 彙整工具，命名空間 `appfn.<package>.<functionId>`；同名衝突用套件名稱區分。
- Agent 規劃時優先快路徑：`appfn_execute` → 不可用/失敗 → UI 自動化。
- 容器引擎在安裝 APK 時解析其 AppFunctions XML/metadata；如果存在，註冊到 `AppFunctionsToolSource`；執行時在容器處理程序內呼叫，避免依賴系統註冊。
- 宿主應用程式也可以用 `@AppFunction` 匯出 `space.list/install/launch`、`ui.observe/tap`，讓系統 Agent/Gemini 直接呼叫宿主。這是「匯出」，不是「匯入」。

**權限/合規：**

- 不要假設一般 APK 能呼叫第三方 App 的 AppFunctions；`EXECUTE_APP_FUNCTIONS` 是 privileged。
- 宿主匯出的 AppFunctions 仍然要走租約/核准/稽核；不要繞過安全模型。
- 容器內 AppFunctions 仍受容器的 Binder/UID 限制；依賴系統服務的函式可能失敗。
- AppFunctions 是可選快路徑，不是唯一控制面；失敗必須回退 UI 自動化。

**基礎版：**

- 已加入 `AppFunctionsBridge` 偵測（API 36、`AppFunctionManager`、權限）與 `appfn_status/list/execute` 工具預留位置。
- 宿主匯出、容器內本機橋接放在 Stage F。

---

## 6. 架構

### 6.1 分層圖

```text
┌──────────────────────────┐        ┌──────────────────────────┐
│ 本機 Agent Runtime       │        │ 遠端 Agent               │
│ (on-device loop)         │        │ (Claude Desktop/Code/雲) │
└────────────┬─────────────┘        └────────────┬─────────────┘
             │ ToolRegistry                      │ MCP Server
             │                                   │ (Streamable HTTP)
             └───────────────┬───────────────────┘
                             ▼
┌──────────────────────────────────────────────────────────────┐
│ UI (Compose): 虛擬空間 / 安裝器 / Agent 主控台 / 核准 / 軌跡 │
└──────────────────────────────┬───────────────────────────────┘
                               │
┌──────────────────────────────▼───────────────────────────────┐
│ Domain                                                       │
│  SpaceRepository  ControlRepository  AgentRepository         │
│  ToolRegistry  LeaseManager  ApprovalGate  AuditLog          │
│  AppFunctionsBridge（快路徑）                                │
└───────────────┬──────────────────────────────┬───────────────┘
                │                              │
┌───────────────▼──────────────┐  ┌────────────▼───────────────┐
│ ContainerEngine              │  │ ControlChannel             │
│  Installer / VPMS / VAMS     │  │  ShizukuChannel            │
│  ClassLoader / Resources     │  │  AccessibilityChannel      │
│  Binder / IO / ProcessPool   │  │  VisionChannel             │
└───────────────┬──────────────┘  └────────────┬───────────────┘
                │                              │
┌───────────────▼──────────────────────────────▼───────────────┐
│ Infrastructure                                               │
│  宿主宣告的 :c1..:c16    | MCP Server | 本機 Agent           │
│  AccessibilityService / MediaProjection / Shizuku            │
│  Room / DataStore / WorkManager                              │
└──────────────────────────────────────────────────────────────┘
```

### 6.2 模組劃分

```text
app/
  src/main/java/com/coke/virtual/
    core/model/          # SpaceInfo, AppInfo, UiNode, UiSnapshot, Target, Condition
    core/space/          # SpaceBackend, SpaceRepository
    core/control/        # ControlChannel, AccessibilityService, ShizukuBridge
    core/tools/          # ToolRegistry, ToolSpec, validation, scopes
    core/session/        # ControlLease, SessionManager, ApprovalGate, AuditLog
    core/mcp/            # MCP server adapter (remote), auth
    core/agent/          # Shared AgentLoop, prompt, skills
    core/agent/model/    # ClaudeAdapter, GeminiNanoAdapter, LiteRTAdapter
    engine/installer/    # APK/split 解析、預檢、安裝
    engine/vpms/         # 虛擬 PackageManager
    engine/vams/         # 虛擬 ActivityManager、StubActivity
    engine/classloader/  # ClassLoader/LoadedApk
    engine/resources/    # Resources/AssetManager
    engine/binder/       # Binder/UID 虛擬化
    engine/io/           # 資料目錄重新導向
    engine/process/      # :c1..:c16 處理程序池、VClientService
    engine/hooks/        # Java/Native hook、版本調適
    fastpath/            # AppFunctions 偵測、宿主匯出、容器內本機橋接
    feature/spaces/      # Compose UI: 虛擬空間清單、App 網格
    feature/installer/   # APK 選擇、預檢、安裝進度
    feature/agent/       # Agent 主控台、軌跡、核准
    service/             # ForegroundService, AccessibilityService
```

### 6.3 關鍵介面

```kotlin
// 雙通道共用：本機 Agent 直接呼叫 ToolRegistry；遠端 Agent 透過 MCP adapter 呼叫同一個 ToolRegistry
data class ToolSpec(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val mutating: Boolean,
    val requiredScope: Scope
)

interface ToolRegistry {
    fun list(): List<ToolSpec>
    suspend fun execute(name: String, args: JsonObject, ctx: ToolContext): ToolResult
}

interface AgentRuntime {
    suspend fun run(goal: String, spaceId: SpaceId, sessionId: String): TaskResult
    fun cancel(sessionId: String)
}

data class ControlLease(
    val spaceId: SpaceId,
    val leaseId: String,
    val owner: Caller,
    val sessionId: String,
    val mode: LeaseMode,
    val expiresAt: Long
)

interface ContainerEngine {
    suspend fun install(spaceId: SpaceId, apk: ApkSource): InstallResult
    suspend fun uninstall(spaceId: SpaceId, packageName: String)
    suspend fun listApps(spaceId: SpaceId): List<AppInfo>
    suspend fun launch(spaceId: SpaceId, packageName: String, activity: String? = null): LaunchResult
    suspend fun stop(spaceId: SpaceId, packageName: String)
    suspend fun status(spaceId: SpaceId): SpaceStatus
}

interface ControlChannel {
    suspend fun observe(id: SpaceId, options: ObserveOptions = ObserveOptions()): UiSnapshot
    suspend fun screenshot(id: SpaceId): ImageBitmap
    suspend fun tap(id: SpaceId, target: Target)
    suspend fun longPress(id: SpaceId, target: Target, durationMs: Long = 800)
    suspend fun swipe(id: SpaceId, from: Point, to: Point, durationMs: Long = 300)
    suspend fun setText(id: SpaceId, target: Target, text: String)
    suspend fun key(id: SpaceId, key: Key)
    suspend fun waitFor(id: SpaceId, condition: Condition, timeoutMs: Long): Boolean
}
```

### 6.4 一次完整流程

> 遠端 Agent 走 MCP Server → ToolRegistry；本機 Agent 直接走 ToolRegistry。下面的 Engine/Control 步驟兩者相同。

```text
Agent                         MCP Server / ToolRegistry      Engine / Control
  │                              │                                │
  │ space_install_apk            │                                │
  │─────────────────────────────►│ 驗證 APK / REQUIRE_SECURE_ENV   │
  │                              │───────────────────────────────►│ Installer
  │                              │◄───────────────────────────────│ ok
  │ space_launch                 │                                │
  │─────────────────────────────►│───────────────────────────────►│ VAMS + ProcessPool
  │                              │◄───────────────────────────────│ foreground
  │ ui_observe                   │                                │
  │─────────────────────────────►│───────────────────────────────►│ dump tree
  │◄─────────────────────────────│ nodes + screenshot hash        │
  │ ui_tap(node_id=n_7)          │                                │
  │─────────────────────────────►│───────────────────────────────►│ tap
  │ ui_wait_for(text="結果")     │                                │
  │─────────────────────────────►│───────────────────────────────►│ poll
  │◄─────────────────────────────│ matched / timeout              │
```

### 6.5 目前專案改造建議

1. `core/model` + `core/tools` + `core/session`：模型、ToolRegistry、租約/核准先實作。
2. `service/SpaceAccessibilityService.kt` + `res/xml/accessibility_service_config.xml`：觀察/點擊。
3. `core/control/ShizukuBridge.kt`：接 Shizuku API（`dev.rikka.shizuku:api` + `provider`），做 `input`/`screencap`。
4. `core/mcp/`：基礎版用內建最小 HTTP server（ServerSocket），先 5 個工具（observe/tap/launch/list_apps/screenshot）；正式版換 Ktor + kotlin-sdk。
5. `service/McpForegroundService.kt`：前景服務 + 通知 + token。
6. `core/agent/`：本機 Agent 骨架（`ClaudeAdapter` 或假模型），重用同一套 ToolRegistry。
7. `engine/` + `container/`：Stage A/B 單處理程序容器、安裝器、多開和執行個體資料目錄已實作（見第 7 節）。
8. 處理程序池、原生程式庫、Binder/UID、Provider/通知/Job 與 Android 7.1–17 調適已實作；後續重點是背景喚醒、系統端真實 UID 邊界和更多通用相容樣本。

---

## 7. 建設順序（不設工期）

| 階段 | 目標 | 內容 | 完成標準（能力門檻） |
|---|---|---|---|
| **S0 控制層 + 雙通道骨架** | 先把「控制」做成獨立層 | ToolRegistry、MCP Server、本機 Agent 骨架、Shizuku/無障礙、租約/核准、軌跡 | 遠端 MCP 和本機 Agent 都能控制已安裝 App 完成 3 類任務；工具行為一致 |
| **A 單處理程序最小容器** ✅ 已驗證 | 驗證一個簡單 APK 可執行 | AXML/資源解析、ClassLoader、Resources、StubActivity、Instrumentation/ActivityThread hook、Context 包裝 | 一個無原生程式庫/無 GMS 的簡單 APK 能在宿主處理程序啟動並顯示 UI（API 25 模擬器已驗證） |
| **B 安裝器 + 多開** ✅ 已驗證（API 25 / API 36） | 能安裝、能多執行個體 | split APK、簽章驗證、`REQUIRE_SECURE_ENV`、IO 重新導向、VPMS 清單/啟動、執行個體資料目錄 | 簡單 App 能安裝、啟動、多開；資料互不污染（testapp + Android 16 已驗證；base+合成 split 已驗證，真實 config split 資源 overlay 待驗證） |
| **C 處理程序池 + Native** ✅ 已實作 | 當機隔離、native App | `:c1..:c16` 處理程序池、VClientService、原生程式庫擷取/載入、16 KB 測試、Binder/UID Java hook | 目標在獨立處理程序執行；原生程式庫正常；16 KB 裝置可安裝並提供明確診斷 |
| **D 背景/系統能力** ✅ 核心已實作 | 主流 App 可用 | 通知、靜態 Receiver、Job/Alarm 部分代理、Provider、WebView 處理程序隔離、Android 15–17 調適 | 通知、廣播、Provider、權限與常見背景元件可用；系統終止後的完整 Job/Alarm 喚醒仍受限 |
| **E Agent 自主性 + 安全** | 可上線 | 裝置端/雲端模型、技能庫、驗證/重試、人工接管、稽核、去識別化、MCP 身分驗證 | 常見任務成功率達標；安全審查通過 |
| **F AppFunctions 快路徑** | 可選加速 | 宿主 `@AppFunction` 匯出、容器 APK 中繼資料解析、容器內本機執行、權限偵測、UI 回退 | 宿主工具可被系統 Agent 呼叫；容器內 AppFunctions 可執行；不可用時回退 UI |

### 7.1 Stage A 的關鍵驗收

在 Android 7.1.1 x86 模擬器驗證（testapp APK 未安裝，直接載入）：

- ✅ `Activity` 的 `onCreate/onStart/onResume` 執行，版面顯示，`findViewById` 正常。
- ✅ 目標資源生效：`@string`、`@layout` 正常解析，點擊按鈕後 TextView 更新為目標字串。
- ✅ 點擊/繪製不當機、不黑畫面、不 ANR。
- ✅ 目標 `Application` 的 `attachBaseContext`/`onCreate` 已由 Stage B 接入（testapp 驗證）。
- ✅ 目標 `getPackageName()` 與 PackageManager 查詢由虛擬套件索引回答，並涵蓋虛擬應用程式之間的 VPMS 查詢。
- ✅ Android 9+ hidden API 由 HiddenApiBypass 處理；目前驗證涵蓋 Android 7.1、15、16、17。

### 7.2 Stage B 的關鍵驗收

在 Android 7.1.1 x86 模擬器驗證：

- ✅ 單一 APK 安裝成功並持久化；base + 合成 split APK 透過 `space_install_apk` 安裝並啟動，`split_apks` 已寫入磁碟。真實 config split 的資源 overlay 仍待驗證。
- ✅ 簽章不一致拒絕：`SIGNATURE_MISMATCH`。
- ✅ `REQUIRE_SECURE_ENV=1` 被拒絕：`SECURE_ENV_REQUIRED`。
- ✅ 多開 `instance-1` / `instance-2`，資料目錄獨立；`file/prefs count` 互不影響。
- ✅ 切換執行個體後舊執行個體資料保留；殺掉處理程序重新啟動 App 後安裝記錄和執行個體仍存在。
- ✅ 卸載清理 APK、執行個體資料、state；目錄為空。
- ✅ 啟動第二個執行個體時，第一個執行個體在單處理程序模型下切換到背景/被取代；資料不遺失。
- ✅ 目標 Context 的 `getPackageName`、`getFilesDir`、`getSharedPreferences`、`getApplicationInfo` 已重新導向。
- ✅ 純淨桌面：預設僅顯示虛擬應用程式 + 「空间管理」（空間管理）；系統應用程式需在空間管理裡勾選後才上桌面；支援設定/清除壁紙，重新啟動後保留。
- ✅ Android 16（API 36）複測：桌面、容器、多開、執行個體資料隔離正常。
- ✅ Android 14+ 可寫 dex 限制：安裝後的 APK 設為唯讀後，`DexClassLoader` 正常載入。
- ✅ Android 9+ hidden API：接入 HiddenApiBypass 6.2，`Activity.attach` 等反射可用；未豁免時 `Activity.attach not found`。
- ✅ Android 17（API 37）與 MuMu（Android 15、ARM bridge）已納入目前驗證矩陣。
- ✅ 相容樣本涵蓋多執行個體、顯式 Activity、Provider、WebView、外掛元件和 native 模組。
- ✅ 多處理程序路由、元件橋接、Binder/UID 代理與 native 環境均已實作，不再侷限於單處理程序入口 Activity。
- ✅ 引擎內的虛擬應用程式清單/啟動（非系統 PMS/AMS hook）、桌面入口與目標 PackageManager 虛擬化均已接入。

實作位置：`app` 的 `engine/MiniContainerEngine.kt`、`container/ContainerContext.kt`、`container/ContainerSession.kt`、`container/ContainerProcessPool.kt`，以及 `ui/DesktopScreen.kt`。

### 7.3 Stage C 的關鍵驗收（已實作）

- 兩個執行個體在不同 `:c1..:c16` 槽位處理程序，當機互不影響。
- 目標應用程式的原生程式庫能載入。
- 原生程式庫載入結果與診斷可區分已對齊、未對齊和 ABI 不符；通用 ELF `p_align` 預檢仍作為後續增強。
- `Process.myUid`/`Binder.getCallingUid` 的 Java hook 至少涵蓋常見檢查（不做系統端 UID 欺騙）。

### 7.4 Stage D 的關鍵驗收（核心已實作）

- 通知能顯示（或明確降級）。
- 靜態 Receiver 能收到。
- Job/Alarm 至少不當機（可以退化）。
- WebView 多執行個體不串資料（獨立處理程序）。
- Android 15/16/17 各一台裝置通過。

### 7.5 Stage F 的關鍵驗收（AppFunctions 快路徑）

- 宿主應用程式用 `@AppFunction` 公開至少 3 個工具（如 `space_list`、`space_launch`、`ui_observe`），系統 Agent 或 `adb shell cmd app_function` 能探索。
- 容器內測試 APK 帶 AppFunctions；安裝時解析到中繼資料；執行時能在容器處理程序內呼叫。
- 一般 APK 呼叫系統其他 App 的 AppFunctions 被明確拒絕（`EXECUTE_APP_FUNCTIONS`），並回退 UI。
- 快路徑失敗不影響 UI 自動化。

> 每個階段都可以獨立示範；不要等「全功能」再驗證。Stage A 失敗，表示 hook 路線/版本調適有問題，先解決再往下走。

---

## 8. 測試矩陣

### 8.1 裝置

| 類型 | 範例 | 關注點 |
|---|---|---|
| Pixel | Pixel 8/9/10（Android 15/16/17） | 原生行為、hidden API、AAPM |
| Samsung | S24/S25/S26（One UI） | 背景限制、Knox |
| 小米 | 14/15（HyperOS） | 背景、權限、套件管理 |
| OPPO/vivo | Find X / X 系列 | 權限、背景、無障礙 |
| 華為 | 無 GMS 機型 | GMS 相依、HMS |
| 16 KB page | Android 15+ 新機 | 原生程式庫對齊 |

### 8.2 App

- 自研測試 APK（最小 Activity、Resources、原生程式庫、split、Service、Receiver）
- 開源簡單 App（F-Droid 上的小工具）
- 常見社交、電商、出行、內容類應用程式（相容性目標，不承諾）
- 遊戲（Unity/UE，Surface/Canvas）
- Flutter App
- 以 WebView 為主的 App
- 銀行 App（預期失敗，用於驗證降級提示）

### 8.3 情境

- 安裝：單一 APK、split APK、XAPK、低 targetSdk、16 KB
- 啟動：冷啟動、熱啟動、背景喚起、多執行個體切換
- 生命週期：旋轉、分割畫面、鎖定螢幕、返回、最近任務
- 權限：首次啟動、執行階段權限、拒絕/允許
- 資料：files/cache/db/sp、外部儲存、卸載清理
- 元件：Activity、Service、Receiver、Provider、Notification
- 例外：斷網、逾時、當機、ANR、低記憶體
- 雙通道：同一任務分別走本機 Agent 和遠端 MCP，結果一致
- 並行：本機 Agent 持有租約時，遠端 MCP 修改類呼叫傳回 `SPACE_BUSY`；觀察類不受影響
- 核准：兩條通道都能觸發同一個核准 UI；逾時拒絕

### 8.4 指標

- 安裝成功率、啟動成功率、任務成功率
- 單步延遲 p50/p95
- 當機率、ANR
- 每次任務 token 成本
- 誤點率、人工接管比例
- 容器：Activity 啟動成功率、資源載入成功率、原生程式庫載入成功率、16 KB 通過率、處理程序存活
- 雙通道：工具一致性通過率、租約衝突/排隊/接管次數、核准逾時率

---

## 9. 風險清單

| 風險 | 影響 | 緩解 |
|---|---|---|
| Hidden API 變化 | hook 失效、啟動當機 | 版本調適層；漸進式發布；固定測試矩陣 |
| Binder/UID 限制 | 系統服務依宿主 UID 判定，部分 App 功能異常 | 不承諾強 UID 隔離；Java hook 涵蓋常見檢查；明確邊界 |
| 背景啟動限制 | Agent 無法從背景拉起介面 | 前景服務/通知/使用者觸發；設計「可見任務」 |
| 通知/Job/Provider | 目標應用程式背景能力退化 | 分階段支援；產品文案說明 |
| Keystore/簽章/Play Integrity | 金融/強校驗 App 不可用 | 不承諾；偵測後提示 |
| 16 KB / split APK | 安裝/載入失敗 | 預檢；明確錯誤；測試矩陣 |
| 處理程序池/記憶體 | 多開卡頓、被殺 | 處理程序池上限；前景優先；低記憶體降級 |
| 容器安全 | 惡意 App 讀取宿主/其他執行個體資料 | 只安裝可信 APK；不宣傳安全隔離 |
| 提示注入 | Agent 被螢幕內容操控 | 螢幕文字視為資料；工具允許清單；危險操作確認 |
| 雙通道並行 | 本機/遠端同時操作，互相中斷 | 租約 + 工作階段 + 使用者接管 |
| 本機 Agent 模型能力 | 裝置端模型規劃/工具呼叫較弱 | ClaudeAdapter 備援；裝置端只做簡單任務 |
| API key 洩漏 | 費用/濫用 | Keystore 儲存；代理服務；限額 |
| 自動化違反 ToS | 帳號停權 | 使用者告知；限速；不碰支付/風險控制 |
| OEM 限制 | 背景/權限/無障礙不可用 | 機型矩陣；降級方案 |
| 效能/電量 | 體驗差 | 前景服務最佳化；截圖降頻；依需求喚醒 |
| MCP 暴露 | 區域網路被控 | 預設 loopback；token；TLS；關閉 LAN 開關 |
| 隱私 | 螢幕內容含敏感資訊 | 去識別化；不寫入磁碟；稽核；使用者可關閉截圖 |
| 法律/合規 | GPL/Play/第三方 ToS | Clean-room；法務評估；遵守 REQUIRE_SECURE_ENV |

---

## 10. 參考專案與資料

### AOSP / Android

- AOSP `ActivityThread`：https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/ActivityThread.java
- AOSP `Instrumentation`：https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/Instrumentation.java
- AOSP `LoadedApk`：https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/LoadedApk.java
- AOSP `ContextImpl`：https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/ContextImpl.java
- Android 17 開發人員預覽：https://developer.android.com/about/versions/17
- 16 KB page size：https://developer.android.com/guide/practices/page-sizes
- Hidden API 限制：https://developer.android.com/guide/app-compatibility/restrictions-non-sdk-interfaces

### AppFunctions

- AppFunctions 概覽：https://developer.android.com/ai/appfunctions
- 將 AppFunctions 加入應用程式：https://developer.android.com/ai/appfunctions/add-appfunctions
- `AppFunctionManager` 參考：https://developer.android.com/reference/android/app/appfunctions/AppFunctionManager
- androidx.appfunctions：https://developer.android.com/reference/androidx/appfunctions/package-summary
- 官方範例：https://github.com/android/appfunctions
- 社群範例（探索/呼叫）：https://github.com/FilipFan/AppFunctionsPilot

### 參考實作（僅架構參考，GPL，勿複製）

- VirtualApp（asLody）：https://github.com/asLody/VirtualApp
- BlackBox / NewBlackbox：https://github.com/ALEX5402/NewBlackbox

### Hook / Native

- ShadowHook：https://github.com/bytedance/android-inline-hook
- ByteHook：https://github.com/bytedance/bhook
- Dobby：https://github.com/jmpews/Dobby
- xDL：https://github.com/hexhacking/xDL

### 控制 / Agent

- Shizuku：https://github.com/RikkaApps/Shizuku
- MCP Kotlin SDK：https://github.com/modelcontextprotocol/kotlin-sdk
- MCP Inspector：https://github.com/modelcontextprotocol/inspector

### 政策

- Google Play 容器 App 與 REQUIRE_SECURE_ENV：https://support.google.com/googleplay/android-developer/answer/13609005
- 限制容器載入（Android Developers）：https://developer.android.com/training/basics/intents/limit-play-loading
- Android 17 AAPM 報導：https://www.androidauthority.com/android-17-beta-2-advanced-protection-mode-accessibility-apps-3648860/

### 裝置端模型

- ML Kit GenAI Agent API：https://developers.google.com/ml-kit/genai/agent/android
- Gemma 4 本機 Agent：https://android-developers.googleblog.com/2026/04/gemma-4-new-standard-for-local-agentic-intelligence.html

---

## 附錄 A：APK 預檢清單

```text
[ ] packageName / versionCode / versionName
[ ] minSdk / targetSdk
[ ] ABI（arm64-v8a / armeabi-v7a / x86_64）
[ ] 是否為 split APK（base + config.*）
[ ] 原生程式庫是否 16 KB 對齊（Android 15+）
[ ] 是否宣告 REQUIRE_SECURE_ENV=1 → 是則拒絕
[ ] 是否依賴 GMS / Play Integrity
[ ] 是否使用 Android Keystore
[ ] 是否使用自訂簽章驗證
[ ] 是否包含多處理程序/前景服務/JobScheduler
[ ] 是否包含 WebView / Flutter / Unity（影響控制方式）
[ ] 危險權限清單
[ ] 安裝包簽章（是否可信來源）
```

## 附錄 B：Hook 點地圖

| 子系統 | Java hook | Native fallback | 版本注意 |
|---|---|---|---|
| PackageManager | `ApplicationPackageManager`、`IPackageManager`、`ActivityThread.getPackageManager` | `Binder.execTransact` | AIDL 方法隨版本變化 |
| Activity | `Instrumentation.execStartActivity`、`ActivityThread.mH`、`IActivityManager` | `Binder.execTransact` | Android 10+ 背景啟動限制；Android 14+ PendingIntent |
| ClassLoader | `LoadedApk`、`PathClassLoader`、`DexClassLoader` | — | split/multi-dex |
| Resources | `ResourcesManager`、`AssetManager.addAssetPath` | `open` | split/overlay |
| Binder/UID | `Binder.getCallingUid`、`Process.myUid`、`UserHandle` | `Binder.execTransact` | 系統端 UID 無法偽造 |
| IO | `ContextImpl`、`SharedPreferencesImpl`、`SQLiteDatabase` | `open`/`fopen` | scoped storage |
| 通知 | `NotificationManager`、`PendingIntent` | — | Android 12 trampoline；Android 13 權限 |
| 處理程序 | 宿主 `:c1..:c16`、`StubActivity`、`VClientService` | — | 不要用 `app_process` 子處理程序 |
| 原生程式庫 | `System.loadLibrary`、`System.load` | `dlopen`/`android_dlopen_ext` | 16 KB；ABI |
| WebView | `WebView.setDataDirectorySuffix`（處理程序級） | — | 多執行個體需獨立處理程序 |

## 附錄 C：Clean-room 清單

```text
[ ] 不複製 VirtualApp/BlackBox 的 GPL 程式碼
[ ] 參考 AOSP（Apache-2.0）和公開文件
[ ] 記錄每個 hook 點的來源（AOSP 類別/方法/版本）
[ ] native hook 程式庫逐一核對授權
[ ] 法務評估：GPL、Play 容器政策、第三方 App ToS
[ ] 程式碼隔離：看過 GPL 程式碼的人不參與 Clean-room 實作（可選但更穩）
[ ] 不在產品中聲明「安全隔離」
[ ] 遵守 REQUIRE_SECURE_ENV
```

## 附錄 D：最小可行 Demo（建議先做這個）

**目標**：同一個任務，分別由遠端 Claude Code（MCP）和本機 Agent 控制手機上的設定 App，完成「開啟設定 → 搜尋 WLAN → 開啟 WLAN」。

**範圍：**

- 不做容器
- 不做安裝器
- 只做：
  1. `ToolRegistry`：3 個工具（`ui_observe`、`ui_tap`、`ui_key`），參數驗證 + scope
  2. `SpaceAccessibilityService`（observe + tap + back）
  3. `McpForegroundService`（內建最小 HTTP server + token）
  4. `LlmAgentRuntime`（需先設定模型；工具迴圈在 App 內）
  5. `ControlLease` + `ApprovalGate`（至少實作獨占租約和確認彈出視窗）

**驗收：**

- 遠端 Claude Code 能列出/呼叫工具，完成 3 個任務
- 本機 Agent 能用同一套工具完成同樣任務
- 兩條通道的工具行為一致（同一輸入 → 同一結果/錯誤碼）
- 本機 Agent 持有租約時，遠端修改類呼叫傳回 `SPACE_BUSY`；觀察類不受影響
- 單步 p50 < 2 s
- 失敗時有結構化錯誤

這個 Demo 驗證通過後，再進入 Stage A（單一處理程序最小容器）。

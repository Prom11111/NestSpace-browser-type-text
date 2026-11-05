# Android 虚拟空间（VirtualApp）+ 双通道 Agent/MCP：技术方案

> 项目：`virtual`（`com.coke.virtual`，Jetpack Compose，minSdk 24 / targetSdk 37）
> 日期：2026-11-03
> 约束：普通 APK、不能控制 ROM；虚拟化引擎确定使用 **VirtualApp 商业版**；本地 Agent 与远程 MCP 双通道都要支持。
> 状态：方案设计，未开始实现

---

## 阅读路径

| 你想先看什么 | 直接跳到 |
|---|---|
| 结论、技术选型、范围 | **0** |
| 需求与约束 | 1 |
| VirtualApp 原理与商业版/开源版区别 | 2 |
| VirtualApp 集成、安装器、多开、兼容性 | 3 |
| Agent 怎么操作虚拟 App（控制层） | 4 |
| 本地 Agent + 远程 MCP 双通道 | 5 |
| 架构、接口、工程改造 | 6 |
| 能力建设顺序 | 7 |
| 测试矩阵、风险 | 8–9 |

---

## 0. 结论

### 0.1 技术选型

- **唯一虚拟化引擎：VirtualApp 商业版（VA）。**
- **单后端**：不再引入工作资料、Private Space、VMOS/X8、Wayondroid、AVF、redroid 等第二套虚拟化后端。
- **空间操作**：安装/卸载/启动/停止/多开走 VirtualApp API。
- **UI 控制**：Shizuku/UiAutomation 或 AccessibilityService；Vision 兜底。
- **双通道**：本地 Agent（App 内循环）+ 远程 Agent（MCP），共用同一套 `ToolRegistry`、权限、租约和审计。

### 0.2 为什么是 VirtualApp

| 需求 | VirtualApp 商业版 |
|---|---|
| 普通 APK、不控制 ROM | ✅ 不需要 root/系统签名 |
| APK 装在 App 内、不落到系统 | ✅ 应用级容器 |
| 多开/多实例 | ✅ 同一 APK 多份数据目录 |
| Agent 控制 | ✅ 目标 App 是真实窗口；可用 Shizuku/无障碍；VA API 负责空间操作 |
| Android 15–17 | ✅ 商业版宣称支持 Android 17（截至 2026-10 仍在更新） |
| 源码/支持 | ✅ 商业版提供源码、接入文档、技术支持（以官方为准） |

补充：

- **开源 VirtualApp 不是商业版**。GitHub 上的开源代码 2017-12 停更，GPL-3.0，不能用于现代 Android 产品。
- **BlackBox/NewBlackbox 不作为主引擎**：Android 15 是上限，且源自 GPL 代码的 Apache-2.0 许可链需要法务确认；只适合做早期验证。
- **CloniX 不作为主引擎**：它走"真实安装 + 重打包/签名伪装"，不是容器 runtime；会破坏签名、触发完整性校验。

### 0.3 适用范围

**做：**

- 在 App 内创建多个"空间"
- 安装 APK / split APK，启动、停止、多开
- 用本地 Agent 或远程 MCP 控制空间里的 App
- 记录/回放/技能
- 审批、审计、租约

**不做（当前版本）：**

| 不纳入 | 原因 |
|---|---|
| 金融/银行/GMS/Play Integrity 类 App | 容器共享宿主 UID，完整性校验大概率失败 |
| 不可信 APK 的强隔离 | 容器不是安全沙箱 |
| 工作资料 / Private Space | 不是 App 内空间，且当前只做 VirtualApp |
| VMOS/X8 / Wayondroid / AVF / redroid | 闭源、alpha、只跑 Linux 或云端，均不纳入 |

> 如果以后必须支持金融/GMS 或不可信 APK，再单独评估工作资料/VM；当前方案不为它们做设计妥协。

### 0.4 关键风险

1. **商业授权**：VirtualApp 商业版需要付费；2019 年公开判決书报价量级为 30–100 万元/年，2026 年现行价格需询价。
2. **兼容性**：hidden API、Binder、split APK、16 KB page、phantom process、Keystore、GMS/Play Integrity。
3. **弱隔离**：容器共享宿主 UID，不能当安全沙箱。
4. **Android 17 AAPM**：Advanced Protection Mode 会限制非无障碍类 App 使用 AccessibilityService；控制层不能只靠无障碍。
5. **Google Play 政策**：容器允许但有条件；必须检查 `REQUIRE_SECURE_ENV`，不得代理容器外 App 的 API。
6. **安全/ToS**：Agent 能看屏幕、能点支付；自动化操作第三方 App 可能违反其服务条款。

---

## 1. 需求与约束

### 1.1 目标

做一个 Android App，提供"虚拟空间"：

- 空间内可以安装第三方 APK；
- 空间内可以多开、启动、停止；
- 本地 Agent 能用自然语言操作空间内的 App；
- 远程 Agent 能通过 MCP 操作同一个空间；
- 两条通道行为一致，不会互相打断。

### 1.2 约束

- 普通 APK，不能控制 ROM/系统签名。
- 虚拟化引擎固定为 VirtualApp 商业版。
- Android 12–17，arm64，尽量不 root。
- 需要 Google Play 可解释性/合规评估（如果上架）。
- 本地 Agent 的模型可以是端侧（Gemini Nano 4/Gemma 4）或云（Claude API）。

### 1.3 功能范围

- 空间管理：创建、删除、重命名、列表
- App 管理：安装 APK（含 split）、卸载、列出、图标/名称
- 运行管理：启动、停止、前台切换、最近任务
- 观察：UI 树、截图、当前包名/Activity
- 操作：点击、长按、滑动、滚动、输入、返回/Home/最近任务
- 等待/验证：等文本、等包名、等界面空闲
- MCP：远程 Agent 发现/调用工具
- 本地 Agent：自然语言任务 → 工具调用循环
- 会话：租约、审批、审计、轨迹
- 技能：录制、回放、参数化

### 1.4 非功能需求

- 单次操作 p50 < 500 ms，p95 < 2 s（不含模型推理）
- 空间之间数据隔离（逻辑隔离；VA 的 IO 重定向）
- 崩溃率、ANR、后台存活可观测
- 安装/启动失败有结构化错误
- MCP 默认只绑 loopback，LAN 需显式开启
- 本地 Agent 与远程 Agent 的工具行为一致

---

## 2. VirtualApp 技术选型

### 2.1 原理

VirtualApp 是一个应用级虚拟化引擎：宿主 App 在自己的进程/UID 里加载目标 APK，通过 hook `ActivityThread`、`Instrumentation`、AMS/PMS/Binder，让目标 App 以为自己在正常系统里运行。

```text
┌─────────────────────────────────────────────────────────────┐
│ Host App（com.coke.virtual，宿主 UID）                      │
│                                                             │
│  ┌──────────────────┐   ┌───────────────────────────────┐   │
│  │ VA Core          │   │ Virtual PackageManager        │   │
│  │ - hook AMS/PMS   │   │ - 解析 APK manifest           │   │
│  │ - hook Binder    │   │ - 维护 PackageInfo 缓存       │   │
│  │ - hook Activity  │   └───────────────────────────────┘   │
│  └────────┬─────────┘                                       │
│           │                                                 │
│  ┌────────▼─────────┐   ┌───────────────────────────────┐   │
│  │ IO Redirect      │   │ Target App Activity           │   │
│  │ data/db/sp/cache │   │ （真实窗口，宿主进程/UID）    │   │
│  │ → /data/.../va/  │   └───────────────────────────────┘   │
│  └──────────────────┘                                       │
└─────────────────────────────────────────────────────────────┘
```

关键点：

- 目标 App 的 Activity 是**真实窗口**，所以 Shizuku/无障碍/截图仍然可用。
- 目标 App 与宿主**共享 UID**，所以它不是安全沙箱。
- 多开 = 同一 APK 复制多份数据目录，并伪造 packageName/userId。
- VA 的进程模型通常是：主进程跑 VA Core/宿主 UI，client 进程跑虚拟 App；具体进程名和 API 以商业版文档为准。

### 2.2 商业版 vs 开源版

| 维度 | 商业版 | 开源版 |
|---|---|---|
| Android 支持 | 宣称支持 Android 17（2026-10 仍在更新） | 2017-12 停更，现代 Android 不可用 |
| 许可 | 商业授权（源码 + 文档 + 支持） | GPL-3.0 |
| 适用 | 生产 | 学习/研究 |
| 风险 | 授权费用；需按官方文档集成 | 隐藏 API/Binder 全面失配；不要用于产品 |

**本项目结论：买商业授权，用商业版源码集成；开源版只用来理解原理。**

### 2.3 授权与合规

- **商业授权**：2019 年公开判決书报价量级：单款 App 30 万元/年、3 年 50 万元；游戏/广告/金融等 40–80 万元；不限应用 50–100 万元。2026 年价格需向官方询价。
- **Google Play 容器政策**：容器类 App 允许，但必须：
  - 不代理/拦截容器外 App 的 API 调用；
  - 检查目标 App 的 `REQUIRE_SECURE_ENV` 属性，声明为 `1` 的不得加载；
  - 遵守 Device and Network Abuse 政策。
- **GPL**：开源版 GPL-3.0；闭源产品商用必须商业授权。不要只改 GitHub 代码就发布。

### 2.4 兼容性边界

**大概率可用：**

- 普通工具类、内容类、社交类 App
- 老版本 App、单 APK、无强完整性校验的 App
- 多开/双开场景

**大概率不可用/不承诺：**

- 银行/支付/金融类（Play Integrity、硬件 attestation、签名校验）
- Google 服务依赖重的 App（GMS 登录、Play 服务）
- 强反调试/反容器/反 hook 的 App
- 只发 AAB/split 且 native lib 复杂的 App（需逐项验证）
- Android 15+ 16 KB page 且 native lib 未对齐的 App

> 产品文案要写清楚"兼容性因 App 而异"，不要承诺"所有 App 都能跑"。

---

## 3. VirtualApp 集成设计

### 3.1 引擎接入

1. 获取商业版源码/依赖和接入文档。
2. 在 `Application` 初始化 VA（商业版通常是 `VirtualCore.get().startup(context)` 一类入口，具体以文档为准）。
3. 配置宿主包名、进程名、权限、FileProvider、通知等。
4. 宿主 UI 只负责空间管理、安装器、Agent 控制台；虚拟 App 的 Activity 由 VA 启动。
5. 所有 VA API 调用集中到 `ContainerBackend`，不要让 UI/Agent 直接依赖 VA。

建议的封装：

```kotlin
interface SpaceBackend {
    suspend fun listSpaces(): List<SpaceInfo>
    suspend fun createSpace(spec: SpaceSpec): SpaceId
    suspend fun deleteSpace(id: SpaceId)
    suspend fun install(id: SpaceId, apk: ApkSource): InstallResult
    suspend fun uninstall(id: SpaceId, packageName: String)
    suspend fun listApps(id: SpaceId): List<AppInfo>
    suspend fun launch(id: SpaceId, packageName: String, activity: String? = null): LaunchResult
    suspend fun stop(id: SpaceId, packageName: String)
    suspend fun status(id: SpaceId): SpaceStatus
}
```

`ContainerBackend` 内部调 VA；将来 VA 升级/换 API，只改这一层。

### 3.2 安装器

```text
1. 接收 APK / APKS / XAPK
2. 解析 manifest：
   - packageName, versionCode, versionName
   - minSdk, targetSdk
   - ABI、split 列表、native lib
   - REQUIRE_SECURE_ENV 属性
3. 预检：
   - targetSdk 是否在 VA 支持范围
   - 是否 64-bit / 16 KB page 兼容
   - 是否声明 REQUIRE_SECURE_ENV=1（是则拒绝）
   - 签名/来源是否可信
4. 拷贝 APK 到空间私有目录
5. 建立 PackageInfo / ActivityInfo 缓存
6. 创建 data/db/sp/cache/lib 目录
7. 注册 launcher / shortcut
8. 首次启动初始化 Application/ContentProvider
```

安装器要支持的输入：

- 单 APK
- split APK（base + config.arm64_v8a + config.xxhdpi 等）
- `.apks` / `.xapk`（解包后按 split 处理）
- 低 targetSdk（Android 14+ 的 `--bypass-low-target-sdk-block` 在 VA 内部是否支持需验证）

预检清单见附录 A。

### 3.3 启动 / 停止 / 多开

- 启动：通过 VA 的 `VActivityManager`/商业版启动 API，传入目标 package/activity 和 instance id。
- 多开：每个实例独立数据目录；启动时指定 instance id。
- 停止：VA 的进程管理/`forceStopPackage` 等价 API。
- 最近任务/返回栈：VA 负责虚拟 Activity 的 task 记录；宿主 UI 显示空间内最近任务。
- 通知：虚拟 App 的通知需要 VA 适配；先支持前台 Activity，通知后置。
- 进程模型：VA 可能为虚拟 App 启动 client 进程；Android 12+ 的 phantom process killer 可能杀进程，需要：
  - 尽量复用进程；
  - 或引导用户用 Shizuku/root 关闭 phantom process 监控；
  - 或在商业版文档中确认其进程策略。

### 3.4 数据隔离

VA 的 IO 重定向通常覆盖：

- `SharedPreferences`
- `files/`
- `databases/`
- `cache/`
- `lib/`
- 部分 `external` 路径

注意：

- **不是安全隔离**：共享 UID，恶意 App 可以绕过重定向读宿主/其他实例的数据。
- 每个实例要有独立目录和 instance id；卸载时清理对应目录。
- 外部存储/MediaStore 的隔离有限，产品上要说明。
- 备份/恢复：只备份空间元数据 + 允许备份的 App 数据；敏感数据默认不备份。

### 3.5 兼容性适配清单

| 问题 | 处理 |
|---|---|
| Hidden API / Binder | 用商业版已适配的代码；跟随 VA 升级；灰度 |
| Split APK / AAB | 安装器支持 split；不完整的 split 直接拒绝 |
| 16 KB page | 检查 native lib 对齐；不支持则提示/拒绝 |
| Phantom process killer | 复用进程；Shizuku/root 关闭监控；测试长时多开 |
| Keystore 冲突 | 同 UID 下 alias 可能冲突；金融 App 不做承诺 |
| GMS / Play Integrity | 不承诺；检测到依赖时提示"可能不可用" |
| 运行时权限 | 宿主权限 vs 虚拟 App 权限；自建权限映射/弹窗 |
| 通知/Job/Widget | 逐项适配；第一版只保证前台 Activity |
| Native libs | 按 split 选择 ABI；加载路径/16 KB 校验 |
| OEM ROM | 后台限制、权限、包管理差异；机型矩阵 |
| REQUIRE_SECURE_ENV | 安装前读 manifest；拒绝加载 |

### 3.6 控制接口

VA 负责"空间操作"，UI 控制单独设计：

| 能力 | 首选 | 备选 |
|---|---|---|
| 安装/卸载/启动/停止/列表 | VA API（`ContainerBackend`） | — |
| 观察 UI 树 | AccessibilityService | Shizuku + `uiautomator dump` |
| 截图 | AccessibilityService.takeScreenshot（API 30+） | MediaProjection / PixelCopy |
| 点击/滑动/输入 | AccessibilityService `dispatchGesture` | Shizuku `input` |
| 读取日志 | Shizuku/ADB `logcat` | VA 商业版日志接口 |
| 进程内 hook | VA 商业版 `VInstrumentation`/client helper | 不作为第一版依赖 |

> 商业版可能在 client 进程提供 hook/控制扩展；具体 API 以官方文档为准。第一版不要假设"能直接反射到目标 Activity 的 View 树"，先用 Shizuku/无障碍把闭环跑通。

### 3.7 已知限制

- 容器不是安全沙箱；不装不可信 APK。
- 金融/GMS/Play Integrity 类 App 不承诺。
- 商业授权是硬成本。
- 兼容性需要长期维护；Android 每个大版本都要回归。
- Google Play 对容器/无障碍/安装器有政策约束，上架前做法务评估。

---

## 4. 控制层

### 4.1 控制通路

| 通路 | 前提 | 观测 | 动作 | 延迟 | Android 17 风险 | 适用 |
|---|---|---|---|---|---|---|
| **Shizuku + shell** | 安装 Shizuku，无线调试启动 | `uiautomator dump`、`screencap` | `input tap/swipe/text/keyevent` | 100–500 ms | 不受 AAPM 影响 | **主力（如果用户愿意装 Shizuku）** |
| **Shizuku + UiAutomation** | 同上，UserService 内跑 UiAutomation | 节点树、截图 | 注入事件 | 50–150 ms | 不受 AAPM 影响 | 低延迟场景 |
| **AccessibilityService** | 用户手动开启 | 节点树、事件 | `dispatchGesture`、`ACTION_SET_TEXT` | 50–100 ms | AAPM 会限制非无障碍类 App | **主力（如果不想装 Shizuku）** |
| **Vision + 坐标** | 截图权限 | 像素 | 坐标点击（需另一通路注入） | 1–3 s | 不受 AAPM 影响 | 游戏/Canvas/Flutter 兜底 |
| **VA in-process** | 商业版 API | 视 VA 能力 | 视 VA 能力 | 最低 | 不受 AAPM 影响 | 空间操作；UI 控制待验证 |

**建议：**

- 空间操作走 VA API。
- UI 控制做两个 adapter：`ShizukuChannel` 和 `AccessibilityChannel`，按可用性自动选择。
- Vision 只做兜底。
- 不要假设 in-process 能直接读目标 Activity 的 View 树；以商业版文档为准。

### 4.2 观测协议

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

建议：

- 节点扁平化，只保留可见/可点击/可编辑/有文本/有描述的节点。
- 默认 `max_nodes=120`；需要时 `detail=full`。
- 截图默认不下发；需要视觉时再取。
- 节点 id 单次 snapshot 内稳定；跨 snapshot 用 `(package, activity, viewId, text, bounds bucket)` 近似匹配。
- 截图来源：AccessibilityService.takeScreenshot → MediaProjection → PixelCopy。

### 4.3 动作协议

| 动作 | 参数 | 说明 |
|---|---|---|
| `tap` | node_id / bounds / text | 优先 node_id |
| `long_press` | 同上 + duration | 长按菜单 |
| `swipe` | from/to/duration | 滑动/翻页 |
| `scroll` | direction/amount | 优先 `ACTION_SCROLL_FORWARD` |
| `set_text` | node_id/text | `ACTION_SET_TEXT` → 剪贴板粘贴 → 逐键 |
| `key` | keycode | back/home/recents/enter/delete |
| `launch_app` | package/activity | 走 VA |
| `wait_for` | text/package/activity/idle | 带超时 |
| `intent` | action/data/extras | 深链/系统 Intent |
| `clipboard` | get/set | 敏感操作要确认 |

执行后必须：重新 observe → 验证预期变化 → 失败重试一次 → 再失败走视觉/人工。

### 4.4 视觉兜底

适用于游戏、Flutter、Canvas、部分 WebView（节点树为空/不可靠）。

1. 截图降采样到 720p/1080p。
2. 多模态模型输出坐标/区域，或 ML Kit OCR + 模板匹配。
3. 坐标归一化到屏幕比例。
4. 通过 Shizuku/无障碍注入点击。

视觉是兜底，不是主路径；成本和延迟都高。

### 4.5 录制 / 回放 / 技能

- 录制：`(action, snapshot_hash, timestamp, result)`。
- 生成：稳定序列转成带断言的 YAML/JSON 脚本。
- 回放：每步检查前置条件；不满足则回退 Agent。
- 技能库：按 App 组织，如 `wechat.send_message`、`settings.open_wifi`。
- 技能是降低 token 成本的关键：常见任务走脚本+校验，不走大模型。

### 4.6 Android 17 Advanced Protection Mode（AAPM）

- 开启 AAPM 后，非无障碍类 App 无法使用 AccessibilityService，已有权限会被撤销。
- 应对：
  1. 控制层做成双 adapter：Shizuku 优先，无障碍次之，Vision 兜底。
  2. 检测 `AdvancedProtectionManager`，提示用户"关闭 AAPM 或使用 Shizuku 模式"。
  3. 不要把"无障碍自动化"作为唯一卖点。
  4. 上架 Google Play 时，核心功能要能在不依赖无障碍的情况下工作。

### 4.7 安全边界

- 容器不是安全沙箱；只装可信 APK。
- Agent 能看到屏幕敏感信息；默认脱敏，默认不记录密码/支付页面。
- 提示注入：屏幕文本是不可信输入；工具白名单 + 危险操作确认。
- MCP 默认 loopback；LAN 模式 token + TLS。
- 所有安装/卸载/支付/权限变更二次确认并审计。

---

## 5. 双通道 Agent / MCP

### 5.1 两种控制通道（都要支持）

| 通道 | Agent 在哪 | 模型 | 连接方式 | 优点 | 缺点 |
|---|---|---|---|---|---|
| **本地 Agent** | 手机 App 内 | 端侧（Gemini Nano 4 / Gemma 4 / LiteRT-LM）或云（Claude API） | 进程内直接调 `ToolRegistry` | 独立、离线（端侧模型）、低延迟 | 本地模型能力/机型受限；云模型需网络 |
| **远程 Agent** | 手机外（Claude Desktop/Code/云） | 由远程 Agent 决定 | MCP over Streamable HTTP（USB/LAN） | 模型能力最强、易调试 | 需要 PC/网络；暴露面需鉴权 |

两条通道不是二选一，而是共用同一套工具和权限模型：

- 本地 Agent：Agent 循环在 App 内，直接调 `ToolRegistry`。
- 远程 Agent：Agent 循环在手机外，通过 MCP adapter 调同一套 `ToolRegistry`。
- App 对两者都是"工具执行器 + 空间控制器"。

> "本地 Agent"指 Agent 循环在手机上，不代表模型一定在手机上：模型可以是 Gemini Nano 4（端侧），也可以是 Claude API（云）。两种都通过 `ModelAdapter` 接入。

### 5.2 统一工具层

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

- MCP adapter：把 `ToolSpec` 映射成 MCP `tools/list` + `tools/call`，JSON Schema 校验。
- 本地 Agent adapter：把同一份 `ToolSpec` 转成 Gemini/Claude 的 function declaration，执行仍走 `ToolRegistry`。
- 两边共用：参数校验、错误码、审批、租约、审计、轨迹。新增工具只写一次。

### 5.3 MCP Server（远程通道）

> 本地 Agent 不走 MCP，直接调用 `ToolRegistry`；但两者的工具定义、权限、租约、审计完全一致。MCP Server 只是远程 Agent 的传输适配层。

技术选型：

- **MCP Kotlin SDK**：`io.modelcontextprotocol:kotlin-sdk-server`（2026 年约 0.13.x，以最新为准）。
- **Ktor 3.x**：`ktor-server-cio`。
- **Transport**：Streamable HTTP（`/mcp`），不用 stdio（Android 没有 stdio host）。
- **状态**：有状态 session；注意 `Mcp-Session-Id`、idle timeout、CORS（给 MCP Inspector 调试）。

Android 侧：

- 前台服务（`dataSync` 或 `specialUse`）+ 常驻通知。
- 电池优化白名单；Doze 下保持连接。
- 默认绑定 `127.0.0.1:8765`；首次启动生成随机 token。
- LAN 模式：显式开关 + token + TLS；Android 17 需要 `ACCESS_LOCAL_NETWORK`。

连接：

```bash
# USB（推荐）
adb forward tcp:8765 tcp:8765

# Claude Code
claude mcp add --transport http space http://127.0.0.1:8765/mcp \
  --header "Authorization: Bearer <token>"
```

### 5.4 本地 Agent Runtime

- `AgentLoop`：observe → plan → tool call → verify → repeat；与远程 Agent 使用同一套工具。
- `ModelAdapter` 三选一：
  - `GeminiNanoAdapter`：ML Kit GenAI Agent API（Alpha；设备支持有限）。
  - `LiteRTAdapter`：Gemma 4 / 自定义模型（离线，重）。
  - `ClaudeAdapter`：手机直接调 Anthropic Messages API（能力最强，需网络/key）。
- 前台服务 + 通知；长任务可取消；屏幕关闭时保持 wake lock。
- UI：任务输入、实时轨迹、审批弹窗、紧急停止。
- 失败/超时：回退到远程 Agent 或人工。

### 5.5 会话与租约

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

规则：

- 修改类工具（tap/type/launch/install/uninstall）必须先拿 `EXCLUSIVE` 租约。
- 观察类工具（observe/screenshot）可以共享。
- 另一个通道调用修改类工具时返回 `SPACE_BUSY`（带 holder 信息），可排队/等待/请求用户接管。
- 用户可以在 UI 强制回收租约。
- 租约空闲超时自动释放；MCP 断连/本地任务结束立即释放。
- 所有租约变更写审计日志。

### 5.6 审批流

| 场景 | 本地 Agent | 远程 MCP |
|---|---|---|
| 支付/卸载/权限/安装 | App 内弹窗确认 | `approval_request` + 手机通知；远程 Agent 轮询 `approval_wait` |
| 只读观察 | 默认允许 | 默认允许（需 token scope） |
| 高风险 shell | 默认关闭 | 默认关闭；需用户开启 + 二次确认 |

- 审批请求进入同一个 `ApprovalGate`，UI 显示"本地 Agent 请求 / 远程 Agent 请求"。
- 审批结果写审计；超时默认拒绝。

### 5.7 工具清单

**空间管理**

| 工具 | 作用 |
|---|---|
| `space_list` | 列出空间及状态 |
| `space_create` | 创建空间 |
| `space_delete` | 删除空间 |
| `space_install_apk` | 安装 APK/split 到空间 |
| `space_uninstall` | 卸载 |
| `space_list_apps` | 列出空间内 App |
| `space_launch` | 启动 App/Activity |
| `space_stop` | 停止 App |
| `space_status` | 空间/前台 App 状态 |

**UI 控制**

| 工具 | 作用 |
|---|---|
| `ui_observe` | 返回节点树（可选截图） |
| `ui_screenshot` | 返回截图 |
| `ui_tap` / `ui_long_press` | 点击/长按 |
| `ui_swipe` / `ui_scroll` | 滑动/滚动 |
| `ui_set_text` | 输入文本 |
| `ui_key` | 按键（back/home/recents/enter） |
| `ui_wait_for` | 等待条件 |
| `ui_find` | 在最近 snapshot 中查找节点 |

**应用/系统**

| 工具 | 作用 |
|---|---|
| `app_intent` | 发送 Intent/深链 |
| `sys_shell` | Shizuku shell（默认关闭，命令白名单） |
| `sys_logcat` | 读取 logcat（按包/pid） |
| `sys_clipboard_get/set` | 剪贴板（敏感，需确认） |

**会话/审批**

| 工具 | 作用 |
|---|---|
| `session_acquire` | 获取空间独占/观察租约 |
| `session_release` | 释放租约 |
| `session_status` | 查看当前 holder |
| `approval_request` | 请求人工确认 |
| `approval_wait` | 等待审批结果 |

**任务/技能**

| 工具 | 作用 |
|---|---|
| `task_run` | 本地 Agent 执行自然语言任务 |
| `task_status` / `task_cancel` | 查询/取消 |
| `skill_list` / `skill_run` | 技能列表/执行 |
| `trajectory_get` | 获取执行轨迹 |

设计原则：

- 所有工具带 `space_id`（`space_list`/`space_create` 除外）。
- 返回结构化错误：`{code, message, retryable, hint}`。
- 安装/启动幂等；`idempotency_key` 防重复。
- 危险操作必须 `confirm=true` 或人工确认。
- `ui_observe` 默认 `max_nodes=120`、不返回截图。
- `sys_shell` 默认不暴露；开启后只允许白名单命令。

### 5.8 工具 schema 示例

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

### 5.9 模型选择

截至 2026-10，Anthropic 第一方 API 价格（美元/百万 token，输入/输出）：

| 模型 | Model ID | 价格 | 建议用途 |
|---|---|---|---|
| Claude Opus 5.5 | `claude-opus-5-5` | $4 / $20 | 默认；复杂规划、长任务 |
| Claude Sonnet 5.5 | `claude-sonnet-5-5` | $2 / $10 | 性价比/速度 |
| Claude Haiku 5.5 | `claude-haiku-5-5` | $0.10 / $0.50 | 简单分类、等待、摘要 |
| Claude Fable 5.1 | `claude-fable-5-1` | $10 / $50 | 最难任务；成本高 |

端侧模型：

- **Gemini Nano 4 / ML Kit GenAI Agent API**（Alpha）：端侧工具调用循环；设备支持有限。
- **Gemma 4 + LiteRT-LM**：可自带微调模型；集成/性能成本高。

建议：远程 Agent 用 Claude Opus 5.5；本地 Agent 第一版先用 `ClaudeAdapter` 跑通循环，再接 Gemini Nano 4 / Gemma 4；简单子步骤用 Haiku 5.5。

### 5.10 Agent 循环与失败处理

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

要点：

- 系统提示写清楚：角色、可用 App、工具语义、安全规则、输出格式。
- 上下文：紧凑节点树 + 按需截图 + 技能提示 + 历史摘要。
- 限制轮数、token、单步超时；支持取消。
- 每步验证；连续 2 次失败 → 重规划；3 次 → 人工接管。
- 失败处理：

| 失败 | 处理 |
|---|---|
| 节点找不到 | 重新 observe；文本/坐标兜底 |
| 点击无效 | 检查可点击；坐标；等待动画 |
| 输入失败 | `ACTION_SET_TEXT` → 剪贴板 → 逐键 |
| App 崩溃/ANR | 杀进程重启；备用路径；人工 |
| 权限弹窗 | 识别系统弹窗；按策略允许/拒绝/询问 |
| 加载慢 | `wait_for` + 超时 + 重试 |
| 验证失败 | 回退上一步；重新规划 |

---

## 6. 架构

### 6.1 分层图

```text
┌──────────────────────────┐        ┌──────────────────────────┐
│ 本地 Agent Runtime       │        │ 远程 Agent               │
│ (on-device loop)         │        │ (Claude Desktop/Code/云) │
└────────────┬─────────────┘        └────────────┬─────────────┘
             │ ToolRegistry                      │ MCP Server
             │                                   │ (Streamable HTTP)
             └───────────────┬───────────────────┘
                             ▼
┌──────────────────────────────────────────────────────────────┐
│ UI (Compose): 空间 / 安装器 / Agent 控制台 / 审批 / 轨迹      │
└──────────────────────────────┬───────────────────────────────┘
                               │
┌──────────────────────────────▼───────────────────────────────┐
│ Domain                                                       │
│  SpaceRepository  ControlRepository  AgentRepository         │
│  ToolRegistry  LeaseManager  ApprovalGate  AuditLog          │
└───────────────┬──────────────────────────────┬───────────────┘
                │                              │
┌───────────────▼──────────────┐  ┌────────────▼───────────────┐
│ SpaceBackend                 │  │ ControlChannel             │
│  ContainerBackend (VA)       │  │  ShizukuChannel            │
│                              │  │  AccessibilityChannel      │
│                              │  │  VisionChannel             │
└───────────────┬──────────────┘  └────────────┬───────────────┘
                │                              │
┌───────────────▼──────────────────────────────▼───────────────┐
│ Infrastructure                                               │
│  VirtualApp 商业版 | MCP Server | 本地 Agent (foreground)     │
│  AccessibilityService / MediaProjection / Shizuku             │
│  Room / DataStore / WorkManager                              │
└──────────────────────────────────────────────────────────────┘
```

### 6.2 模块划分

第一版可以放在 `app` 单模块的 package 里，稳定后再拆 Gradle module：

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
    feature/spaces/      # Compose UI: 空间列表、App 网格
    feature/installer/   # APK 选择、预检、安装进度
    feature/agent/       # Agent 控制台、轨迹、审批
    backend/container/   # VirtualApp 商业版 adapter
    service/             # ForegroundService, AccessibilityService
```

### 6.3 关键接口

```kotlin
// 双通道共用：本地 Agent 直接调 ToolRegistry；远程 Agent 通过 MCP adapter 调同一个 ToolRegistry
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

interface SpaceBackend {
    suspend fun listSpaces(): List<SpaceInfo>
    suspend fun install(id: SpaceId, apk: ApkSource): InstallResult
    suspend fun uninstall(id: SpaceId, packageName: String)
    suspend fun listApps(id: SpaceId): List<AppInfo>
    suspend fun launch(id: SpaceId, packageName: String, activity: String? = null): LaunchResult
    suspend fun stop(id: SpaceId, packageName: String)
    suspend fun status(id: SpaceId): SpaceStatus
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

> 远程 Agent 走 MCP Server → ToolRegistry；本地 Agent 直接走 ToolRegistry。下面的 Backend/Control 步骤两者相同。

```text
Agent                         MCP Server / ToolRegistry      Backend / Control
  │                              │                                │
  │ space_install_apk            │                                │
  │─────────────────────────────►│ 校验 APK / REQUIRE_SECURE_ENV   │
  │                              │───────────────────────────────►│ VA install
  │                              │◄───────────────────────────────│ ok
  │ space_launch                 │                                │
  │─────────────────────────────►│───────────────────────────────►│ VA launch
  │                              │◄───────────────────────────────│ foreground
  │ ui_observe                   │                                │
  │─────────────────────────────►│───────────────────────────────►│ dump tree
  │◄─────────────────────────────│ nodes + screenshot hash        │
  │ ui_tap(node_id=n_7)          │                                │
  │─────────────────────────────►│───────────────────────────────►│ tap
  │ ui_wait_for(text="结果")     │                                │
  │─────────────────────────────►│───────────────────────────────►│ poll
  │◄─────────────────────────────│ matched / timeout              │
```

### 6.5 当前工程改造建议

1. `core/model` + `core/tools` + `core/session`：模型、ToolRegistry、租约/审批先落地。
2. `service/SpaceAccessibilityService.kt` + `res/xml/accessibility_service_config.xml`：观察/点击。
3. `core/control/ShizukuBridge.kt`：接 Shizuku API（`dev.rikka.shizuku:api` + `provider`），做 `input`/`screencap`。
4. `core/mcp/`：Ktor + kotlin-sdk，先 5 个工具（observe/tap/launch/list_apps/screenshot）。
5. `service/McpForegroundService.kt`：前台服务 + 通知 + token。
6. `core/agent/`：本地 Agent 骨架（`ClaudeAdapter` 或假模型），复用同一套 ToolRegistry。
7. `backend/container/`：接入 VirtualApp 商业版，先实现 install/list/launch/stop。
8. 最后补多开、数据隔离、兼容性适配。

---

## 7. 能力建设顺序（不设工期）

| 阶段 | 目标 | 内容 | 完成标准（能力门） |
|---|---|---|---|
| **S0 统一控制层 + 双通道骨架** | 先把"控制"做成独立层 | ToolRegistry、MCP Server、本地 Agent 骨架、ControlChannel、租约/审批、轨迹 | 远程 MCP 和本地 Agent 都能控制已安装 App 完成 3 类任务；工具行为一致 |
| **S1 VirtualApp 接入** | 跑通空间/安装/启动 | 商业版授权、VA 初始化、ContainerBackend、APK 预检、安装/启动/停止 | 3 个目标 App 能装进空间并启动 |
| **S2 多开与兼容性** | 多实例、主流 App | 多开、数据目录、split APK、16 KB、通知/权限、phantom process | 8/10 目标 App 启动；核心流程 7/10 |
| **S3 本地 Agent 自主性** | 从"单步工具"到"任务" | 模型 adapter、技能库、验证/重试、人工接管 | 常见任务成功率达标；失败可恢复 |
| **S4 安全与运维** | 可上线 | 审批、审计、脱敏、MCP 鉴权、崩溃/ANR 监控、机型矩阵 | 安全评审通过；新 Android 版本可回归 |

### 7.1 S0 任务清单

- [ ] `core/model`：`SpaceInfo`、`AppInfo`、`UiNode`、`UiSnapshot`、`Target`
- [ ] `ToolRegistry` + `ToolSpec` + 参数校验/scope
- [ ] `ControlLease` + `SessionManager` + `ApprovalGate`
- [ ] `SpaceAccessibilityService`：节点树 dump、`dispatchGesture`、`takeScreenshot`
- [ ] Shizuku 集成：`input tap/swipe/text`、`screencap`、`uiautomator dump`
- [ ] MCP Server（远程通道）：`/mcp`、token、5 个工具
- [ ] 本地 Agent 骨架（先用 `ClaudeAdapter` 或假模型）
- [ ] 前台服务 + 通知
- [ ] Claude Code 连接 + 3 个端到端任务
- [ ] 双通道一致性测试
- [ ] 轨迹日志（JSONL）

### 7.2 S1/S2 任务清单

- [ ] 获取 VirtualApp 商业授权/源码/文档
- [ ] VA 初始化、进程/包名/权限配置
- [ ] `ContainerBackend`：install/list/launch/stop/uninstall
- [ ] 安装器：APK/APKS/XAPK、split、ABI、16 KB、`REQUIRE_SECURE_ENV`
- [ ] 多开：实例 id、独立数据目录
- [ ] 空间 UI：App 网格、安装、卸载、启动
- [ ] 兼容性测试矩阵（附录 A + 第 8 节）
- [ ] 错误码/重试/日志

### 7.3 容器后端的 Go/No-Go 标准

- 10 个目标 App（含微信/支付宝/淘宝/抖音/游戏/Flutter/WebView）中：
  - 8 个能启动到主界面
  - 7 个能完成核心流程（登录后搜索/发消息/浏览）
  - split APK 安装成功率 ≥ 90%
  - Android 15/16/17 各至少 1 台设备不崩溃
  - 不加载 `REQUIRE_SECURE_ENV=1` 的 App
  - 16 KB page 设备安装/启动正常
- 任一不达标 → 不产品化，回到 VA 官方支持范围或缩小目标 App 列表。

---

## 8. 测试矩阵

### 8.1 设备

| 类型 | 示例 | 关注点 |
|---|---|---|
| Pixel | Pixel 8/9/10（Android 15/16/17） | 原生行为、AAPM |
| Samsung | S24/S25/S26（One UI） | 后台限制、Knox |
| 小米 | 14/15（HyperOS） | 后台、权限、包管理 |
| OPPO/vivo | Find X / X 系列 | 权限、后台、无障碍 |
| 华为 | 无 GMS 机型 | GMS 依赖、HMS |
| 16 KB page | Android 15+ 新机 | native lib 对齐 |

### 8.2 App

- 微信、支付宝、淘宝、抖音、小红书
- 游戏（Unity/UE，Surface/Canvas）
- Flutter App
- WebView 为主的 App
- 银行 App（预期失败，用于验证降级提示）
- 自研测试 App（可注入崩溃/弹窗/延迟）

### 8.3 场景

- 安装：单 APK、split APK、XAPK、低 targetSdk、16 KB
- 权限：首次启动、运行时权限、拒绝/允许
- 登录：账号密码、验证码、生物识别
- 核心流程：搜索、发消息、下单（不支付）、浏览
- 系统交互：通知、后台、旋转、分屏、锁屏、低内存
- 异常：断网、超时、崩溃、弹窗、广告
- 双通道：同一任务分别走本地 Agent 和远程 MCP，结果一致
- 并发：本地 Agent 持租约时，远程 MCP 修改类调用返回 `SPACE_BUSY`；观察类不受影响
- 审批：两条通道都能触发同一个审批 UI；超时拒绝

### 8.4 指标

- 安装成功率、启动成功率、任务成功率
- 单步延迟 p50/p95
- 崩溃率、ANR
- 每次任务 token 成本
- 误点率、人工接管比例
- 容器：兼容性通过率、16 KB 通过率、phantom process 存活
- 双通道：工具一致性通过率、租约冲突/排队/接管次数、审批超时率

---

## 9. 风险清单

| 风险 | 影响 | 缓解 |
|---|---|---|
| 商业授权费用 | 成本 | 询价；按 App 数量/类型选授权档位 |
| 容器兼容性 | Android 15–17 上部分 App 无法运行 | 固定测试矩阵；不承诺全兼容；跟随 VA 升级 |
| 弱隔离 | 恶意 App 读宿主/其他实例数据 | 只装可信 APK；产品明确说明 |
| Android 17 AAPM | 无障碍控制不可用 | Shizuku 优先；无障碍次之；Vision 兜底 |
| Google Play 政策 | 下架/拒审 | 遵守 REQUIRE_SECURE_ENV；无障碍不作为唯一卖点；企业分发 |
| 提示注入 | Agent 被屏幕内容操控 | 屏幕文本当数据；工具白名单；危险操作确认 |
| 双通道并发 | 本地/远程同时操作，互相打断 | 租约 + 会话 + 用户接管 |
| 本地 Agent 模型能力 | 端侧模型规划/工具调用弱 | ClaudeAdapter 兜底；端侧只做简单任务 |
| API key 泄露 | 费用/滥用 | Keystore 存储；代理服务；限额 |
| 自动化违反 ToS | 账号封禁 | 用户告知；限速；不碰支付/风控 |
| OEM 限制 | 后台/权限/无障碍不可用 | 机型矩阵；降级方案 |
| 性能/电量 | 体验差 | 前台服务优化；截图降频；按需唤醒 |
| MCP 暴露 | 局域网被控 | 默认 loopback；token；TLS；关闭 LAN 开关 |
| 隐私 | 屏幕内容含敏感信息 | 脱敏；不落盘；审计；用户可关闭截图 |

---

## 10. 参考项目与资料

### VirtualApp

- VirtualApp（asLody）：https://github.com/asLody/VirtualApp
- 商业版官网：https://www.virtual-app.com/

### Android 系统能力

- Android 17 开发者预览：https://developer.android.com/about/versions/17
- 16 KB page size：https://developer.android.com/guide/practices/page-sizes
- DevicePolicyManager（仅参考，不在当前方案）：https://developer.android.com/reference/android/app/admin/DevicePolicyManager

### 控制 / 自动化

- Shizuku：https://github.com/RikkaApps/Shizuku
- Shizuku 特权 API（HackTricks）：https://hacktricks.wiki/zh/mobile-pentesting/android-app-pentesting/shizuku-privileged-api.html
- MCP Kotlin SDK：https://github.com/modelcontextprotocol/kotlin-sdk
- MCP Inspector：https://github.com/modelcontextprotocol/inspector

### 政策

- Google Play 容器 App 与 REQUIRE_SECURE_ENV：https://support.google.com/googleplay/android-developer/answer/13609005
- 限制容器加载（Android Developers）：https://developer.android.com/training/basics/intents/limit-play-loading
- Android 17 AAPM 报道：https://www.androidauthority.com/android-17-beta-2-advanced-protection-mode-accessibility-apps-3648860/

### 端侧模型

- ML Kit GenAI Agent API：https://developers.google.com/ml-kit/genai/agent/android
- Gemma 4 本地 Agent：https://android-developers.googleblog.com/2026/04/gemma-4-new-standard-for-local-agentic-intelligence.html

---

## 附录 A：APK 预检清单

```text
[ ] packageName / versionCode / versionName
[ ] minSdk / targetSdk
[ ] ABI（arm64-v8a / armeabi-v7a / x86_64）
[ ] 是否 split APK（base + config.*）
[ ] native lib 是否 16 KB 对齐（Android 15+）
[ ] 是否声明 REQUIRE_SECURE_ENV=1 → 是则拒绝
[ ] 是否依赖 GMS / Play Integrity
[ ] 是否使用 Android Keystore
[ ] 是否使用自定义签名校验
[ ] 是否包含多进程/前台服务/JobScheduler
[ ] 是否包含 WebView / Flutter / Unity（影响控制方式）
[ ] 危险权限列表
[ ] 安装包签名（是否可信来源）
```

## 附录 B：术语

| 术语 | 含义 |
|---|---|
| VA | VirtualApp |
| VA Core | VirtualApp 的核心进程/引擎 |
| split APK | App Bundle 拆分出的 base + config APK |
| 16 KB page | Android 15+ 新设备的内存页大小，native lib 需对齐 |
| REQUIRE_SECURE_ENV | App 声明"不允许在容器中加载"的 manifest 属性 |
| AAPM | Advanced Protection Mode，Android 17 的高级保护模式 |
| MCP | Model Context Protocol |
| Streamable HTTP | MCP 的 HTTP 传输方式，替代旧 SSE |
| ToolRegistry | 双通道共用的工具注册/执行层 |
| ControlLease | 空间控制租约，防止本地/远程同时操作 |

## 附录 C：最小可行 Demo

**目标**：同一个任务，分别由远程 Claude Code（MCP）和本地 Agent 控制手机上的"设置"App，完成"打开设置 → 搜索 WLAN → 打开 WLAN"。

**范围：**

- 不做 VirtualApp 集成
- 不做安装器
- 只做：
  1. `ToolRegistry`：3 个工具（`ui_observe`、`ui_tap`、`ui_key`），参数校验 + scope
  2. `SpaceAccessibilityService`（observe + tap + back）
  3. `McpForegroundService`（Ktor + Streamable HTTP + token）
  4. `LocalAgentRuntime`（先用 `ClaudeAdapter` 或假模型；循环在 App 内）
  5. `ControlLease` + `ApprovalGate`（至少实现独占租约和确认弹窗）

**验收：**

- 远程 Claude Code 能列出/调用工具，完成 3 个任务
- 本地 Agent 能用同一套工具完成同样任务
- 两条通道的工具行为一致（同一输入 → 同一结果/错误码）
- 本地 Agent 持租约时，远程修改类调用返回 `SPACE_BUSY`；观察类不受影响
- 单步 p50 < 2 s
- 失败时有结构化错误

这个 Demo 跑通后，再接 VirtualApp 的 `ContainerBackend`；控制层不需要重写。

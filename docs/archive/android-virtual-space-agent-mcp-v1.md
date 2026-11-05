# Android 虚拟空间 + 本地 Agent / MCP 控制：方案调研与设计

> 项目：`virtual`（`com.coke.virtual`，Jetpack Compose，minSdk 24 / targetSdk 37）
> 日期：2026-11-03
> 状态：技术选型调研，未开始实现
> **约束更新（2026-11-03）：不能控制 ROM/设备，系统级空间路线排除。以下按普通 APK 路线展开：主后端 = 应用级容器；强隔离 = 工作资料；长期能力上限 = 用户态 Android 容器。**

---

## 阅读路径

| 你想先看什么 | 直接跳到 |
|---|---|
| 结论、推荐组合、明确不建议 | **0** |
| 时间不是约束时的能力上限排序 | 0.6 |
| 先确认需求边界（8 个问题） | 1 |
| 九条路线总览对比 | 2 |
| 系统级空间（能力上限最高，本项目排除） | 2.1 |
| 应用级容器（VirtualApp 系） | 3 |
| 工作资料（普通 APK 的强隔离后端） | 4 |
| Private Space / VMOS / Wayondroid / AVF / 云手机 | 5–8 |
| Agent 怎么"点"App（控制层） | 10 |
| 双通道（本地 Agent / 远程 MCP）/ 模型 / Agent 循环 | 11 |
| 架构、接口、能力建设顺序 | 12–13 |
| 测试矩阵、风险、参考资料 | 14–16 |

---

## 0. 结论摘要（先看这里）

### 0.1 一句话结论

**在"不能控制 ROM、只能发普通 APK"的约束下，推荐双后端：应用级容器负责"App 内装 APK / 多开"的体验，工作资料负责"真隔离 / 原生安装"；用户态 Android 容器（Wayondroid 产品化）作为长期能力上限路线。控制层统一抽象；本地 Agent（端侧循环）和远程 Agent（MCP）两条通道都支持，共用同一套 ToolRegistry、权限、租约和审计。**

不考虑工期后，选型标准是：**隔离强度、兼容性上限、可控性、可维护性、合规**，而不是"哪条最快"。

### 0.2 四类路线（本项目排除系统级空间）

| 你想要的"虚拟空间" | 本质 | 隔离级别 | 典型实现 |
|---|---|---|---|
| App 内多开/双开，APK 不安装到系统 | 应用级容器 | 弱（共享宿主 UID，不是安全沙箱） | VirtualApp、BlackBox、CloniX |
| 一个独立的空间，App 真正安装在里面 | 系统多用户 / 工作资料 | 强（独立 UID、独立数据） | DevicePolicyManager、Shizuku/ADB 多用户 |
| App 嵌在你的界面里，但 App 真正装在独立 user | 系统级空间（定制 ROM/特权 App）**（本项目排除）** | 强 | Samsung Secure Folder、OEM 应用分身 |
| 一个完整的第二套 Android | 虚拟机 / 容器 / 云手机 | 最强 | VMOS、X8 沙箱、Wayondroid、redroid |

四者的能力上限、可控性、合规风险完全不同。**不要把它们混成一个"虚拟空间"概念来做选型。**

其中**系统级空间**是能力上限最高的一类，但它需要定制 ROM、平台签名或系统特权，普通 APK 在零售机上做不到。**本项目已确认不能控制 ROM，因此排除这条路线；2.1 仅保留作对比。**

### 0.3 本项目的后端组合

系统级空间已排除；普通 APK 下的推荐组合：

| 角色 | 后端 | 控制方式 | 说明 |
|---|---|---|---|
| **主后端（App 内装 APK）** | **应用级容器**（商业 VA 源码优先） | in-process hook + Shizuku/无障碍兜底 | 唯一成熟的"APK 装在 App 内"方案；弱隔离 |
| **强隔离模式** | **工作资料（DPC）** | 跨资料无障碍 / Shizuku / DPC | 真 UID 隔离、原生安装；UX 是系统工作资料 |
| **长期能力上限** | **用户态 Android 容器**（Wayondroid 产品化） | guest ADB + 嵌入 Surface | 完整 guest Android；alpha，需自研/深度改造 |
| **云端/批量** | **redroid** | ADB + scrcpy/WebRTC | 需要服务器/网络；非本地 |

> 主后端解决"虚拟空间"的产品定义；工作资料解决容器跑不起来的 App（金融/GMS/Play Integrity）和不可信 APK；用户态容器是未来替换容器引擎的长期路线。

控制层建议（与后端无关）：

1. 统一 `ControlChannel` 接口，按优先级实现：`InProcess` > `Shizuku/UiAutomation` > `Accessibility` > `Vision`。
2. MCP Server 只依赖统一接口，不感知后端。
3. **双通道都支持**：本地 Agent 直接调用 `ToolRegistry`；远程 Agent 通过 MCP adapter 调用同一套 `ToolRegistry`。
4. 两条通道共用权限、审批、租约、审计；用租约避免两边同时操作同一个空间。
5. 无论选哪条后端，先把 ToolRegistry + 控制层做成独立层——它是唯一不会白做的投资。

### 0.4 明确不建议

- ❌ 在没有商业版源码/专职系统团队的情况下，从零写一个 VirtualApp 类引擎。不是工期问题，而是 Android 15–17 的兼容性维护没有终点，且它没有安全边界。
- ❌ 把 Wayondroid 当成开箱即用的产品级容器。它仍是 alpha，需要解决性能/机型/镜像分发。
- ❌ 依赖 Private Space 的第三方 API。它没有公开的创建/控制 API。
- ❌ 把应用级容器当安全沙箱，用来装不可信 APK。
- ❌ 把 VMOS / X8 沙箱嵌进产品。闭源、不可嵌入、ROM 老旧。
- ❌ 把无障碍服务当唯一控制通路。Android 17 的 Advanced Protection Mode 会限制非无障碍类 App。
- ❌ 在没做 POC 的情况下承诺 Android 17 兼容。

### 0.5 最大风险

1. **容器兼容性**：hidden API、Binder、split APK、16 KB page、phantom process、Keystore、GMS/Play Integrity。
2. **Android 17 AAPM**：Advanced Protection Mode 开启后，非无障碍类 App 无法使用 AccessibilityService。
3. **Google Play 政策**：容器允许但有条件；无障碍自动化类 App 很难按 `isAccessibilityTool` 上架；DPC 审查严格。
4. **授权**：VirtualApp 开源版 GPL-3.0 且 2017 年停更；生产需商业授权（2019 年判決书报价 30–100 万元/年量级，需询价）。
5. **安全与 ToS**：Agent 能看屏幕、能点支付；自动化操作第三方 App 可能违反其服务条款。
6. **用户态容器的成熟度**：Wayondroid 仍是 alpha，性能/机型/镜像分发需要自研；不能当成现成 SDK。

### 0.6 如果时间真的不是约束：按能力上限排序

| 你的优先级 | 能力上限最高的路线 | 工程上更稳的替代 | 说明 |
|---|---|---|---|
| 要"App 内装 App" + 强隔离，愿意投入 | **用户态 Android 容器（Wayondroid 产品化）** | 工作资料 | 完整 guest Android；alpha，需自研 |
| 要"App 内装 App"，优先成熟度 | **应用级容器（商业 VA 源码）** | 用户态容器 | 不追求安全隔离；兼容性维护是长期成本 |
| 要真隔离、可接受系统 UX | **工作资料（DPC）** | 用户态容器 | 最稳、最原生；不是 App 内空间 |
| 批量/云端 | **redroid** | — | 控制面最成熟；依赖网络 |

> 系统级空间已排除，不再列入。  
> "用户态 Android 容器"不是现成的 SDK；它意味着深度改造 Wayondroid 或自研用户态容器。能力上限最高，但工程风险也最高。

---

## 1. 需求拆解：先确认 8 个前提

在选型前，先把下面这些前提定下来。不同答案会直接改变推荐路线。

| # | 问题 | 影响 |
|---|---|---|
| 1 | **已确认：不能控制 ROM/设备** | 系统级空间排除；只能在普通 APK 路线中选择 |
| 2 | "空间"是 App 内多开，还是系统级隔离？ | 决定应用级容器 vs 工作资料 vs 用户态容器 |
| 3 | 是否必须"APK 不安装到系统、只在 App 内运行"？ | 只有容器能真正做到；工作资料是原生安装 |
| 4 | 需要跑微信/支付宝/银行/游戏/Play Integrity 应用吗？ | 容器大概率失败；工作资料/VM 更稳 |
| 5 | 要上 Google Play / 国内应用商店，还是企业内部/自用？ | 直接决定合规路线和可用 API |
| 6 | 用户能否安装 Shizuku、开启无障碍、创建工作资料？ | 决定控制通路和首次配置成本 |
| 7 | 是否需要离线端侧 Agent？ | 决定用 Gemini Nano 4 / Gemma 4 还是云模型 |
| 8 | 目标 Android 版本和机型？ | 16 KB page、OEM 多用户限制、Android 17 AAPM |

### 1.1 功能需求（建议的核心范围，可按能力扩展）

- 空间管理：创建、删除、重命名、列表
- App 管理：安装 APK（含 split）、卸载、列出、图标/名称
- 运行管理：启动、停止、前台切换、最近任务
- 观察：UI 树、截图、当前包名/Activity
- 操作：点击、长按、滑动、滚动、输入、返回/Home/最近任务
- 等待/验证：等文本、等包名、等界面空闲
- MCP：把上述能力暴露给外部 Agent
- 轨迹：记录、回放、失败重试
- 端侧 Agent（可选）：自然语言任务 → 工具调用循环

### 1.2 非功能需求

- Android 12–17，arm64，尽量不 root
- 单次操作 p50 < 500 ms，p95 < 2 s（不含模型推理）
- 空间之间数据隔离（至少逻辑隔离；强隔离用工作资料/VM）
- 可上架或可企业分发
- 崩溃率、ANR、后台存活可观测

---

## 2. 路线总览（九条）

| 路线 | 隔离 | 装 APK 方式 | Agent 控制 | Android 15–17 可行性 | 核心限制/代价 | 上架/合规 | 适合 |
|---|---|---|---|---|---|---|---|
| **S. 系统级空间（定制 ROM/特权 App）**（本项目排除） | 强（独立 user/UID） | 系统 PackageInstaller 静默安装 | 多用户 + VirtualDisplay 嵌入 + `INJECT_EVENTS` + `UiAutomation` | 取决于 ROM/系统权限 | 需要系统签名/定制 ROM；隐藏 API | 需 OEM/企业；普通 APK 不可用 | 最佳 UX + 真隔离 |
| **A. 应用级容器** | 弱（共享 UID） | App 内解析/加载 APK | 同进程 hook / 无障碍 / 截图 | 商业 VA 可；开源版困难 | 兼容性 treadmill；不是安全沙箱 | 中高风险 | 多开、内部工具、研究 |
| **B. 工作资料/多用户** | 强（独立 user/UID） | 原生 PackageInstaller / pm | 跨资料无障碍 / Shizuku / DPC | 好 | 需 DPC；UX 是系统资料 | DPC 审查严格 | 真隔离、真安装、产品化 |
| **C. Private Space** | 强 | 系统安装 | 无第三方 API | 不可程序化 | 无公开创建/控制 API | — | 仅用户手工使用 |
| **D. 第三方 Android VM** | 强 | VM 内安装 | guest ADB / 脚本 / 投屏 | 可用但闭源 | 闭源、不可嵌入、ROM 老 | 高 | 自用、游戏挂机、POC |
| **E. 自研用户态容器（Wayondroid）** | 强 | guest 内安装 | guest ADB | Alpha | 机型窄、成熟度低 | 中 | 技术预研 |
| **F. AVF Linux VM** | 强 | 只能装 Linux 应用 | Linux 侧 ADB/脚本 | 机型受限 | 只跑 Linux；Snapdragon 限制 | 低 | Linux 工具链，不是 Android App |
| **G. 云端 redroid** | 强 | ADB 安装 | ADB + scrcpy/WebRTC | 成熟 | 需服务器/网络 | 低（自建） | 云手机、批量 Agent |
| **H. AppFunctions 快路径** | — | — | 结构化工具 | Android 16+ | 需目标 App 实现 | 低 | 自研/合作 App 的加速通道 |

### 2.1 路线 S：系统级空间（能力上限最高，本项目排除）

> 本项目已确认不能控制 ROM，本节仅作对比，不纳入实施。

如果"不考虑工期"也意味着允许控制设备/ROM，那么这是最理想的路线：它不是"App 内的容器"，而是把"空间"做成系统能力。Samsung Secure Folder、部分 OEM 的"应用分身/隐私空间"就是这一类。

Secure Folder 类产品主要是"系统级隔离 + 独立启动器"；要把目标 App 直接嵌进你的界面，还需要 VirtualDisplay/ActivityView 这一层。

```text
┌──────────────────────────────────────────────────────────────┐
│ 你的系统空间 App（平台签名 / priv-app / 定制 ROM）           │
│                                                              │
│  ┌────────────────┐  ┌────────────────┐  ┌────────────────┐  │
│  │ Space Manager  │  │ Embed Host     │  │ Control Service│  │
│  │ MANAGE_USERS   │  │ ActivityView / │  │ INJECT_EVENTS  │  │
│  │ INSTALL_PACKAGES│  │ VirtualDisplay │  │ UiAutomation   │  │
│  └───────┬────────┘  └───────┬────────┘  └───────┬────────┘  │
│          │                   │                   │           │
│  ┌───────▼───────────────────▼───────────────────▼────────┐  │
│  │ VirtualDisplay（目标 App 的 Activity 渲染在这里）       │  │
│  │ 独立 user / 独立 UID / 原生 PackageManager              │  │
│  └─────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────┘
```

实现方式：

- **隔离**：用多用户/工作资料/自定义 profile 给空间独立 UID 和数据。
- **安装**：系统特权 App 持有 `INSTALL_PACKAGES` / `MANAGE_USERS`，静默安装 APK 到指定 user。
- **嵌入 UI**：用 `ActivityView` 或 `VirtualDisplayTaskEmbedder` 把目标 App 的 Activity 启动到 VirtualDisplay，再嵌入你的 Compose 界面（不是全屏切换）。
- **输入**：系统 App 持有 `INJECT_EVENTS`，把触摸/按键注入 VirtualDisplay；或通过 `UiAutomation`。
- **截图**：`CAPTURE_VIDEO_OUTPUT` / MediaProjection，或直接读 VirtualDisplay 的 Surface。
- **控制**：跨 user 的 `LauncherApps`、`ActivityManager`、`UiAutomation`。

前提：

- 平台签名 / `priv-app` / 定制 ROM；普通 APK 在零售机上无法获得这些权限。
- 隐藏 API（`ActivityView`、`VirtualDisplayTaskEmbedder`）随 Android 版本变化，需要跟 AOSP。
- 目标 Activity 通常需要 `resizeableActivity="true"`，跨 App 嵌入还要求 `allowEmbedded` 和嵌入权限；不同 OEM/应用支持度不同。
- 如果对外发货，涉及 GMS/CTS 认证与 OEM 合作。

优点：

- 真正的 UID 隔离 + 原生 App 兼容性（GMS、Keystore、通知都正常）。
- "App 内嵌入"的 UX，不是系统工作资料切换。
- 控制面是系统级，稳定、低延迟，不受 Android 17 AAPM 影响。

缺点：

- 门槛最高：没有 ROM/系统权限就做不了。
- 需要维护隐藏 API 适配。
- 不适合作为普通应用商店产品。

**结论：如果你能控制设备/ROM，这是最优解；如果只能发普通 APK，它不是候选，回到容器/工作资料。**

---

## 3. 路线 A：应用级容器（VirtualApp 系）

### 3.1 原理

宿主 App 在自己的进程（或自己派生的进程）里加载目标 APK，通过 hook `ActivityThread`、`Instrumentation`、AMS/PMS/Binder，让目标 App 的 Activity/Service/Receiver 以为自己在正常系统里运行。

```text
┌─────────────────────────────────────────────────────────┐
│ Host App process (com.coke.virtual, UID 10xxx)          │
│                                                         │
│  ┌─────────────────┐   ┌─────────────────────────────┐  │
│  │ VA Core         │   │ Virtual PackageManager      │  │
│  │ - hook AMS/PMS  │   │ - 解析 APK manifest         │  │
│  │ - hook Binder   │   │ - 维护 PackageInfo 缓存     │  │
│  │ - hook Activity │   └─────────────────────────────┘  │
│  └────────┬────────┘                                    │
│           │                                             │
│  ┌────────▼────────┐   ┌─────────────────────────────┐  │
│  │ IO Redirect     │   │ Target App Activity         │  │
│  │ data/db/sp/cache│   │ (真实窗口，宿主进程/UID)    │  │
│  │ → /data/.../va/ │   └─────────────────────────────┘  │
│  └─────────────────┘                                    │
└─────────────────────────────────────────────────────────┘
```

关键点：

- 目标 App 的窗口是**真实窗口**，所以无障碍、截图、输入注入仍然可用。
- 目标 App 与宿主**共享 UID**，所以它不是安全沙箱。
- 多开 = 同一 APK 复制多份数据目录，并伪造 packageName/userId。

### 3.2 主流引擎对比

| 引擎 | 许可 | 支持范围 | 现状（2026-11） | 备注 |
|---|---|---|---|---|
| **VirtualApp（asLody）** | 开源版 GPL-3.0；商业版闭源 | 商业版宣称 Android 17 | 开源代码 2017-12 停更；商业版更新到 2026-10 | 生产必须买商业授权；Android 15/16/17 适配只在商业版 |
| **BlackBox / NewBlackbox** | Apache-2.0（源自 VA，许可链需法务确认） | Android 5–15 | 社区维护，compileSdk 35，Dobby/xDL | 适合做 spike；长期维护不确定 |
| **SpaceCore（FSpaceCore）** | 需核实 | Android 14 及以前 | 2024-02 后基本停更 | VA 后继，但没跟上 Android 15 |
| **CloniX** | GPL-3.0 | Android 9–16（API 28–36） | 活跃度需核实 | 不是容器：真实安装 + 重打包/签名伪装；能力与风险都不同 |
| **Wayondroid** | 见项目 | Android 13 guest（LineageOS 20） | Alpha | 用户态容器跑完整 Android，见第 7 节 |

> 重要：**开源 VirtualApp 和商业 VirtualApp 不是同一套代码。** 不要假设 GitHub 上的 2017 年代码能跑 Android 15+。

**本项目选型结论：主引擎用 VirtualApp 商业版；BlackBox/NewBlackbox 只做 POC；CloniX 不作为主引擎。**

- VirtualApp 商业版：目前唯一同时满足"普通 APK + App 内装 APK + 商用级 Android 17 适配"的引擎；有源码/文档/支持，控制层可以走 in-process。
- BlackBox/NewBlackbox：开源、可改，但 Android 15 是上限，且源自 GPL 代码的 Apache-2.0 许可链需要法务确认；适合做 spike，不适合直接做 Android 16/17 产品底座。
- CloniX：走"真实安装 + 重打包/签名伪装"，不是容器 runtime；会破坏签名、触发完整性校验，法律和兼容风险都高；不作为主引擎。

### 3.3 安装流程（容器后端）

```text
1. 接收 APK / APKS / XAPK
2. 解析 manifest：
   - packageName, versionCode, minSdk, targetSdk
   - ABI、split 列表、native lib 对齐
   - REQUIRE_SECURE_ENV 属性（必须检查）
3. 检查：
   - targetSdk 是否在支持范围
   - 是否 64-bit / 16 KB page 兼容
   - 是否声明 REQUIRE_SECURE_ENV=1（是则拒绝加载）
4. 拷贝 APK 到空间私有目录
5. 建立 PackageInfo / ActivityInfo 缓存
6. 创建 data/db/sp/cache/lib 目录
7. 注册 launcher / shortcut
8. 首次启动时初始化 Application、ContentProvider
```

### 3.4 难点清单（决定成败）

| 难点 | 说明 | 缓解 |
|---|---|---|
| Hidden API / Binder | Android 9+ 限制非 SDK 接口；Android 14–17 内部实现变化快 | 买商业版；持续适配；灰度 |
| Split APK / AAB | base + config splits，很多 App 只发 AAB | 自己实现安装会话；支持 `.apks`/`.xapk`；测试主流 App |
| 16 KB page size | Android 15+ 设备要求 native lib 16 KB 对齐 | 检查 `.so` 对齐；加载前校验；不支持则拒绝 |
| Phantom process killer | Android 12+ 限制子进程数量，容器多开会杀进程 | root/Shizuku 关闭监控；改造进程模型 |
| Keystore | Keystore 按 UID 隔离，虚拟 App 共享宿主 UID，alias 冲突 | 命名空间隔离/代理；测试金融 App |
| GMS / Play Integrity | Google 服务与完整性校验在容器内大概率失败 | 不承诺；改用工作资料/VM |
| 权限模型 | 宿主权限 vs 虚拟 App 权限；运行时权限要自己弹窗 | 自建权限映射；默认最小权限 |
| 通知/Job/Widget | 需要逐项适配 | 先支持前台 Activity，其他后置 |
| Native libs | ABI、加载路径、JNI | 按 split 选择 ABI；校验 16 KB |
| OEM ROM | 后台限制、包管理差异、权限 | 机型测试矩阵 |
| REQUIRE_SECURE_ENV | Play 政策要求容器检查并拒绝 | 安装前读 manifest；记录拒绝原因 |
| 安全 | 共享 UID，恶意 App 可读宿主/其他虚拟 App 数据 | 不装不可信 APK；强隔离用工作资料/VM |

### 3.5 成本与合规

- **商业授权**：2019 年公开判決书中的报价量级为：单款 App 30 万元/年、3 年 50 万元；游戏/广告/金融等 40–80 万元；不限应用 50–100 万元。2026 年现行价格需向官方询价。
- **自研**：真正的代价不是工期，而是长期兼容性维护——Android 每个大版本都会改 Framework/Binder/权限模型，必须持续投入；没有商业版源码或专职系统团队，不建议从零写。
- **Google Play**：容器类 App 被允许，但必须：
  - 不代理/拦截容器外 App 的 API 调用；
  - 检查目标 App 的 `REQUIRE_SECURE_ENV` 属性，声明为 `1` 的不得加载；
  - 遵守 Device and Network Abuse 政策。
- **GPL**：开源版 GPL-3.0；闭源产品商用需商业授权，不能只看 GitHub README。
- **结论**：适合多开/双开、内部工具、老 App、研究；不适合"安全隔离不可信 App"或"金融级产品"。

### 3.6 为什么它是本项目的主后端

- 普通 APK + 无 ROM：容器是唯一能实现"APK 装在 App 内、不落到系统"的成熟方案。
- 商业 VirtualApp 已适配 Android 17；开源 BlackBox 系可做 spike。
- 控制层可以用 in-process hook，延迟最低；MCP 工具可以直接调容器内部接口。
- 但它不是安全沙箱；强隔离需求交给工作资料。
- 长期如果用户态容器成熟，可以替换容器后端，控制层不变。

### 3.7 容器 + 工作资料的双后端策略

```text
用户点击"安装 APK"
        │
        ▼
  APK 预检（REQUIRE_SECURE_ENV / ABI / split / 16KB）
        │
        ├─ 容器兼容 → ContainerBackend（默认，App 内体验）
        │
        └─ 容器不兼容 / 需要强隔离 / 不可信 APK
                 → ProfileBackend（工作资料，真隔离）
```

- 默认走容器：满足"虚拟空间"的产品定义，体验最完整。
- 失败/高风险走工作资料：原生安装、真隔离，但 UX 是系统工作资料。
- 两个后端共用同一套 `ControlChannel` 和 MCP 工具；对 Agent 透明。

---

## 4. 路线 B：工作资料 / 多用户（普通 APK 的强隔离后端）

### 4.1 原理

Android 的多用户模型：每个 user 有独立 UID、独立数据目录、独立应用列表。工作资料（managed profile）是其中一个特殊 user，由 DPC（Device Policy Controller）创建和管理。

```text
┌──────────────────────────────┐      ┌──────────────────────────────┐
│ User 0（个人资料）           │      │ User 10（工作资料）          │
│                              │      │                              │
│  Host App / DPC              │      │  Target Apps（真实安装）     │
│  MCP Server                  │      │  独立 UID / 独立 data        │
│  Accessibility / Shizuku     │      │  Helper（可选）              │
│                              │      │                              │
│         └────────── 同一屏幕、同一 WindowManager ──────────┘        │
└──────────────────────────────┘      └──────────────────────────────┘
```

- App 是**真正安装**的，走系统 PackageManager，支持 split APK、GMS、Keystore、通知、后台限制。
- 个人资料和工作资料默认数据隔离；跨资料访问需要 DPC 或 `INTERACT_ACROSS_PROFILES`。
- 一台设备通常只能有一个工作资料；OEM 可能限制或已有企业资料。

### 4.2 创建

1. App 声明 `BIND_DEVICE_ADMIN`，实现 `DeviceAdminReceiver`。
2. 调用 `ACTION_PROVISION_MANAGED_PROFILE`（带 DPC 组件名），用户确认后系统创建 profile 并把 DPC 安装进去。
3. DPC 成为 profile owner，可配置策略、安装/卸载、跨资料权限。
4. 参考实现：Shelter、Island、Harbor、Google Test DPC。

注意：

- 创建工作资料是**用户可见的系统行为**，不是"App 内静默创建"。
- 部分 OEM（小米/OPPO/vivo 等）对工作资料有额外限制，需逐机型验证。
- 已存在工作资料时，再次创建会失败；需要引导用户先移除旧资料。

### 4.3 安装 APK 到工作资料

| 方式 | 说明 | 是否需要用户确认 |
|---|---|---|
| Profile owner + `PackageInstaller` | DPC 在 profile 内发起安装 | 取决于 device owner/affiliation；BYOD 通常需要确认 |
| `DevicePolicyManager.installExistingPackage` | 只能安装设备上已存在的包（可配合 `setKeepUninstalledPackages`） | 否（满足条件时） |
| Shizuku / ADB `pm install --user 10` | shell 身份直接装到 user 10 | 否（需 Shizuku/ADB） |
| 用户在资料内手动安装 | 最原始，但可靠 | 是 |

split APK 用 `pm install-multiple --user 10 base.apk split_config.arm64_v8a.apk ...`。

> 关键限制：**静默安装**通常需要 device owner，或 profile owner 与 device owner 是 affiliated user。普通 BYOD 工作资料的 profile owner 往往仍会弹安装确认。如果产品要求"一键静默安装"，优先考虑 Shizuku/ADB 或 device owner 场景。

### 4.4 控制工作资料里的 App

| 能力 | 方案 | 备注 |
|---|---|---|
| 列出 App | `LauncherApps.getActivityList(pkg, user)` | DPC/跨资料权限 |
| 启动 App | `LauncherApps.startMainActivity` / DPC | 普通 App 需要 `INTERACT_ACROSS_PROFILES` |
| 观察 UI | 个人资料的 AccessibilityService | 社区实践通常可见工作资料窗口；需 POC |
| 备用观察 | 在 profile 内安装 helper，启用其无障碍服务 | 可用 ADB `settings --user 10 put secure enabled_accessibility_services ...` |
| 注入操作 | 无障碍 `dispatchGesture` / Shizuku `input` | 跨资料需验证 |
| 截图 | MediaProjection（整屏） | 最稳；需用户授权 |
| 通信 | DPC 跨资料配置；或 localhost HTTP | 网络命名空间通常共享 |

> 跨资料控制是这条路线的**最大不确定性**。建议 POC 先验证三件事：
> 1. 个人资料的无障碍服务能否读到工作资料 App 的节点树；
> 2. 能否对工作资料 App 执行 `dispatchGesture`；
> 3. 能否用 `LauncherApps` 启动工作资料 App。
> 任一项失败，就在 profile 内装 helper，或退回 Shizuku/ADB。

### 4.5 优缺点

**优点**

- 真隔离：独立 UID、独立数据、独立 SELinux domain。
- 原生安装：split APK、GMS、Keystore、通知、后台限制都正常。
- 无需 root；Android 15–17 稳定。
- 适合"工作空间/隔离空间/隐私空间"的产品叙事。

**缺点**

- 不是"App 内装 APK"的体验；用户会看到系统的工作资料。
- 一台设备一个工作资料；OEM 限制。
- 静默安装受 device owner/affiliation 限制。
- 跨资料控制需要 POC；无障碍/截图可能受 user restriction 影响。
- Play 对 DPC 类 App 审查严格；企业分发更合适。

### 4.6 结论

**在本项目里，工作资料不是主后端，而是强隔离模式/降级后端。** 当容器跑不起来（金融/GMS/Play Integrity）或需要真隔离时，走工作资料；它风险可控、隔离真实，代价是 UX 是系统工作资料。

---

## 5. 路线 C：Private Space（私有空间）

Android 15+ 的系统功能，基于多用户模型，user type 为 `android.os.usertype.profile.PRIVATE`。

事实：

- 每台设备只能有一个 Private Space，且只属于主用户。
- 锁定时在启动器/设置中隐藏；需要主用户解锁才能进入。
- 启动器集成需要 `ACCESS_HIDDEN_PROFILES` + `ROLE_HOME`；普通第三方 App 没有创建/管理 API。
- Android 16 QPR2+ 支持把文件移入 Private Space（由 profile 内的前台服务处理）。

结论：

- **不能作为第三方 App 的"虚拟空间"后端。** 没有公开 API 创建、安装、启动、自动化。
- 只能引导用户手工创建；锁定态下无法可靠自动化。
- 可以作为"用户隐私空间"的互补说明，但不要写进产品主流程。

---

## 6. 路线 D：第三方 Android 虚拟机（VMOS / X8 沙箱 / VPhoneGaGa）

### 6.1 原理

这些 App 在自己的 APK 里带一套 Android ROM（或容器镜像），启动一个"第二个 Android"。常见特性：

- guest 内自带 root、Xposed/LSPosed
- 独立桌面、独立存储、独立设备信息
- 支持导入 APK、多开、悬浮窗、分辨率设置
- guest 版本通常偏老（Android 4.4 / 5.1 / 7.1 / 9）

技术实现不完全是硬件虚拟化：多数是"容器/沙箱 + 路径拦截 + 兼容层"，guest 与宿主共享内核。

### 6.2 控制方式

- **宿主无障碍看不到 guest UI**：guest 的画面通常渲染在一个 Surface 里，宿主的 AccessibilityService 只能看到 VM App 的 View 层级，看不到 guest 内部的按钮/文本。
- 可行控制路径：
  1. **guest 内 ADB**：部分产品（如 X8 沙箱）暴露 `127.0.0.1:5038` 之类的网络 ADB，可用 `adb connect` + `uiautomator`/`input` 控制。
  2. **guest 内脚本/root**：Auto.js、Xposed 模块、shell 脚本。
  3. **投屏 + 视觉**：宿主截图，用多模态模型给坐标，再通过 guest ADB/输入注入点击。
- 这意味着控制层要**为 VM 后端单独写一个 adapter**，不能复用工作资料的无障碍方案。

### 6.3 优缺点

**优点**

- 隔离强，guest 内可 root，能跑老版本 App。
- 适合游戏挂机、自动化、测试。
- 不用改真机系统。

**缺点**

- 闭源、不可嵌入、无法打包进你的 App。
- ROM 老旧；Android 14/15 宿主可能有启动失败/兼容问题。
- 性能、内存、存储开销大。
- 商业/合规风险；依赖第三方产品的稳定性。
- 无法做"产品级"集成。

### 6.4 结论

**POC / 自用可以，产品不要依赖。** 如果只是验证"Agent 能否控制一个隔离 Android"，用 X8/VMOS 做对照实验是合理的。

---

## 7. 路线 E/F：自研用户态容器 / AVF

### 7.1 Wayondroid（普通 APK 的能力上限路线，但仍是 alpha）

- 在普通 App 内启动 Waydroid 的 LineageOS 20（Android 13）镜像。
- 不需要 root、不需要内核模块；用用户态容器 runtime + userspace binder（`binderd`）让 guest 的 init/zygote/system_server/SurfaceFlinger 作为 App 的进程运行。
- 声称支持：直接上屏、可选零拷贝 GPU 加速、多点触控/键盘/文本输入、音频、网络、共享 `/sdcard`、GAPPS、多实例。
- 限制：alpha；只在 Galaxy S24（Exynos 2400，Android 17，arm64）验证；需要大量 RAM 和约 2× ROM 大小的存储；首启几分钟。
- 结论：**如果时间不是约束，这是普通 APK 路线里能力上限最高的方向**——完整 guest Android、强隔离、原生安装、可嵌入 UI。但要产品化，需要解决性能/GPU/音频/输入/机型适配/镜像分发/GMS 许可；当前仍是 alpha。**在本项目里定位为长期能力上限/R&D，不作为第一版主后端。**

### 7.2 AVF / Linux Terminal

- Android 16+ 的 Terminal App 用 AVF + crosvm 跑 Debian VM；有完整 Linux 内核、apt、systemd，支持 GUI Linux 应用。
- **只能跑 Linux 应用，不跑 Android APK。**
- 理论上可以在 Debian VM 里再跑 Waydroid（嵌套），但：
  - 需要 guest 内核支持 binder；
  - GPU/内存受限；
  - Snapdragon 平台不支持非保护 VM（2025-10 报道），主要限 Pixel/Exynos；
  - 实验性，性能不确定。
- 结论：当前不作为 Android App 空间方案。它只跑 Linux，不跑 APK；嵌套 Waydroid 理论可行，但受机型/GPU/内存限制。若目标是"最强隔离且能控制机型"，这是长期可探索方向，但不是现成方案。

---

## 8. 路线 G：云端 redroid（批量/云手机）

### 8.1 原理

```text
┌─────────────┐     WebRTC / scrcpy      ┌──────────────────────────┐
│ 手机/浏览器 │ ◄──────────────────────► │ 云服务器                  │
│ 只做客户端  │                          │  Linux + binder/ashmem    │
└─────────────┘                          │  Docker: redroid (Android)│
                                         │  ADB + scrcpy-server      │
                                         │  Agent / MCP Server       │
                                         └──────────────────────────┘
```

- 服务器 Linux 内核加载 binder/ashmem，Docker 跑 redroid（Android 容器）。
- 投屏用 scrcpy / WebRTC；控制用 ADB。
- Agent 可以跑在云端，也可以跑在手机端通过 WebSocket/HTTP 调云端。

### 8.2 优缺点

**优点**

- 强隔离、多实例、机型无关、易扩展。
- Agent 控制最成熟：ADB 就是标准接口。
- 适合批量任务、SaaS、云手机。

**缺点**

- 需要服务器和稳定网络；离线不可用。
- 延迟、带宽、隐私、运维成本。
- 不适合"本地/离线"产品叙事。

### 8.3 结论

**如果目标是"批量 Agent / 云手机"，这是最成熟、最省兼容性成本的路线。** 手机端只做 UI 和 Agent 控制台。

---

## 9. 路线 H：AppFunctions（Android 原生 MCP 快路径）

Android 16+ 引入 AppFunctions：App 用 `@AppFunction` 注解声明能力，系统生成 XML schema 并注册；调用方通过 `AppFunctionManager` 发现/执行，需要 `EXECUTE_APP_FUNCTIONS` 权限。

- 定位：Android 版的 MCP 工具层；Gemini/Agent 可以直接调用，不必点 UI。
- 限制：**需要目标 App 自己实现**；绝大多数第三方 App 不会实现。
- 2026-10 状态：beta；Gemini 集成 private preview；生产发布需 Connected Apps Platform/EAP。
- 用法：
  - 对我们能控制的 App（自研/合作 App）提供结构化工具，作为快路径；
  - 对普通 App 仍然走 UI 自动化；
  - 长期可以把 AppFunctions 作为 MCP 工具的一个来源。

结论：**作为加速通道和长期方向，不是第一版依赖。**

---

## 10. 控制层：怎么"操作"空间里的 App

这是整个项目最核心、也最容易被低估的部分。虚拟化只解决"App 在哪跑"，控制层解决"Agent 怎么用"。

### 10.1 控制通路对比

| 通路 | 前提/权限 | 观测 | 动作 | 延迟 | Android 17 风险 | Play 风险 | 适用 |
|---|---|---|---|---|---|---|---|
| **AccessibilityService** | 用户手动开启 | 节点树、事件 | `dispatchGesture`、`ACTION_SET_TEXT`、全局动作 | 50–100 ms | AAPM 会限制非无障碍类 App | 高（`isAccessibilityTool` 限制） | 通用兜底 |
| **Shizuku + shell** | 安装 Shizuku 并用无线调试启动 | `uiautomator dump`、`screencap` | `input tap/swipe/text/keyevent` | 100–500 ms（持久 shell 可优化） | 不受 AAPM 影响 | 中 | 无 root 的主力 |
| **Shizuku + UiAutomation** | 同上，UserService 内跑 UiAutomation | 节点树、截图 | 注入事件 | 50–150 ms | 不受 AAPM 影响 | 中 | 需要低延迟时 |
| **ADB（PC/无线）** | USB/无线调试 | 全部 | 全部 | 10–100 ms | 无关 | 低（开发工具） | 外置 Agent/调试 |
| **容器 in-process** | 仅容器后端 | 反射 View 树、截图 | 直接调用/注入 | < 50 ms | 不受 AAPM 影响 | 中（hidden API） | 容器差异化能力 |
| **AppFunctions** | 目标 App 实现 | 结构化结果 | 结构化调用 | 毫秒级 | 无关 | 低 | 自研/合作 App |
| **视觉 + 坐标** | 截图权限 | 像素 | 坐标点击（需另一通路注入） | 1–3 s | 不受 AAPM 影响 | 低 | 游戏/Canvas/Flutter 兜底 |
| **Root** | root | 全部 | 全部 | 最低 | 无关 | 高 | 不推荐产品 |

**设计原则：控制通路必须抽象成可替换的 adapter，不能把 AccessibilityService 写死。**

推荐优先级：

```text
容器后端：in-process  >  Accessibility  >  Shizuku  >  视觉
工作资料：Shizuku/UiAutomation  >  Accessibility  >  视觉
VM 后端：guest ADB  >  视觉
云后端：ADB  >  视觉
```

### 10.2 观测协议

一次 `observe` 返回：

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

- **扁平化**节点列表，不要嵌套树；模型更容易用，token 更省。
- 只保留：可见、可点击、可编辑、有文本、有描述的节点。
- 默认 `max_nodes = 120`；需要时用 `detail=full`。
- 节点 `id` 在单次 snapshot 内稳定；跨 snapshot 用 `(package, activity, viewId, text, bounds bucket)` 做近似匹配。
- 截图默认不下发；需要视觉时再取，避免 token 爆炸。
- 截图来源优先级：`AccessibilityService.takeScreenshot`（API 30+）→ `MediaProjection` → `PixelCopy`（仅自己的窗口）。

### 10.3 动作协议

| 动作 | 参数 | 说明 |
|---|---|---|
| `tap` | `node_id` 或 `bounds` 或 `text` | 优先 node_id；找不到则按文本/坐标 |
| `long_press` | 同上 + duration | 长按菜单 |
| `swipe` | from/to/duration | 滑动、翻页 |
| `scroll` | direction/amount | 优先 `ACTION_SCROLL_FORWARD`，失败再手势 |
| `set_text` | node_id/text | 优先 `ACTION_SET_TEXT`；失败用剪贴板+粘贴 |
| `key` | keycode | back/home/recents/enter/delete |
| `launch_app` | package/activity | 空间后端负责 |
| `wait_for` | text/package/activity/idle | 等待条件，带超时 |
| `intent` | action/data/extras | 深链/系统 Intent |
| `clipboard` | get/set | 敏感操作要确认 |

执行后必须：**重新 observe → 验证预期变化 → 失败重试一次 → 再失败走视觉/人工**。

### 10.4 视觉兜底

适用于：

- 游戏、Flutter、Canvas、部分 WebView（节点树为空或不可靠）
- 自定义绘制的列表/地图
- 节点树存在但文本缺失

做法：

1. 截图（降采样到 720p/1080p，WebP/JPEG）。
2. 用多模态模型输出目标坐标/区域；或用 ML Kit Text Recognition v2 做 OCR + 模板匹配。
3. 坐标归一化到屏幕比例，适配不同分辨率。
4. 通过 Shizuku/无障碍注入点击。

注意：视觉是**兜底**，不是主路径；成本和延迟都高。

### 10.5 录制 / 回放 / 技能

- 录制：记录每次 `(action, snapshot_hash, timestamp, result)`。
- 生成：把稳定序列转成带断言的脚本（YAML/JSON）。
- 回放：每步前检查前置条件；不满足则回退到 Agent 重新规划。
- 技能库：按 App 组织，例如：
  - `wechat.send_message`
  - `taobao.search`
  - `settings.open_wifi`
- 技能是**降低 token 成本**的关键：常见任务不走大模型，只走脚本+校验。

### 10.6 Android 17 Advanced Protection Mode（AAPM）

2026 年 Android 17 Beta 2 引入：当用户开启 Advanced Protection Mode 时，**非无障碍类 App 无法使用 AccessibilityService**，已有权限会被撤销，用户需要关闭 AAPM 才能重新授予。

影响：

- 如果产品把无障碍当唯一控制通路，在 AAPM 用户上直接不可用。
- Play 政策本来就限制 `isAccessibilityTool=true` 只给残障辅助类 App；自动化/助手类 App 不应依赖它上架。

应对：

1. 控制层抽象，优先 Shizuku/UiAutomation、容器 in-process、VM guest ADB。
2. 检测 `AdvancedProtectionManager`，提示用户"本功能需要关闭 AAPM 或使用 Shizuku 模式"。
3. 不要把"无障碍自动化"作为唯一卖点；提供多种控制模式。
4. 在 Google Play 上架时，把核心功能设计成不依赖无障碍（例如企业/内部工具走 Shizuku）。

### 10.7 安全边界（必须写进产品文档）

- **容器不是安全沙箱。** 共享 UID，恶意 App 可以读宿主/其他虚拟 App 的数据。只装可信 APK。
- **工作资料/VM 才是隔离边界。** 装不可信 APK 用这两条路线。
- Agent 能看到屏幕上的敏感信息；默认脱敏、默认不记录密码/支付页面。
- **提示注入**：屏幕上的文本是不可信输入，模型不能执行屏幕里"请转账/请点击"的指令；工具白名单 + 人工确认。
- MCP 只绑定 `127.0.0.1`；LAN 模式必须 token + TLS + 可信网络。
- 所有安装/卸载/支付/权限变更操作要二次确认，并写审计日志。

---

## 11. Agent / MCP 层

### 11.1 两种控制通道（都要支持）

| 通道 | Agent 在哪 | 模型 | 连接方式 | 优点 | 缺点 |
|---|---|---|---|---|---|
| **本地 Agent** | 手机 App 内 | 端侧（Gemini Nano 4 / Gemma 4 / LiteRT-LM）或云（Claude API） | 进程内直接调 `ToolRegistry` | 独立、离线（端侧模型）、低延迟 | 本地模型能力/机型受限；云模型需网络 |
| **远程 Agent** | 手机外（Claude Desktop/Code/云） | 由远程 Agent 决定 | MCP over Streamable HTTP（USB/LAN） | 模型能力最强、易调试 | 需要 PC/网络；暴露面需鉴权 |

**两条通道不是二选一，而是共用同一套工具和权限模型。**

- 本地 Agent：Agent 循环在 App 内，直接调用 `ToolRegistry`。
- 远程 Agent：Agent 循环在手机外，通过 MCP adapter 调用同一套 `ToolRegistry`。
- App 对两者都是"工具执行器 + 空间控制器"；工具行为、审批、租约、审计必须一致。

> 本地 Agent 的"本地"指 Agent 循环在手机上，不代表模型一定在手机上：模型可以是 Gemini Nano 4（端侧），也可以是 Claude API（云）。两种都通过 `ModelAdapter` 接入。

### 11.2 MCP Server 设计（远程通道）

> 本地 Agent 不走 MCP，直接调用 `ToolRegistry`；但两者的工具定义、权限、租约、审计完全一致。MCP Server 只是远程 Agent 的传输适配层。

技术选型：

- **MCP Kotlin SDK**：`io.modelcontextprotocol:kotlin-sdk-server`（2026 年约 0.13.x，以最新为准）。
- **Ktor 3.x**：`ktor-server-cio` / `ktor-server-netty`。
- **Transport**：Streamable HTTP（`/mcp`），不要用 stdio（Android 上没有 stdio host）。
- **状态**：有状态 session；注意 `Mcp-Session-Id`、session idle timeout、CORS（给 MCP Inspector 调试用）。

Android 侧：

- 前台服务（`dataSync` 或 `specialUse` 类型）+ 常驻通知；否则后台会被杀。
- 申请电池优化白名单；Doze 下保持连接。
- 默认绑定 `127.0.0.1:8765`；首次启动生成随机 token，显示在设置页/二维码。
- LAN 模式：显式开关 + token + TLS；Android 17 需要 `ACCESS_LOCAL_NETWORK` 运行时权限。

连接方式：

```bash
# USB（推荐，最稳）
adb forward tcp:8765 tcp:8765

# Claude Code 添加 HTTP MCP
claude mcp add --transport http space http://127.0.0.1:8765/mcp \
  --header "Authorization: Bearer <token>"

# 局域网（可选）
# http://<phone-ip>:8765/mcp
```

调试：MCP Inspector 连 `http://127.0.0.1:8765/mcp`，先 `tools/list` 再逐个调用。

### 11.3 工具清单（建议）

**空间管理**

| 工具 | 作用 |
|---|---|
| `space_list` | 列出空间及状态 |
| `space_create` | 创建空间（后端相关） |
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
| `sys_notifications` | 读取通知（可选） |

**会话/审批**

| 工具 | 作用 |
|---|---|
| `session_acquire` | 获取空间独占/观察租约 |
| `session_release` | 释放租约 |
| `session_status` | 查看当前 holder |
| `approval_request` | 请求人工确认（支付/卸载/权限） |
| `approval_wait` | 等待审批结果 |

**任务/技能**

| 工具 | 作用 |
|---|---|
| `task_run` | 端侧 Agent 执行自然语言任务 |
| `task_status` / `task_cancel` | 查询/取消 |
| `skill_list` / `skill_run` | 技能列表/执行 |
| `trajectory_get` | 获取执行轨迹 |

工具设计原则：

- 所有工具都带 `space_id`（`space_list`/`space_create` 除外）。
- 返回结构化错误：`{code, message, retryable, hint}`。
- 安装/启动幂等；用 `idempotency_key` 防重复。
- 危险操作（卸载、支付、权限、shell）必须 `confirm=true` 或人工确认。
- `ui_observe` 默认 `max_nodes=120`、不返回截图；需要时显式请求。
- `sys_shell` 默认不暴露；用户开启后也只允许白名单命令。

### 11.4 工具 schema 示例

```json
{
  "name": "space_install_apk",
  "description": "Install an APK or split APK set into a space. Returns package name and version. Validates REQUIRE_SECURE_ENV and ABI before install.",
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

### 11.5 模型选择

截至 2026-10，Anthropic 第一方 API 的价格（美元/百万 token，输入/输出）：

| 模型 | Model ID | 价格 | 建议用途 |
|---|---|---|---|
| Claude Opus 5.5 | `claude-opus-5-5` | $4 / $20 | 默认；复杂规划、长任务 |
| Claude Sonnet 5.5 | `claude-sonnet-5-5` | $2 / $10 | 性价比/速度；中等复杂度 |
| Claude Haiku 5.5 | `claude-haiku-5-5` | $0.10 / $0.50 | 简单分类、等待、摘要、子步骤 |
| Claude Fable 5.1 | `claude-fable-5-1` | $10 / $50 | 最难任务；成本高 |

端侧模型：

- **Gemini Nano 4 / ML Kit GenAI Agent API**（Alpha）：`AgentRunner` 跑端侧工具调用循环，适合离线/隐私；设备支持有限（Pixel 9/10/11、Galaxy S26、部分旗舰）。
- **Gemma 4 + LiteRT-LM**：可自带微调模型，但集成/性能成本更高。
- **AppFunctions**：Android 原生工具注册，需要目标 App 配合。

建议：远程 Agent 用 Claude Opus 5.5；本地 Agent 第一版可先用 Claude API（`ClaudeAdapter`）跑通循环，再接 Gemini Nano 4 / Gemma 4；简单子步骤用 Haiku 5.5 降成本。

### 11.6 Agent 循环

```text
state = observe(space)
for turn in 1..MAX_TURNS:
    decision = model.decide(goal, state, tools, history)
    if decision.done: return result
    if decision.needs_user_confirmation:
        ask_user(); continue
    result = execute(decision.tool, decision.args)
    state = result.observation ?? observe(space)
    history.append(compact(result))
```

要点：

- 系统提示写清楚：角色、可用 App、工具语义、安全规则、输出格式。
- 上下文：紧凑节点树 + 按需截图 + 技能提示 + 历史摘要。
- 限制轮数、token、单步超时；支持取消。
- 每步验证；连续 2 次失败 → 重规划；3 次 → 人工接管。
- 记录完整轨迹，便于回放和调参。

### 11.7 失败处理

| 失败 | 处理 |
|---|---|
| 节点找不到 | 重新 observe；用文本/坐标兜底 |
| 点击无效 | 检查是否可点击；改用坐标；等待动画 |
| 输入失败 | `ACTION_SET_TEXT` → 剪贴板粘贴 → 逐键输入 |
| App 崩溃/无响应 | 杀进程重启；换备用路径；人工 |
| 权限弹窗 | 识别系统弹窗；按策略允许/拒绝/询问 |
| 网络/加载慢 | `wait_for` + 超时 + 重试 |
| 验证失败 | 回退上一步；重新规划 |

### 11.8 双通道统一架构（本地 Agent + 远程 MCP）

两条通道必须共用同一套工具、权限、租约和审计；否则会出现"本地能点、远程不能点"或两边同时操作打架。

```text
┌────────────────────────────┐        ┌────────────────────────────┐
│ 本地 Agent Runtime         │        │ 远程 Agent                  │
│ (App 内 loop)              │        │ (Claude Desktop/Code/云)    │
│  ModelAdapter:             │        │                             │
│   - Gemini Nano 4          │        │                             │
│   - Gemma 4 / LiteRT-LM    │        │                             │
│   - Claude API             │        │                             │
└─────────────┬──────────────┘        └─────────────┬──────────────┘
              │ ToolRegistry (in-process)            │ MCP adapter
              │                                      │ (Streamable HTTP)
              └──────────────────┬───────────────────┘
                                 ▼
                    ┌─────────────────────────┐
                    │ ToolRegistry            │
                    │  - schema / validation  │
                    │  - permission / approval│
                    │  - lease / session      │
                    │  - audit / trajectory   │
                    └────────────┬────────────┘
                                 ▼
                    SpaceBackend / ControlChannel
```

#### 统一工具层

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

- MCP adapter：把 `ToolSpec` 映射成 MCP `tools/list` + `tools/call`，参数用 JSON Schema 校验。
- 本地 Agent adapter：把同一份 `ToolSpec` 转成 Gemini/Claude 的 function declaration，执行仍走 `ToolRegistry`。
- 两边共用：参数校验、错误码、审批、租约、审计、轨迹。新增工具只写一次。

#### 会话与租约（防止两边同时操作）

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

- 任何修改类工具（tap/type/launch/install/uninstall）必须先拿 `EXCLUSIVE` 租约。
- 观察类工具（observe/screenshot）可以共享；不阻塞。
- 租约默认给当前 Agent；另一个通道调用时返回 `SPACE_BUSY`（带 holder 信息），可选择排队/等待/请求用户接管。
- 用户可以在 UI 里强制回收租约；本地 Agent 和远程 Agent 都要服从。
- 租约空闲超时自动释放；MCP 断连/本地任务结束立即释放。
- 所有租约变更写审计日志。

#### 审批流（两边都要有人类确认）

| 场景 | 本地 Agent | 远程 MCP |
|---|---|---|
| 支付/卸载/权限/安装 | App 内弹窗确认 | `approval_request` 工具 + 手机通知；远程 Agent 轮询 `approval_wait` |
| 只读观察 | 默认允许 | 默认允许（需 token scope） |
| 高风险 shell | 默认关闭 | 默认关闭；需用户开启 + 二次确认 |

- 审批请求进入同一个 `ApprovalGate`，UI 显示"本地 Agent 请求 / 远程 Agent 请求"。
- 审批结果写审计；超时默认拒绝。

#### 本地 Agent Runtime

- `AgentLoop`：observe → plan → tool call → verify → repeat；与远程 Agent 使用同一套工具。
- `ModelAdapter` 三选一：
  - `GeminiNanoAdapter`：ML Kit GenAI Agent API（Alpha；设备支持有限）。
  - `LiteRTAdapter`：Gemma 4 / 自定义模型（离线，重）。
  - `ClaudeAdapter`：手机直接调 Anthropic Messages API（能力最强，需网络/key）。
- 运行在前台服务 + 通知；长任务可取消；屏幕关闭时保持 wake lock。
- UI：任务输入、实时轨迹、审批弹窗、紧急停止。
- 失败/超时：回退到远程 Agent 或人工。

#### 远程 MCP 的额外要求

- 只绑定 loopback 默认；LAN 显式开启 + token + TLS。
- 每个远程客户端一个 MCP session；session 绑定 `Caller.REMOTE_MCP` 和一个租约。
- 支持多客户端，但同一 space 同时只有一个 writer。
- 远程 Agent 可以调用 `task_run` 把目标委托给本地 Agent；本地 Agent 成为子执行器（需防递归/限额）。
- MCP 工具描述要写清楚"这是手机上的真实操作，可能影响用户数据"。

#### 一致性测试

- 同一任务分别用本地 Agent 和远程 MCP 执行，断言工具调用序列/结果一致（允许模型差异，但工具行为必须一致）。
- 并发测试：本地 Agent 持有租约时，远程 MCP 的修改类调用必须返回 `SPACE_BUSY`；观察类调用不受影响。
- 审批测试：本地/远程两条路径都能触发同一个审批 UI；超时拒绝。
- 断连测试：MCP 客户端断开后租约释放；本地任务崩溃后租约释放。

---

## 12. 推荐架构

### 12.1 分层图

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
│ SpaceBackend (interface)     │  │ ControlChannel (interface) │
│  ContainerBackend (primary)  │  │  InProcessChannel          │
│  ProfileBackend (isolated)   │  │  ShizukuChannel            │
│  UserspaceBackend (future)   │  │  AccessibilityChannel      │
│  VmBackend / CloudBackend    │  │  VisionChannel             │
└───────────────┬──────────────┘  └────────────┬───────────────┘
                │                              │
┌───────────────▼──────────────────────────────▼───────────────┐
│ Infrastructure                                               │
│  MCP Server (Ktor + kotlin-sdk)  |  本地 Agent (foreground)   │
│  DPC / AccessibilityService / MediaProjection / Shizuku      │
│  VirtualApp/BlackBox | Room / DataStore / WorkManager        │
└──────────────────────────────────────────────────────────────┘
```

### 12.2 模块划分

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
    backend/container/   # VirtualApp/BlackBox adapter（主后端）
    backend/profile/     # DPC, profile install/launch, helper（强隔离）
    backend/userspace/   # 用户态容器（Wayondroid 类，长期）
    backend/vm/          # guest ADB（可选）
    service/             # ForegroundService, AccessibilityService
```

### 12.3 关键接口

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
    suspend fun createSpace(spec: SpaceSpec): SpaceId
    suspend fun deleteSpace(id: SpaceId)

    suspend fun install(id: SpaceId, apk: ApkSource): InstallResult
    suspend fun uninstall(id: SpaceId, packageName: String)
    suspend fun listApps(id: SpaceId): List<AppInfo>

    suspend fun launch(
        id: SpaceId,
        packageName: String,
        activity: String? = null,
        extras: Map<String, String> = emptyMap()
    ): LaunchResult

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

```kotlin
data class UiNode(
    val id: String,
    val role: String,
    val text: String?,
    val desc: String?,
    val bounds: Rect,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val enabled: Boolean,
    val focused: Boolean,
    val selected: Boolean
)

data class UiSnapshot(
    val snapshotId: String,
    val spaceId: SpaceId,
    val packageName: String,
    val activity: String,
    val screen: ScreenInfo,
    val nodes: List<UiNode>,
    val screenshot: String?, // hash/uri
    val timestampMs: Long
)
```

### 12.4 一次完整流程

> 远程 Agent 走 MCP Server → ToolRegistry；本地 Agent 直接走 ToolRegistry。下面的 Backend/Control 步骤两者相同。

```text
Agent                         MCP Server                    Backend / Control
  │                              │                                │
  │ space_install_apk            │                                │
  │─────────────────────────────►│ 校验 APK / REQUIRE_SECURE_ENV   │
  │                              │───────────────────────────────►│ install
  │                              │◄───────────────────────────────│ ok
  │ space_launch                 │                                │
  │─────────────────────────────►│───────────────────────────────►│ launch
  │                              │◄───────────────────────────────│ foreground
  │ ui_observe                   │                                │
  │─────────────────────────────►│───────────────────────────────►│ dump tree
  │◄─────────────────────────────│ nodes + screenshot hash        │
  │ ui_tap(node_id=n_7)          │                                │
  │─────────────────────────────►│───────────────────────────────►│ tap
  │                              │                                │
  │ ui_wait_for(text="结果")     │                                │
  │─────────────────────────────►│───────────────────────────────►│ poll
  │◄─────────────────────────────│ matched / timeout              │
```

### 12.5 当前工程改造建议

现有工程是标准 Compose 模板，建议按顺序加：

1. `core/model` + `core/tools` + `core/session`：模型、ToolRegistry、租约/审批先落地。
2. `service/SpaceAccessibilityService.kt` + `res/xml/accessibility_service_config.xml`：先做观察/点击。
3. `core/control/ShizukuBridge.kt`：接 Shizuku API（`dev.rikka.shizuku:api` + `provider`），做 `input`/`screencap`。
4. `core/mcp/`：Ktor + kotlin-sdk，先 5 个工具（observe/tap/launch/list_apps/screenshot）。
5. `service/McpForegroundService.kt`：前台服务 + 通知 + token。
6. `core/agent/`：本地 Agent 骨架（`ClaudeAdapter` 或假模型），复用同一套 ToolRegistry。
7. `backend/profile/`：DPC + 工作资料创建 + 安装。
8. 最后再接容器、用户态容器或 VM。

---

## 13. 能力建设顺序（不设工期）

不考虑工期后，顺序由依赖关系和能力上限决定，而不是由时间决定。

| 阶段 | 目标 | 内容 | 完成标准（能力门） |
|---|---|---|---|
| **S0 统一控制层 + 双通道骨架** | 先把"控制"做成独立层 | ToolRegistry、MCP Server、本地 Agent 骨架、ControlChannel、租约/审批、轨迹 | 远程 MCP 和本地 Agent 都能控制已安装 App 完成 3 类任务；工具行为一致 |
| **S1 空间后端定型** | 定主后端 + 强隔离后端 | 应用级容器（主）/ 工作资料（强隔离）/ 用户态容器（长期） | 选定后端的 10 个目标 App 测试矩阵通过 |
| **S2 主后端产品化** | 安装、启动、控制、空间 UI | 安装器、空间管理、跨资料/跨 user 控制、错误处理 | 8/10 App 安装/启动；核心流程 7/10 |
| **S3 强隔离模式** | 不可信 App / 企业场景 | 工作资料或 VM/云后端 | 独立 UID/数据；不依赖容器 |
| **S4 Agent 自主性** | 从"单步工具"到"任务" | 本地 Agent（端侧/云模型）、技能库、验证/重试、人工接管 | 常见任务成功率达标；失败可恢复 |
| **S5 长期维护** | 兼容性/安全/运维 | 机型矩阵、CI 设备农场、安全审计、遥测 | 新 Android 版本可在发布前回归 |

> 本项目不能控制 ROM，系统级空间排除。主后端用应用级容器；工作资料做强隔离；用户态容器作为长期能力上限。

### 13.1 S0 的具体任务清单

- [ ] 新建 `core/model`：`SpaceInfo`、`AppInfo`、`UiNode`、`UiSnapshot`、`Target`
- [ ] `ToolRegistry` + `ToolSpec` + 参数校验/scope
- [ ] `ControlLease` + `SessionManager` + `ApprovalGate`
- [ ] `AccessibilityService`：节点树 dump、`dispatchGesture`、`takeScreenshot`
- [ ] Shizuku 集成：`input tap/swipe/text`、`screencap`、`uiautomator dump`
- [ ] MCP Server（远程通道）：`/mcp`、token、5 个工具
- [ ] 本地 Agent 骨架（先用 `ClaudeAdapter` 或假模型）
- [ ] 前台服务 + 通知
- [ ] Claude Code 连接 + 3 个端到端任务
- [ ] 双通道一致性测试（同一任务走 MCP 和本地 Agent）
- [ ] 轨迹日志（JSONL）

### 13.2 S1/S2 的具体任务清单

**工作资料后端：**

- [ ] `DeviceAdminReceiver` + `ACTION_PROVISION_MANAGED_PROFILE`
- [ ] 工作资料状态检测、创建引导、错误处理
- [ ] `ProfileBackend.install`（PackageInstaller / Shizuku pm）
- [ ] `ProfileBackend.launch`（LauncherApps / DPC）
- [ ] 跨资料观察 POC（个人资料无障碍 vs profile 内 helper）
- [ ] 空间 UI：App 网格、安装、卸载、启动
- [ ] 跨资料通信（DPC 或 localhost）
- [ ] 权限/隐私说明

**用户态容器（长期 R&D，可选）：**

- [ ] 选定基线（Wayondroid / 自研 userspace runtime）
- [ ] guest 镜像构建、签名、分发
- [ ] binder / SurfaceFlinger / GPU / 音频 / 输入适配
- [ ] 机型矩阵、性能/内存/电量基线
- [ ] GMS/镜像许可

**容器后端（如果必须 App 内装 APK）：**

- [ ] 选定引擎（商业 VA / BlackBox 系 / 自研）
- [ ] APK 预检（附录 A）
- [ ] 安装/启动/停止/卸载
- [ ] 权限映射、通知、后台、Keystore、GMS 逐项验证

### 13.3 容器后端的 Go/No-Go 标准

- 10 个目标 App（含微信/支付宝/淘宝/抖音/银行/游戏/Flutter/WebView）中：
  - 8 个能启动到主界面
  - 7 个能完成核心流程（登录后搜索/发消息/浏览）
  - split APK 安装成功率 ≥ 90%
  - Android 15/16/17 各至少 1 台设备不崩溃
  - 不加载 `REQUIRE_SECURE_ENV=1` 的 App
  - 16 KB page 设备安装/启动正常
- 任一不达标 → 不产品化容器路线。

---

## 14. 测试矩阵

### 14.1 设备

| 类型 | 示例 | 关注点 |
|---|---|---|
| Pixel | Pixel 8/9/10（Android 15/16/17） | 原生行为、AVF、AAPM |
| Samsung | S24/S25/S26（One UI） | 工作资料、Knox、后台限制 |
| 小米 | 14/15（HyperOS） | 多用户/工作资料限制、后台 |
| OPPO/vivo | Find X / X 系列 | 权限、后台、无障碍 |
| 华为 | 无 GMS 机型 | GMS 依赖、HMS |
| 16 KB page | 任意 Android 15+ 新机 | native lib 对齐 |

### 14.2 App

- 微信、支付宝、淘宝、抖音、小红书
- 银行 App（强完整性校验）
- 游戏（Unity/UE，Surface/Canvas）
- Flutter App（节点树可能不完整）
- WebView 为主的 App
- Google Maps（GMS）
- 自研测试 App（可注入崩溃/弹窗/延迟）

### 14.3 场景

- 安装：单 APK、split APK、XAPK、低 targetSdk、16 KB
- 权限：首次启动、运行时权限、拒绝/允许
- 登录：账号密码、验证码、生物识别
- 核心流程：搜索、发消息、下单（不支付）、浏览
- 系统交互：通知、后台、旋转、分屏、锁屏、低内存
- 异常：断网、超时、崩溃、弹窗、广告
- 双通道：同一任务分别走本地 Agent 和远程 MCP，结果一致
- 并发：本地 Agent 持有租约时，远程 MCP 修改类调用返回 `SPACE_BUSY`；观察类不受影响
- 审批：两条通道都能触发同一个审批 UI；超时拒绝

### 14.4 指标

- 安装成功率、启动成功率、任务成功率
- 单步延迟 p50/p95
- 崩溃率、ANR
- 每次任务 token 成本
- 误点率、需要人工接管的比例
- 容器：兼容性通过率、16 KB 通过率
- 工作资料：OEM 创建成功率、跨资料观察成功率
- 双通道：工具一致性通过率、租约冲突/排队/接管次数、审批超时率

---

## 15. 风险清单

| 风险 | 影响 | 缓解 |
|---|---|---|
| 容器引擎兼容性 | Android 15–17 上大量 App 无法运行 | 买商业 VA；固定测试矩阵；不承诺全兼容 |
| 用户态容器 alpha | 性能/机型/镜像分发不可控 | 仅 R&D；不作为第一版主后端 |
| Android 17 AAPM | 无障碍控制不可用 | 多通路设计；Shizuku/in-process/VM ADB 优先 |
| Google Play 政策 | 下架/拒审 | 容器遵守 REQUIRE_SECURE_ENV；无障碍不作为唯一卖点；企业分发 |
| GPL/商业授权 | 法律风险/成本 | 商业授权；法务评估 BlackBox 许可链 |
| 容器安全 | 恶意 App 读数据 | 不装不可信 APK；强隔离用工作资料/VM |
| 双通道并发 | 本地/远程同时操作，互相打断 | 租约 + 会话 + 用户接管；修改类必须独占 |
| 本地 Agent 模型能力 | 端侧模型规划/工具调用弱 | 用 ClaudeAdapter 兜底；端侧只做简单任务 |
| 本地 Agent 设备限制 | Gemini Nano 4 仅部分机型 | 提供云模型 fallback；启动时检测能力 |
| 提示注入 | Agent 被屏幕内容操控 | 屏幕文本当数据；工具白名单；危险操作确认 |
| API key 泄露 | 费用/滥用 | Keystore 存储；代理服务；限额 |
| 自动化违反 ToS | 账号封禁 | 用户告知；限速；不碰支付/风控 |
| OEM 限制 | 工作资料/后台/权限不可用 | 机型矩阵；降级方案 |
| 性能/电量 | 体验差 | 前台服务优化；截图降频；按需唤醒 |
| MCP 暴露 | 局域网被控 | 默认 loopback；token；TLS；关闭 LAN 开关 |
| 隐私 | 屏幕内容含敏感信息 | 脱敏；不落盘；审计；用户可关闭截图 |

---

## 16. 参考项目与资料

### 虚拟化 / 容器

- VirtualApp（asLody）：https://github.com/asLody/VirtualApp
- BlackBox / NewBlackbox：https://github.com/ALEX5402/NewBlackbox
- CloniX：https://github.com/txorav/CloniX
- Wayondroid：https://github.com/yoro1836/wayondroid
- redroid：https://github.com/remote-android/redroid-doc
- ScrcpyOverWebRTC：https://github.com/hqw700/ScrcpyOverWebRTC
- VMOS Pro 指南：https://vmospro.net/vmos-pro-guide/index.html

### Android 系统能力

- Private Space（AOSP）：https://source.android.com/docs/security/features/private-space
- Android 16 CDD（clone profile）：https://source.android.com/docs/compatibility/16/android-16-cdd
- Android Virtualization Framework：https://source.android.com/docs/core/virtualization
- Android 17 开发者预览：https://developer.android.com/about/versions/17
- 16 KB page size：https://developer.android.com/guide/practices/page-sizes
- AppFunctions：https://developer.android.com/ai/appfunctions
- DPC 专用设备 cookbook：https://developer.android.com/work/dpc/dedicated-devices/cookbook

### 控制 / 自动化

- Shizuku：https://github.com/RikkaApps/Shizuku
- Shizuku 特权 API（HackTricks）：https://hacktricks.wiki/zh/mobile-pentesting/android-app-pentesting/shizuku-privileged-api.html
- uiautomator2-mcp-server：https://pypi.org/project/uiautomator2-mcp-server/
- mobile-next/mobile-mcp：https://github.com/mobile-next/mobile-mcp
- droidrun：https://github.com/droidrun/droidrun
- MCP Kotlin SDK：https://github.com/modelcontextprotocol/kotlin-sdk

### 政策

- Google Play 容器 App 与 REQUIRE_SECURE_ENV：https://support.google.com/googleplay/android-developer/answer/13609005
- 限制容器加载（Android Developers）：https://developer.android.com/training/basics/intents/limit-play-loading
- Android 17 AAPM 报道：https://www.androidauthority.com/android-17-beta-2-advanced-protection-mode-accessibility-apps-3648860/
- OWASP MASTG 无障碍数据敏感性：https://mas.owasp.org/MASTG/knowledge/android/MASVS-PLATFORM/MASTG-KNOW-0143/

### 端侧模型

- ML Kit GenAI Agent API：https://developers.google.com/ml-kit/genai/agent/android
- Gemma 4 本地 Agent：https://android-developers.googleblog.com/2026/04/gemma-4-new-standard-for-local-agentic-intelligence.html
- Android AI 概览：https://developer.android.com/ai

---

## 附录 A：APK 预检清单（容器后端必做）

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
| DPC | Device Policy Controller，设备策略控制器 |
| managed profile | 工作资料，独立 user |
| profile owner | 工作资料的所有者 App |
| device owner | 整台设备的 owner（企业完全托管） |
| AAPM | Advanced Protection Mode，Android 17 的高级保护模式 |
| MCP | Model Context Protocol |
| Streamable HTTP | MCP 的 HTTP 传输方式，替代旧 SSE |
| split APK | App Bundle 拆分出的 base + config APK |
| 16 KB page | Android 15+ 新设备的内存页大小，native lib 需对齐 |
| REQUIRE_SECURE_ENV | App 声明"不允许在容器中加载"的 manifest 属性 |

---

## 附录 C：最小可行 Demo（建议先做这个）

**目标**：同一个任务，分别由远程 Claude Code（MCP）和本地 Agent 控制手机上的"设置"App，完成"打开设置 → 搜索 WLAN → 打开 WLAN"。

**范围**：

- 不做虚拟化
- 不做安装器
- 只做：
  1. `ToolRegistry`：3 个工具（`ui_observe`、`ui_tap`、`ui_key`），参数校验 + scope
  2. `SpaceAccessibilityService`（observe + tap + back）
  3. `McpForegroundService`（Ktor + Streamable HTTP + token）
  4. `LocalAgentRuntime`（先用 `ClaudeAdapter` 或假模型；循环在 App 内）
  5. `ControlLease` + `ApprovalGate`（至少实现独占租约和确认弹窗）

**验收**：

- 远程 Claude Code 能列出/调用工具，完成 3 个任务
- 本地 Agent 能用同一套工具完成同样任务
- 两条通道的工具行为一致（同一输入 → 同一结果/错误码）
- 本地 Agent 持租约时，远程修改类调用返回 `SPACE_BUSY`；观察类不受影响
- 单步 p50 < 2 s
- 失败时有结构化错误

这个 Demo 跑通后，再决定后端是工作资料还是容器，会容易得多。

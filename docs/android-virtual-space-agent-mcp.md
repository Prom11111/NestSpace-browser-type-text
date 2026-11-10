# NestSpace：Android 自研应用级容器 + 双通道 Agent/MCP 技术方案

> 本文是技术设计与实现记录，面向开发和二次开发；日常使用请看 [NestSpace 使用手册](README.md)。
>
> 项目：`NestSpace`（`com.coke.nestspace`，Jetpack Compose，minSdk 24 / targetSdk 37）
> 日期：2026-11-03
> 约束：普通 APK、不能控制 ROM；**使用自研容器引擎**；本地 Agent 与远程 MCP 双通道都要支持。
> 状态：Stage A/B + Stage C-lite + Stage C-D（独立进程/崩溃隔离、Job/通知虚拟化、宿主/系统广播桥接、Binder/UID 权限模型、完整 VPMS、跨虚拟应用查询、权限授予、多实例 .so 进程隔离）已实现并在 Android 7.1.1 / Android 16 / Android 17（API 37 模拟器）/ MuMu(Android 15, ARM bridge) 验证；本地 LLM Agent（Claude/OpenAI 兼容，共用 chat 协议）已实现并用 mock 与第三方 Anthropic 兼容端点实网验通；完整后台 Job/Alarm 唤醒与系统侧真实 UID 欺骗仍属于后续增强

> 当前实现已拆分 `plugin-api` 和 `runtime-api`，并加入 Debug 源码插件、
> 插件中心、自定义设置 View 和版本化运行时代理插件。本文保留早期容器设计背景；
> 当前模块边界以 [架构与限制](architecture.md)、
> [运行时模块边界](https://github.com/chuccp/NestSpace/blob/main/docs/runtime-modules.md) 和
> [插件编写指南](plugin-authoring.md) 为准。

---

## 阅读路径

| 你想先看什么 | 直接跳到 |
|---|---|
| 结论、可行性边界、自研约束 | **0** |
| 需求与范围 | 1 |
| 容器总体架构、进程模型、Hook 路线 | 2 |
| 关键子系统（PMS/AMS/ClassLoader/Binder/IO） | 3 |
| Agent 怎么操作虚拟 App | 4 |
| 本地 Agent + 远程 MCP 双通道 | 5 |
| 架构、模块、接口、工程改造 | 6 |
| 建设顺序、测试矩阵、风险 | 7–9 |

---

## 0. 结论

### 0.1 技术路线

- **自研应用级容器引擎**：在宿主 App 内加载未安装的 APK，虚拟化 PackageManager/ActivityManager/Binder/IO，让目标 App 以为自己在正常系统里运行。
- **Java 层虚拟化优先，关键点 native hook**：先用 `Instrumentation`/`ActivityThread`/`PackageManager`/`ContextImpl` 的 Java hook 跑通；Binder/UID/native 文件路径等硬点再上 native hook。
- **单进程 MVP → 多进程产品**：先在单进程跑通简单 APK，再由宿主声明的 `:c1..:c16` 进程池承载多实例和多进程。
- **双通道 Agent/MCP**：本地 Agent（App 内循环）+ 远程 Agent（MCP），共用同一套 `ToolRegistry`、权限、租约、审计。
- **AppFunctions 快路径（可选）**：宿主 App 可导出 AppFunctions；容器内 APK 的 AppFunctions 走本地桥接；不可用时回退 UI 自动化。
- **容器引擎采用 clean-room 自研实现**，不复制外部项目代码。

### 0.2 三层架构

```text
┌─────────────────────────────────────────────────────────────┐
│ 3. Agent / MCP 层                                           │
│    本地 Agent（App 内 loop）  |  远程 Agent（MCP）          │
│    共用 ToolRegistry / 租约 / 审批 / 审计                   │
├─────────────────────────────────────────────────────────────┤
│ 2. 控制层                                                   │
│    ContainerService（空间/安装/启动）                        │
│    Shizuku / Accessibility / Vision（UI 观测与操作）        │
├─────────────────────────────────────────────────────────────┤
│ 1. 自研容器引擎                                             │
│    Installer / VPMS / VAMS / ClassLoader / Resources        │
│    Binder/UID / IO Redirect / Permissions / Process Pool    │
└─────────────────────────────────────────────────────────────┘
```

### 0.3 可行性边界（先说实话）

**能做：**

- 普通 APK、单进程、单实例
- 多开、独立数据目录
- 无强完整性校验的内容/工具/社交类 App
- 用 Shizuku/无障碍/视觉控制虚拟 App
- 本地 Agent + 远程 MCP 双通道

**难做，需要逐项攻坚：**

- 多进程 + Binder/UID 虚拟化
- split APK / AAB
- 16 KB page / native libs
- 通知、JobScheduler、AlarmManager、ContentProvider
- 后台启动 Activity（Android 10+ 限制）
- 目标 App 的签名校验 / Keystore
- Android 15–17 的 hidden API 变化

**做不了/不承诺：**

- 真正的 UID/SELinux 隔离（应用级容器共享宿主 UID）
- 金融/银行/支付/Play Integrity/GMS 强依赖 App
- 强反容器/反 hook/反调试 App
- "所有 App 都能跑"

> 自研的正确目标不是"替代 Android 系统"，而是"在普通 App 里跑一个受控的、兼容性有限的第二空间"。产品文案要按这个边界写。

### 0.4 最大技术风险

1. **Hidden API / ART / Binder 版本适配**：Android 每个大版本都会改 `ActivityThread`、`PackageParser`、`Resources`、Binder 事务；没有"一次写完长期稳定"。
2. **Binder/UID 虚拟化**：系统服务看到的是宿主 UID；Java 层能骗一部分，系统侧权限/AppOps/账号/支付等仍按宿主 UID 判定。这是应用级容器的根本限制。
3. **后台启动 Activity**：Android 10+ 限制后台启动；虚拟 App 从宿主后台启动会被拦。需要前台服务/通知/用户操作触发。
4. **通知/Job/Provider**：系统按包名/UID 归属；虚拟化这些比 Activity 难得多，很多 App 的后台能力会退化。
5. **Keystore/签名/Play Integrity**：同 UID 下 Keystore alias 冲突；签名校验/完整性校验大概率失败。
6. **进程模型**：`app_process` 子进程会被 phantom process killer 杀；宿主声明的 `:c1..:c16` 进程池更稳，并需要处理进程复用、内存和崩溃隔离。
7. **16 KB / split APK**：安装器和 native lib 加载都要适配。
8. **长期维护**：这是"mini Android runtime"，不是普通 App 功能；需要持续跟 AOSP。

### 0.5 自研 ≠ 无约束

自研实现仍需要遵守以下约束：

- **Clean-room**：参考 AOSP（Apache-2.0）和公开文档实现，不复制外部容器项目代码。
- **Google Play 容器政策**：容器 App 允许，但必须检查目标 App 的 `REQUIRE_SECURE_ENV`，不得代理容器外 App 的 API。
- **目标 App 的 ToS**：自动化操作、重打包、绕过校验可能违反第三方 App 条款。
- **Native hook 库许可证**：ShadowHook/ByteHook/Dobby/xDL 等要逐个核对许可证和商用条款。
- **Hidden API 限制**：Android 9+ 对非 SDK 接口有限制；不同版本需要不同绕过策略，稳定性自负。

---

## 1. 需求与约束

### 1.1 目标

- 在 App 内创建多个"空间"。
- 空间内安装 APK、启动、停止、多开。
- 本地 Agent 用自然语言操作空间内 App。
- 远程 Agent 通过 MCP 操作同一个空间。
- 两条通道行为一致，不会互相打断。

### 1.2 约束

- 普通 APK，不能控制 ROM/系统签名。
- 自研容器引擎。
- Android 12–17，arm64，尽量不 root。
- 本地 Agent 模型可以是端侧（Gemini Nano 4/Gemma 4）或云（Claude API）。

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
- 空间之间数据隔离（逻辑隔离）
- 崩溃率、ANR、后台存活可观测
- 安装/启动失败有结构化错误
- MCP 默认只绑 loopback，LAN 需显式开启
- 本地 Agent 与远程 Agent 的工具行为一致

---

## 2. 自研容器总体架构

### 2.1 设计目标

1. 目标 App 的 `Activity` 能正常创建、显示、接收输入、走生命周期。
2. 目标 App 认为自己已安装：`PackageManager` 能查到自己的 `PackageInfo`、权限、签名。
3. 目标 App 的私有数据落到独立目录，不污染宿主。
4. 多个实例可以共存。
5. 崩溃不要拖垮宿主。
6. 控制层能观察/操作目标 App 的 UI。

### 2.2 进程模型

**阶段 A：单进程（MVP）**

- 宿主主进程里加载目标 APK；目标 Activity 跑在宿主进程。
- 优点：实现简单，调试方便，不需要跨进程。
- 缺点：目标 App 崩溃会拖垮宿主；多开共享一个进程；内存压力大。
- 适合：验证可行性、简单 App。

**阶段 B：宿主声明的 `:c1..:c16` 进程池（产品）**

- 在宿主 `AndroidManifest.xml` 里声明 `:c1..:c16` 进程槽位，并为每个进程声明一个 `StubActivity`/`VClientService`。
- 启动虚拟 App 时，选择空闲进程，启动该进程里的 `StubActivity`；系统会创建正常的 app 进程和 `ActivityThread`。
- 在该进程里初始化容器 hook，加载目标 APK，运行目标 Activity。
- 优点：是正常 app 进程，不受 phantom process killer 影响；崩溃隔离；可并行多实例。
- 缺点：进程池大小固定；内存/启动成本；进程复用要清理状态。

> 不建议用 `Runtime.exec("app_process ...")` 启子进程：Android 12+ 的 phantom process killer 会杀，且权限/进程归属更复杂。

**进程分配策略：**

- 每个空间一个进程；或每个实例一个进程；或按内存/前台状态动态分配。
- 进程池默认按设备内存自动计算，最多 16 个；固定模式使用用户选择的 1–16。
- 前台实例优先独占；后台实例可复用进程。
- 进程崩溃后标记 dirty，清理后再复用。

### 2.3 总体图

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

### 2.4 两条实现路线

| 路线 | 做法 | 优点 | 缺点 |
|---|---|---|---|
| **Java 层虚拟化**（应用级容器） | Hook `Instrumentation`、`ActivityThread.mH`、`ApplicationPackageManager`、`ContextImpl`、`Binder` Java 方法；动态代理/反射 | 不需要 native hook；调试相对容易；能覆盖大部分 Java 调用 | Hidden API 限制；Binder/UID/native 层骗不过；每个 Android 版本都要适配 |
| **Native hook / ART** | Hook `Binder.execTransact`、`Process.myUid`、`open`、`dlopen`、ART 方法 | 更强；能处理 native 代码和系统调用 | 极复杂；ABI/版本/ROM 差异；稳定性/安全风险高 |

**推荐：Java-first，native 只打关键点。**

- 第一版：Java 层 hook 跑通 Activity/PMS/Resources/IO。
- 第二版：对 Binder/UID/native 路径做 native hook。
- 不要一开始就写"通用 ART hook 框架"；先用成熟 hook 库，把业务跑通。

### 2.5 Hook 框架

**Java 层：**

- 反射 + 动态代理（`Proxy`/`InvocationHandler`）替换 `IPackageManager`、`IActivityManager` 等接口。
- `Unsafe`/`MethodHandles` 修改 final 字段。
- Hidden API 豁免：`VMRuntime.setHiddenApiExemptions` 或 "FreeReflection" 类方案；Android 10+ 需要按版本处理。
- `ActivityThread.mH` 的 `Handler.Callback` 替换（注意不能破坏系统消息）。
- `Instrumentation` 子类替换（`ActivityThread.mInstrumentation`）。

**Native 层：**

- PLT/GOT hook、inline hook；优先用维护中的库（ShadowHook、ByteHook、Dobby、xDL），逐个核对许可证。
- 关键 hook 点：`Binder.execTransact`、`Process.myUid`、`open`/`fopen`、`dlopen`/`android_dlopen_ext`。
- 版本适配：每个 Android 大版本维护 offset/符号表；用 feature flag 灰度。

**版本适配层：**

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

每个 API level 一个实现；公共逻辑抽到 base；不要在新版本上假设旧 hook 点还在。

---

## 3. 关键子系统

### 3.1 APK 解析 / 安装器

**输入：**

- 单 APK
- split APK（base + config.arm64_v8a + config.xxhdpi 等）
- `.apks` / `.xapk`（解包后按 split 处理）

**解析：**

- 二进制 `AndroidManifest.xml`（AXML）：package、versionCode、minSdk、targetSdk、application、activities、services、providers、receivers、permissions、meta-data、`REQUIRE_SECURE_ENV` property。
- `resources.arsc`：资源表、配置、split 资源。
- 签名：V1/V2/V3；签名者/证书摘要。
- native lib：ABI、16 KB 对齐。
- split 元数据：`splitNames`、`splitTypes`、`configForSplit`、`isFeatureSplit`。

**实现选择：**

- 用 AOSP `PackageParser`/`ParsingPackageUtils`（hidden API，版本差异大）；或
- 用开源 APK 解析库（如 ARSCLib/apk-parser）自己建模型；或
- 两者结合：解析用开源库，运行时 `PackageInfo` 自己构造。

**安装：**

1. 预检（附录 A）。
2. 拷贝 APK/splits 到空间私有目录。
3. 解析并构造内部 `PackageInfo`/`ActivityInfo`/`ServiceInfo`/`ProviderInfo`。
4. 创建 `data/`、`files/`、`cache/`、`databases/`、`shared_prefs/`、`lib/`。
5. 注册到 VPMS。
6. 创建 launcher/shortcut。
7. 首次启动时初始化 `Application`。

**必须检查：**

- `REQUIRE_SECURE_ENV=1` → 拒绝加载。
- 未对齐 16 KB 的 native lib → 在 16 KB 设备上拒绝/提示。
- 不完整 split → 拒绝。
- 低 targetSdk → 是否允许，按 Android 版本策略。

### 3.2 虚拟 PackageManager（VPMS）

目标：让目标 App 通过 `Context.getPackageManager()` / `ActivityThread.getPackageManager()` 看到"自己已安装"。

**要虚拟化的方法（至少）：**

- `getPackageInfo` / `getApplicationInfo` / `getActivityInfo` / `getServiceInfo` / `getProviderInfo`
- `resolveActivity` / `queryIntentActivities` / `queryIntentServices`
- `getInstalledPackages` / `getInstalledApplications`
- `getPackagesForUid` / `getPackageUid` / `getNameForUid` / `getPackageGids`
- `checkPermission` / `checkSignatures` / `getPackageInfo(GET_SIGNATURES)`
- `getLaunchIntentForPackage`
- `getApplicationEnabledSetting` / `setApplicationEnabledSetting`
- `getResourcesForApplication` / `getResourcesForActivity`
- `getPackagesHoldingPermissions`

**实现方式：**

1. **Context 级包装**：给目标 App 一个自定义 `Context`/`ContextWrapper`，`getPackageManager()` 返回 `VPackageManager`；`getResources()` 返回目标 Resources；`getClassLoader()` 返回目标 ClassLoader。
2. **ActivityThread/IPackageManager 代理**：替换 `ActivityThread.getPackageManager()` 返回的 `IPackageManager` 动态代理；非虚拟包转发给系统，虚拟包查 VPMS。
3. **ApplicationPackageManager hook**：对直接 `new ApplicationPackageManager(...)` 或静态调用的场景做补充。

> 优先做 1 + 2；3 作为兜底。`IPackageManager` 是 AIDL 接口，Android 版本间方法会变，代理要按版本生成/适配。

**签名：**

- 返回目标 APK 的真实签名（如果可信），或按产品策略伪造；后者会触发完整性校验，不建议。
- `checkSignatures` 对自己返回 `MATCH`；对系统包按真实结果。

### 3.3 虚拟 ActivityManager（VAMS）

目标：让目标 App 的 `startActivity`/`startService`/`bindService`/`sendBroadcast` 能启动到虚拟组件，而不是被系统拒绝。

**核心：Stub Activity 方案**

1. 宿主 Manifest 声明一组 `StubActivity0..N`（可带 `android:process=":pX"`、不同 `launchMode`/`taskAffinity`）。
2. Hook `Instrumentation.execStartActivity`：
   - 判断目标 Intent 是否指向虚拟包；
   - 是：把 component 替换成某个 `StubActivity`，把原始 Intent/目标组件/实例 id 存到 extras 或 VAMS 映射；
   - 否：放行。
3. 系统启动 `StubActivity`；`ActivityThread` 创建它。
4. Hook `Instrumentation.newActivity` / `ActivityThread.mH`：
   - 用目标 APK 的 `ClassLoader` 实例化目标 Activity；
   - 替换 `StubActivity` 的实例/字段，或让 `StubActivity` 代理生命周期；
   - 注入目标 `Resources`、`Context`、`Application`、`Window`。
5. 生命周期回调转发到目标 Activity；`onBackPressed`/`onNewIntent`/`onActivityResult` 也要处理。
6. 任务栈：把 StubActivity 映射回目标 Activity；`finish`/`moveTaskToBack`/最近任务显示目标名称/图标。

**已实现：Stage C-lite（2026-11-03）**

没有引入 `StubActivity0..N` 数组，而是复用 `StageAActivity` 本身作为“通用 Stub”：

- 每个目标 Activity 对应一个真实 `StageAActivity` 实例，通过 `host.startActivity`/`startActivityForResult` 入栈；Android 自己的任务栈负责返回。
- `ContainerInstrumentation` 声明 AOSP 的隐藏方法 `execStartActivity(Context, IBinder, IBinder, Activity, Intent, int, Bundle)`（不加 `override`，先安装 HiddenApiBypass 豁免），运行时由 ART 虚分派到它。
- 目标包内的显式 ComponentName（含 activity-alias）→ 新建 `StageAActivity`，extras 携带 sessionId、目标类、原始 Intent；外部 Intent → 交给当前宿主启动。
- `ContainerSessions` 按 sessionId 共享 target Application/ClassLoader/Resources；每个目标 Activity 只创建自己的 Activity 实例、Theme 和 decorView。
- `finish()` 使用真实 host token，所以只关闭当前目标对应的 `StageAActivity`，上一个宿主自然恢复；Splash → 首页、`startActivityForResult` 结果回传均已验证。
- 生命周期由宿主转发：onCreate/onStart/onRestart/onResume/onPause/onStop/onDestroy，以及 `onActivityResult`（反射调用目标 Activity 的 protected 方法）。
- 尚未实现：IntentFilter/隐式 Intent 解析、`onNewIntent` 的 singleTop/CLEAR_TOP 语义、Activity options/转场、`requestPermissions` 的 token 映射、最近任务显示目标名称/图标。
- 真实目标应用兼容补充（MuMu / Android 15 验证）：
  - 目标 APK 可能自带 AndroidX。`DexClassLoader` 默认 parent-first，会让目标类链接到宿主的 AndroidX，出现字段缺失。`ContainerClassLoader` 改为 child-first，先查目标 dex，再回退宿主/系统。
  - 目标 Activity 的 `attachBaseContext` 可能调用 `createConfigurationContext` 处理 locale；早期实现会返回宿主资源。`ContainerContext` 现在用目标 APK 的 AssetManager + 新 Configuration 重建 Resources，并返回一个保持目标包名、ClassLoader 和目录的 ContextWrapper。
  - 目标应用仍可能依赖系统服务绑定、Provider 或 Binder 虚拟化；未覆盖时应输出明确诊断，而不是增加针对单个应用的启动特判。

**已实现：容器内 Service / Provider（Stage C-lite+）**

- Service：
  - `ContainerContext` 覆盖 `startService`/`startForegroundService`/`bindService`/`unbindService`/`stopService`；目标包显式 Service 交给 `ContainerServiceManager`，外部 Service 回退宿主。
  - 目标 Service 用目标 `ContainerClassLoader` 实例化，`attachBaseContext(ContainerContext)`，设置 `Service.mApplication`，直接驱动 `onCreate`/`onStartCommand`/`onBind`/`onUnbind`/`onDestroy`。
  - `bindService` 把 `onBind` 返回的 `IBinder` 通过主线程 `onServiceConnected` 交给目标 `ServiceConnection`；`startActivityForResult` 与 Service 回调都在目标进程内完成。
  - 仅显式 ComponentName；隐式 Service Intent 未解析。`startForeground`/`stopSelf` 仍可能触达宿主 AMS。
- ContentProvider：
  - Session 启动时（Application.onCreate 之前）按 manifest 创建目标 Provider，`attachInfo(ContainerContext, ProviderInfo)` 会执行 `onCreate`。
  - 用 `ContentResolver.wrap(dispatcherProvider)` 得到目标 resolver；dispatcher 按 URI authority 路由到本地 Provider，外部 authority 回退宿主 application context 的真实 resolver。
  - 覆盖 query/insert/update/delete/bulkInsert/getType/call/openFile/openAssetFile/openTypedAssetFile；目标 Provider 之间可以互相查询。
  - Provider 权限、进程隔离、`FileProvider` 的 PackageManager meta-data 查询未虚拟化。
**已实现：Stage C-D（2026-11-04）**

- 进程模型：`ContainerProcessPool` 把每个 `package@instance` 固定到 `:c1..:c16` 之一；slot 复用前强制 kill 并等待退出，`ContainerSessions` 在进程内拒绝第二个 session。目标后台线程的未捕获异常由 `TargetCrashGuard` 记录；目标主线程未捕获异常按 Android 的正常致命语义写入崩溃标记并结束该 slot，避免半初始化页面继续运行后把容器拖成无响应；native crash/`System.exit` 同样只杀死 slot，主进程与其它实例不受影响。下次 `space_launch` 返回 `restarted_after_crash`/`crash_reason`。
- JobScheduler：`ContainerContext.getSystemService(JOB_SCHEDULER_SERVICE)` 返回 `ContainerJobScheduler`；目标 `JobService` 用目标 ClassLoader 实例化，驱动 `onCreate`/`onStartJob`/`onStopJob`，支持最小延迟、周期、退避和网络/充电/低电量/空闲约束（无法判断的约束按满足处理）。进程存活期间有效。
- 通知：`INotificationManager` 代理把包名改成宿主包，tag/channel/group 加虚拟包前缀，small icon 重绘为 bitmap，目标 PendingIntent 换成宿主 `ContainerLaunchProxyActivity` 跳板；channel 查询结果去掉前缀。
- 广播桥接：目标静态/动态 Receiver 的 action 会注册到宿主；系统 sticky/普通广播注入容器，目标发出的非自身作用域广播在本地分发后继续发给宿主，并带 origin extra 防回环。
- 权限：`checkSelfPermission`/`checkPermission`/`enforce*`/PackageManager 权限查询走虚拟授权表；重写 `ContainerPackageManager.buildRequestPermissionsIntent`/`getPermissionControllerPackageName` 并声明隐藏的 `Context.getPermissionRequestState`，使 `requestPermissions` 能被容器接管。宿主声明的权限会弹真实的系统对话框，结果按包持久化；MCP `space_permissions` 支持 list/grant/revoke。
- 完整 VPMS：`VirtualPackageRegistry` 从 `state.json` 建轻量索引，按需解析同空间其它 APK；`getInstalledPackages`/`getInstalledApplications`、显式组件、隐式 Intent、`resolve*`/`query*`、标签/图标/Resources/启动 Intent/签名/权限声明均覆盖跨虚拟应用。其它应用使用稳定合成 UID。
  - 性能约束：列表/标签/UID/签名走轻量索引；未指定包的隐式 Intent 不扫描所有虚拟应用，只有显式包/组件查询才解析对应 APK。否则重资源目标应用启动时解析另一大型 APK 会阻塞主线程。
- 兼容性约束：`WindowManager` 动态代理只在实际需要 Toast 包名改写的目标上下文启用。全局代理可能被目标 native SDK 传入 JNI，触发 CheckJNI `invalid jobject` 并终止容器进程。
- 多实例 `.so` 隔离：同包不同实例分配不同 slot；native 库按实例目录提取；slot 复用时先杀旧进程，避免同名 `.so` 与 JNI 全局状态混用。

**其他组件：**

- BroadcastReceiver：静态/动态注册；静态 receiver 需要在 VPMS 里解析，动态注册 hook `registerReceiver`；宿主侧桥接见 Stage C-D。
- JobScheduler/通知已做容器内虚拟化（见上）；WorkManager 若走自身的 `SystemJobScheduler` 会使用容器 JobScheduler，依赖系统唤醒的持久后台任务仍会退化。`startForeground` 前台服务仍可能触达宿主 AMS。

**后台启动 Activity：**

- Android 10+ 限制后台启动；宿主必须在前台，或有通知/前台服务/用户交互。
- 方案：`SpaceLaunchActivity` 透明前台 Activity + 通知；或在用户点击时启动；不要假设后台 Agent 随时能拉起界面。
- Android 14+ 对 `PendingIntent` 后台启动还有额外限制，需逐版本验证。

### 3.4 ClassLoader / Resources

**ClassLoader：**

- 用 `DexClassLoader`/`PathClassLoader` 加载目标 APK 的 dex（base + splits）。
- 父 ClassLoader：宿主 ClassLoader（能共享 AndroidX/Kotlin 运行时）或 `ClassLoader.getSystemClassLoader()`；注意类冲突（目标 App 自带同名库）。
- 多 dex：`BaseDexClassLoader` 支持 dex 列表；split 的 dex 都要加。
- 目标 App 的 `Application`/`Activity` 类通过目标 ClassLoader 加载。
- `LoadedApk` 构造：反射创建/填充 `mApplicationInfo`、`mClassLoader`、`mResources`、`mAppDir`、`mNativeLibraryDir`、`mDataDir`；注册到 `ActivityThread.mPackages`。

**Resources：**

- `AssetManager` 添加 base + 所有 split 的资源路径。
- 用目标 `DisplayMetrics`/`Configuration` 创建 `Resources`；设置 `Resources.Theme`。
- 处理 `resources.arsc` 的 split/overlay；资源 id 冲突要按 split 合并。
- `Context.getResources()`/`getAssets()`/`getTheme()` 返回目标资源。
- 图标/名称：从目标 `ApplicationInfo`/`Resources` 读取，用于空间 UI。

**Application：**

- 由 `runtime-compat` 的 `CompatApplicationFactory` 创建目标 Application，并处理普通、Tinker 和 ApplicationLike 启动链。
- `attachBaseContext(targetContext)` → `onCreate()`。
- 目标 App 的 `ContentProvider` 初始化顺序要模拟（`installContentProviders`）。

### 3.5 Binder / UID 虚拟化（最难）

**目标：**

- 目标 App 调 `Binder.getCallingUid()`/`Process.myUid()`/`UserHandle.myUserId()` 时，看到虚拟 UID/user。
- 目标 App 调系统服务时，系统认为它是宿主 UID（现实），但 App 侧以为自己是虚拟 UID。

**Java 层能做的：**

- Hook `Process.myUid()`、`Binder.getCallingUid()`、`UserHandle.myUserId()`、`Process.myUserHandle()` 的返回值。
- Hook `PackageManager.getPackagesForUid`/`getNameForUid`/`getPackageUid` 返回虚拟结果。
- Hook `Context.getUserId()`/`getPackageName()`/`getOpPackageName()`。

**做不到的：**

- 系统服务侧（AMS/PMS/AccountManager/Location/Notification）仍按宿主 UID 判定权限/AppOps；你无法在不改系统的前提下让系统认一个不存在的 UID。
- Binder 事务里的 `uid`/`pid` 是内核填的；Java 层改不了。要改只能 native hook `Binder.execTransact`/`Parcel`，风险极高。
- 因此：**应用级容器无法提供真正的 UID 隔离**；它只能"骗 App 的 Java 层"，不能"骗系统"。

**建议：**

- 第一版不做 UID 虚拟化；只做 package/component 虚拟化。
- 第二版对 `Process.myUid`/`Binder.getCallingUid` 做 Java hook，覆盖常见检查。
- 第三版再评估 native Binder hook；不要作为 MVP 前置。

### 3.6 IO / 数据隔离

**要重定向的路径：**

- `Context.getFilesDir()` / `getCacheDir()` / `getDir()` / `getDatabasePath()` / `getSharedPreferences()`
- `ContextImpl.getDataDir()` / `getDatabasesDir()` / `getSharedPreferencesPath()`
- `SQLiteDatabase.openDatabase` / `openOrCreateDatabase`
- `Environment.getExternalStorageDirectory()` / `getExternalFilesDir()`
- `System.loadLibrary` / `System.load` / `dlopen`
- `Runtime.exec` 的工作目录（可选）

**实现：**

- 给目标 App 一个 `ContextWrapper`，覆写上述方法，返回实例目录。
- 对静态/内部调用，hook `ContextImpl` 对应方法。
- 对 `SharedPreferencesImpl`/`SQLiteDatabase` 的直接路径调用，hook 打开方法。
- 对 native 文件访问，native hook `open`/`fopen`/`stat`（可选，后期）。
- 每个实例一个根目录：`/data/user/0/<host>/spaces/<spaceId>/instances/<instanceId>/<pkg>/`。

**注意：**

- 这不是安全隔离；native 代码/绝对路径可以绕过。
- 外部存储/MediaStore 的隔离有限；产品上要说明。
- 备份/恢复只覆盖允许的目录。

### 3.7 权限 / AppOps / 通知

**权限：**

- 目标 App 的 `checkPermission`/`checkCallingPermission` 走虚拟权限管理器：先查目标 App 的 manifest 权限，再查宿主是否已授予对应系统权限，最后按产品策略返回。
- 运行时权限：hook `Activity.requestPermissions`/`Fragment.requestPermissions`，宿主弹窗；用户允许后记录虚拟授权，并在需要时向系统申请宿主权限。
- `AppOpsManager`：目标 App 的操作会按宿主包名/UID 记录；如需精确，hook `AppOpsManager` 的 Java 方法（有限）。
- 权限映射表要维护：目标权限 → 宿主权限 → 是否必须。

**通知：**

- 目标 App 的 `NotificationManager.notify` 需要重写 `Notification` 的 package/tag/id，或用宿主包名发通知并保留目标图标/文案。
- `PendingIntent` 的 package/UID 要映射回虚拟组件；Android 12+ 通知 trampoline 限制。
- Android 13+ `POST_NOTIFICATIONS` 运行时权限由宿主申请。
- 第一版可以先不支持目标通知；提供"空间内通知中心"作为替代。

### 3.8 进程模型 / phantom process

- 用宿主声明的 `:c1..:c16` 进程，不用 `app_process` 子进程。
- `StubActivity`/`VClientService` 声明在 `:c1..:c16`，启动时系统创建正常 app 进程。
- 进程池自动按设备内存计算，最多 16 个；固定模式严格使用用户选择的 1–16。
- 进程复用前清理：目标 Activity/Service、ClassLoader、Resources、静态状态、文件锁。
- 进程崩溃：标记 dirty，重启；宿主 UI 显示"空间已重启"。
- 前台服务保活：宿主主进程 + 当前空间进程；避免所有进程常驻。
- Android 12+ 的 phantom process killer 不适用于正常 app 进程，但后台进程仍会被 LMK 杀；用前台服务/用户可见状态。

### 3.9 Native libs / 16 KB

- 安装时按 ABI 选择 split；把 `.so` 解压到实例 `lib/` 目录。
- `ApplicationInfo.nativeLibraryDir` 指向该目录；`LoadedApk`/`ClassLoader` 使用它。
- Hook `System.loadLibrary`/`System.load`：按目标 App 的 `nativeLibraryDir` 解析。
- 16 KB page：检查 ELF `p_align`；不支持则拒绝/提示。
- `dlopen` 命名空间：目标 `.so` 依赖的系统库由 linker 解析；自有 `.so` 用绝对路径。
- JNI：目标 App 的 `System.loadLibrary` 和 `RegisterNatives` 通常在目标 ClassLoader 下工作；注意全局 JNI 状态冲突。
- 不要在同一进程加载两个版本不兼容的同一个 `.so`（多实例可能冲突）；必要时按进程隔离。

当前实现（Stage C-D）：

- `ApkInspector` 扫描 base/split 中的 `lib/<abi>/*.so`，得到可用 ABI 集合。
- `NativeLibs` 按 `Build.SUPPORTED_ABIS` 选第一个匹配项，把该 ABI 的 `.so` 平铺提取到 `vspace/data/<pkg>/<instance>/native-libs`，用 stamp 缓存避免每次启动重复复制。
- `DexClassLoader(dexPath, codeCache, nativeLibsDir, parent)`，并设置 `ApplicationInfo.nativeLibraryDir`。
- 安装不因 ABI 失败；启动前检查，若无匹配 ABI 返回 `ABI_MISMATCH` 并给出设备/应用 ABI。arm64-only APK 在 x86_64 模拟器上会停在这里。
- `dlopen`/`android_dlopen_ext` 已接入 Hook，新模块在加载后进入增量扫描队列。
- 同包不同实例使用不同 slot；slot 复用前结束旧进程，避免同名 `.so` 和 JNI 全局状态混用。
- 通用 ELF `p_align` 预检仍不是强制门禁；16 KB 兼容由构建参数、设备测试和运行诊断共同覆盖。

### 3.10 后台 / Job / Provider / WebView

这些是"产品化"绕不过去的硬骨头，建议分阶段：

| 能力 | 难度 | 第一版策略 |
|---|---|---|
| JobScheduler | 高 | 不支持；目标 App 的后台任务退化 |
| AlarmManager | 高 | 部分支持；按宿主包名调度，唤醒后转发 |
| ContentProvider | 高 | 先不支持跨 App provider；内部 provider 尝试 hook |
| 静态 BroadcastReceiver | 中 | VPMS 解析 + 宿主 receiver 转发 |
| 通知 | 中 | 第一版不做；后续代理 |
| WebView | 中 | `WebView.setDataDirectorySuffix` 是进程级；多实例需独立进程 |
| 账号/同步 | 高 | 不承诺 |
| 桌面快捷方式 | 中 | 宿主 shortcut 指向 StubActivity |

### 3.11 Hook / 版本适配

- **Java hook 点**：`ActivityThread`、`Instrumentation`、`LoadedApk`、`ApplicationPackageManager`、`ContextImpl`、`Binder`、`Process`、`UserHandle`、`ResourcesManager`。
- **Native hook 点**：`Binder.execTransact`、`Process.myUid`、`open`/`fopen`、`dlopen`/`android_dlopen_ext`。
- **版本适配**：每个 API level 一个 `AndroidAdapter`；hook 点缺失时降级/禁用功能。
- **灰度**：按设备/Android 版本/目标 App 开关；线上崩溃可回滚。
- **测试**：每个 Android 版本至少一台真机；AOSP/Pixel 优先，OEM 后补。

### 3.12 安全边界

- 应用级容器共享宿主 UID；恶意 App 可以绕过 Java 层重定向读宿主/其他实例数据。
- 建议只安装自己熟悉的 App，不要把容器当作隔离不可信应用的手段。
- Agent 能看到屏幕敏感信息；默认脱敏、默认不记录密码/支付。
- 提示注入：屏幕文本是不可信输入；工具白名单 + 危险操作确认。
- MCP 默认 loopback；LAN 模式显式开启 + token（当前 HTTP，TLS 待补）。

---

## 4. 控制层

### 4.1 控制通路

| 通路 | 前提 | 观测 | 动作 | 延迟 | Android 17 风险 | 适用 |
|---|---|---|---|---|---|---|
| **Shizuku + shell** | 安装 Shizuku，无线调试启动 | `uiautomator dump`、`screencap` | `input tap/swipe/text/keyevent` | 100–500 ms | 不受 AAPM 影响 | **主力（如果用户愿意装 Shizuku）** |
| **Shizuku + UiAutomation** | 同上，UserService 内跑 UiAutomation | 节点树、截图 | 注入事件 | 50–150 ms | 不受 AAPM 影响 | 低延迟场景 |
| **AccessibilityService** | 用户手动开启 | 节点树、事件 | `dispatchGesture`、`ACTION_SET_TEXT` | 50–100 ms | AAPM 会限制非无障碍类 App | **主力（如果不想装 Shizuku）** |
| **Vision + 坐标** | 截图权限 | 像素 | 坐标点击（需另一通路注入） | 1–3 s | 不受 AAPM 影响 | 游戏/Canvas/Flutter 兜底 |
| **容器 in-process** | 自研 hook | 目标 View 树（可行时） | 直接调用/注入 | 最低 | 不受 AAPM 影响 | 空间操作；UI 控制待验证 |

**建议：**

- 空间操作走 `ContainerService`（安装/启动/停止/多开）。
- UI 控制做两个 adapter：`ShizukuChannel` 和 `AccessibilityChannel`，按可用性自动选择。
- Vision 只做兜底。
- 容器 in-process 只用于自研引擎内部能稳定拿到的接口，不作为第一版 UI 控制主路径。

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
| `launch_app` | package/activity | 走 ContainerService |
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
- 技能库：按 App 组织，如 `app.send_message`、`settings.open_wifi`。
- 技能是降低 token 成本的关键：常见任务走脚本+校验，不走大模型。

### 4.6 Android 17 Advanced Protection Mode（AAPM）

- 开启 AAPM 后，非无障碍类 App 无法使用 AccessibilityService，已有权限会被撤销。
- 应对：
  1. 控制层做成双 adapter：Shizuku 优先，无障碍次之，Vision 兜底。
  2. 检测 `AdvancedProtectionManager`，提示用户"关闭 AAPM 或使用 Shizuku 模式"。
  3. 不要把"无障碍自动化"作为唯一卖点。
  4. 上架 Google Play 时，核心功能要能在不依赖无障碍的情况下工作。

### 4.7 安全边界

- 容器共享宿主 UID；建议只安装自己熟悉的 App。
- Agent 能看到屏幕敏感信息；默认脱敏，默认不记录密码/支付页面。
- 提示注入：屏幕文本是不可信输入；工具白名单 + 危险操作确认。
- MCP 默认 loopback；LAN 模式显式开启 + token（当前 HTTP，TLS 待补）。
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

实现策略：

- **基础版**：内置 `ServerSocket` 最小 HTTP server（`POST /mcp`），只用 `java.net`/`java.io`，兼容 API 24/25。**不要用 Ktor 3.x**：它依赖 `java.nio.file`（API 26+），在 Android 7.x 会启动失败。
- **生产版**：官方 MCP Kotlin SDK（`io.modelcontextprotocol:kotlin-sdk-server`）+ Ktor 3.x；此时 minSdk 需要 ≥ 26，或做兼容处理。
- **Transport**：Streamable HTTP（`/mcp`），支持 POST、GET SSE 心跳和 DELETE 会话；不用 stdio（Android 没有 stdio host）。
- **状态**：每个客户端独立 `Mcp-Session-Id`，30 分钟空闲回收；未知/过期会话返回 404；CORS 供 MCP Inspector 调试。
- **限制**：请求体上限 40 MB、请求头 64 KB、并发连接 32；鉴权在读取 body 前完成，Bearer Token 使用常量时间比较。

Android 侧：

- 前台服务（`dataSync` 或 `specialUse`）+ 常驻通知。
- 电池优化白名单；Doze 下保持连接。
- 默认绑定 `127.0.0.1:8765`；首次启动生成随机 token。
- LAN 模式：显式开关 + token；当前实现为 HTTP（TLS 待补），Android 17 需要 `ACCESS_LOCAL_NETWORK`。仓库提供零依赖 CLI：`tools/nestspace-mcp`。

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

已实现（NestSpace 0.3）：

- **共用 chat 协议**（参考 go-agent-sdk 的 `chat.Service` / `Messages` / `Block` 抽象）：
  - `ChatMessage` = role + `List<ChatBlock>`；`ChatBlock` = `Text` / `ToolUse` / `ToolResult`。
  - `ChatService.chat(ChatRequest): ChatResponse` 是 provider 边界；`ChatRequest` 携带 model/system/messages/tools/maxTokens/temperature。
  - `AnthropicChatService` 与 `OpenAiChatService` 只负责共享模型与各自 wire JSON 的互转。
  - `LlmAgentRuntime` 只有一份 provider-neutral 循环：调用模型 → 收集 `ToolUse` → 执行 `ToolRegistry` → 回传 `ToolResult` → 直到无工具调用。
  - 对话使用 `ChatService.streamChat` 的 SSE 流式输出；Anthropic 与 OpenAI 兼容端点都按增量事件组装文本和工具调用，Agent 循环不变。
- 设置 → **大模型 / 本地 Agent** 提供两种协议；配置保存在应用私有目录，API Key 通过 Android Keystore 加密保存。
- **Claude 兼容**：`POST {base}/v1/messages`，header `x-api-key` + `anthropic-version`，原生 `tools` / `tool_use` / `tool_result`。
- **OpenAI 兼容**：`POST {base}/v1/chat/completions`，`Authorization: Bearer`，原生 `tool_calls` / role=`tool` 消息。
- 已移除第三方自定义模板协议；旧版本保存的 `CUSTOM` 配置会被忽略并回退到 Claude 默认值，避免残留的自定义 endpoint 混入新协议。
- `LlmAgentRuntime` 复用 `ToolRegistry`，`ToolContext(sessionId, LOCAL_AGENT, "virtual-1")`，排除 session/approval 工具，最多 16 轮工具调用，单次工具结果截断 12k 字符。
- 未配置时回退到规则 `LocalAgentRuntime`；规则 Agent 仍保留系统空间能力，大模型 Agent 只控制 virtual-1。
- `AssistantConversation` 统一维护助手页与悬浮球语音助手共用的消息流和最近 24 条文本轮次；`LlmAgentRuntime.run(history = ...)` 在每次请求前回放历史，实现多轮追问。语音入口默认隐藏文字输入，最终识别结果自动提交，回复后自动恢复聆听。
- 语音识别使用内置 Vosk 离线模型（`com.alphacephei:vosk-android`），不经过云端 STT。模型在首次使用时下载，也可在悬浮球设置中管理并切换下载路径；下载前会探测 HF 国内镜像、Hugging Face 和 Vosk 官网等线路，每条线路测试 3 次并取最大值，选择最优线路，失败自动切换。中文/English 小模型分别缓存，语音结果会整理中文逐字空格，模型就绪后 `SpeechService` 持续提供 partial/final 结果。
- `LocalSpeechEngine` 在进程内缓存已加载的 `Model`；下载完成立即后台加载，App 启动时若模型已缓存也会预加载，语音页只创建 `Recognizer`/`SpeechService`，打开即可聆听。切换语言时先加载新模型；旧模型保留到进程退出，避免暂停中的语音页仍持有旧 Recognizer 时被提前释放。
- 已用本地 mock 分别验通两种协议的核心转换与工具调用往返；用户配置的 Anthropic 兼容端点在模拟器实网验通 `space_list` + `space_list_apps` 多轮工具调用。
- 单元测试：`app/src/test/java/com/coke/nestspace/llm/AnthropicChatServiceTest.kt`、`OpenAiChatServiceTest.kt`。

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

**AppFunctions 快路径**

| 工具 | 作用 |
|---|---|
| `appfn_status` | 报告 Android 16+/AppFunctionManager/EXECUTE_APP_FUNCTIONS 可用性 |
| `appfn_list` | 列出目标包暴露的 AppFunctions（宿主/容器内） |
| `appfn_execute` | 执行 AppFunction；失败/不可用时回退 UI 自动化 |

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

### 5.11 AppFunctions 快路径（宿主 / 容器内 APK）

Android 16+ 的 AppFunctions 是"系统级工具注册"：App 用 `@AppFunction` 声明能力，KSP 生成 service/XML schema，系统注册；Agent 通过 `AppFunctionManager` 发现/执行。

**三种情况，权限和能力不同：**

| 场景 | 能否用 | 说明 |
|---|---|---|
| **宿主 App 自己暴露 AppFunctions** | ✅ 可以做 | 宿主是 function owner，可以执行自己的函数；系统 Agent/Gemini 也能发现 |
| **容器内 APK 暴露 AppFunctions** | ✅ 需要本地桥接 | 虚拟包没有注册到系统，`AppFunctionManager` 看不到；安装时解析 AppFunctions 元数据，在容器进程内调用生成的 service/entry point |
| **系统里其他 App 的 AppFunctions** | ⚠️ 通常不行 | 调用方需要 `EXECUTE_APP_FUNCTIONS`，是特权/系统级权限；普通 APK 一般拿不到 |

**架构：**

- `ToolSource` 抽象：`BuiltinToolSource`（空间/UI 工具）、`AppFunctionsToolSource`（宿主/容器 AppFunctions）、`SkillToolSource`。
- `ToolRegistry` 聚合工具，命名空间 `appfn.<package>.<functionId>`；同名冲突用包名区分。
- Agent 规划时优先快路径：`appfn_execute` → 不可用/失败 → UI 自动化。
- 容器引擎在安装 APK 时解析其 AppFunctions XML/metadata；如果存在，注册到 `AppFunctionsToolSource`；执行时在容器进程内调用，避免依赖系统注册。
- 宿主 App 也可以用 `@AppFunction` 暴露 `space.list/install/launch`、`ui.observe/tap`，让系统 Agent/Gemini 直接调用宿主。这是"导出"，不是"导入"。

**权限/合规：**

- 不要假设普通 APK 能调用第三方 App 的 AppFunctions；`EXECUTE_APP_FUNCTIONS` 是 privileged。
- 宿主导出的 AppFunctions 仍然要走租约/审批/审计；不要绕过安全模型。
- 容器内 AppFunctions 仍受容器的 Binder/UID 限制；依赖系统服务的函数可能失败。
- AppFunctions 是可选快路径，不是唯一控制面；失败必须回退 UI 自动化。

**基础版：**

- 已加 `AppFunctionsBridge` 检测（API 36、`AppFunctionManager`、权限）与 `appfn_status/list/execute` 工具占位。
- 宿主导出、容器内本地桥接放在 Stage F。

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
│  AppFunctionsBridge (fast path)                              │
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
│  Host-declared :c1..:c16     | MCP Server | Local Agent      │
│  AccessibilityService / MediaProjection / Shizuku             │
│  Room / DataStore / WorkManager                              │
└──────────────────────────────────────────────────────────────┘
```

### 6.2 模块划分

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
    engine/installer/    # APK/split 解析、预检、安装
    engine/vpms/         # 虚拟 PackageManager
    engine/vams/         # 虚拟 ActivityManager、StubActivity
    engine/classloader/  # ClassLoader/LoadedApk
    engine/resources/    # Resources/AssetManager
    engine/binder/       # Binder/UID 虚拟化
    engine/io/           # 数据目录重定向
    engine/process/      # :c1..:c16 进程池、VClientService
    engine/hooks/        # Java/Native hook、版本适配
    fastpath/            # AppFunctions 检测、宿主导出、容器内本地桥接
    feature/spaces/      # Compose UI: 空间列表、App 网格
    feature/installer/   # APK 选择、预检、安装进度
    feature/agent/       # Agent 控制台、轨迹、审批
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

> 远程 Agent 走 MCP Server → ToolRegistry；本地 Agent 直接走 ToolRegistry。下面的 Engine/Control 步骤两者相同。

```text
Agent                         MCP Server / ToolRegistry      Engine / Control
  │                              │                                │
  │ space_install_apk            │                                │
  │─────────────────────────────►│ 校验 APK / REQUIRE_SECURE_ENV   │
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
  │ ui_wait_for(text="结果")     │                                │
  │─────────────────────────────►│───────────────────────────────►│ poll
  │◄─────────────────────────────│ matched / timeout              │
```

### 6.5 当前工程改造建议

1. `core/model` + `core/tools` + `core/session`：模型、ToolRegistry、租约/审批先落地。
2. `service/SpaceAccessibilityService.kt` + `res/xml/accessibility_service_config.xml`：观察/点击。
3. `core/control/ShizukuBridge.kt`：接 Shizuku API（`dev.rikka.shizuku:api` + `provider`），做 `input`/`screencap`。
4. `core/mcp/`：基础版用内置最小 HTTP server（ServerSocket），先 5 个工具（observe/tap/launch/list_apps/screenshot）；生产换 Ktor + kotlin-sdk。
5. `service/McpForegroundService.kt`：前台服务 + 通知 + token。
6. `core/agent/`：本地 Agent 骨架（`ClaudeAdapter` 或假模型），复用同一套 ToolRegistry。
7. `engine/` + `container/`：Stage A/B 单进程容器、安装器、多开和实例数据目录已实现（见第 7 节）。
8. 当前已覆盖进程池、native 库、Binder/UID、Provider/通知/Job 和 Android 7.1–17 适配；后续重点是后台唤醒、系统侧真实 UID 边界和更多通用兼容样本。

---

## 7. 建设顺序（不设工期）

| 阶段 | 目标 | 内容 | 完成标准（能力门） |
|---|---|---|---|
| **S0 控制层 + 双通道骨架** | 先把"控制"做成独立层 | ToolRegistry、MCP Server、本地 Agent 骨架、Shizuku/无障碍、租约/审批、轨迹 | 远程 MCP 和本地 Agent 都能控制已安装 App 完成 3 类任务；工具行为一致 |
| **A 单进程最小容器** ✅ 已验证 | 跑通一个简单 APK | AXML/资源解析、ClassLoader、Resources、StubActivity、Instrumentation/ActivityThread hook、Context 包装 | 一个无 native/无 GMS 的简单 APK 能在宿主进程启动并显示 UI（API 25 模拟器已验证） |
| **B 安装器 + 多开** ✅ 已验证（API 25 / API 36） | 能装、能多实例 | split APK、签名校验、`REQUIRE_SECURE_ENV`、IO 重定向、VPMS 列表/启动、实例数据目录 | 简单 App 能安装、启动、多开；数据互不污染（testapp + Android 16 已验证；base+合成 split 已验证，真实 config split 资源 overlay 待验证） |
| **C 进程池 + Native** ✅ 已实现 | 崩溃隔离、native App | `:c1..:c16` 进程池、VClientService、native lib 提取/加载、16 KB 测试、Binder/UID Java hook | 目标在独立进程运行；native lib 正常；16 KB 设备可安装并给出明确诊断 |
| **D 后台/系统能力** ✅ 核心已实现 | 主流 App 可用 | 通知、静态 Receiver、Job/Alarm 部分代理、Provider、WebView 进程隔离、Android 15–17 适配 | 通知、广播、Provider、权限和常见后台组件可用；系统死亡后的完整 Job/Alarm 唤醒仍受限 |
| **E Agent 自主性 + 安全** | 可上线 | 端侧/云模型、技能库、验证/重试、人工接管、审计、脱敏、MCP 鉴权 | 常见任务成功率达标；安全评审通过 |
| **F AppFunctions 快路径** | 可选加速 | 宿主 `@AppFunction` 导出、容器 APK 元数据解析、容器内本地执行、权限检测、UI 回退 | 宿主工具可被系统 Agent 调用；容器内 AppFunctions 可执行；不可用时回退 UI |

### 7.1 Stage A 的关键验收

在 Android 7.1.1 x86 模拟器验证（testapp APK 未安装，直接加载）：

- ✅ `Activity` 的 `onCreate/onStart/onResume` 执行，布局显示，`findViewById` 正常。
- ✅ 目标资源生效：`@string`、`@layout` 正常解析，点击按钮后 TextView 更新为目标字符串。
- ✅ 点击/渲染不崩溃、不黑屏、不 ANR。
- ✅ 目标 `Application` 的 `attachBaseContext`/`onCreate` 已由 Stage B 接入（testapp 验证）。
- ✅ 目标 `getPackageName()` 和 PackageManager 查询均由虚拟包索引回答，跨虚拟应用查询已接入 VPMS。
- ✅ Android 9+ hidden API 通过 HiddenApiBypass 处理；当前验证覆盖 Android 7.1、15、16 和 17。

### 7.2 Stage B 的关键验收

在 Android 7.1.1 x86 模拟器验证：

- ✅ 单 APK 安装成功并持久化；base + 合成 split APK 通过 `space_install_apk` 安装并启动，`split_apks` 已落盘。真实 config split 的资源 overlay 仍待验证。
- ✅ 签名不一致拒绝：`SIGNATURE_MISMATCH`。
- ✅ `REQUIRE_SECURE_ENV=1` 被拒绝：`SECURE_ENV_REQUIRED`。
- ✅ 多开 `instance-1` / `instance-2`，数据目录独立；`file/prefs count` 互不影响。
- ✅ 切换实例后旧实例数据保留；杀进程重启 App 后安装记录和实例仍存在。
- ✅ 卸载清理 APK、实例数据、state；目录为空。
- ✅ 启动第二个实例时，第一个实例在单进程模型下切换到后台/被替换；数据不丢。
- ✅ 目标 Context 的 `getPackageName`、`getFilesDir`、`getSharedPreferences`、`getApplicationInfo` 已重定向。
- ✅ 纯净桌面：默认仅显示虚拟应用和固定入口；宿主应用通过“导入应用”安装成独立虚拟副本，不再创建系统应用快捷方式；支持设置/清除壁纸，重启后保留。
- ✅ Android 16（API 36）复测：桌面、容器、多开、实例数据隔离正常。
- ✅ Android 14+ 可写 dex 限制：安装后的 APK 设为只读后，`DexClassLoader` 正常加载。
- ✅ Android 9+ hidden API：接入 HiddenApiBypass 6.2，`Activity.attach` 等反射可用；未豁免时 `Activity.attach not found`。
- ✅ Android 17（API 37）和 MuMu（Android 15，ARM bridge）已纳入当前验证矩阵。
- ✅ 兼容样本已扩展到多实例、显式 Activity、Provider、WebView、插件组件和 native 模块等通用场景。
- ✅ 多进程、组件路由、Binder/UID 代理和 native 环境均已接入，不再局限于单进程入口 Activity。
- ✅ 引擎内的虚拟应用列表/启动（非系统 PMS/AMS hook）、桌面式入口和目标 `PackageManager` 虚拟化均已接入。

实现位置：`runtime-core` 的 `engine/MiniContainerEngine.kt`、`container/ContainerContext.kt`、`container/ContainerSession.kt`、`container/ContainerProcessPool.kt`，以及 `app` 的 `ui/DesktopScreen.kt`。

### 7.3 Stage C 的关键验收（已实现）

- 两个实例在不同 `:c1..:c16` 槽位进程，崩溃互不影响。
- 目标 App 的 native lib 能加载。
- 原生库加载结果和诊断能够区分对齐、未对齐及 ABI 不匹配；通用 ELF `p_align` 预检仍作为后续增强。
- `Process.myUid`/`Binder.getCallingUid` 的 Java hook 至少覆盖常见检查（不做系统侧 UID 欺骗）。

### 7.4 Stage D 的关键验收（核心已实现）

- 通知能显示（或明确降级）。
- 静态 Receiver 能收到。
- Job/Alarm 至少不崩（可以退化）。
- WebView 多实例不串数据（独立进程）。
- Android 15/16/17 各一台设备通过。

### 7.5 Stage F 的关键验收（AppFunctions 快路径）

- 宿主 App 用 `@AppFunction` 暴露至少 3 个工具（如 `space_list`、`space_launch`、`ui_observe`），系统 Agent 或 `adb shell cmd app_function` 能发现。
- 容器内测试 APK 带 AppFunctions；安装时解析到元数据；执行时能在容器进程内调用。
- 普通 APK 调用系统其他 App 的 AppFunctions 被明确拒绝（`EXECUTE_APP_FUNCTIONS`），并回退 UI。
- 快路径失败不影响 UI 自动化。

> 每个阶段都可以独立演示；不要等"全功能"再验证。Stage A 失败，说明 hook 路线/版本适配有问题，先解决再往下走。

---

## 8. 测试矩阵

### 8.1 设备

| 类型 | 示例 | 关注点 |
|---|---|---|
| Pixel | Pixel 8/9/10（Android 15/16/17） | 原生行为、hidden API、AAPM |
| Samsung | S24/S25/S26（One UI） | 后台限制、Knox |
| 小米 | 14/15（HyperOS） | 后台、权限、包管理 |
| OPPO/vivo | Find X / X 系列 | 权限、后台、无障碍 |
| 华为 | 无 GMS 机型 | GMS 依赖、HMS |
| 16 KB page | Android 15+ 新机 | native lib 对齐 |

### 8.2 App

- 自研测试 APK（最小 Activity、Resources、native lib、split、Service、Receiver）
- 开源简单 App（F-Droid 上的小工具）
- 社交、电商、出行、内容等常见目标类型（兼容性目标，不承诺）
- 游戏（Unity/UE，Surface/Canvas）
- Flutter App
- WebView 为主的 App
- 银行 App（预期失败，用于验证降级提示）

### 8.3 场景

- 安装：单 APK、split APK、XAPK、低 targetSdk、16 KB
- 启动：冷启动、热启动、后台唤起、多实例切换
- 生命周期：旋转、分屏、锁屏、返回、最近任务
- 权限：首次启动、运行时权限、拒绝/允许
- 数据：files/cache/db/sp、外部存储、卸载清理
- 组件：Activity、Service、Receiver、Provider、Notification
- 异常：断网、超时、崩溃、ANR、低内存
- 双通道：同一任务分别走本地 Agent 和远程 MCP，结果一致
- 并发：本地 Agent 持租约时，远程 MCP 修改类调用返回 `SPACE_BUSY`；观察类不受影响
- 审批：两条通道都能触发同一个审批 UI；超时拒绝

### 8.4 指标

- 安装成功率、启动成功率、任务成功率
- 单步延迟 p50/p95
- 崩溃率、ANR
- 每次任务 token 成本
- 误点率、人工接管比例
- 容器：Activity 启动成功率、资源加载成功率、native lib 加载成功率、16 KB 通过率、进程存活
- 双通道：工具一致性通过率、租约冲突/排队/接管次数、审批超时率

---

## 9. 风险清单

| 风险 | 影响 | 缓解 |
|---|---|---|
| Hidden API 变化 | hook 失效、启动崩溃 | 版本适配层；灰度；固定测试矩阵 |
| Binder/UID 限制 | 系统服务按宿主 UID 判定，部分 App 功能异常 | 不承诺强 UID 隔离；Java hook 覆盖常见检查；明确边界 |
| 后台启动限制 | Agent 无法从后台拉起界面 | 前台服务/通知/用户触发；设计"可见任务" |
| 通知/Job/Provider | 目标 App 后台能力退化 | 分阶段支持；产品文案说明 |
| Keystore/签名/Play Integrity | 金融/强校验 App 不可用 | 不承诺；检测后提示 |
| 16 KB / split APK | 安装/加载失败 | 预检；明确错误；测试矩阵 |
| 进程池/内存 | 多开卡顿、被杀 | 进程池上限；前台优先；低内存降级 |
| 容器安全 | 恶意 App 读宿主/其他实例数据 | 只装可信 APK；不宣传安全隔离 |
| 提示注入 | Agent 被屏幕内容操控 | 屏幕文本当数据；工具白名单；危险操作确认 |
| 双通道并发 | 本地/远程同时操作，互相打断 | 租约 + 会话 + 用户接管 |
| 本地 Agent 模型能力 | 端侧模型规划/工具调用弱 | ClaudeAdapter 兜底；端侧只做简单任务 |
| API key 泄露 | 费用/滥用 | Keystore 存储；代理服务；限额 |
| 自动化违反 ToS | 账号封禁 | 用户告知；限速；不碰支付/风控 |
| OEM 限制 | 后台/权限/无障碍不可用 | 机型矩阵；降级方案 |
| 性能/电量 | 体验差 | 前台服务优化；截图降频；按需唤醒 |
| MCP 暴露 | 局域网被控 | 默认 loopback；token；TLS；关闭 LAN 开关 |
| 隐私 | 屏幕内容含敏感信息 | 脱敏；不落盘；审计；用户可关闭截图 |
| 法律/合规 | 平台政策与目标 App ToS | 遵守 `REQUIRE_SECURE_ENV`；只安装可信 APK；危险操作确认 |

---

## 10. 参考项目与资料

### AOSP / Android

- AOSP `ActivityThread`：https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/ActivityThread.java
- AOSP `Instrumentation`：https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/Instrumentation.java
- AOSP `LoadedApk`：https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/LoadedApk.java
- AOSP `ContextImpl`：https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/ContextImpl.java
- Android 17 开发者预览：https://developer.android.com/about/versions/17
- 16 KB page size：https://developer.android.com/guide/practices/page-sizes
- Hidden API 限制：https://developer.android.com/guide/app-compatibility/restrictions-non-sdk-interfaces

### AppFunctions

- AppFunctions 概览：https://developer.android.com/ai/appfunctions
- 向应用添加 AppFunctions：https://developer.android.com/ai/appfunctions/add-appfunctions
- `AppFunctionManager` 参考：https://developer.android.com/reference/android/app/appfunctions/AppFunctionManager
- androidx.appfunctions：https://developer.android.com/reference/androidx/appfunctions/package-summary
- 官方示例：https://github.com/android/appfunctions
- 社区示例（发现/调用）：https://github.com/FilipFan/AppFunctionsPilot

### Hook / Native

- ShadowHook：https://github.com/bytedance/android-inline-hook
- ByteHook：https://github.com/bytedance/bhook
- Dobby：https://github.com/jmpews/Dobby
- xDL：https://github.com/hexhacking/xDL

### 控制 / Agent

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

## 附录 B：Hook 点地图

| 子系统 | Java hook | Native fallback | 版本注意 |
|---|---|---|---|
| PackageManager | `ApplicationPackageManager`、`IPackageManager`、`ActivityThread.getPackageManager` | `Binder.execTransact` | AIDL 方法随版本变化 |
| Activity | `Instrumentation.execStartActivity`、`ActivityThread.mH`、`IActivityManager` | `Binder.execTransact` | Android 10+ 后台启动限制；Android 14+ PendingIntent |
| ClassLoader | `LoadedApk`、`PathClassLoader`、`DexClassLoader` | — | split/multi-dex |
| Resources | `ResourcesManager`、`AssetManager.addAssetPath` | `open` | split/overlay |
| Binder/UID | `Binder.getCallingUid`、`Process.myUid`、`UserHandle` | `Binder.execTransact` | 系统侧 UID 无法伪造 |
| IO | `ContextImpl`、`SharedPreferencesImpl`、`SQLiteDatabase` | `open`/`fopen` | scoped storage |
| 通知 | `NotificationManager`、`PendingIntent` | — | Android 12 trampoline；Android 13 权限 |
| 进程 | 宿主 `:c1..:c16`、`StubActivity`、`VClientService` | — | 不要用 `app_process` 子进程 |
| Native libs | `System.loadLibrary`、`System.load` | `dlopen`/`android_dlopen_ext` | 16 KB；ABI |
| WebView | `WebView.setDataDirectorySuffix`（进程级） | — | 多实例需独立进程 |

## 附录 C：Clean-room 清单

```text
[ ] 不复制未授权的外部项目代码
[ ] 参考 AOSP（Apache-2.0）和公开文档
[ ] 记录每个 hook 点的来源（AOSP 类/方法/版本）
[ ] native hook 库逐个核对许可证
[ ] 核对依赖许可证、Google Play 容器政策和目标 App ToS
[ ] 外部参考资料与实际实现保持代码隔离
[ ] 不在产品中声明"安全隔离"
[ ] 遵守 REQUIRE_SECURE_ENV
```

## 附录 D：最小可行 Demo（建议先做这个）

**目标**：同一个任务，分别由远程 Claude Code（MCP）和本地 Agent 控制手机上的"设置"App，完成"打开设置 → 搜索 WLAN → 打开 WLAN"。

**范围：**

- 不做容器
- 不做安装器
- 只做：
  1. `ToolRegistry`：3 个工具（`ui_observe`、`ui_tap`、`ui_key`），参数校验 + scope
  2. `SpaceAccessibilityService`（observe + tap + back）
  3. `McpForegroundService`（内置最小 HTTP server + token）
  4. `LocalAgentRuntime`（先用 `ClaudeAdapter` 或假模型；循环在 App 内）
  5. `ControlLease` + `ApprovalGate`（至少实现独占租约和确认弹窗）

**验收：**

- 远程 Claude Code 能列出/调用工具，完成 3 个任务
- 本地 Agent 能用同一套工具完成同样任务
- 两条通道的工具行为一致（同一输入 → 同一结果/错误码）
- 本地 Agent 持租约时，远程修改类调用返回 `SPACE_BUSY`；观察类不受影响
- 单步 p50 < 2 s
- 失败时有结构化错误

这个 Demo 跑通后，再进入 Stage A（单进程最小容器）。

# 单 APK 免 Root Android 兼容运行时技术设计

- 状态：通用基础层、Native 路径/`/proc`/property facade、多进程边界和真实启动进度联动已实现
- 日期：2026-11-09
- 目标版本：NestSpace 当前应用级容器
- 输入约束：单个 APK、免 Root、不要求 Device Owner、不切换 Android 用户、不依赖云手机或外部 VM

当前代码进度：

- 已实现 `VirtualEnvironment`、动态 `VirtualIdentitySnapshot` 和进程级注册表。
- 已实现 Native JNI 环境镜像、UID/PID/page-size、`/proc`、property 和直接 syscall 探针。
- 已实现通用 `AndroidStartupCoordinator`，记录 ClassLoader、Application、Provider、兼容初始化器和首个 Activity 的阶段与失败点。
- 已实现每实例脱敏诊断快照，写入实例数据目录下的 `.nestspace/runtime-diagnostics.json`。
- 已实现 credential-protected、device-protected 和 external storage 的实例级隔离；只有实际写入探针成功才发布存储 capability。
- 已实现实例级 AccountManager 代理，支持基础账号增删、密码、Token 和 UserData，不向宿主或其他实例共享账号。
- 已实现通用系统服务 attribution 重写，覆盖 Activity、通知、闹钟、MediaSession、AppOps、网络、剪贴板和输入法；数组返回值保持原始运行时类型，避免 Binder 代理的强类型转换失败。
- 已实现启动加载界面与真实启动阶段联动；进度和文案由 ClassLoader、资源、身份、Application、Provider、兼容初始化及首 Activity 事件驱动，不再按定时器猜测阶段。阶段权重表示容器初始化进度，不表示目标 App 的图片、WebView 或网络内容加载百分比。
- 已实现显式 Activity 启动与 `android:process` 槽位路由，`space_launch` 可指定目标 Activity；每进程使用独立 WebView data-directory suffix 和诊断文件。
- 显式子进程组件若不在 base APK，会从实例已下载插件中定位包含该类的 APK，并按需统一挂载 DEX、Resources 和当前 ABI 的 native library 目录。
- 已实现 manifest BroadcastReceiver 的跨进程路由：当前进程只分发本进程 receiver，其他 `android:process` 去重后异步绑定对应槽位；ordered broadcast 暂保持本地语义。
- 已将 `SpaceStore` 升级为 v2，实例记录包含环境版本、兼容策略和最近探测结果，并兼容 v1 数据。
- 已将目标业务 Hook 收拢到独立插件模块中的 `RuntimeCompatibilityProfile`。
- 已将 `FragmentPagerAdapter` 首次布局归一化从版本化 profile 提升为所有目标共用的 AndroidX UI 兼容层。
- 已对目标 native 模块启用受控 GOT/PLT Hook，覆盖 UID 读取和基础路径 API；宿主/系统库不 patch。
- `/proc/self/status`、`cmdline`、`maps`、`mountinfo` 已支持只读合成和 fd→虚拟路径映射；property 的 `get/find/read_callback/read/serial` 已读取 Java Build 设备快照；直接 inline syscall 和完整跨进程自动化矩阵仍需继续实现。

实现边界必须区分：

- `NATIVE_UID_PROBE` 表示 libc、直接 syscall 和 `/proc/self/status` 读取到同一真实进程 UID。
- `NATIVE_PROC_STATUS_PROBE` 表示能够读取该文件，不表示该文件已经被虚拟化。
- `NATIVE_PROPERTY_PROBE` 表示 property 后端可用，不表示属性值已被 Hook。
- `native_facade.mode=active` 表示至少一个目标模块的函数 GOT 已成功 patch；它仍不代表直接 syscall、Binder 或系统级身份已完成。
- `getDataNetworkTypeForSubscriber` 等特权蜂窝查询被系统拒绝，表示宿主 UID/权限限制，不等价于普通数据网络不可用。单 APK 不应伪造蜂窝网络类型或订阅身份来掩盖该差异。

## 1. 摘要

NestSpace 当前是一个运行在普通 Android 应用进程内的 Java 级虚拟化容器。它能够隔离 APK、数据目录、Activity 栈、资源和部分系统服务，但目标 App 仍会观察到宿主进程的 UID、Binder 调用者、SELinux 域、`/proc`、挂载信息和部分 native 环境。

继续为每个 App、每个 SDK、每个请求参数单独写 Hook，维护成本会持续上升，而且不同观察层之间容易产生互相矛盾的事实。本设计提出一条新的主线：

> 在单个普通 APK 内构建统一的 Android 语义兼容运行时，让 Java、native、文件系统、系统属性和系统服务代理都读取同一份虚拟环境事实。

本方案不是 DEX 解释器，也不是完整 Android 模拟器。它是在宿主 Android 内核和系统服务之上，增加一层跨 Java/native 的进程内兼容运行时。

## 2. 目标与非目标

### 2.1 目标

1. 保持单 APK、免 Root、普通应用安装方式。
2. 目标 APK 的安装、Activity、Application、Service、Provider 和 native 库仍由 NestSpace 在容器进程内驱动。
3. Java、native、文件、属性和系统服务代理使用同一套实例身份。
4. 兼容逻辑不再散落在业务代码中，而是由通用运行时和应用策略组成。
5. 减少目标 App 在启动、网络、数据、图片、WebView 和安全 SDK 初始化上的时序差异。
6. 为 Android 版本升级提供清晰适配层、诊断点和回滚开关。

### 2.2 非目标

本方案不承诺以下能力：

1. 把宿主 Binder 的真实调用者 UID 改成虚拟 UID。
2. 把宿主进程切换到目标 SELinux 域。
3. 创建真实 Android 用户、PID namespace 或 mount namespace。
4. 生成服务端无法区分的硬件级设备身份或系统完整性证明。
5. 支持所有强反容器、强完整性校验、金融或支付类 App。
6. 让 `system_server`、其他进程或外部设备看到完整的虚拟 UID/进程表。
7. 在本进程外建立全局虚拟 `/proc`、namespace 或 Binder 身份目录。
8. 提供按目标包隔离的 AndroidKeyStore namespace 或系统认可的自定义 Account 类型。
9. 让外部 App 或 `system_server` 把一个未真实安装的虚拟包当成已安装组件来启动或绑定。

这些能力属于操作系统或虚拟化边界，单 APK 内的 Java/native Hook 无法提供系统级保证。

本方案也不应被宣传为恶意 App 的安全沙箱。实例之间的数据与崩溃隔离属于产品级逻辑隔离；同宿主 UID 下的恶意 native 代码仍可能访问宿主进程可见的数据。强安全隔离需要 Android 用户、VM 或系统 supervisor。

## 3. 设计原则

### 3.1 一份事实，多个观察面

每个实例必须只有一个环境事实源。任何观察层都不能单独伪造字段。

```text
VirtualEnvironment
    ├─ Java Framework Facade
    ├─ Native syscall/proc Facade
    ├─ Virtual Package Manager
    ├─ Virtual Services / Content
    ├─ Request/Parameter Provider
    └─ Diagnostics
```

### 3.2 Java-first，Native 补足

- Kotlin/Java 负责生命周期、组件管理、策略、配置和可观测性。
- C/C++ 负责 Java 无法可靠控制的系统事实和高频路径。
- 不追求通用 ART Hook 框架；Native 改动必须有明确观察点和回滚开关。

### 3.3 通用运行时优先，App 策略最后

优先修正 Android 启动和环境语义。只有无法由通用 Android 规则解释的差异，才进入版本绑定的兼容策略。

### 3.4 先一致性，再兼容性

兼容性验收不能只看页面是否出现，还要验证多个观察面是否返回一致结果。

### 3.5 核心运行时不得依赖具体 App

核心代码不得通过包名或版本号改变 Android 环境语义。应用级策略只能隔离在可选适配层中，用于处理框架模拟尚未覆盖的短期差异，不能成为“模拟 Android”的主线。

通用验收必须来自多个独立测试 APK，而不是只以某一个业务 App 的页面作为成功标准。

## 4. 当前架构与主要缺口

### 4.1 当前主要模块

| 模块 | 当前职责 |
| --- | --- |
| `ContainerProcessPool` | 为实例分配 `:c1`–`:c16` 槽位进程 |
| `ContainerSession` | 创建 ClassLoader、Context、Application 和首个 Activity |
| `AndroidStartupCoordinator` | 按 Android 可观察顺序执行启动阶段，并发出阶段开始、结束、耗时和失败事件 |
| `ContainerLaunchProgress` | 保存当前进程最近一次启动的粗粒度阶段快照，供加载界面和诊断读取 |
| `LaunchLoadingView` | 在独立渲染线程绘制品牌启动页、实时阶段文案和阶段进度 |
| `ContainerContext` | 虚拟文件目录、权限、PackageManager、组件入口 |
| `VirtualPackageRegistry` | 虚拟包、签名、组件和实例数据目录 |
| `SystemServiceIdentity` | 代理部分系统 Binder 和包管理查询 |
| `VirtualContentResolver` | Settings、Provider 和虚拟 Content 查询 |
| `ProcessIdentitySeedHook` | 目标进程名、主进程判断和设备注册前置状态 |
| `RequestSnapshotHook` | 首请求公共参数初始化 |
| `StartupTaskHook` | 补齐目标应用启动任务 |
| `NativeLibs` | 从 APK 中抽取匹配 ABI 的 native 库 |
| `ContainerProcessRouter` | 把目标 `android:process` 映射到独立宿主槽位 |
| `ContainerProcessBrokerService` | 在主进程集中分配和回收子进程槽位 |
| `ContainerRemoteServices` | 在目标子进程槽位中创建对应 Session/组件 |

### 4.2 观察层缺口

| 层级 | 当前状态 | 主要问题 |
| --- | --- | --- |
| DEX/Java 对象 | 已虚拟化 | 基本可用 |
| Activity/Application | 手工驱动 | 与真实 `ActivityThread` 时序仍有差异 |
| PackageManager | 部分代理 | 系统 Binder 侧仍看到宿主 UID |
| 文件目录 | 实例隔离 | 路径变换尚未覆盖全部 native 入口 |
| Android ID/DID | 实例生成 | 与其他 native 系统事实不一定一致 |
| native 库 | 独立 ClassLoader/ABI | 仍运行于宿主进程和宿主 UID |
| 存储根目录 | 部分实例化 | device-protected、credential-protected、external、OBB 尚未全部统一 |
| AccountManager/Keystore | 部分兼容 | 账号类型、Keystore alias 和调用者 UID 仍受宿主约束 |
| `/proc` | 未统一 | maps、status、mountinfo 暴露宿主事实 |
| 进程表 | 部分代理 | ActivityManager 和系统服务仍识别宿主进程 |
| Telephony/蜂窝身份 | 受宿主 UID 和特权权限约束 | 网络类型、订阅和 SIM 相关查询可能抛 `SecurityException` |
| SELinux | 未模拟 | 读取到宿主 `untrusted_app` 上下文 |
| Binder caller | 无法系统级修改 | 系统服务按宿主 UID 归因 |

因此，当前兼容问题不能只归因于“某个初始化任务没调用”，也不能只靠 DEX 层继续扩展解决。

### 4.3 现有版本化适配盘点

当前代码中与具体目标版本绑定的兼容逻辑需要区分处理：

| 模块 | 当前适配内容 | 通用替代方向 | 可安全替换程度 |
| --- | --- | --- | --- |
| `StartupTaskHook` | 补齐目标 RPC、网络、图片和日志启动任务 | 正确的 Application/Provider/InitScheduler 和主线程启动语义 | 条件可替换 |
| `ServiceRegistrySeedHook` | 解决目标服务注册器在启动中的重入和依赖顺序 | 统一组件初始化顺序和 service registry facade | 条件可替换 |
| `ProcessIdentitySeedHook` | 预热进程名、账号配置和设备注册迁移缓存 | 进程名、PackageManager、Provider 和注册状态统一环境 | 部分可替换 |
| `RequestSnapshotHook` | 代理目标业务 API 的安装列表快照 | 让目标自身初始化请求参数，并统一 PackageManager 视图 | 低可替换 |
| `TargetFragmentPagerHook` | 处理 AndroidX FragmentPagerAdapter 重复 add | 通用的 ViewPager/Fragment 生命周期保护，只在检测到冲突时生效 | 机制可通用化 |
| `CompatApplicationFactory` | AppComponentFactory、加固 Embedded Application、Tinker/ApplicationLike 启动链 | 已按加固命名空间、类继承和构造参数关系检测，不再绑定单个 App 包名或业务类名 | 基础路径已通用化 |
| GMS/WebView/Provider/Job 等桥接 | 宿主系统能力代理 | 通用 Android Framework 服务 facade | 原本就是通用方向 |

通用容器不包含目标 App 包名判断。少量版本兼容逻辑放在中性命名的版本 profile 中，只通过 versionCode 和结构指纹命中，通用层仅看到不透明 profile id；加固应用和 Tinker 解析器只识别框架命名空间、类继承和构造参数关系。

兼容启动按能力分流：未检测到加固时直接使用 Manifest Application；带 Application delegate 的 Tinker 外壳保留框架代理；拒绝直接启动的加固外壳优先解析内部 ApplicationLike 链或内部 Application。加固外壳的内部启动链都无法可靠解析时直接输出 unsupported 诊断，不再静默回退到外层壳。

“删除包名判断”不等于“改成通用方案”。如果通用层没有补上 ActivityThread 状态、启动顺序、PackageManager 一致性或 native 环境，直接删除上述适配会造成明确回归。

### 4.4 电信/蜂窝特权身份边界

真实目标应用样本在启动后，运营商登录 SDK 调用 `TelephonyManager.getNetworkType()` / `getDataNetworkTypeForSubscriber` 时收到：

```text
java.lang.SecurityException: getDataNetworkTypeForSubscriber
at com.android.internal.telephony.ITelephony$Stub$Proxy.getNetworkTypeForSubscriber
at android.telephony.TelephonyManager.getNetworkType
at <vendor>.account.api...
```

该异常来自系统 Telephony 服务对调用者 UID、权限和订阅归属的校验。`SystemServiceIdentity` 的包名和 `AttributionSource` 重写只能让常见的包名归属检查看到宿主包，不能把普通应用 Binder caller 变成有特权电话权限的 UID，也不能凭空创建真实 SIM/订阅身份。

本轮边界结论：

- 这是特权蜂窝身份能力缺失，不是普通网络链路故障。测试 App 同期返回 `network=active all=1`，目标应用首屏、内容流、图片和普通网络请求正常。
- 运营商登录 SDK 捕获了该 `SecurityException`，没有造成 FATAL、SIGSEGV 或首屏不可用；运营商一键登录能力本身可能不可用。
- 不应通过伪造 `getDataNetworkTypeForSubscriber` 返回值、订阅 ID、SIM 状态或电话权限状态来“修复”日志。这些值会被其他 SDK 交叉读取，伪造会制造互相矛盾的环境事实。
- Java 动态代理只能覆盖经过被代理接口的调用；系统服务侧的 UID/权限判断仍在 Telephony 服务中。Native Hook 也不能给当前进程增加真实特权权限。
- 若产品必须支持依赖特权蜂窝身份的一键登录、运营商风控或 SIM 绑定，需要系统应用/特权权限、真实 Android 用户/VM 或厂商合作能力，已超出当前“单 APK、免 Root、普通应用”约束。
- 通用诊断应将该类问题标记为 `UNSUPPORTED_PRIVILEGED_TELEPHONY` 一类的能力边界，并明确区分“普通数据网络可用”和“特权蜂窝身份可用”。当前文档记录该边界；统一诊断码仍可作为后续实现项。

## 5. 目标架构

```text
┌──────────────────────────────────────────────────────────┐
│ NestSpace UI / Agent / MCP                               │
├──────────────────────────────────────────────────────────┤
│ AppRuntime                                               │
│  安装、启动、停止、实例管理、兼容策略、诊断               │
├──────────────────────────────────────────────────────────┤
│ Virtual Android Framework                                │
│  Application/Activity/Service/Provider/Lifecycle         │
│  PackageManager/ContentResolver/Job/Notification         │
├──────────────────────────────────────────────────────────┤
│ VirtualEnvironment                                       │
│  UID、包名、路径、DID、属性、进程、安装信息、SELinux 视图 │
├──────────────────────────────────────────────────────────┤
│ Native Compatibility Facade                              │
│  libc/syscall、/proc、property、ELF、native library       │
├──────────────────────────────────────────────────────────┤
│ Android framework / Linux kernel / system services        │
└──────────────────────────────────────────────────────────┘
```

## 6. VirtualEnvironment

### 6.1 实例事实模型

建议新增统一的不可变环境快照：

```kotlin
data class VirtualEnvironment(
    val instanceId: String,
    val packageName: String,
    val hostUid: Int,
    val observedUid: Int,
    val virtualPid: Int,
    val dataRoot: String,
    val filesDir: String,
    val cacheDir: String,
    val codeCacheDir: String,
    val apkPaths: List<String>,
    val splitApkPaths: List<String>,
    val nativeLibraryDir: String?,
    val processName: String,
    val versionName: String,
    val versionCode: Long,
    val properties: Map<String, String>,
    val selinuxContext: String?,
    val capabilities: Set<RuntimeCapability>,
    val bootTimeMillis: Long,
    val installTimeMillis: Long,
    val updateTimeMillis: Long
)

enum class RuntimeCapability {
    NATIVE_UID_FACADE,
    NATIVE_PROC_VIEW,
    NATIVE_PROPERTY_VIEW,
    MULTIPROCESS_COMPONENTS,
    DEVICE_PROTECTED_STORAGE,
    EXTERNAL_STORAGE,
    ACCOUNT_KEYSTORE_BASIC
}
```

静态字段必须来自实例记录或目标 APK 描述，不能在每次查询时临时生成。

`capabilities` 表示当前设备、Android 版本和 Hook 组合已经验证可用的能力，不是对所有 App 的兼容承诺。未列出的能力默认视为 unsupported，运行时不得依靠“看起来可能可用”的 Hook 继续执行。

### 6.2 动态身份快照

DID、IID、clientudid 和部分 Android ID 可能由目标 App 的注册 SDK 异步生成、持久化或更新，不能直接放进不可变静态环境：

```kotlin
data class VirtualIdentitySnapshot(
    val revision: Long,
    val androidId: String?,
    val clientUdid: String?,
    val deviceId: String?,
    val installId: String?,
    val source: String
)
```

规则：

1. 注册前的字段允许为 `null`，不能临时生成另一个随机值冒充已完成注册。
2. 目标 SDK 是 DID/IID 生命周期的权威来源时，虚拟环境读取它持久化后的值，而不是平行维护第二份身份。
3. 新身份通过 `revision` 原子发布；请求参数、设备注册、日志和诊断读取同一版本。
4. 身份更新不能改变静态 UID、包名和路径模型。

### 6.3 关键一致性不变量

以下值应通过测试保证一致：

```text
PackageManager 查询的包名
    == Context.getPackageName()
    == 目标 ApplicationInfo.packageName

目标观察 UID
    == Java Process.myUid() 的兼容返回值
    == native getuid/geteuid 的兼容返回值
    == Binder 相关 Java Hook 的兼容返回值

虚拟数据目录
    == Context.getDataDir()
    == SharedPreferences / Database / Cache 的父目录
    == native stat/open 返回的目标路径

虚拟 DID/Android ID
    == 请求公共参数
    == 日志/设备注册返回
    == Settings 查询和实例记录
```

这里的 UID、PID 和 SELinux 一致性指目标 App 可见的事实，不表示 Linux 内核或系统服务也会使用这些值。无法满足的字段必须标记为 `unsupported`，不能返回随机或互相矛盾的值。

实际文件操作、Binder 调用和系统服务调用始终使用 `hostUid`。`observedUid` 只用于目标 App 的观察面；实现必须明确区分两者，不能让内部权限检查误用观察 UID。

SELinux context 和 system property 同样只是进程内观察视图。真实访问控制仍由宿主 SELinux 域决定，`system_server` 和其他进程仍读取真实属性。

当前最安全的默认值是：

```text
当前正在运行的目标包：observedUid = hostUid
其他未运行虚拟包：      observedUid = 稳定合成 UID
```

这样 `Process.myUid()`、目标 `ApplicationInfo.uid`、PackageManager 和 native `getuid` 可以保持一致，符合当前容器的设计。

如果兼容策略要求当前包也使用合成 UID，则必须视为高风险模式，并同时改写 Java、native、PackageManager、权限表和请求参数的所有观察面。系统服务仍会看到 `hostUid`，因此这类模式不能成为默认配置。

同一目标包的多实例默认共享一个包级观察 UID，因为真实 Android 中同一用户的同一包只有一个 UID。实例隔离靠数据目录和 Session 完成，不应让每个实例暴露一个新的“包 UID”。

### 6.4 Native 镜像

进程启动时把 `VirtualEnvironment` 传入 native：

```cpp
struct NativeVirtualEnvironment {
    int32_t host_uid;
    int32_t observed_uid;
    int32_t virtual_pid;
    std::string package_name;
    std::string data_root;
    std::string apk_path;
    std::string native_library_dir;
    std::string selinux_context;
    uint64_t identity_revision;
    uint64_t capability_mask;
    PathMap path_map;
    PropertyMap properties;
};
```

Native Hook 不应在每个 syscall 中回调 Java。环境应在进程启动阶段一次性注册，后续通过 native 内存、线程局部变量或只读 registry 查询。

环境快照必须不可变并通过原子指针发布；需要更新时创建新版本再切换，不能边遍历边修改 map。Native Hook 在引用环境时必须使用明确的引用生命周期，避免目标线程与槽位清理并发时访问已释放对象。

## 7. 虚拟 Android Framework

### 7.1 进程启动顺序

目标是让目标 App 按其自身依赖执行，而不是由 NestSpace 手工补跑零散任务。

```text
进程与 ClassLoader 准备
→ Application.attachBaseContext
→ Provider 创建
→ Application.onCreate
→ 目标内部 InitScheduler/启动任务
→ SplashActivity
→ Activity onCreate/onStart/onResume
→ 页面请求
```

需要重点模拟：

- `ActivityThread` 初始上下文
- `LoadedApk` 和 `ApplicationInfo`
- Provider 的 init order 和进程匹配
- 主线程 Handler 与 idle 消息
- 目标自建启动调度线程
- Application 生命周期回调
- Activity 栈、结果回传和配置变更

### 7.2 生命周期与组件

继续使用当前 `StageAActivity` 和 `ContainerInstrumentation` 作为宿主入口，但逐步把组件管理收敛到统一运行时：

- ActivityTaskManager facade
- Activity 栈和 task affinity
- ServiceManager
- ProviderManager
- BroadcastManager
- JobScheduler
- Notification
- 权限和 AppOps 查询
- ActivityResult 和权限结果

### 7.3 启动语义必须通用化

当前 `StartupTaskHook` 中的任务补跑属于过渡实现，不是通用 Android 模拟的一部分。长期目标应由统一 Framework bootstrap 自动完成：

1. 按 manifest 创建 Application 和 Provider。
2. 按真实顺序启动目标自己的初始化调度器。
3. 保持主线程 Looper、Handler、idle 消息和后台线程可用。
4. 由目标自身的 `SplashActivity` 和路由决定页面启动。
5. 不在核心运行时中按包名判断需要初始化哪个 SDK。

如果某个初始化逻辑无法由通用 Framework 语义解释，应先判断是缺少 ActivityThread 状态、Provider 顺序、系统服务响应还是 native 环境；修复通用层后，再将应用策略作为最后兜底。

### 7.4 进程全局框架状态约束

普通 APK 无法创建第二个独立的 Android `ActivityThread`。当前进程中的 `ActivityThread`、`Instrumentation`、`LoadedApk`、主 `Looper`、资源管理器和进程级 native linker 状态都是单例或近似单例：

- 一个槽位进程只能服务一个目标实例。
- 每次切换 Instance 前必须完整销毁 Application、Provider、Activity、Handler、线程和 native 状态。
- `ApplicationInfo`、`LoadedApk`、ClassLoader 和进程名只能在一段时间内指向一个目标环境。
- 不能在同一个进程内并行驱动两个目标 App 的 main-thread 生命周期。
- `ThreadLocal` 和调用窗口切换只能处理短时 Facade，不能替代进程隔离。

因此，通用模拟需要承认“一个 Android 进程对应一个虚拟环境实例”，而不是在一个进程里并发模拟多个完整 Android Application。

### 7.5 回调线程的环境传播

目标代码不一定在当前调用线程上访问环境：

- Binder 回调线程
- `HandlerThread`
- 目标自行创建的 `Thread`
- native 线程和线程池
- ContentProvider/Service 回调

只使用 `ThreadLocal<VirtualEnvironment>` 会让这些线程看到空环境或宿主身份。当前“一实例一槽位进程”的模型应采用：

1. **进程级默认环境**：槽位启动后，任意目标线程默认读取该实例环境。
2. **宿主调用窗口**：只有 NestSpace 自身调用真实系统服务时，临时切换到 Host 身份。
3. **显式传播检查**：关键线程创建入口和回调分发必须记录环境来源，发现 host fallback 时输出诊断。

多实例共进程会破坏这个前提，因此不作为普通 APK 容器的目标。

### 7.6 Host/Target 调用边界

槽位进程中同时存在 NestSpace 的窗口、生命周期桥接和目标 App 代码。Native Hook 是进程级的，不能默认把宿主自己的文件、Binder、线程和系统调用也改写：

- Host 代码调用真实系统服务时必须使用 Host 调用窗口；主线程可能在 Host/Target 之间交替，不能只按线程 ID 判断。
- Target 代码、目标 Handler 和目标 Binder 回调必须使用 Target 模式。
- 新建线程不能只靠创建者继承；需要显式传递模式或标记线程。
- 进入和退出 Hook 区域必须可嵌套、异常安全，不能在目标回调抛出异常后遗留 Host 模式。
- 高风险 Hook 应优先在“目标代码进入前启用、目标代码退出后关闭”的边界内运行。

如果无法可靠区分 Host/Target 调用，应把显示宿主与目标运行进程进一步拆分，而不是依赖全局 `getuid` 补丁。

## 8. Native Compatibility Facade

### 8.1 Native 模块划分

| 模块 | 责任 | 优先级 |
| --- | --- | --- |
| `libnestspace_environment.so` | 环境 registry、路径映射、缓存 | P0 |
| `libnestspace_facade.so` | libc、syscall、`/proc`、property Hook | P0 |
| `libnestspace_loader.so` | ELF、linker namespace、native library 加载 | P1 |
| `libnestspace_probe.so` | 安全 SDK 和 native 读取探针 | P1 |
| `libnestspace_render.so` | Surface、EGL、帧缩放和转换 | P2 |

### 8.2 首批 syscall 覆盖

```text
getuid/geteuid/getgid/getegid
getpid/getppid
open/open64/openat/openat2
stat/lstat/fstat/fstatat/statx
access/faccessat
readlink/readlinkat
getxattr/listxattr
read/pread/readv/preadv
uname
__system_property_get
__system_property_find/__system_property_read_callback
```

`/proc/self/status`、`/proc/self/maps`、`/proc/self/mountinfo`、`/proc/self/attr/current` 应由 Hook 合成或流式改写。

必须单独覆盖绕过 libc 包装的路径：

```text
syscall(SYS_openat, ...)
syscall(SYS_statx, ...)
/proc/self/fd 下的 fd 与路径映射
mmap/sendfile 对已打开虚拟文件的操作
```

无法确认覆盖的直接 syscall 必须在诊断中标记为 `unverified`，不能假定 libc Hook 已经生效。

文件描述符在进程内共享，`/proc` 改写还需要维护 fd 级映射：

- `open/openat` 成功后登记目标 fd 的虚拟路径和读取模式。
- `dup/dup2/dup3/fcntl` 必须复制或更新映射。
- `pread/readv/lseek` 必须保持原始 offset 语义。
- Host 打开的 fd 不得被 Target 的 `/proc` fd 映射误改写。
- `close` 和进程退出时清理 fd 映射。

### 8.3 路径映射

路径映射使用预编译前缀索引，不使用正则。

```text
/data/user/0/<target-package>
    → /data/user/0/com.coke.nestspace/files/vspace/data/<target>/<instance>

<target-base-apk>
    → /data/user/0/com.coke.nestspace/files/vspace/apps/<target>/<build>/base.apk
```

映射结果必须和以下接口一致：

- `Context.getDataDir()`
- `ApplicationInfo.sourceDir`
- `ApplicationInfo.nativeLibraryDir`
- `native dlopen`
- `/proc/self/maps`
- `dl_iterate_phdr`/`dladdr` 返回的模块路径
- `readlink("/proc/self/fd/...")`

路径模型至少应覆盖：

| 存储面 | 目标可见值 | Native 映射要求 |
| --- | --- | --- |
| dataDir | 实例主数据根目录 | `stat/open/readlink` 一致 |
| files/cache/code_cache/no_backup | 实例目录 | 创建、权限和所有者视图一致 |
| databases/shared_prefs/mmkv/keva | 实例目录 | SQLite/MMKV/Keva native 路径一致 |
| device-protected storage | 实例隔离目录 | 与普通数据目录分离 |
| credential-protected storage | 实例隔离目录 | 解锁状态语义可见 |
| external files/cache | `package_instance` 目录 | 返回路径与实际可写路径一致 |
| OBB/media | 实例目录或明确只读映射 | 明确支持、拒绝或降级 |

`stat/fstat` 返回的 `st_uid/st_gid/st_dev/st_ino` 也属于观察面；如果无法与路径模型保持一致，应记录为虚拟文件元数据差异，不能只改写路径字符串。

Native loader 还需要满足：

- 每个实例使用独立 linker namespace，避免同名 `.so` 和 JNI 全局符号串用。
- 不依赖 Android 不保证支持的全局 `LD_PRELOAD` 行为，Hook 库应在槽位进程内明确加载。
- NestSpace 自身提供的 `.so` 必须兼容 16 KB page size，并同时适配 arm64-v8a 和 x86_64 构建目标。
- 目标 APK 的 native 库页对齐不满足当前设备要求时，应在启动前返回结构化错误。

### 8.4 Hook 安全策略

1. 只 Hook 当前实例进程。
2. Hook 按能力分组，可独立关闭。
3. 每条 Hook 必须有异常边界和 fallback。
4. 不在进程启动最早期无差别 patch 全部 native 库。
5. 安全 SDK 探针只在目标调用窗口开启。
6. 每次 Android 版本升级重新验证符号和行为。
7. 目标 native 库加载前先初始化 NativeEnvironment 和最小 Hook。
8. 对后续 `dlopen` 加载的库重新应用 GOT/PLT Hook，并避免重复 patch。
9. 已解析到真实符号的调用要通过诊断确认，不能只验证 PLT 是否改写。
10. 子进程必须重新注册 NativeEnvironment，不能直接继承主进程的环境对象假设。
11. Hook 本体必须保存原始 trampoline，禁止在 Hook 内再次调用被 Hook 的同名函数。
12. Hook 热路径不得加全局互斥锁、分配大对象或同步写日志，避免递归和死锁。

### 8.5 Binder 边界

Java Framework 代理只能覆盖经过目标 Context/系统服务包装器的调用。目标 native SDK 可能直接：

- 调用 `libbinder`/`Parcel` 发起事务
- 通过 `ioctl` 与 Binder 驱动交互
- 调用自定义 AIDL 服务
- 读取系统服务返回的调用者 UID、包名和 AttributionSource

这些路径无法保证被 Java Proxy 或 libc Hook 覆盖，而且内核仍会为目标事务附加宿主 UID。诊断必须区分“Java 服务代理成功”和“系统服务真实接受虚拟身份”，不能把前者当成 Binder 隔离完成。

## 9. 性能设计

### 9.1 Native 使用边界

使用 Native 的主要原因是访问系统事实，其次才是性能。

| 场景 | 建议实现 | 原因 |
| --- | --- | --- |
| 路径与身份查询 | C++ | syscall 高频，不能逐次 JNI |
| `/proc` 读取 | C++ | Java 无法覆盖 native 文件读取 |
| ELF/native library | C++ | linker 和 JNI 环境 |
| Binder 安全探针 | C++ | Java Parcel 不覆盖 native 调用 |
| Surface/帧转换 | C++/EGL | 避免 Bitmap 和 Java 数组拷贝 |
| Activity/Fragment | Kotlin | 复杂业务与生命周期 |
| PackageManager API | Kotlin/Java | 可维护性优先 |
| 兼容策略和 UI | Kotlin | 可观察、可配置、可回滚 |

### 9.2 JNI 边界

- 启动时批量传递环境快照。
- 高频查询使用 native registry。
- 热路径使用 `std::string_view` 和预分配 buffer，避免反复构造 `std::string`。
- 二进制数据使用 direct `ByteBuffer`。
- 不在 syscall Hook 中调用 Java。
- Native 日志使用批量环形缓冲区，避免每事件一次 `__android_log_print`。

### 9.3 初始性能预算

以下预算应作为 Native Facade 的初始工程目标，而不是当前实测值：

| 指标 | 初始目标 |
| --- | --- |
| 环境 identity/path 查询 p95 | 小于 2 微秒 |
| 单次路径映射额外分配 | 0 次 |
| NativeEnvironment 进程启动注册 | 小于 20 毫秒 |
| Native shim 常驻内存 | 小于 3 MB |
| 屏幕帧处理 | 不增加一次全分辨率 Java Bitmap 拷贝 |
| 启动时间回归 | 相对当前基线不超过 5% |

路径预算建议使用至少 100 万次相同/前缀/未命中查询的混合负载测量；启动预算至少比较 30 次冷启动的 p50/p95，并同时记录 PSS、页面大小和 ABI。

超过预算时必须先优化 Native fast path，再扩大 Hook 覆盖范围。

## 10. 进程与实例模型

### 10.1 实例主进程

继续把每个实例的主进程固定到一个 `:cN` 宿主槽位：

```text
com.example.app@instance-1 → :c1
com.example.app@instance-2 → :c2
```

规则：

1. 一个槽位进程只承载一个目标实例。
2. 槽位复用时先销毁目标进程中的所有 `Application`、native 状态和线程。
3. NativeEnvironment 在复用进程时必须完全重建。
4. 目标 Java 异常由崩溃保护层隔离。
5. native crash 只允许影响当前槽位。
6. 包含 DID、Android ID、Cookie 关联信息的环境对象不得在同一槽位同时保留多个实例副本。

### 10.2 目标声明的子进程

目标 APK 可以声明：

```xml
android:process=":push"
android:process="com.example.remote"
```

当前容器已经通过 `ContainerProcessRouter`、`ContainerProcessBrokerService` 和 `ContainerRemoteServices` 将不同目标进程映射到不同宿主槽位：

```text
package@instance              → slot A
package@instance|package:push → slot B
package@instance|remote       → slot C
```

每个子进程必须拥有：

- 独立 Session
- 独立目标 Application
- 独立静态变量和 native 全局状态
- 独立 NativeEnvironment
- 明确的进程名、PID 和主进程判断
- 与主进程一致的 APK、资源和数据映射

当前实现的覆盖程度不同，设计文档不能把这些组件视为完全等价：

| 组件 | 当前覆盖 | 仍需补齐 |
| --- | --- | --- |
| Service | 已有跨槽位 start/bind/unbind/stop 桥接 | 前台服务归因、系统回调语义 |
| Activity | 目标子进程 Activity 可使用独立 StageA 槽位 | 多窗口、任务迁移和返回栈边界 |
| Provider | 远程 Session 启动时按目标进程初始化 Provider | 冷启动 Provider、跨进程查询和权限 |
| Broadcast | 主要在进程内和宿主桥接层处理 | 目标子进程定向广播、进程拉起 |
| Job/Alarm | 容器存活期间本地调度 | 进程死亡后的系统级唤醒 |

单个 APK 无法在运行时向 Manifest 增加任意新进程名，因此目标子进程仍由固定的 `:c1`–`:c16` 槽位承载。槽位数量、进程总数和内存上限是产品约束。

同一个 App 的主进程和 `android:process` 子进程会占用不同槽位。一个 App 创建多个子进程时，可能快速消耗容器槽位，因此需要按实例统计总槽位数，而不是只统计“打开的 App 数量”。

### 10.3 原生 fork/exec

目标 native 代码可能自行调用 `fork`、`posix_spawn` 或 `exec`。它们不等同于 Android `android:process`：

- 子进程默认继承宿主 UID 和 native 内存。
- `exec` 后的新进程不会自动加载 Kotlin Session 或 NativeEnvironment。
- 纯 `fork` 如果发生在 Hook 安装之后，子进程可能继承部分 native 状态，但仍需要立即建立实例上下文和锁状态。
- `exec` 会丢弃当前映射、Hook 和 ART 状态；普通 APK 通常也不能从应用私有目录执行任意新二进制。
- 因此，对 `exec` 后进程的可靠注入应视为单 APK 的高风险或 `unsupported` 能力，不能作为默认承诺。

依赖独立 native helper 进程、命令执行或严格进程祖先检查的 App，仍属于高风险类别。

## 11. 数据模型改造

当前 `VirtualInstance` 只保存数据目录。建议扩展为：

```kotlin
data class VirtualInstance(
    val id: String,
    val createdAt: Long,
    val lastLaunchedAt: Long,
    val dataDir: String,
    val environmentVersion: Int,
    val compatibilityProfile: String?,
    val lastProbeResult: String?
)
```

`SpaceStore` 升级 JSON 版本，并保留旧数据迁移：

```text
version 1 → version 2
```

迁移只补充环境版本和策略字段，不复制 DID、Token 或另一个实例的数据。

## 12. 兼容策略

### 12.1 策略层级

```text
Android 版本适配
→ 目标 SDK/ABI 适配
→ 应用版本策略
→ 实例级配置
```

### 12.2 禁止的修复方式

以下方式不得作为通用修复：

- 改写业务 URL 绕过参数校验。
- 伪造 `110` 响应或隐藏错误文案。
- 导入其他设备的 DID、Token 或注册数据。
- 拦截所有异常后继续展示错误页面。
- 为单个 App 永久 Hook 整个进程的 native 函数。

### 12.3 例外策略约束

应用级例外只能位于独立策略层，并满足：

- 核心 Framework、Native Facade 和 PackageManager 不得读取包名特判。
- 策略必须绑定 APK 版本和签名摘要，禁止跨版本盲目复用。
- 默认关闭，只有通用运行时诊断证明无法覆盖时才启用。
- 每项策略必须有适用原因、退场条件和独立回滚开关。
- 策略加载失败必须回退通用运行时并输出结构化诊断。

任何应用策略如果反复出现，应优先抽象成新的 Android 通用语义测试和 Framework 修复，而不是继续增加版本分支。

### 12.4 从特定适配迁移到通用运行时

不能一次性删除现有兼容代码，应按以下顺序迁移：

1. **行为封装**：把现有 `Target*Hook` 和包名判断移入独立 profile，保持当前行为不变。
2. **建立探针**：记录每个 profile 实际修复的 Java、native、组件或请求层事实。
3. **实现通用修复**：优先补 Framework、NativeEnvironment、PackageManager 和进程启动语义。
4. **影子对比**：通用路径和 profile 路径同时运行，比较生命周期、环境读取和首个请求。
5. **特性开关**：按 profile 关闭单个 Hook，保留快速回滚。
6. **删除业务判断**：只有通用路径在独立测试 APK 和目标回归集上都稳定，才移除版本绑定代码。

迁移验收至少包括：

- Application/Provider 启动顺序正确。
- 首个网络请求在没有目标业务 Hook 的情况下能获得完整环境。
- native 和 Java 观察到一致身份。
- 冷启动、进程回收后重新启动一致。
- 目标功能不是只展示页面，而是能完成真实业务请求。

## 13. 诊断与可观测性

每个实例应生成脱敏诊断快照：

```json
{
  "instance_id": "instance-1",
  "package": "com.example",
  "version_code": 1,
  "environment_version": 1,
  "identity_revision": 0,
  "capabilities": [],
  "java_uid": 0,
  "native_uid": 0,
  "package_manager_uid": 0,
  "process_name": "",
  "real_process_name": "",
  "target_process_name": "",
  "data_root_kind": "virtual",
  "proc_status": "virtual",
  "selinux_context": "virtual",
  "direct_syscall_probe": "unknown",
  "native_hooks": [],
  "compatibility_profile": null
}
```

诊断不得直接导出 DID、Token、Cookie、账号和完整请求头。

需要记录：

- Application/Provider 启动耗时
- 启动任务失败点
- Java/native 身份不一致
- native Hook 命中次数与失败次数
- 目标 `android:process` 与宿主槽位的映射
- 直接 syscall 探针结果
- 首个网络请求公共参数生成路径
- Activity/Fragment 生命周期异常
- native crash 栈和对应 Hook 组

### 13.1 启动加载进度联动

启动页的目标不是让动画“看起来更忙”，而是把容器已经完成的真实初始化工作反馈给用户。当前实现使用粗粒度阶段权重，不根据经过时间伪造进度。

#### 13.1.1 事件链

```text
StageAActivity 显示 LaunchLoadingView
→ ContainerSession.create() 调用 ContainerLaunchProgress.begin()
→ 每个关键初始化点更新 LaunchProgressStage
→ AndroidStartupCoordinator 在阶段执行前发出 phaseStarted
→ AndroidStartupCoordinator 在阶段结束后发出 phaseResult
→ ContainerLaunchProgress 只做单调推进和失败标记
→ LaunchLoadingView 的独立渲染线程读取快照并更新文案、进度条
→ 首个目标 Activity 创建后，宿主加载层被目标 decorView 替换
```

`SurfaceView` 使用独立渲染线程。目标 `Application.onCreate`、Provider 或兼容初始化即使长时间占用 Android 主线程，品牌动画、文字和进度条仍能继续刷新；阶段本身不会被错误推进。

#### 13.1.2 阶段与权重

| `LaunchProgressStage` | 权重 | 触发条件 |
| --- | ---: | --- |
| `PREPARING_RUNTIME` | 4% | 开始创建 Session，还没完成代码加载 |
| `LOADING_CODE` | 10% | ClassLoader 已创建，准备资源 |
| `LOADING_RESOURCES` | 16% | 目标 Resources 已创建 |
| `PREPARING_IDENTITY` | 22% | 准备 PackageManager、系统服务和虚拟身份 |
| `ATTACHING_APPLICATION` | 30% | 系统服务和兼容 Profile 已准备，开始 attach Application |
| `PROCESS_APPLICATION` | 36% | 发布目标进程身份 |
| `CONTENT_PROVIDERS` | 46% | 安装并创建目标 Provider |
| `APPLICATION_CREATE` | 58% | 执行目标 `Application.onCreate()` |
| `APPLICATION_COMPATIBILITY` | 68% | 执行 Application 创建后的兼容初始化 |
| `HOST_BRIDGES` | 75% | 建立宿主广播等桥接 |
| `EMBEDDED_APPLICATION_CREATE` | 80% | 创建 Embedded ApplicationLike；没有时记录 skipped |
| `STARTUP_COMPATIBILITY` | 86% | 执行 Embedded Application 后的兼容初始化 |
| `CRASH_GUARD` | 90% | 重新安装目标进程崩溃保护 |
| `READY` | 94% | Session 初始化完成 |
| `FIRST_ACTIVITY_SCHEDULED` | 97% | 首个目标 Activity 已调度或已有 Session 再次打开 Activity |
| `FIRST_ACTIVITY_OPENED` | 100% | 首个目标 Activity 生命周期已到达宿主记录的 opened 节点 |
| `FAILED` | 失败 | 容器创建或首 Activity 打开抛出未处理异常 |

阶段权重是代码内固定的粗粒度估计，不是耗时比例、字节比例或 ETA。一个阶段执行 100 ms 或 10 秒，都停在对应权重，直到工作真正结束。

#### 13.1.3 推进与展示规则

- 正常阶段只允许单调前进，避免异步回调让进度倒退。
- `ContainerSession.create()` 开始新 Session 时重置到 `PREPARING_RUNTIME`。
- 同一进程已经存在 Session、只重新打开 Activity 时，不重跑 Application 初始化；打开 Activity 前推进到 `FIRST_ACTIVITY_SCHEDULED`。
- 渲染线程只负责把显示值平滑接近真实快照，不自行跨阶段推进；真实快照下降超过 15 个百分点时允许立即重置。
- 中文/英文状态文案跟随宿主语言；目标 App 名称仍使用 APK 自身 label。
- `FAILED` 的权重字段使用 100 仅表示“进度不可继续”，不是启动成功。当前界面不展示数字百分比，且失败后会切换到错误页，避免把失败误读为 100% 成功。
- 进度观察器异常不会中断启动；加载展示属于可观测层，不能改变 Android 初始化语义。

#### 13.1.4 必须注意的边界

- 该进度只表示 NestSpace 容器初始化，不表示目标 App 的网页、Feed、图片、视频、WebView、Lynx、Compose 首帧或首屏网络请求已经完成。
- `FIRST_ACTIVITY_OPENED` 是容器能通用观察到的最后节点。目标 Activity 仍可显示自己的 Splash、隐私协议、骨架屏或异步加载页面，这些继续由目标 App 自己控制。
- 当前目标加载层在目标 `decorView` 挂入宿主后会被替换，因此 100% 节点主要用于诊断；视觉上不一定能看到数字 100%。
- 目标 `onCreate` 内部如果阻塞，宿主的 SurfaceView 仍会绘制阶段文字；但 Android 主线程上的目标生命周期本身不能被并行化。
- `ContainerLaunchProgress` 当前是进程级单值。现有约束是一个槽位进程只承载一个目标 Session；如果未来允许同一进程加载多个 Session，必须按 Session 隔离进度状态。
- SurfaceView 的轨道动画仍有审美性呼吸和轨道运动，但进度条长度和阶段文案只来自真实启动快照，不按时间轮播。
- headless 和 mirror 模式不需要给用户展示启动页；其目标生命周期仍按既有 headless/mirror 规则执行，不能用“加载界面是否可见”判断启动是否完成。
- 阶段权重是产品展示契约。新增、拆分或合并 `AndroidStartupPhase` 时必须同步更新映射和测试，否则构建会因 `when` 非穷尽而失败，避免静默丢失新阶段。
- 进度停在某一阶段时，应先查看 `ContainerSession` 的 `startup phase=...` 日志和耗时，而不是增加假进度。长时间停留通常意味着目标 Application、Provider、兼容 Profile 或主线程工作正在阻塞。

## 14. 分阶段实施

### 阶段 0：基线与验收

交付：

- 原生/容器对照测试工具
- 应用兼容矩阵
- 环境一致性探针
- 结构化诊断导出

验收：

- 能明确区分 Java、native、系统服务、网络或 UI 哪一层失败。

### 阶段 1：VirtualEnvironment 统一

当前实现：已完成基础层。实例启动前生成能力集合，JNI 环境镜像在目标 Application 前安装；Java、native、PM 和 dataDir 的一致性校验会写入诊断。credential-protected 使用实例主根，device-protected 使用独立 `device_protected` 子根，external files/cache/media 使用实例隔离目录；三处都通过实际写探针验证。Keystore namespace、跨存储区数据库迁移和 native 硬编码路径映射仍未完成。

交付：

- `VirtualEnvironment`
- Kotlin registry
- native 环境快照
- 路径和身份一致性检查

验收：

- Java、native、PackageManager 和数据目录观察值一致。

### 阶段 2：进程启动重构

当前实现：部分完成。`AndroidStartupCoordinator` 已接管 Embedded ApplicationLike、进程 Application、Provider、`Application.onCreate`、兼容初始化器、宿主桥和崩溃保护的阶段执行与计时；首个 Activity 的调度和打开也有独立节点。Coordinator 会在阶段执行前发出 `phaseStarted`，结束后发出带 outcome、duration 和 failure 的结果，`ContainerLaunchProgress` 据此驱动启动页。`LaunchLoadingView` 在独立 SurfaceView 渲染线程读取阶段快照，因此主线程被目标初始化阻塞时仍能更新真实阶段文案与进度。固定 2.5 秒 settle 已移除，Coordinator 完成后直接将首个 Activity 排入下一轮主线程调度。已有 Session 只重新打开 Activity 时，不重跑 Application 和 Provider，而是推进到首 Activity 阶段。

目标主线程上的未捕获异常会记录崩溃标记并结束该槽位进程，不再被静默吞掉后继续运行；
后台目标线程的可隔离异常仍由 `TargetCrashGuard` 记录并继续容器。这样单个目标的半初始化
状态不会拖垮其他实例，下一次启动会通过进程池的崩溃恢复流程重新创建槽位。

启动页只覆盖容器初始化。目标 App 自己的 Splash、网络、图片、WebView 和首屏内容加载没有统一 Framework 回调，不能声称已经由容器精确观测；详见 13.1.4。目标业务初始化仍可由用户安装的版本化 `RuntimeCompatibilityProfile` 插件补齐，不再编译进通用容器。

交付：

- 统一 Application/Provider 启动器
- 主线程和 idle 调度
- 启动任务可观测性
- 目标 `android:process` 与槽位映射诊断
- 移除核心运行时中的启动任务包名特判

验收：

- 目标 App 原生初始化任务按自身依赖执行。
- 首个 Activity 不依赖手工等待固定时长。
- 主进程和显式 `android:process` 子进程使用各自独立 Session。
- 启动页显示的阶段与 `startup phase=...` 诊断日志一致，阶段阻塞时不得自行越过。

### 阶段 3：Native Facade

当前实现：已启用目标模块级 Native Facade。`getuid/geteuid/open/open64/openat/openat64/fopen/fopen64/fclose/access/faccessat/stat/lstat/readlink/close/dup/dup2/dup3/fcntl/__system_property_get/__system_property_find/__system_property_read_callback/__system_property_read/__system_property_serial` 的 GOT/PLT 槽位会被 patch，只匹配当前实例 native library 目录和实例数据目录；Android `/data/user/0` 与 `/data/data` 等价路径已归一化，`/storage/emulated/0/Android/data/<target>` 与 `/sdcard/Android/data/<target>` 会映射到实例 external root。目标模块读取 `/proc/self/status`、`cmdline`、`maps`、`mountinfo` 时，libc `open/openat/fopen` 返回实例化只读临时 fd 或 `FILE*`：状态中的 Name/Uid 使用当前目标事实，maps/mountinfo 中的实例数据根改写为目标虚拟路径。重写文件和合成 `/proc` 文件会登记 fd→虚拟路径，`readlink("/proc/self/fd/N")` 返回虚拟路径，close/fclose/dup/dup2/dup3/fcntl 保持映射一致。通过 libc `syscall` 发起的 `getuid/geteuid/openat/fstatat/faccessat/statx/readlinkat/close/dup/dup3` 也走同等映射；内联 `svc` 指令仍无法覆盖并继续标记 unverified。property API 优先返回 Java Build 快照中的 SDK、ABI、产品型号、品牌、版本、fingerprint 等属性：`find` 对快照内属性返回稳定虚拟 `prop_info`，`read_callback/read/serial` 识别该指针；未知键回退真实 property 后端。Hook 保存原指针并在环境重建/清理时恢复，解析失败或非目标模块自动回退真实调用。实测首批 patch 46 槽位/10 模块，随后增长到 156/34，扫描错误 0，目标应用实际触发 1 次虚拟 `/proc` 和 11 次 property 读取，`proc_view_probe=verified`、`property_value_probe=verified`、`property_callback_probe=verified`、`external_path_map_probe=verified`。

交付：

- UID/PID
- 路径映射
- `/proc` 合成
- system properties
- SELinux context 视图
- 直接 syscall 覆盖探针

验收：

- native 探针与 Java 侧读取到同一实例身份。
- 无法覆盖的直接 syscall 被明确标记为 `unverified`。

### 阶段 4：组件与系统服务

当前实现：已有 Service、Provider、Broadcast、Job 和部分系统服务代理；每个显式 `android:process` 槽位会通过独立 `ContainerSession` 创建独立 `VirtualEnvironment`。AccountManager 已支持实例级基础账号语义。MediaSession、AppOps、Connectivity、Clipboard 和 InputMethod 已通过通用 attribution 代理覆盖常见包名归属检查，数组返回值保持原始类型。显式 Activity 会按 Manifest `processName` 分配 Session 和槽位，WebView data-directory 按 package/instance/process 隔离，诊断文件名也按目标进程隔离。显式子进程组件不在 base APK 时，会从实例已下载插件中定位对应 APK，并同时挂载 DEX、Resources 和当前 ABI 的 native library 目录；已实测目标 `:plugin` 占位 Activity 从插件 APK 成功加载并创建。Manifest Broadcast 会按 receiver process 路由到对应槽位，ordered broadcast、Job/Alarm 进程死亡唤醒、Binder 归因和完整多进程自动化矩阵尚未通过。Telephony 特权蜂窝查询仍受宿主 UID/权限限制，详见 4.4。虚拟账号不会注册到 `system_server`，外部应用仍看不到该账号。

交付：

- Binder 查询代理
- PackageManager 一致性
- Service/Provider/Job/Notification 统一
- 权限和 AppOps 诊断

验收：

- 普通 App、多进程 App、WebView/Lynx App 不因组件身份出现回归。
- 普通数据网络可用与特权蜂窝身份可用分别诊断；后者不支持时必须明确降级，不能导致首页崩溃或伪造蜂窝状态。

### 阶段 5：安全和性能

当前实现：未开始默认启用。Native Hook 仍按分组关闭；当前只有启动阶段耗时、页大小和诊断文件。性能预算、路径缓存、JNI 批量和零拷贝帧路径仍需实现后再做 Go/No-Go。

交付：

- 安全 SDK 选择性探针
- Native hook 故障隔离
- 路径缓存和 JNI 批量接口
- 帧转换零拷贝路径

验收：

- 启动时间、内存和崩溃率不高于当前基线。
- 每个 Native Hook 可单独关闭并回滚。

## 15. 测试矩阵

### 15.1 环境一致性

| 检查项 | Java | Native | PackageManager |
| --- | --- | --- | --- |
| 包名 | 必须一致 | 必须一致 | 必须一致 |
| UID | 兼容值一致 | 兼容值一致 | 兼容值一致 |
| PID | 进程内一致 | 进程内一致 | 不对外承诺稳定 |
| dataDir | 必须一致 | 必须一致 | 必须一致 |
| deviceProtectedDataDir | 必须一致 | 必须一致 | 必须一致 |
| credentialProtectedDataDir | 必须一致 | 必须一致 | 必须一致 |
| APK 路径 | 必须一致 | 必须一致 | 必须一致 |
| nativeLibraryDir | 必须一致 | 必须一致 | 必须一致 |
| Android ID | 必须一致 | 必须一致 | 必须一致 |
| targetProcessName | 必须一致 | 必须一致 | 必须一致 |
| /proc 身份视图 | 不适用 | 必须一致或标记 unverified | 不适用 |
| SELinux context | 不适用 | best effort | 不适用 |

### 15.2 应用场景

- 普通单进程 App
- 多进程 App
- 显式 `android:process` Service
- 目标自行调用 `fork/exec` 的 native helper
- Splash + 隐私协议 App
- WebView/Lynx App
- 带 native 安全 SDK 的 App
- 使用 AccountManager/Keystore 的 App
- 依赖公共参数和 DID 的 App
- 图片和视频首屏
- Activity/Fragment 动态重建
- 冷启动、热启动和进程回收恢复

### 15.3 本轮实测

- 设备：Android 16 / API 36，`arm64-v8a` Android Emulator。
- 目标：通用目标应用样本，实例 `instance-1`。
- 日志：`virtual environment validation passed`。
- 能力：`NATIVE_BRIDGE`、`NATIVE_UID_PROBE`、`NATIVE_PROC_STATUS_PROBE`、`NATIVE_PROC_MAPS_PROBE`、`NATIVE_PROC_MOUNTINFO_PROBE`、`NATIVE_SELINUX_PROBE`、`NATIVE_DIRECT_SYSCALL_PROBE`、`NATIVE_PROPERTY_PROBE`、`SUBPROCESS_SLOTS`、`DEVICE_PROTECTED_STORAGE`、`EXTERNAL_STORAGE`、`ACCOUNT_BASIC` 共 12 项实际发布。
- 环境：Java `10216`、libc `10216`、直接 syscall `10216`、`/proc/self/status` `10216`，一致性校验通过。
- 存储：诊断中 credential、device-protected、external 均为 `instance`，device-protected 根为实例目录下的 `device_protected`。
- 账号：`ACCOUNT_BASIC` 发布，目标应用首次添加目标类型账号成功，日志中不再出现 `cannot explicitly add accounts of type`。
- 冷启动：移除固定 settle 后连续执行 3 次完整冷启动，均到达目标首屏 Activity，搜索、频道、内容卡片和图片正常。
- 多进程：显式启动目标 `:plugin` 占位 Activity 后，独立目标进程完成 `VirtualEnvironment`、AccountManager、WebView suffix 和诊断初始化；插件 APK 中的 DEX、Resources 和一个 ABI native library 目录被按需挂载，占位 Activity 成功创建。不再出现两个进程争用同一 WebView 数据目录。
- 多进程能力：`:plugin` 诊断发布 13 项 capability，包含 `PLUGIN_CLASS_PATH`；Java 与 native 进程名均为目标包名加进程后缀，启动报告完成。
- Native Facade：主进程诊断 `native_facade.mode=active`，路径和 property 映射探针 `verified`；首批 patch 46 槽位/10 模块，随模块加载增长到 156 槽位/34 模块，扫描错误 0。未出现 SIGSEGV、EBADF、fd 泄漏或网络回归，目标首屏正常且实际完成 1 次虚拟 `/proc`、11 次 property 读取。
- `/proc` Facade：合成状态探针 `verified`；目标应用本次未主动读取 `/proc`，运行计数器为 0，因此诊断标记 `available-unexercised`，不伪造“已命中”。
- 启动报告：Provider、`Application.onCreate`、兼容初始化、网络初始化和首个 Activity 全部成功；本轮冷启动阶段总耗时约 19.45 秒。
- 可见模式：目标首屏、图片、列表和底部导航均正常加载；网络与日志初始化成功。
- headless：宿主被移到后台后，虚拟 Activity 现在仍会执行 `onStart/onResume`，并在 observe/screenshot 前强制目标 View 树完成 measure/layout。MCP 已能看到搜索栏、频道、内容卡片文字和底栏。
- headless 图片边界：虚拟显示中的目标窗口仍被系统标记为 `isShown=false`，Fresco 等图片管线可能不提交真实图片请求；因此当前 headless 文字和布局可用，但截图中海报可能仍是占位背景。
- 启动进度：目标应用冷启动时连续截到 `Loading app resources`、`Running app startup` 和 `Opening the app`，对应阶段权重约为 16%、58% 和 97%，随后进入目标首屏 Activity。当前测试机语言为英文，因此状态文字为英文，目标 App label 保持目标 APK 自身提供的名称。
- 启动耗时样本：本次目标应用冷启动日志为 `CLASS_LOADER` 约 2.49 秒、`APPLICATION_ATTACH` 约 0.48 秒、`CONTENT_PROVIDERS` 约 0.09 秒、`APPLICATION_CREATE` 约 0.61 秒、`STARTUP_COMPATIBILITY` 约 0.33 秒。耗时只代表该次模拟器样本，不能作为固定 ETA。
- 启动失败边界：`ContainerSession` 创建、首 Activity 调度或首 Activity 打开抛出未处理异常时，进度标记为 `FAILED` 并进入错误页；这不等同于已成功打开目标页面。
- 运营商特权边界：目标应用启动后，运营商登录 SDK 调用 `getDataNetworkTypeForSubscriber` 收到系统 `SecurityException`；异常由 SDK 捕获，没有 FATAL/SIGSEGV，首屏和普通网络正常。同期测试 App 为 `network=active all=1`。该问题记录为现有 MediaSession、AppOps、Connectivity、Clipboard 和 InputMethod 代理之外的剩余 Telephony 身份边界，详见 4.4。
- 自动化验证：`LaunchProgressStageTest` 检查真实阶段映射单调且最终到达 100%，`AndroidStartupCoordinatorTest` 检查 `phaseStarted` 在阶段 block 执行前通知。`./gradlew :app:testDebugUnitTest :app:assembleDebug -PnestspaceHostAbi=universal` 通过。

### 15.4 启动进度验收

| 场景 | 预期 |
| --- | --- |
| 新进程冷启动 | 从 4% 的 `PREPARING_RUNTIME` 开始，按真实初始化阶段单调推进 |
| `Application.onCreate` 长时间阻塞 | 进度停在 `APPLICATION_CREATE`，SurfaceView 轨道动画继续，文案不得跳到后续阶段 |
| Provider 被跳过或失败 | skipped 可推进到对应阶段；关键失败标记 `FAILED`，不能显示“即将完成” |
| Session 已存在、重新打开 Activity | 不重跑 Application，直接进入 `FIRST_ACTIVITY_SCHEDULED` |
| 首个 Activity 创建 | 目标 decorView 接管宿主窗口，目标自己的 Splash/加载页随后正常出现 |
| 目标首屏仍在联网 | 容器启动进度可以已完成，目标内容仍可继续加载；不得把这部分描述成容器初始化失败 |
| 速度过快的阶段 | 允许快速越过并做显示平滑，但不能由渲染线程生成不存在的阶段 |
| 启动失败 | 错误页取代加载页；`FAILED` 不得被当作成功 100% 展示 |

## 16. 风险与回滚

| 风险 | 缓解 |
| --- | --- |
| Hidden API 随 Android 版本变化 | 按 API 版本拆适配器 |
| Native Hook 导致 SIGSEGV | 分组启用、异常隔离、默认关闭高风险 Hook |
| native 库符号冲突或页大小不兼容 | 独立 linker namespace、启动前 ELF 校验 |
| 目标直接 syscall 绕过 libc Hook | 增加直接 syscall 探针；无法覆盖时标记 unsupported |
| 子进程未加载 NativeEnvironment | 进程创建 Hook、子进程 bootstrap 探针 |
| Java/native 事实不一致 | 启动时一致性探针，失败阻止高风险模式 |
| 单 App 策略污染通用容器 | 策略版本绑定，独立回滚 |
| 性能退化 | Native registry、路径缓存、粗粒度 JNI |
| 启动进度被误认为目标内容加载 | 文案只描述容器初始化；目标 Splash、网络和媒体加载继续由目标 App 负责 |
| 启动阶段权重与代码流程漂移 | 阶段映射使用穷尽 `when`，新增阶段必须同步权重和单测 |
| 长耗时阶段被误判为卡死 | 结合 `startup phase` 开始、结束、duration 和主线程日志诊断，不增加假进度 |
| 多 Session 共用进程级进度 | 当前约束为一进程一目标 Session；放开前必须把进度改为按 Session 键控 |
| 特权蜂窝查询被误判为网络故障 | 将 Telephony UID/权限异常与普通 Connectivity 状态分开诊断；不伪造网络类型或 SIM 身份 |
| 安全策略变化 | 保留诊断模式，不伪造服务端信任 |
| Hook 库本身被扫描或被识别 | Hook 模块最小化、可关闭；不以隐藏全部映射为承诺 |
| 第三方 hook/ELF 库许可证或维护风险 | 选型前审计许可证、维护状态和商用条款 |

回滚原则：

1. 新运行时使用 feature flag。
2. 每个 Native 模块有独立开关。
3. 应用策略可禁用并回退通用容器。
4. 环境版本升级前备份实例元数据，不复制身份密钥。

## 17. 待验证问题与 Go/No-Go

以下问题必须在扩大 Native Hook 范围前通过独立实验确认：

| 问题 | 最小实验 | No-Go 条件 |
| --- | --- | --- |
| 直接 syscall 可覆盖性 | 目标 native 测试库直接调用 `syscall(SYS_openat/statx)` | 关键环境读取无法观测或无法安全改写 |
| Native Hook 生效顺序 | 预加载、`dlopen`、延迟解析函数三条路径 | Hook 导致崩溃、递归或无法确认命中 |
| 子进程启动 | 显式 `android:process` Service/Provider/Activity | 子进程身份、Session 或资源出现不一致 |
| `fork/exec` | native helper 进程探针 | `exec` 后失去环境且无法诊断 |
| Java/native 一致性 | 同一实例读取 UID、路径、包名、进程名 | 任一核心字段出现不可解释的冲突 |
| 性能 | 冷启动、路径查询和首屏渲染基准 | 启动回归超过 5% 或高频路径超过预算 |
| 特权蜂窝身份 | 一键登录 SDK 查询网络类型、订阅和 SIM 状态 | 普通 APK 无法获得权限；应将该能力降级为不支持，不能阻塞普通网络或伪造状态 |
| 安全 SDK | 只在调用窗口记录读操作 | 探针本身导致 native crash 或明显卡顿 |

Go/No-Go 应按能力分级，而不是用一个实验否决整个运行时：

- **核心 Go**：直接 syscall 探针、Java/native 一致性、加载顺序和性能稳定通过，可启用默认 Native Facade。
- **组件 Go**：子进程和 Provider 实验通过，才允许 App 策略声明多进程兼容。
- **限制级**：`fork/exec`、Keystore、强完整性校验无法通过时，只限制对应 App 类别，并给出明确诊断。

只有核心 Go 通过后，才能把 Native Facade 从诊断模式提升为默认运行模式。

## 18. 结论

单个免 Root APK 能继续显著提高 Android 兼容性，但正确方向不是构建一个 DEX 解释器，也不是为每个 App 增加更多零散 Hook，而是：

1. 建立统一 VirtualEnvironment。
2. 用虚拟 Android Framework 还原真实进程和组件启动语义。
3. 用 Native Compatibility Facade 统一 syscall、`/proc`、property、ELF 和 native 路径。
4. 将无法通用的差异收敛到版本化兼容策略。
5. 通过严格的一致性、性能和崩溃测试控制风险。

该方案能够提高普通应用级容器的兼容上限，并减少后续 App 适配量；但 Binder 真实调用者、SELinux、内核 namespace 和系统完整性证明仍属于单 APK 无法彻底跨越的系统边界。

因此，服务端 `110/ILLEGAL_ACCESS`、支付、登录和风控页面不能只用“页面出现”作为成功标准。必须同时验证请求参数、服务端响应和重启后的稳定性；如果根因是服务端信任系统级身份，本设计只能改善可观察环境，不能保证消除该错误。

# NestSpace 整体性能审计与修改建议

- 状态：草案
- 日期：2026-11-09
- 范围：宿主启动、容器启动、Java/native Hook、进程池、APK 处理、插件加载、Agent/MCP UI 链路、截图、存储和发布构建
- 依据：当前仓库静态代码审查
- 说明：本文尚未加入线上或真机采样数据，因此不承诺具体百分比收益。实施前应先建立基线，再按优先级逐步验证。

## 1. 结论

当前项目的主要性能风险不是缺少 native 模块，而是多个高频链路同时存在以下问题：

1. 截图与实时预览链路已完成改造：容器直接生成目标尺寸 JPEG，跨进程传 `ByteArray`，UI 只解码一次，Base64 仅保留在模型 API 边界。
2. 容器 UI Service 已改为按 slot 复用 binder；普通调用空闲后释放，headless keep-alive 持同一连接。
3. native Hook 快路径已改为增量扫描、哈希查重、不可变环境快照和 FD/property 无全局锁快路径。
4. APK descriptor 与 ABI 索引已完成持久化；安装只完整 inspect 一次，后续启动优先读取带文件指纹校验的缓存。
5. 自动槽位已按可用内存动态计算；固定模式继续严格使用用户选择的 1–16。
6. 主线程与进程池锁内 I/O 已完成改造：桌面启动改用轻量内存索引，kill、`/proc`、ActivityManager、PSS 和 SharedPreferences 均在状态锁外执行。
7. target 启动热路径仍存在重复反射和完整 Parcel 深拷贝；宿主 `Application.onCreate` 的非首屏任务已迁到首帧后的串行后台线程。
8. Debug 源码插件在启动时解析 Manifest、提取插件资源并使用宿主 ClassLoader 注册；插件分享包在构建时生成 DEX 和 ZIP，不进入日常源码运行热路径。外部 `.nestplugin` 仍需要解压和 `DexClassLoader` 初始化，插件数量增加后应纳入启动基准。

建议按以下顺序处理：

| 优先级 | 项目 | 预期收益 | 主要风险 |
| --- | --- | --- | --- |
| P0-A | 截图与实时预览链路（已完成） | 已移除 PNG/Base64 中间层和全分辨率预览 Bitmap | MuMu 与 JVM 预览测试已通过 |
| P0-B | 复用容器 UI Service binder（已完成） | 连续工具调用只建立一次连接 | 断连与空闲释放已在 MuMu 验证 |
| P0-C | native Hook 快路径（已完成） | 已降低目标 App 文件/property/JNI 调用成本和后台 CPU | GOT/PLT Hook 回归风险 |
| P0-D | APK 元数据缓存（已完成） | 缩短容器冷/热启动，减少重复 I/O 与签名计算 | 路径、大小、mtime、payload digest 或 schema 变化时回退完整 inspect |
| P0-E | 主线程与进程池锁内 I/O（已完成） | 已消除启动入口的完整运行列表查询和全局锁内 kill/进程扫描 | generation 状态机防止旧 kill 覆盖新 owner |
| P1-A | 进程池与内存策略（自动槽位已完成） | 自动按内存计算，固定模式严格使用用户选择 | MuMu 已验证自动 12、固定 8/16 |
| P1-C | 常驻轮询与启动渲染开销 | 降低后台 CPU、电量和锁竞争 | 事件覆盖不完整会影响镜像跟随 |
| P1-D | Token usage 落盘（已完成） | 500 ms debounce，合并完整 JSON 写入 | 进程异常退出前最多约 500 ms 未落盘 |
| P1-E | target 反射与 PackageManager 深拷贝 | 缩短目标 App 启动和系统服务调用时间 | Android 版本/ROM 差异 |
| P1-F | 主进程非首屏启动任务（已完成） | 缩短 `Application.onCreate` 和宿主首屏路径 | 功能初始化统一延迟到首帧或 500 ms fallback |

## 2. 整体运行链路

当前主要链路可以概括为：

```text
NestSpace 主进程
  ├─ Compose UI / Agent / MCP Server
  ├─ MiniContainerEngine
  │    ├─ APK 复制、解析、签名和 ABI 检查
  │    ├─ native `.so` 缓存提取
  │    └─ 分配 :c1..:c16 容器进程
  └─ Agent UI 工具
       └─ bindService -> ContainerUiService
            ├─ View 树快照
            ├─ 点击、输入、返回
            └─ 截图

:cN 容器进程
  ├─ ContainerSession / ActivityThread 桥接
  ├─ Java Framework Hook
  ├─ target Application / Activity
  └─ libnestspace_runtime.so
       ├─ 环境快照
       ├─ libc/property/proc 路径 facade
       └─ 周期性 Hook 刷新
```

性能问题需要分四类看待：

- 用户直接感知：首屏、容器启动、Agent 工具响应、点击延迟、实时预览流畅度。
- 持续成本：Hook 刷新、镜像轮询、位置模拟、MCP/前台服务、进程存活。
- 峰值成本：截图位图、APK 复制、DEX/资源加载、多个目标 App 同时运行。

## 3. P0-A：截图和实时预览链路（已完成）

### 3.1 最终实现

容器截图协议已经从 PNG/Base64 String 改为二进制 JPEG：

- [`IContainerUi.screenshotJpeg()`](../runtime-core/src/main/aidl/com/coke/nestspace/container/IContainerUi.aidl) 直接返回 `byte[]`。
- [`ContainerUiController.screenshotJpeg()`](../runtime-core/src/main/java/com/coke/nestspace/control/ContainerUiController.kt) 按预览目标宽度直接创建缩放 Bitmap，然后编码 JPEG。
- [`ContainerPreviewCapture`](../runtime-core/src/main/java/com/coke/nestspace/control/ContainerPreviewCapture.kt) 按 slot 串行截图，并合并 600 ms 内的预览触发。
- [`LivePreview.Frame`](../runtime-api/src/main/java/com/coke/nestspace/control/LivePreview.kt) 保存 JPEG 字节，不再保存 Base64。
- [`AgentLivePreview`](../app/src/main/java/com/coke/nestspace/ui/agent/AgentLivePreview.kt) 直接从字节数组解码一次并显示。
- [`ui_screenshot`](../app/src/main/java/com/coke/nestspace/tools/UiToolSpecs.kt) 只在最终提供给视觉模型的边界执行 Base64 编码。

最终链路为：

```text
target View 树（保持真实布局尺寸）
  -> 容器主线程按 640px 目标宽度绘制
  -> 容器 IO 线程做亮度采样并编码 JPEG
  -> Binder 返回 ByteArray
  -> LivePreview 保存 ByteArray
  -> Compose 解码一次并显示
  -> 仅模型 API 边界转换为 Base64
```

容器侧旧的 `screenshotBytes()`、`screenshotBase64()`、`screenshotPngPath()` 和 `screenshotJpegBase64()` 已删除；不再创建全分辨率预览 Bitmap，也不再经过 PNG 解码、缩放和二次 JPEG 压缩。

预览链路还完成了以下收口：

- 空白帧检测在容器内基于缩放后的 Bitmap 执行，不再在主进程解码 Base64。
- `ui_observe`、点击、输入和等待后的预览请求经过统一调度器。
- 同一 slot 同时只允许一个截图任务。
- Agent 页面轮询与工具操作不再并发重复截图。
- 空白帧返回后继续轮询，不会错误终止整个实时预览循环。
- 用户截图与 Agent 低带宽预览采用不同参数：用户截图不丢弃空白帧，预览会拒绝近似纯白帧。

### 3.2 MuMu 实测

测试设备：

```text
MuMu / PGBM10
Android 15
主 ABI x86_64
支持 ABI x86_64, arm64-v8a, x86
1080x1920
安装 ABI arm64-v8a
```

验证结果：

- arm64 Debug APK 安装成功，系统实际 `primaryCpuAbi=arm64-v8a`。
- `com.coke.testapp` 在 slot 0 以 headless 模式启动成功。
- `ui_observe` 返回 18 个控件。
- `ui_screenshot` 返回 `image/jpeg`，JPEG magic 为 `FF D8 FF`。
- 单次截图约 16 ms，图像约 42 KB。
- 点击测试页的“点击并 +1”后再次截图，图像哈希发生变化，确认截图不是旧缓存。
- 日志中未发现本次链路的 `FATAL EXCEPTION`、`UnsatisfiedLinkError` 或 `TransactionTooLargeException`。
- Debug APK 构建成功。
- `LivePreviewTest` 通过。

### 3.3 已知的非本次问题

全量 JVM 单元测试仍有一个既有失败：

```text
NativeLibsTest.sharedCacheIsReusedInvalidatedAndHonorsExclusions
```

失败点是 Windows 上删除 `.ready` 后的缓存恢复，与截图和预览链路无关。

## 4. P0-B：复用容器 UI Service 连接（已完成）

### 4.1 最终实现

[`ContainerUiClient`](../runtime-core/src/main/java/com/coke/nestspace/control/ContainerUiClient.kt) 现在按 slot 维护唯一的 `SlotBinding`：

- 首次调用只执行一次 `bindService()`，之后直接复用缓存的 `IContainerUi`。
- 每次工具调用只借还 Binder lease，不再在 `finally` 中解绑。
- 同一个 slot 同时只有一个连接和一个绑定锁。
- 普通调用空闲 10 秒后自动解绑；新调用会取消旧 idle timer。
- `onServiceDisconnected`、`onBindingDied`、`onNullBinding` 会清除连接。
- Binder 调用遇到 `DeadObjectException` 或 `RemoteException` 时，失效连接并自动重试一次。
- 多调用并发时按 slot 串行建立连接，避免重复 bind。

[`ContainerUiKeepAlive`](../runtime-core/src/main/java/com/coke/nestspace/control/ContainerUiKeepAlive.kt) 不再维护第二套 `ServiceConnection`，而是持有同一个连接池中的 slot：

- headless 模式保持 binder，超过 10 秒也不释放。
- 非 headless 模式只享受短时间复用，空闲后释放。
- `space_stop`、卸载、slot 回收继续通过 `unbind()` 释放保留状态。

### 4.2 MuMu 实测

headless 场景：

- 连续 4 次 `ui_observe`，耗时分别为 10ms、7ms、8ms、6ms。
- 连续 3 次 `ui_screenshot`，耗时分别为 21ms、11ms、12ms。
- `slot 0 connected` 日志只出现 1 次。
- 空闲 12 秒后 `slot 0 binding closed` 为 0 次，确认 keep-alive 连接被保留。

非 headless 场景：

- 启动容器后连接一次。
- 无后续工具调用时，10 秒空闲后出现 `slot 0 binding closed: idle`。
- 目标进程停止后，旧连接通过 service disconnected 正确失效。

### 4.3 约束

- 不长期持有全部 16 个 slot 的连接。
- 非 headless 连接必须有 idle 释放。
- 不把旧 slot 的 binder 发给复用后的新目标进程。
- keep-alive 只延长连接池中的同一个 binding，不额外创建 ServiceConnection。

## 5. P0-C：优化现有 native Hook 热路径（已完成）

当前实现已把原先集中的 native 源码拆为 JNI 桥接、环境/FD/property 状态、libc Hook 和 ELF Hook 四部分。环境通过不可变快照和原子指针读取；已扫描模块按 base address 与名称哈希去重，已 patch 地址使用哈希集合；路径前缀在 install 阶段预计算；FD、property 和 `readlink` 快路径不再获取全局锁。目标 native 库优先选择宿主进程实际安装的 ABI，避免 ARM 宿主在转译模拟器中选择 x86 目标库后无法改写 Houdini GOT。

`dlopen`、`android_dlopen_ext` 及其 Linker 内部别名已接入 Hook；成功后向 native 合并刷新队列投递增量扫描请求，不阻塞调用 `dlopen` 的业务线程。`libart`、`libnativeloader` 和 `libandroid_runtime` 只安装 `dlopen` 观察入口，不应用 open/property/UID 等其他系统 Hook，因此 `System.loadLibrary` 也能触发增量处理。

`/proc/self/maps` 与整文件 ELF fallback 不再跟随 250 ms 快刷新执行，而是在目标启动稳定 20 秒后一次性补齐，作为自定义 Linker 或非标准加载方式的兜底。重 native 目标应用启动时会同时 mmap、解压和加载大量 `.so`；在加载期执行文件级 fallback 会显著拖慢甚至卡死模拟器。普通 `dl_iterate_phdr` 增量 Hook 仍保持快刷新。

### 5.1 周期性 Hook 刷新

[`ContainerSession.startNativeHookPump()`](../runtime-core/src/main/java/com/coke/nestspace/container/ContainerSession.kt) 当前采用：

- 启动后每 250 ms 刷新一次。
- 20 秒后降为每 2 秒刷新一次。
- 刷新持续到进程结束。

每次刷新会进入 native：

- `dl_iterate_phdr()` 遍历已加载模块。
- 已扫描模块按 base address 与名称哈希跳过，只解析新模块。
- `patch_slot()` 使用地址哈希集合去重。

[`patch_slot()`](../app/src/main/cpp/native_elf_hooks.cpp) 使用地址哈希集合判断槽位是否已经处理；重复 relocation 不再线性扫描全部已 patch 地址。

最终实现：

- 用模块 base address 与路径哈希记录已扫描模块，只处理新模块。
- 用 `unordered_set<void**>` 记录已 patch slot。
- 首次启动完整扫描；标准 `dlopen`/`android_dlopen_ext` 成功后增量处理。
- 对 Linker 系统模块只观察加载入口；maps ELF fallback 延迟为启动稳定后的单次兜底。
- 同一内存页只执行一次 `mprotect()`。
- 普通 release 构建中降低 Hook 统计和日志频率。

### 5.2 环境读取（已完成）

[`current_environment()`](../app/src/main/cpp/native_environment_state.cpp) 只执行原子读取。环境更新时创建新快照并原子发布；旧快照保留到进程结束，避免并发读者悬空。

```cpp
std::atomic<const Environment*> g_environment{nullptr};
```

`getuid/geteuid`、路径、property 和 `syscall` Facade 的热路径均不获取全局互斥锁。

### 5.3 路径映射（已完成）

[`rewrite_target_path()`](../app/src/main/cpp/native_environment_state.cpp) 的环境快照在 install 阶段预计算 data/device/external 路径前缀和 alias；普通路径不匹配时直接回退真实调用，只有确实命中目标路径时才构造重写结果。

### 5.4 FD 表（已完成）

[`hook_close()`](../app/src/main/cpp/native_libc_hooks.cpp) 使用固定 FD 槽位和原子指针记录虚拟路径；`close`、`fclose`、`dup`、`dup2`、`dup3` 与 `fcntl` 只更新对应槽位，不获取全局锁。快路径只记录需要反向映射的 FD。

### 5.5 property 快路径（已完成）

[`hook_property_get()`](../app/src/main/cpp/native_libc_hooks.cpp) 与 property callback 从不可变快照读取名称索引和稳定虚拟 `prop_info` 指针；属性表和 fake pointer 索引只在环境构建时创建，读取路径不获取共享互斥锁。

### 5.6 其他 native 优化

- [已完成] 虚拟 `/proc/self/maps` 与 `mountinfo` 使用 `memfd_create`，并对同一环境/路径增加 200 ms 内容短缓存；旧内核回退到临时文件。
- [已完成] target 请求参数 Java 代理 Hook 改为后台单次初始化，所有请求共享完成信号，不再在锁内重复执行 `getInstalledPackages()` 和 3 秒轮询。
- 缓存 page size，避免每个 patch 调用 `sysconf()`。
- `replacement_for()` 按符号首字符或预计算哈希分组，减少长字符串比较。
- 对 relocation 地址排序后再批量修改相邻页面。
- 单独记录“已 patch 但失败”的地址，避免每次重试。
- 增加 native monotonic 计时，输出单次 refresh 的模块数、relocation 数和耗时。

## 6. P0-D：缓存 APK 描述和 ABI 扫描（已完成）

当前实现会在 `StoredApp.apk_index` 中保存 base/split 的路径、大小、mtime、SHA-256、合并 ABI 集合和 descriptor cache 路径。安装阶段对临时 APK 完成一次包名、签名、manifest 和安全校验，移动后通过当时计算的 digest 证明内容未变化，不再执行第二次完整 inspect；descriptor 以版本化 Parcel 缓存写入安装目录。主进程启动、容器进程和子进程都先校验索引后读取缓存，损坏或失效时自动回退完整扫描。旧安装记录会在首次启动时惰性生成索引。

### 6.1 改造前重复工作

APK 相关重复工作包括：

- 安装时对临时 APK 执行 [`ApkInspector.inspect()`](../runtime-core/src/main/java/com/coke/nestspace/container/loader/ApkInspector.kt)。
- 移动为最终 APK 后再执行一次完整 inspect。
- 启动时 [`MiniContainerEngine.launchLocked()`](../runtime-core/src/main/java/com/coke/nestspace/engine/MiniContainerEngine.kt) 再 inspect base APK。
- 启动时 [`NativeLibs.abisOf()`](../runtime-core/src/main/java/com/coke/nestspace/engine/MiniContainerEngine.kt) 扫描所有 APK。
- 容器进程创建时再次扫描所有 APK。
- [`ApkInspector.nativeAbis()`](../runtime-core/src/main/java/com/coke/nestspace/container/loader/ApkInspector.kt) 遍历 ZIP 全部 entry。
- [`NativeLibs.extractIfNeeded()`](../runtime-core/src/main/java/com/coke/nestspace/container/loader/NativeLibs.kt) 缓存未命中时再遍历全部 ZIP entry。

`ApkPayloadIdentity.same()` 也会完整读取所有 APK 并计算 SHA-256。

### 6.2 最终缓存方案

#### A. 安装时生成一次索引

在 `StoredApp` 或独立索引文件中持久化：

```text
baseApkPath
splitApkPaths
packageName
versionCode
versionName
launcherActivity
signatureSha256
nativeAbis
fileSize
lastModified
payloadDigest
schemaVersion
```

运行时优先读取索引，不重复解析 manifest。

#### B. 定义严格缓存失效条件

以下任一变化必须使缓存失效：

- 文件大小变化。
- `lastModified` 变化。
- 安装目录或文件名变化。
- App 版本变化。
- 索引 schema 版本变化。
- 完整性校验失败。

APK 安装后是只读文件，可以在首次生成索引后信任路径、大小、mtime 和已记录摘要。

#### C. 安全校验必须保留，避免重复解析

APK 包名、签名、Manifest 组件、`minSdk`、`REQUIRE_SECURE_ENV` 和 split 一致性属于安装正确性要求，不能被当作性能成本删除。

临时 APK 移入最终目录后，优先验证：

- 文件大小。
- 已计算 payload digest。
- package/version。

如果 payload digest 证明移动后的文件与已经通过校验的临时文件字节完全一致，可以复用之前的 `ApkDescriptor`，只更新路径，不必再次遍历 manifest 和 v1 签名。若 digest、大小或版本不一致，必须重新执行完整校验或拒绝安装。

不得把首次签名校验延迟到容器启动时，也不得使用 auto-generated placeholder 绕过校验。

#### D. 把 ABI 结果随描述保存

`ApkDescriptor.nativeAbis` 已经包含 ABI 信息，不应在后续每一步重新扫描 ZIP。安装和启动都直接使用索引。

## 7. P1-A：调整进程池、内存和回收策略

### 7.1 自动槽位与内存策略（已完成）

当前 Manifest 定义 16 个容器槽位，[`android:largeHeap="true"`](../app/src/main/AndroidManifest.xml)，最大槽位常量为 [`SLOT_COUNT = 16`](../runtime-core/src/main/java/com/coke/nestspace/container/ContainerProcessPool.kt)。

产品语义：

- **自动模式**：根据当前可用内存动态计算可用槽位，最高可以使用 16 个。自动模式的“16”只是上限，不是固定值。
- **手动模式**：严格使用用户选择的固定值。用户选择 1、4、8 或 16，就分别以 1、4、8、16 作为应用自己的槽位上限，不能再次被“自动策略”改小。

最终实现：

- [`SlotMemoryPolicy`](../runtime-core/src/main/java/com/coke/nestspace/container/SlotMemoryPolicy.kt) 负责纯内存计算。
- [`slotLimit()`](../runtime-core/src/main/java/com/coke/nestspace/container/ContainerProcessPool.kt) 在自动模式返回动态缓存值，固定模式直接返回用户选择。
- [`refreshAutoSlotLimit()`](../runtime-core/src/main/java/com/coke/nestspace/container/ContainerProcessPool.kt) 在启动、停止、设置和系统内存回调时刷新容量。
- [`NestSpaceApp.onTrimMemory()`](../app/src/main/java/com/coke/nestspace/NestSpaceApp.kt) 将系统内存压力传给主进程槽位池。
- [`evictAutoExcess()`](../runtime-core/src/main/java/com/coke/nestspace/container/ContainerProcessPool.kt) 只回收后台 slot，保护当前活动槽位。

计算模型：

```text
basePerSlot = 320 MB
estimatedPerSlot = max(basePerSlot, 最近 slot PSS 的 P75)
reserve = max(threshold * 2, totalMemory * 15%, 768 MB)
rawLimit = runningSlots + floor((availableMemory - reserve) / estimatedPerSlot)
autoLimit = clamp(rawLimit, 1, 16)
```

防抖规则：

- 自动容量下降立即生效。
- 自动容量上升每次最多增加 1。
- `lowMemory` 时不允许增加容量。
- 普通读取最多每 2 秒刷新一次，不增加常驻监控线程。
- 固定模式不经过任何自动计算，用户选 16 时返回 16。

MuMu 实测：

- Android 15，总内存约 5.9GB，可用约 4.8GB。
- 自动模式计算为 12，而不是固定返回 16。
- 固定 8 返回 8，固定 16 返回 16。
- 切回自动模式后重新计算为 12。
- 发送 `RUNNING_LOW` 后重新计算为 12，未错误扩容，也未崩溃。
- 自动有效值写入独立 `auto_effective_limit`，没有覆盖用户的 `slot_limit` 模式。

### 7.2 杀死 slot 的等待路径

[`killSlotProcess()`](../runtime-core/src/main/java/com/coke/nestspace/container/ContainerProcessPool.kt) 在 `Process.killProcess()` 后最多等待 1.5 秒，每 50 ms 检查一次。

必须先明确：**槽位复用时先杀旧进程是设计约束，不能删除。** 同名 native library、JNI 全局状态和 linker 状态无法可靠卸载；跳过 kill 会造成跨 App native 状态污染。这里允许优化的是等待机制和锁范围，不是“不等待”或“直接复用旧进程”。

改造前检查可能执行：

- `ActivityManager.runningAppProcesses`。
- [`pidOfProcess()`](../runtime-core/src/main/java/com/coke/nestspace/container/ContainerProcessPool.kt) 遍历整个 `/proc`。
- 读取 marker 文件和 `/proc/<pid>/status`。

这会在槽位复用前引入固定延迟和额外 Binder/文件 I/O。

最终实现：

- 从 marker 中取得 pid 后，直接等待该 pid 消失。
- 等待逻辑使用 `Process.killProcess(pid)` 后的轻量 `pid` 存活检查。
- 可增加一个 native `waitForPidExit(pid, timeoutMs)`，使用 `kill(pid, 0)` 或可用的 `pidfd`，避免扫描全部 `/proc`。
- `ActivityManager.runningAppProcesses` 只在诊断或无法取得 pid 时使用。
- 轮询间隔根据剩余时间退避，例如 20 ms、50 ms、100 ms。

### 7.3 运行中列表

[`RunningAppsScreen`](../app/src/main/java/com/coke/nestspace/ui/running/RunningAppsScreen.kt) 每 2 秒刷新 `runningSlots()`。

该页打开时刷新是合理的，但应确保：

- 页面不可见时停止刷新。
- 每次只读取一次 `runningAppProcesses`。
- 内存读取失败时不要让整页刷新失败。
- 多进程 PSS 读取可缓存 1 到 2 秒。

## 8. P1-C：减少常驻轮询和启动渲染开销

### 8.1 Mirror 50 ms 轮询

**状态：已完成自适应改造。** 启动前 2 秒继续每 50 ms 检测，
之后降为每 250 ms；VirtualDisplay 刷新固定为每秒一次。快速阶段保证
Activity 切换和黑帧兜底，稳定阶段避免 headless 常驻时持续占用主线程。

[`StageAActivity.mirrorFinishPoller`](../runtime-core/src/main/java/com/coke/nestspace/container/StageAActivity.kt) 在启动快速阶段每 50 ms 执行一次，稳定后降为每 250 ms：

- 检查 target 是否 finishing。
- 每秒刷新一次 mirror display。

可选进一步优化：

- 目标 Activity 生命周期回调直接通知 back stack 变化。
- `VirtualDisplay` 变化由明确的创建/销毁事件驱动。
- 保留低频 fallback，例如前台 250 ms、后台 1 秒或停止。
- 不要用高频主线程轮询替代生命周期事件。

50 ms 轮询当前承担 target `finishing` 和跨进程 mirror display 变化的兜底检测。必须先用测试证明生命周期回调能覆盖所有 target 导航、崩溃、后台切换和 display 重建场景，再降低频率或移除。不要只为降低主线程调用次数而破坏镜像跟随语义。

### 8.2 LaunchLoadingView

**状态：已完成。** 背景、halo 和 badge 三类 shader 仅在尺寸变化时创建；
halo 呼吸通过 Canvas 缩放完成，60 FPS 独立渲染线程保留。

[`LaunchLoadingView`](../runtime-core/src/main/java/com/coke/nestspace/container/LaunchLoadingView.kt) 在独立线程按约 60 FPS 绘制。

改造前，每帧都会重新创建：

- `LinearGradient` 背景。
- `RadialGradient` halo。
- badge 的 `LinearGradient`。

最终实现：

- 只在尺寸变化时创建与尺寸相关的 shader。
- 呼吸和旋转通过 Matrix、Canvas 变换或预创建 shader 完成。
- 启动完成后立即停止线程，确认所有生命周期分支都能停止。

独立的高帧率渲染线程本身是启动体验的设计要求：target `Application.onCreate` 可能长时间占用主线程，普通 View 动画会冻结。不要为了节省 CPU 默认降低帧率，只优化每帧 shader 分配和线程停止逻辑。

## 9. P1-D：Token usage 落盘

**状态：已完成。** 内存聚合值实时更新；`JSONObject.toString()` 和
`SharedPreferences.commit()` 合并为 500 ms debounce，在同一写线程落盘。
`reset()` 仍取消待写任务并同步清除。

改造前，[`TokenUsageStore.save()`](../app/src/main/java/com/coke/nestspace/agent/TokenUsageStore.kt) 每次记录 usage 都执行：

- `JSONObject.toString()`。
- `SharedPreferences.commit()`。

最终实现：

- 内存中维护聚合值。
- 使用短 debounce，例如 500 ms 到 2 秒合并写入。
- 进程退到后台或任务完成时强制 flush。
- 避免每次流式调用或 probe 都同步提交完整 JSON。

## 10. 建议实施顺序

### 阶段 0：建立基线

- 固定测试设备、系统版本和 ABI。
- 生成 release benchmark 变体。
- 记录冷启动、容器启动、Agent 工具、截图、内存和后台 CPU。
- 增加关键 Trace section。
- 保存 Perfetto 和 simpleperf 样例。

### 阶段 1：低风险高收益

- [已完成] 截图改为按目标尺寸绘制、JPEG、少 Base64。
- [已完成] 合并预览触发源。
- [已完成] UI Service 改为复用 binder。
- [已完成] `launchVirtual()` 改用轻量 `findRunningSlot()`，需要确认进程的操作放到 IO。
- [已完成] 把进程池的 kill、`/proc`、ActivityManager、PSS 和 prefs I/O 移出状态锁。
- [已完成] target 生命周期触发的 native Hook 刷新改为后台合并执行。
- [已完成] ContentProvider 创建和 attach 移出进程级锁，并发请求共享同一加载任务。
- [已完成] GMS Binder 热路径缓存 reflection，移除正常请求的全 Parcel 校验和逐笔详细日志。
- [已完成] LaunchLoadingView shader 复用。
- [已完成] Token usage 批量写入。

### 阶段 2：启动与 Hook

- [已完成] APK descriptor/nativeAbis 持久化缓存。
- [已完成] 去掉安装和启动中的重复 inspect。
- [已完成] 已安装 APK 命中元数据缓存后仅校验版本和签名，复用 descriptor 与已安装路径，不再重复解析 Manifest/filters。
- [已完成] native Hook 增量刷新和 patch slot 哈希表。
- [已完成] 环境快照无锁读取。
- [已完成] 路径、property 和 FD 快路径优化。
- [已完成] WebView User-Agent 改为首次容器启动时后台预取，并与 APK/slot 初始化重叠；容器进程读取带 Provider 版本校验的缓存。
- [已完成] 系统服务代理缓存 Class/Field/Method/Constructor 反射元数据，并缓存 miss 路径。
- [已完成] 宿主 `Application` 非首屏任务迁到首帧后的单线程后台队列；无 UI 启动使用 500 ms fallback。

### 阶段 3：内存与进程回收

- [已完成] 自动模式按当前内存动态计算 slot 上限。
- [已完成] 固定模式严格使用用户选择的 1–16，用户选 16 就允许 16。
- [已完成] kill 后仅探测原 PID，并使用退避等待；完整运行列表增加 PSS 短缓存。

### 阶段 4：重新采样

- 对比每个阶段的收益。
- 清理没有产生稳定收益的试改。

## 11. 测试矩阵

至少覆盖：

- ARM64 低端、中端、高端真机。
- x86_64 模拟器和带 ARM translation 的模拟器。
- Android 7/10/12/14/16。
- 16 KB page size 设备或模拟配置。
- 纯 Java 测试 APK。
- 带 JNI 的 `testapp`。
- split APK。
- 只读 `/proc/self/status`、property callback、native open/stat/readlink 探针。
- 1、4、8、16 个容器并发运行。
- 自动模式在不同可用内存下能动态调整上限，且自动上限不超过 16。
- 手动选择 16 时，应用内部始终按 16 作为槽位上限，不被自动策略改小。
- headless、mirror、普通前台三种显示模式。
- 无网络、慢网络、LLM 流式中断。
- 多实例登录和 AccountManager。

## 12. 观测点与工具

建议统一增加以下指标：

```text
main_cold_start_ms
main_first_frame_ms
container_launch_start_ms
container_first_activity_scheduled_ms
container_first_activity_opened_ms
apk_inspect_ms
apk_native_abi_scan_ms
native_lib_extract_ms
native_hook_refresh_ms
native_hook_patch_count
native_hook_scan_count
ui_service_bind_ms
ui_observe_ms
ui_tap_ms
screenshot_draw_ms
screenshot_encode_ms
screenshot_bytes
screenshot_total_ms
slot_process_count
slot_total_pss_kb
app_background_cpu_ms_per_min
```

推荐工具：

- Perfetto：主线程、RenderThread、Binder、进程生命周期、CPU 调度。
- Macrobenchmark：冷启动和热启动回归测试。
- JankStats/FrameTimeline：用户可见掉帧。
- simpleperf：目标 slot、native Hook、Bitmap/ZIP 热点。
- `dumpsys meminfo`：Java heap、native heap、PSS。
- `dumpsys activity processes`：容器进程状态。
- `dumpsys gfxinfo`：宿主 UI 帧统计。

## 13. 风险与注意事项

### Native Hook 风险

- 环境快照回收必须保证并发线程不会访问已释放对象。
- Hook 失败必须能够回退到原始函数。
- 不允许在 native Hook 中回 Java，也不允许同步写日志。
- 不能因为性能优化改变路径和身份语义。
- 每次 Android/NDK 升级都要重新验证 relocation 类型和 libc 入口。

### Binder 复用风险

- 需要在进程死亡、Service 重启、槽位复用和 binder died 时正确失效。
- 不能把旧 slot 的 binder 发给新目标 App。
- 连接池必须有空闲回收，避免长期持有全部 16 个进程。

### APK 缓存风险

- 先验证路径、大小、mtime、版本和摘要。
- 缓存 schema 必须可迁移。
- 损坏或不完整缓存必须自动回退到完整解析。
- 安全检查不能为了避免一次 inspect 而删除。

### 图像链路风险

- 降低尺寸和 JPEG quality 不能影响验证码、表单和视觉模型识别。
- UI 工具“截图”和 Agent“低带宽预览”应使用不同参数。
- 对无障碍层、Dialog、PopupWindow 和 SurfaceView 仍需单独验证。

## 14. 修改完成标准

一项优化只有满足以下条件才算完成：

1. 有修改前后的同设备、同 APK、同系统版本数据。
2. CPU、内存或延迟至少有一项主指标稳定改善。
3. 其他主指标没有不可接受的退化。
4. 容器启动、native probe、UI 工具和目标 App 回归通过。
5. 失败路径和降级路径有测试。
6. 新增缓存有版本、失效和恢复机制。
7. 没有把性能采样、频繁日志或诊断文件写入热路径。

## 15. 首要改造结果

本轮已经落地以下五项：

1. 截图与预览链路已完成，继续观察设备端帧时间和内存数据。
2. 容器 UI Service binder 复用已完成，继续观察断连和槽位复用回归。
3. native Hook 刷新已改为增量和哈希查重，环境/property/FD 快路径已移除全局锁。
4. APK descriptor 与 native ABI 已持久化，启动和安装阶段不再重复解析未变化内容。
5. 已完成桌面启动轻量查询，以及进程池 `/proc`、ActivityManager、PSS 读取和 kill 等待的锁外化。

这些改动覆盖 Agent 体验、容器启动、目标 App 运行、常驻 CPU 和宿主交互延迟五个主要方向。

## 16. 第二轮整体补充审计

本节记录对调用线程、锁范围和 target Framework 热路径的补充审计；相关改造与截图、binder、native Hook 和 APK 缓存优化共同构成当前实现。

### 16.1 P0：主线程直接读取运行中槽位（已完成）

改造前，[`VirtualViewModel.launchVirtual()`](../app/src/main/java/com/coke/nestspace/vm/VirtualViewModel.kt) 在 `viewModelScope` 默认的 Main dispatcher 上直接执行：

```kotlin
ContainerProcessPool.runningSlots(getApplication())
```

[`runningSlots()`](../runtime-core/src/main/java/com/coke/nestspace/container/ContainerProcessPool.kt) 并不轻量，可能执行：

- `ActivityManager.runningAppProcesses`。
- `pidOfProcess()` 遍历 `/proc`。
- 读取 slot marker 和 `/proc/<pid>/status`。
- 读取每个进程的 PSS/`smaps_rollup`。

因此用户点击桌面图标时，主线程可能在真正启动容器前发生一次明显停顿。

最终实现：

- `findRunningSlot(packageName, instanceId)` 只查询进程池内存索引。
- 启动入口不再调用完整 `runningSlots()`。
- 运行管理页仍按需读取 PSS 和完整运行列表。
- 慢确认和启动操作继续在 `Dispatchers.IO` 执行。

### 16.2 P0：进程池在全局锁内做 I/O 和等待（已完成）

**状态：已完成。** 当前实现把 assignment/owner 状态集中到
`ContainerSlotState`，状态锁只负责选择、预留、提交和失效；所有慢操作都在
锁外执行。每次分配都有 generation，旧 kill、旧进程探测或旧运行列表快照
不能覆盖更新后的 owner。

改造前，[`ContainerProcessPool.acquire()`](../runtime-core/src/main/java/com/coke/nestspace/container/ContainerProcessPool.kt) 在全局锁内可能依次调用：

- `reportIfDied()`。
- `evictBackgroundForMemory()`。
- `reusableSlot()`。
- `isProcessAlive()`。
- `killSlotProcess()`。

其中 [`killSlotProcess()`](../runtime-core/src/main/java/com/coke/nestspace/container/ContainerProcessPool.kt) 最多等待 1.5 秒。旧实现会在整个等待期间持锁，其他启动、关闭或列表查询只能排队。

[`runningSlots()`](../runtime-core/src/main/java/com/coke/nestspace/container/ContainerProcessPool.kt) 的旧实现也会在锁内调用 ActivityManager、`/proc` 和 PSS 读取，运行管理页刷新时可能阻塞新的容器启动。

本次按以下边界完成改造：

```text
锁内：
  - 选择候选 slot
  - 标记 RESERVED
  - 更新 assignments/owners
  - 释放或提交状态

锁外：
  - ActivityManager 查询
  - /proc 扫描
  - PSS 读取
  - kill 和等待退出
  - SharedPreferences 写入
```

使用以下显式状态：

```text
FREE
RESERVED
LIVE
KILLING
```

`RESERVED`/`KILLING` slot 不会被第二次分配。kill 完成后再次进入短临界区把状态改成 `FREE`；kill 失败则保持不可分配的 `KILLING`。这样既不牺牲并发正确性，也不会让锁覆盖最慢的系统调用。

`runningSlots()` 应先复制 assignments，再在锁外读取进程和内存信息。结果回来时如果 slot owner 已改变，丢弃旧结果。

实际落地内容：

- `launchVirtual()` 使用 `findRunningSlot()` 只读内存 assignment，不再执行完整 `runningSlots()`。
- `runningSlots()` 先复制 assignment/generation 快照，再在锁外查询 ActivityManager、`/proc`、marker 和 PSS，最后丢弃已变更的 owner。
- `acquire()` 在短锁内把候选标为 `RESERVED`，进入 `KILLING` 后于锁外杀进程；kill 完成才提交新 owner。
- `closeSlot()`、`forget()`、`forgetPrefix()`、`closePackage()`、自动回收和降低上限均先短锁摘除，再锁外 kill 与写 prefs。
- `slotForPid()` 不再持池锁扫描 marker 和 `/proc`。
- kill 后只探测原 PID 并采用退避，避免每 50 ms 重新读取 ActivityManager 和扫描全部 `/proc`。
- PSS 增加 1.5 秒短缓存；内存压力回调改由进程池维护线程执行，不占主线程。
- 仍保留一个 target 独占一个 slot、最多 16 个 slot、复用前杀旧进程、headless UI Service keep-alive 和固定上限严格按用户选择执行。

### 16.3 P1：target Framework 反射缓存（已完成）

**状态：已完成。** Activity/Application 生命周期使用按 owner、名称和参数签名缓存
Method，失败结果同样缓存；字段列表和 callback Method 只解析一次。

改造前，以下路径位于 target 启动、Activity 生命周期和系统服务调用热路径：

- [`TargetLifecycle.perform()`](../runtime-core/src/main/java/com/coke/nestspace/container/activity/TargetLifecycle.kt) 会重复遍历 `Activity::class.java.declaredMethods` 查找匹配方法。
- [`TargetLifecycle.postResume()`](../runtime-core/src/main/java/com/coke/nestspace/container/activity/TargetLifecycle.kt) 会重复沿类继承链查找。
- [`TargetApplicationLifecycle.dispatch()`](../runtime-core/src/main/java/com/coke/nestspace/container/activity/TargetApplicationLifecycle.kt) 会在每次生命周期事件中扫描 Application 字段。
- `TargetApplicationLifecycle.invoke()` 会对每个 callback 重新执行 `Class.forName()` 和 `getMethod()`。
- [`SystemServiceIdentity`](../runtime-core/src/main/java/com/coke/nestspace/container/identity/SystemServiceIdentity.kt) 会在系统服务结果改写时重复查找 `Class`、Builder 和 Method。
- `rewritePackageOps()` 会在热路径中重复查找 `AppOpsManager$PackageOps` 及其方法。

最终实现使用进程级反射缓存：

```kotlin
data class MethodKey(
    val owner: Class<*>,
    val name: String,
    val parameterTypes: List<Class<*>>
)
```

缓存规则：

- `Class`、`Method`、`Field`、`Constructor` 按 Android API 版本和类加载器缓存。
- 允许缓存“找不到”，避免每次异常路径都重复完整查找。
- `TargetLifecycle` 第一次扫描后按 `name + argument signature` 保存结果。
- `TargetApplicationLifecycle` 按 `application.javaClass` 缓存是否存在 delegate，以及每个生命周期回调的 Method。
- `SystemServiceIdentity` 在代理创建时一次性解析类、方法和 Constructor，InvocationHandler 内只执行 `invoke()`。
- 反射缓存字段本身使用 `@Volatile` 或不可变 map；不要在每次调用时对全局锁竞争。

缓存只是减少 Method 查找，不能把反射 `invoke()` 无差别替换成方法句柄；先用采样确认开销，再决定是否引入更高成本的 MethodHandle 适配。

### 16.4 P1：PackageManager flags-aware 组件复制（已完成）

**状态：已完成。** target 和虚拟包都按 flags 只复制请求的组件、权限、签名和
meta-data；常见 `flags=0` 查询使用 PackageInfo 字段浅拷贝，不再先完整 Parcel 深拷贝。

[`ContainerPackageManager.getPackageInfo()`](../runtime-core/src/main/java/com/coke/nestspace/container/ContainerPackageManager.java) 对 target 调用 flags-aware 的 `copyPackageInfo(flags)`。

改造前，`copyPackageInfo()` 无论调用者是否传入 `GET_ACTIVITIES`、`GET_SERVICES`、`GET_PROVIDERS` 等 flags，都会：

- Parcel 序列化和反序列化 `PackageInfo`。
- 克隆 `ApplicationInfo`。
- 克隆全部 Activity、Receiver、Service 和 Provider。
- 为每个组件重新设置 ApplicationInfo。

target App 启动时经常只用 `flags=0` 查询 package/version，旧实现却承担完整组件树深拷贝。

最终实现：

- 增加 `copyPackageInfo(int flags)`，严格按 flags 选择要返回的组件数据。
- 保留不可变的 target `PackageInfo`/`ApplicationInfo` 模板。
- 只对 target 可能修改的返回对象做浅层隔离；深层数组按需克隆。
- `getInstalledPackages(flags)` 也应遵守 flags，不应无条件返回完整 target 组件。
- 对频繁查询建立短期对象模板缓存，但仍保证 target 修改一个返回值不会污染下一次查询。

同类检查还应覆盖 `getActivityInfo()` 等路径，避免只为设置 `applicationInfo` 就重复深拷贝较大的对象。

### 16.5 P1：VirtualPackageRegistry 的锁范围

**状态：已完成。** APK inspect、Manifest 解析、AssetManager 和 Resources
创建均已移出 registry monitor；锁内只检查和登记 loading，解析完成后再短锁发布。
递归标记仍保留，避免目标查询 label/icon 时重入同一个包。

[`VirtualPackageRegistry`](../runtime-core/src/main/java/com/coke/nestspace/container/vpm/VirtualPackageRegistry.kt) 的多数只读 map 查询仍使用短锁。改造前，[`loadEntry()`](../runtime-core/src/main/java/com/coke/nestspace/container/vpm/VirtualPackageRegistry.kt) 会在锁内执行 `ApkInspector.inspect()`、AssetManager 创建和 Resources 初始化。

当 target 第一次跨包查询组件或图标时，其他 PackageManager 查询会被 APK 解析阻塞。

最终实现：

- 把 APK 解析改为 double-checked load：锁内只登记“正在加载”，锁外解析，再锁内发布。
- 并发请求同一 package 时等待同一个加载任务。
- `records` 载入后作为不可变快照发布。
- 热查询使用只读 map，不使用实例级 `@Synchronized`。
- PackageManager 线程上的 Resource 创建要检查是否必须回到特定线程。

### 16.6 P1：Application 非首屏任务异步化（已完成）

改造前，[`NestSpaceApp.onCreate()`](../app/src/main/java/com/coke/nestspace/NestSpaceApp.kt) 在主进程同步执行：

- `ContainerProcessPool.resetLaunchedFlagsAfterHostRestart()`。
- `UserGateManager.init()`。
- `RunRecovery.recover()`。
- `ScheduledTaskManager.recoverInterrupted()`。
- `McpClientManager.initialize()`。
- `ScheduledTaskManager.rescheduleAll()`。
- 创建悬浮球。
- `BrowserProxyManager.apply()`。
- 启动 MCP foreground service。

其中 Run Recovery、schedule 文件解析、MCP tool schema 解析和 WebView Feature/ProxyController 初始化都不是首帧必需工作。

当前实现分两级：

```text
必须同步：
  - HostIdentity.captureProcess
  - ContainerSessions.installHostActivityInstrumentation
  - AppLanguage.init

可异步/首帧后：
  - RunRecovery
  - schedule recovery/reschedule
  - McpClientManager.reload
  - BrowserProxyManager.apply
  - 非关键通知和恢复提示
```

执行约束：

- MCP foreground service 和闹钟恢复有后台可用性要求，可以继续启动，但文件解析和对象构建应移到 IO executor。
- 异步初始化必须用单一调度器串行执行，避免多个启动任务同时争用磁盘。
- 如果后台初始化失败，必须记录且可在 UI 可见时重试。

### 16.7 P1：共享库和 plugin 类路径重复扫描

[`SharedLibraries.classPathEntries()`](../runtime-core/src/main/java/com/coke/nestspace/container/loader/SharedLibraries.kt) 每次创建容器 session 都会：

- 新建 AssetManager。
- 挂载所有 APK。
- 解析 AndroidManifest。
- 遍历 `<uses-library>`。

[`PluginClassPathResolver.resolveForComponent()`](../runtime-core/src/main/java/com/coke/nestspace/container/loader/PluginClassPathResolver.kt) 会枚举 plugin APK，并对候选 APK 逐个打开 `DexFile`、遍历全部 DEX entry，直到找到目标类。

建议：

- 把 `<uses-library>` 名称放入 APK 安装索引，session 创建时直接读取。
- 系统共享库路径解析结果按设备进程缓存。
- plugin APK 建立 class-to-APK 索引，按 APK 路径、大小、mtime 或 plugin version 失效。
- plugin 索引构建放在安装/下载完成后的 IO 阶段，而不是组件首次启动时临时扫描。

### 16.8 第二轮验收指标

额外记录：

```text
home_launch_click_to_engine_ms
running_slots_lookup_ms
container_pool_lock_wait_ms
container_pool_kill_wait_ms
target_lifecycle_reflection_ms
package_manager_query_ms
package_info_clone_ms
application_on_create_ms
shared_library_scan_ms
plugin_dex_scan_ms
```

第二轮优先修改顺序：

1. [已完成] `launchVirtual()` 移除主线程 `runningSlots()`。
2. [已完成] 进程池锁外执行 kill、`/proc`、ActivityManager 和 PSS 读取。
3. [已完成] PackageManager 按 flags 复制所需组件，并缓存反射成员。
4. [已完成] 将 Application 非首屏初始化迁到首帧后的串行 IO。
5. 缓存 APK uses-library 和 plugin class index。

## 17. 设计需求与性能建议复核

本节用于防止把产品语义、兼容性边界或安全校验误当成“性能浪费”。以下分类是最终取舍标准。

### 17.1 必须保留的设计行为

| 设计行为 | 为什么必须保留 | 可以优化什么 |
| --- | --- | --- |
| 最多 16 个固定槽位 | 每个槽位对应独立 Android 进程，最大 16 是产品能力 | 自动模式动态计算；固定模式严格尊重 1–16 的用户选择 |
| 一个 target 独占一个 slot | native library、JNI 状态和 linker 状态是进程级的 | 进程分配索引、锁范围和启动路径 |
| slot 复用前杀旧进程 | 不杀会混入上一 App 的 `.so`、JNI 和 linker 状态 | kill 的 pid 获取、等待方式、锁外执行和退避 |
| target 按真实分辨率 measure/layout | 保证目标 App 的布局、坐标和点击语义 | 预览截图按目标尺寸降采样，不改变 target View 尺寸 |
| 安装时验证 package/signature/manifest | 决定安装是否合法、split 是否一致、目标是否安全 | 同一份字节内容只解析一次并复用 descriptor |
| 仅 patch target 模块 GOT/PLT | 避免宿主和系统服务被目标环境语义污染 | 增量扫描、哈希查重、批量 mprotect |
| headless slot 保持 UI Service 绑定 | 后台目标进程可能被系统回收，下一次 observe 需要同一页面 | 在 Agent 回合内复用 binder，空闲后释放 |
| target PackageManager 返回隔离副本 | target 可能修改 PackageInfo/ApplicationInfo，不能污染内部模板 | 按 flags 只克隆请求的字段和组件 |
| AccountManager 成功返回具有持久化语义 | 多进程/多实例后续读取依赖写入结果 | 已增加内存 JSON 缓存；修改仍同步 commit，持久化语义不变 |
| MCP 可在未打开 UI 时启动容器 | 后台 Agent 和 BootReceiver 是产品能力 | 文件解析和工具 schema 构建移出主线程 |
| 16 KB native page 兼容 | Android 15+ 设备要求 | 保持 CMake 链接参数和测试矩阵 |

### 17.2 值得做，但必须保持原行为

| 优化建议 | 结论 | 约束 |
| --- | --- | --- |
| 截图去掉 PNG/Base64 中间层 | 已完成 | 保留高清截图接口；预览降采样不能影响验证码、表单和视觉模型 |
| 复用 UI Service binder | 已完成 | 仅在同一 Agent 回合或短时间内复用；非 headless 且不再使用时及时释放 |
| native Hook 增量刷新 | 已完成 | `dlopen`/`android_dlopen_ext` 已事件化；maps/ELF fallback 仅作为启动稳定后的单次兜底 |
| 环境快照原子读取 | 已完成 | 旧环境快照保留到进程结束，避免并发读者悬空 |
| property/path/FD 快路径 | 已完成 | 扩大 Hook 覆盖范围仍不属于性能优化 |
| APK descriptor/native ABI 缓存 | 已完成 | 缓存不能替代安装安全校验；失效条件必须覆盖文件变化 |
| 自动 slot 按内存计算 | 产品要求 | 自动最高 16；固定选择必须严格按用户值 |
| 进程池锁外 I/O | 已完成 | 用 RESERVED/KILLING 状态保证同一 slot 不被并发复用 |
| Provider 锁外初始化 | 已完成 | 锁内保留 loading/占位，target 创建和 attach 在锁外执行 |
| GMS Binder 快路径 | 已完成 | 保留 Parcel 重写；取消正常请求的二次扫描和逐笔日志 |
| PackageManager flags-aware copy | 已完成 | 保持返回对象隔离，避免 target 修改内部模板 |
| Framework 反射缓存 | 已完成 | 按 API 版本和 ClassLoader 缓存；必须保留 ROM fallback |
| Application 非首屏任务异步化 | 已完成 | 统一使用首帧触发和 500 ms fallback；恢复逻辑按进程启动时间隔离旧记录 |
| 共享库/plugin 索引缓存 | 值得做 | 必须随 APK 版本、路径或 mtime 正确失效 |

### 17.3 最终优先级

剩余优化按以下顺序推进：

1. 共享库和 plugin class index：补充安装期缓存，并定义版本、路径和 mtime 失效规则。
2. 常驻轮询与启动渲染：用 Perfetto 和帧数据确认是否仍存在稳定收益，再调整频率。
3. native Hook：继续观察新增模块加载、relocation 数量和失败重试，只在采样支持时增加批量修改。
4. APK 索引：在新安装格式或 split 形态下持续验证 descriptor、ABI 和安全校验的一致性。
5. target 热路径：继续检查 ROM 差异和新增系统服务代理，避免反射缓存绕过 fallback。

其余项目只有在对应基线的 CPU、延迟或内存数据支持时才实施。

# 架构与限制

本文面向想了解实现细节或排查兼容性问题的读者。完整的路线和目标设计见 [技术方案](android-virtual-space-agent-mcp.md)。

单 APK、免 Root 条件下的 Android 兼容运行时升级设计见 [单 APK Android 兼容运行时](single-apk-android-compatibility-runtime.md)。

## 整体结构

NestSpace 由四部分组成：

- **应用级容器**：在宿主进程内加载目标 APK，为目标应用提供一套虚拟化的 Android 运行环境。
- **桌面与空间管理**：安装 APK、导入宿主应用、多开、数据隔离和图标整理。
- **控制通道**：容器内的 Binder UI 控制、本地 AI 助手，以及对外的 MCP Server。
- **插件平台**：通过稳定 Java API 扩展 Agent、界面、Host 服务、运行策略和目标专用
  运行时 Hook，不需要修改主工程。

容器不是系统级虚拟化；它运行在 NestSpace 自己的进程里，通过 ClassLoader、资源、Context 和系统服务代理来模拟目标应用需要的环境。

## 代码模块

当前主要 Gradle 模块：

- `plugin-api`：Java 优先的外部插件 SDK、Agent Hook、Host API、设置 View、能力和
  启动策略契约。
- `runtime-api`：引擎契约、共享模型、Application factory 注册表、宿主 hook，以及目标
  运行时代理 API、启动描述、类名索引和 ClassLoader scope。
- `runtime-core`：APK 与实例存储、Session、Activity Host、进程槽位、Provider、生命周期、Native Facade 和 MCP UI。
- `runtime-compat`：通用目标 Application 启动链解析、兼容 Application factory 和
  Tinker/AppLike 行为。
- `app`：Compose 控制面、Agent、插件中心、设置 View 宿主和插件注册。
- `example-plugin/plugin`、`dev-plugins/*`：只在 Debug 构建中发现和加载的源码插件。

依赖方向为 `runtime-compat → runtime-core → runtime-api`；`runtime-core` 还依赖
`plugin-api`，`app` 作为控制面依赖全部模块。通用兼容后端
通过 `RuntimePluginLoader` 安装；目标包名、版本、完整类名和专用 Profile 放在独立插件
模块，不进入通用容器代码。

## 安装与启动

### 安装

1. 解析 APK 的 manifest、资源表和签名。
2. 校验包名、签名一致性、`minSdk` 和 `REQUIRE_SECURE_ENV`。
3. 把 APK 复制到应用私有目录，写入安装记录；split APK 会合并成一份安装描述。
4. 为应用创建实例，实例数据目录按 `data/<package>/<instance>` 分隔。

### 启动

1. 按 `package@instance` 从容器进程池分配一个槽位进程。
2. 用 `DexClassLoader` 加载目标代码，用 `AssetManager` 加载目标资源，并构造目标感知的 `Context` 和 `Resources`。
3. 通过反射把目标 Activity attach 到宿主窗口，驱动完整的生命周期。
4. 目标的 `Application` 会执行 `attachBaseContext` 和 `onCreate`。

## 隔离方式

- 每个 `package@instance` 固定到独立的 `:c1`–`:c16` 宿主进程。
- 实例的 `files`、`cache`、`code_cache`、`databases`、`getDir` 和 SharedPreferences 使用独立命名空间。
- native 库按 ABI 提取到实例目录，作为 `DexClassLoader` 的 `librarySearchPath`；同一实例复用时先结束旧进程，避免同名 `.so` 的 JNI 全局状态串用。
- 目标 Java 异常由崩溃保护层拦截并记录；native crash 或 `System.exit` 只影响对应槽位进程。

进程池默认自动管理最多 16 个槽位；内存紧张时按最近最少使用回收后台容器，前台容器不会被关闭。也可以设置固定槽位上限（1–16）。

## 虚拟化的系统能力

- **Activity**：按 `onCreate → onStart → onRestoreInstanceState → onPostCreate → onResume → onPostResume` 等完整回调驱动，支持容器内 Activity 跳转、返回栈、Splash 页和 `startActivityForResult`。
- **Service / Provider / Broadcast**：目标 Service 由目标 ClassLoader 实例化；Provider 按 authority 路由；manifest 和动态 BroadcastReceiver 支持普通、有序和 sticky 广播。
- **PackageManager**：目标包的组件、权限、签名、UID、标签/图标、资源和启动 Intent 由 APK 描述符回答；同空间跨虚拟应用的查询、隐式 Intent 解析也由虚拟索引提供。
- **通知**：包名、tag、channel 和 group 按虚拟包隔离，small icon 和 PendingIntent 自动改写为宿主可接受的形式。
- **JobScheduler**：容器内返回本地调度器，目标 `JobService` 用目标 ClassLoader 驱动，支持延迟、周期、退避和常见约束。
- **权限**：`checkSelfPermission` 等查询走虚拟授权表；`requestPermissions` 接入宿主的真实权限申请，结果按包持久化。
- **GMS**：对 `IGmsServiceBroker` 的 Binder 事务做包名和 `AttributionSource` 改写，覆盖 WebView/Chimera 等常见调用路径。
- **UI 控制**：容器进程内通过 Binder 观察 View 树、点击、输入和截图，并合并目标 Activity 自建的 Dialog、底部弹窗等窗口；不依赖无障碍服务。

## 控制与 Agent

- **本地 AI 助手**：支持 Claude 兼容和 OpenAI 兼容接口，通过 `space_*`、`ui_*`、`browser_*` 工具操作 `virtual-1` 空间。直接打开或操作应用时使用全屏窗口，查询和读取信息时使用方形无头小窗。
- **远程 MCP**：内置 Streamable HTTP Server，默认只监听 `127.0.0.1`，可显式开启局域网监听；支持多客户端会话、SSE 心跳和 DELETE 会话。
- **外部 MCP 工具**：本地助手可以连接外部 MCP Server，工具名以 `mcp_<id>_` 前缀隔离，不暴露给远程客户端。
- **浏览器**：内置多标签 WebView，无头标签由 Agent 创建，可被可见浏览器接管；网页弹窗、外部回退链接和下载页不会交给系统浏览器。
- **插件平台**：插件可以扩展 Agent 提示、问答、模型请求、工具和工具展示，也可以提供
  自定义设置 View、网络、任务、事件、缓存和 SQLite。Debug 源码插件直接使用宿主
  ClassLoader；正式 `.nestplugin` 当前使用 `DexClassLoader`。

## 已知限制

- 建议先安装自己熟悉的 App；遇到兼容性问题时更容易判断是应用本身还是容器适配的问题，也方便向项目反馈。
- 兼容性取决于目标应用的加固、native SDK、动态加载和依赖组合；不是所有应用都能直接运行，遇到问题需要逐个适配。
- 同时运行的容器进程最多 16 个，实际数量还受设备内存限制；自动模式下内存不足会回收最久未使用的后台容器。
- Job、Alarm 和广播桥接只在容器进程存活期间有效，进程被系统回收后不会由系统唤醒；完整后台保活仍需前台服务策略。
- 跨虚拟应用的 `startActivity` 仍会回退到宿主，目前主要支持查询和解析级别；跨应用 Provider 查询只支持本包 authority。
- Android 7.x（API 24–25）没有 `ContentResolver.wrap`，Provider 虚拟化在该版本上关闭，其余容器能力仍可用。
- Google Play services 等独立进程按真实 UID/包名校验，目前只代理了部分 Binder 接口；不同应用可能需要补充适配。
- 仅含 arm64 原生库的应用需要 arm64 设备；x86_64 模拟器会在启动前返回 `ABI_MISMATCH`。
- 目标 Activity 的 `savedInstanceState` 保存在进程内，支持同进程重建，但进程被杀后不会跨进程保留。
- 合成 UID 只用于应用层查询，系统侧仍看到宿主 UID。
- 无头启动依赖悬浮窗权限提供的后台 Activity 启动豁免；未授权时 `space_launch` 返回 `LAUNCH_BLOCKED`。
- 局域网 MCP 使用明文 HTTP + Bearer Token，没有 TLS，只应在可信网络开启。
- 内置浏览器代理需要 Android 10+ 且 WebView 支持 `PROXY_OVERRIDE`，暂不支持需要用户名/密码的代理。
- 外部 MCP 客户端目前只支持 HTTP POST（JSON 或 SSE 响应）和 Bearer Token。
- AI 助手固定在 `virtual-1` 空间内操作，不会控制系统全局应用。
- 外部插件尚未运行在独立进程，发布者签名、信任等级和在线市场仍未完成。

## 相关文档

- [插件编写指南](plugin-authoring.md)
- [插件平台与 Java SDK](plugin-center.md)

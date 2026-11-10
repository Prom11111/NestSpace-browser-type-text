# NestSpace 插件平台与 Java SDK

本文描述 NestSpace 插件平台的目标、当前实现、扩展边界和后续计划。

实际创建插件请先阅读[插件编写指南](plugin-authoring.md)。

插件平台要解决四类问题：

1. 第三方向 AI 助手接入自己的工具和问答能力。
2. 第三方在 Agent 请求、提示词和工具展示阶段增加定制逻辑。
3. 特定目标应用的运行时差异通过包名、版本和类名策略或运行时代理解决。
4. 新增扩展不需要修改 NestSpace 主工程并重新发布整个 APK。

插件的“兼容”是执行结果，不是核心接口名称。公共抽象使用
`RuntimeProxy`、`RuntimeProxyPlugin`、目标包名和版本。

## 1. 当前状态

| 能力 | 状态 | 说明 |
| --- | --- | --- |
| 独立 `plugin-api` | 已实现 | 纯 Java Android Library，不依赖 Compose、Kotlin 或 `app` |
| Manifest 注册解析 | 已实现 | Chrome 风格 `manifest.json`、权限和 Host 校验 |
| 插件描述符与能力 | 已实现 | API 版本、版本号、优先级、Capability |
| 运行目标匹配 | 已实现 | 插件直接使用包名、`versionCode` 和类名 |
| 通用容器兼容层 | 已实现 | Tinker/AppLike、Application attach 和系统服务代理始终在核心运行层 |
| 目标兼容插件 | 已支持 | 目标专用策略由独立插件模块提供 |
| Agent 提示 Hook | 已实现 | 注入独立的插件提示区块 |
| Agent 问答 Hook | 已实现 | 模型前直接回答，或继续原流程 |
| Agent 模型请求 Hook | 已实现 | 改写系统提示、直接回答、阻止请求 |
| Agent Tool Provider | 已实现 | 插件工具进入统一 `ToolRegistry`，本地 Agent 和 MCP 共用 |
| Agent Tool Hook | 已实现 | 调用前改参数或拦截，调用后只观察结果 |
| 工具展示 Hook | 已实现 | 文本卡片、JSON Renderer 或任意 Android View，仅影响 UI |
| 命令注册表 | 已实现 | 插件命令可被 UI 或 Agent 调用 |
| 托管任务 | 已实现 | 后台任务、进度、取消、超时和 detach 回收 |
| 插件事件总线 | 已实现 | 按事件类型订阅，来源强制使用插件 ID |
| 设置与密钥 | 已实现 | 普通设置和 Android Keystore 加密密钥 |
| 插件设置 View | 已实现 | 插件通过 Java API 返回任意 Android View；设置页作为独立 Activity 承载 |
| 文件选择与导出 | 已实现 | SAF 选择、读取、另存为用户可见文件 |
| 通知动作 | 已实现 | 通知按钮通过事件总线回传插件 |
| 分享与剪贴板 | 已实现 | 文本/文件分享和敏感剪贴板标记 |
| UI 页面与 Renderer | 部分实现 | 注册表、Manifest 和 JSON 解析已完成，通用 Compose 宿主页待接 |
| LLM Provider | 已实现 | `plugin://provider-id` 接入模型配置；统一 `executeAsync()`，按用户开关返回流式或完整结果 |
| 通知 Host API | 已实现 | 插件命名空间、独立渠道、动作和权限检查 |
| 网络 Host API | 已实现 | HTTP(S)、域名白名单、超时和大小限制 |
| SSE Host API | 已实现 | HTTP/HTTPS 流式事件、多行 data、事件 ID 和 retry |
| WebSocket Host API | 已实现 | `ws`/`wss`、文本和二进制消息、生命周期回调 |
| Android API 门面 | 已实现 | 不暴露原始 Context，提供受控系统操作 |
| 插件文件存储 | 已实现 | 插件私有目录、原子写入和路径校验 |
| 内置安装引导插件 | 已实现 | 演示问答和提示 Hook |
| 自定义页面 | 部分实现 | 声明式页面/Renderer 已可注册，页面宿主 UI 尚未完成 |
| `.nestplugin` 安装包 | 开发版已实现 | 文件安装、路径/大小校验、DEX 加载和重启恢复；签名信任待补 |
| Debug 源码插件直开 | 已实现 | Gradle 自动发现、资源隔离、宿主 ClassLoader 加载和外部同 ID 包遮蔽 |
| 插件中心 UI | 应用中心风格 | 已安装插件网格、搜索、分类、详情、启停、自定义设置、分享、导出和删除 |
| 运行时代理扩展 API | 已实现 | `plugin-api` 提供启动描述、Profile、类名索引和 ClassLoader scope |
| 外部运行时代理安全隔离 | 未实现 | 发布者签名、信任等级、独立插件进程和自动回滚仍待完成 |

“已实现”表示当前 Debug 源码和开发版安装链路可用；外部插件正式发布仍受签名、信任等级
和进程隔离限制，不能把开发版安装器视为完整应用商店安全模型。

## 2. 设计原则

### 2.1 Java-first

对外 SDK 使用纯 Java 11：

- 接口；
- 枚举；
- `final class`；
- 普通构造器和 `getXxx()`；
- `List`、`Set`、`CompletableFuture`。

公开 API 不使用：

- Kotlin `data class`；
- Kotlin 默认参数；
- 扩展函数；
- `suspend`；
- Kotlin 协程类型；
- Compose 类型；
- 宿主内部 `Activity`、`ClassLoader` 或 Binder 对象。

Kotlin 只用于 NestSpace 内部实现和适配。

### 2.2 能力最小化

插件必须声明能力，宿主按能力启用 Hook：

| Capability | 能力 |
| --- | --- |
| `RUNTIME_LAUNCH_POLICY` | 根据目标包名和版本选择启动策略 |
| `AGENT_PROMPT` | 贡献 Agent 提示词 |
| `AGENT_QUESTION` | 在模型前处理问答 |
| `AGENT_MODEL_REQUEST` | 检查或修改模型请求 |
| `AGENT_TOOLS` | 向 Agent 贡献工具 |
| `AGENT_TOOL_HOOKS` | 拦截或观察工具调用 |
| `AGENT_TOOL_PRESENTATION` | 修改工具调用展示 |
| `LLM_PROVIDERS` | 注册模型 Provider |
| `COMMANDS` | 注册可执行命令 |
| `TASKS` | 创建托管后台任务 |
| `EVENTS` | 订阅和发布插件事件 |
| `SETTINGS` | 读写插件普通设置 |
| `SECRETS` | 读写 Keystore 加密密钥 |
| `CACHE` | 使用带 TTL 的插件私有缓存 |
| `DATABASE` | 打开插件私有 SQLite 数据库 |
| `FILE_PICKER` | 使用系统文件选择器读取文件 |
| `FILE_EXPORT` | 另存用户可见文件 |
| `SHARE` | 调用系统分享面板 |
| `CLIPBOARD` | 读写剪贴板 |
| `UI_PAGES` | 贡献声明式页面 |
| `UI_RENDERERS` | 注册结果渲染器 |
| `NOTIFICATIONS` | 发送和取消插件通知 |
| `NETWORK` | 执行受控 HTTP(S)、SSE 和流式请求 |
| `WEBSOCKET` | 建立受控 `ws`/`wss` 会话并收发消息 |
| `ANDROID_APIS` | 使用受控 Android API 门面 |
| `STORAGE` | 读写插件私有文件 |
| `RUNTIME_PROXY` | 按目标包名和版本贡献运行时代理描述 |

后续能力计划：

| Capability | 计划 |
| --- | --- |
| 通用页面宿主 | 在插件中心渲染 `TEXT`、`BUTTON`、`CHART` 等声明式块 |
| 受限 WebView | 仅在声明式页面无法表达复杂交互时启用 |

### 2.3 数据边界

- 插件不获得模型 API Key。
- 插件不获得 Authorization Header 或原始 HTTP 请求。
- 通知使用插件命名空间，不能覆盖其他插件的通知。
- 网络请求只访问安装时声明的域名。
- 工具展示 Hook 不修改模型收到的 `ToolResult`。
- Agent 提示 Hook 不能覆盖宿主安全规则和权限确认。
- 模型请求 Hook 能读取系统提示，因此属于高权限能力。
- 启动策略匹配使用目标包名和 `versionCode`；目标专用 Profile 可以使用启动描述中
  公开的包名、版本、APK 和类名信息。
- Android API 门面属于高权限能力，默认不授予。
- 工具展示 View 和插件设置 View 获得短生命周期、受控的 Android `Context`；插件仍不能
  直接获得宿主 Application 或任意 Activity。
- 插件设置和密钥按插件 ID 命名空间隔离。
- 插件工具结果进入模型前，展示 Hook 不能改写原始结果。
- 文件 API 只返回受控读取句柄，不暴露真实文件路径。

### 2.4 失败隔离

- 单个 Hook 异常或超时视为未处理。
- 展示插件失败时回退宿主原有卡片。
- 运行策略冲突时拒绝安装，不根据注册顺序静默选择。
- 插件停用或卸载后，新 Agent 回合不再使用其 Hook。
- 插件平台最终必须把外部插件放入独立进程或受限运行时。

## 3. 模块结构

当前 Gradle 结构：

```text
:plugin-api       纯 Java 对外 SDK
:app              Compose UI、Agent、插件注册、容器运行时和 Target Application factory
example-plugin/plugin 随 Debug 宿主编译的完整源码插件
dev-plugins/      开发者自己的 Debug 源码插件
```

依赖方向：

```text
:app
└── :plugin-api
```

`plugin-api` 不依赖宿主实现。普通插件和运行时代理都只编译依赖该模块。运行时实现、
Application factory、Agent 和容器代码统一位于 `app`。

### 3.1 Debug 源码插件直开

插件开发不要求先生成 `.nestplugin`。NestSpace 根工程会自动加入：

```text
:dev-plugin-runtime-demo -> example-plugin/plugin
:dev-plugin-<name>       -> dev-plugins/<name>
```

这些模块只通过 `debugImplementation` 进入 Debug APK。Release 不包含源码插件。

开发流程：

```text
拉取 NestSpace 源码
  ↓
编辑 example-plugin 或复制到 dev-plugins/<name>
  ↓
Android Studio Run / Apply Changes
  ↓
宿主扫描 assets/plugins-dev/*
  ↓
Class.forName(entry_class, context.classLoader)
  ↓
PluginRuntimeManager.attach(...)
```

这条路径不执行 `packagePlugin`、不生成 DEX、不写临时 `.nestplugin`，也不经过
`DexClassLoader`。插件资源和 Java 代码都随宿主 APK 编译，因此 IDE 可以正常
编译、断点和增量部署。

资源隔离由插件模块的 Gradle 脚本完成：

```text
src/main/plugin-assets/
  ↓ 自动生成
assets/plugins-dev/<plugin-id>/
```

每个插件仍需保留自己的 `manifest.json`、`icons/` 和 `pages/`。宿主启动时先把
授权资源提取到 `files/plugins/source/<plugin-id>/`，再解析 Manifest 并注册插件。

启动和冲突顺序固定为：

```text
通用容器运行层
  ↓
Debug 源码插件
  ↓
外部 .nestplugin
```

如果外部包与源码插件 ID 相同，外部包不会被恢复；调试期修改源码插件应立即生效，
不会和旧安装包重复注册。Debug 源码插件与第三方插件在同一个已安装网格中显示，
图标左下角带有“源码”角标，不会单独占一栏。

目标是独立分发时才运行：

```bash
./gradlew :dev-plugin-runtime-demo:packagePlugin
```

新插件的目录结构见
[`dev-plugins/README.md`](https://github.com/chuccp/NestSpace/blob/main/dev-plugins/README.md)。

## 4. 公共插件模型

### 4.1 描述符

```java
PluginDescriptor descriptor = new PluginDescriptor(
    "example.plugin",
    "Example plugin",
    "1.2.0",
    PluginDescriptor.API_VERSION,
    100,
    EnumSet.of(PluginCapability.AGENT_PROMPT)
);
```

规则：

- `id` 全局唯一；
- `apiVersion` 必须等于宿主支持的主版本；
- `priority` 越大越先执行；
- 插件可以在同一描述符下实现多个 Java Hook 接口。

### 4.2 API 版本

`PluginDescriptor.API_VERSION` 当前为 `2`。版本 `2` 的运行时代理直接使用目标包名和
`versionCode`，不再接受 SHA-256 运行指纹。

- 新增默认方法：兼容；
- 新增枚举值：宿主必须安全处理未知值；
- 新增接口：兼容；
- 修改方法签名或删除方法：需要新的大版本。

## 5. 运行目标与启动策略

### 5.1 匹配条件

宿主把目标包名、`versionCode` 和 Android API 放入 `RuntimeProxyContext`。插件通过
`RuntimeProxyPlugin` 返回同一包名和版本对应的策略。包名直接使用 Android manifest
中的值，不再转换为哈希指纹。版本默认精确匹配，也可以使用 glob、前缀、区间或任意
版本匹配。

### 5.2 接口

```java
public interface RuntimeProxyPlugin {
    PluginDescriptor getDescriptor();
    List<RuntimeLaunchPolicy> launchPolicies();
}

public final class RuntimeLaunchPolicy {
    public RuntimeLaunchPolicy(
        String id,
        String packageName,
        long versionCode,
        RuntimeLaunchMode mode,
        String reason
    );

    public RuntimeLaunchPolicy(
        String id,
        String packageName,
        RuntimeVersionMatcher versionMatcher,
        RuntimeLaunchMode mode,
        String reason,
        int minAndroidApi,
        int maxAndroidApi
    );
}
```

启动模式：

- `CONTAINER`：继续虚拟容器；
- `HOST_FALLBACK`：启动宿主已安装的官方包；
- `BLOCKED`：拒绝启动并返回说明。

### 5.3 冲突处理

- 插件只返回策略。
- 同一个包名下相互重叠的版本范围只能由一个插件声明。
- 重复目标策略会被 `RuntimeProxyPlugins.install()` 拒绝。
- 多个可用插件按优先级排序，但完全相同目标版本不允许冲突。

### 5.4 宿主兼容模式限制

`HOST_FALLBACK` 不经过 NestSpace 虚拟容器，因此：

- 不提供实例数据隔离；
- 不提供多开；
- Agent 不能通过容器 UI 工具控制该进程；
- 必须启动宿主已安装的同包名应用；
- 宿主未安装时返回 `HOST_APP_REQUIRED`。

## 6. Agent Hook

Agent 执行顺序：

```text
用户消息
  ↓
AGENT_QUESTION Hook
  ├── 已处理 → 直接返回回答
  └── 未处理
        ↓
AGENT_PROMPT Hook → 组成系统提示
        ↓
AGENT_MODEL_REQUEST Hook
  ├── PROCEED
  ├── PROCEED + 改写系统提示
  ├── SHORT_CIRCUIT → 直接回答
  └── BLOCK → 返回错误
        ↓
Claude / OpenAI 兼容请求
        ↓
工具调用
  ↓
AGENT_TOOL_PRESENTATION Hook
  ↓
对话卡片展示
```

### 6.1 提示词 Hook

```java
public interface AgentHookPlugin {
    PluginDescriptor getDescriptor();

    default List<AgentPromptContribution> promptContributions(
        AgentPromptContext context
    ) {
        return Collections.emptyList();
    }
}
```

上下文只包含：

- 当前用户消息；
- 最近少量用户消息；
- 可用工具名；
- Locale。

限制：

- 单片段最多 2000 字符；
- 总提示最多 8000 字符；
- 注入到独立的 `<plugin_instructions>` 区块；
- 不得覆盖宿主安全、权限和工具规则。

### 6.2 问答 Hook

```java
public interface AgentHookPlugin {
    default CompletableFuture<AgentQuestionResult> answerAsync(
        AgentQuestionContext context
    ) {
        return CompletableFuture.completedFuture(
            AgentQuestionResult.notHandled()
        );
    }
}
```

规则：

- 按插件优先级顺序执行；
- 第一个返回 `handled(answer)` 的插件结束本次问答；
- 未处理、异常或超时继续原 Agent；
- 单 Hook 超时 1 秒；
- 回答最多 4000 字符；
- 没有调用安装工具时，不得声称安装已经完成。

适合场景：

- 安装和配置引导；
- 领域术语；
- 固定帮助文档；
- 简单分流。

### 6.3 模型请求 Hook

```java
public interface AgentModelHookPlugin {
    PluginDescriptor getDescriptor();

    default CompletableFuture<AgentModelRequestDecision> beforeRequestAsync(
        AgentModelRequestContext context
    ) {
        return CompletableFuture.completedFuture(
            AgentModelRequestDecision.proceed()
        );
    }
}
```

上下文包含：

- 请求 ID；
- 协议；
- 模型名；
- 当前用户消息；
- 系统提示；
- 工具名；
- 工具轮次。

不包含 API Key、Authorization Header 或原始 HTTP 请求。

决策：

```java
AgentModelRequestDecision.proceed();
AgentModelRequestDecision.withSystemPrompt(prompt);
AgentModelRequestDecision.shortCircuit(answer);
AgentModelRequestDecision.block(message);
```

多插件按优先级顺序执行，改写后的提示会传给下一个插件。

限制：

- 系统提示最多 100000 字符；
- 直接回答最多 8000 字符；
- 阻止说明最多 1000 字符；
- 单 Hook 超时 1 秒；
- `AGENT_MODEL_REQUEST` 安装时必须单独确认。

### 6.4 工具展示 Hook

```java
public interface AgentToolPresentationPlugin {
    PluginDescriptor getDescriptor();

    default CompletableFuture<AgentToolPresentation> presentAsync(
        AgentToolPresentationContext context
    ) {
        return CompletableFuture.completedFuture(null);
    }
}
```

可修改：

- 卡片标题；
- 摘要；
- 展开详情；
- 状态文案；
- `rendererId`；
- 通过 `AgentToolViewFactory` 返回任意 Android `View`。

自定义 View 示例：

```java
AgentToolPresentation presentation = new AgentToolPresentation(
    "Weather",
    "Shanghai · 22°C",
    "",
    "Updated",
    "example.weather.v1",
    viewContext -> {
        LinearLayout layout = new LinearLayout(viewContext.getContext());
        TextView text = new TextView(viewContext.getContext());
        text.setText(viewContext.getResultText());
        layout.addView(text);
        return layout;
    }
);
```

限制：

- 标题 120 字符；
- 摘要 500 字符；
- 详情 20000 字符；
- 单 Hook 超时 1 秒；
- 只影响 UI，不修改模型收到的 ToolResult、错误码或截图；
- View 工厂在 UI 线程执行，不能阻塞；
- `PluginToolViewContext.getContext()` 只用于创建/加载 View，不应保存为静态字段或在
  detach 后继续持有；
- View 工厂异常、返回 `null` 或 Renderer 失败时回退文本卡片；
- 自定义 View 由插件负责测量、可访问性、深色主题和资源释放。

## 7. 注册表

当前公开注册表：

- `RuntimeProxyPlugins`
- `AgentPlugins`
- `AgentModelPlugins`
- `AgentToolPlugins`
- `AgentToolHookPlugins`
- `AgentToolPresentationPlugins`
- `PluginCommandPlugins`
- `PluginUiPlugins`
- `PluginLlmProviders`
- `RuntimeProxyExtensions`

共同规则：

- `install(plugin)`；
- `uninstall(id)`；
- `snapshot()`；
- 内部注册表线程安全；
- 注册表只存在当前进程；
- 插件状态和外部持久化由后续插件中心负责。

## 8. Host API

受权插件通过 `PluginLifecycle.onAttach(PluginContext)` 收到能力受限的自定义上下文。
它不是 Android `Context`：

```java
public interface PluginContext extends PluginHostApi {
    PluginDescriptor getDescriptor();
    PluginManifest getManifest();
    String getHostVersion();
    Locale getLocale();
}

public interface PluginHostApi {
    // 已有服务
    PluginAndroidApi android();
    PluginNotificationApi notifications();
    PluginNetworkApi network();
    PluginWebSocketApi webSockets();
    PluginStorageApi storage();

    // v1 完整服务
    PluginSettingsApi settings();
    PluginSecretsApi secrets();
    PluginCacheApi cache();
    PluginDatabaseApi databases();
    PluginFileApi files();
    PluginCommandApi commands();
    PluginTaskApi tasks();
    PluginEventApi events();
    PluginClipboardApi clipboard();
    PluginShareApi share();
    PluginUiApi ui();
    PluginLlmApi llm();
}
```

未授予对应 Capability 时，访问方法直接抛出权限错误。`onDetach()` 后宿主会取消该
插件任务、事件订阅、SSE 和 WebSocket 会话，不能继续使用旧 Context。

### 8.1 Android API 门面

`android()` 需要单独授予 `ANDROID_APIS`，返回 `PluginAndroidApi`，不返回原始
Android Context。

```java
public interface PluginAndroidApi {
    String getHostPackageName();
    int getAndroidApi();
    boolean hasPermission(String permission);
    List<PluginPackageInfo> getLaunchablePackages();
    CompletableFuture<Void> launchPackage(String packageName);
    CompletableFuture<Void> openUrl(String url);
}
```

能力范围：

- 读取宿主包名和 Android API 级别；
- 检查指定权限；
- 查询可启动应用，最多 200 个；
- 启动指定包名应用；
- 用内置浏览器打开 HTTP(S) URL。

URL 规则：

- 接受 HTTP 和 HTTPS；
- 拒绝 `file:`、`content:`、`javascript:` 等其他 scheme；
- 拒绝携带 userinfo 的 URL；
- 主机名必须存在。

不暴露：

- `Activity`；
- `ContentResolver`；
- 原始 `PackageManager`；
- 原始系统服务；
- 其他应用或实例文件；
- 任意 Intent、反射或 Binder 调用。

安装、更新和启用时必须显示“可查询和启动宿主应用”的高风险提示。

### 8.2 通知

```java
public interface PluginNotificationApi {
    CompletableFuture<Void> post(PluginNotification notification);
    CompletableFuture<Void> cancel(String notificationId);
}
```

规则：

- 通知 ID 在插件命名空间内；
- tag 固定为插件 ID；
- 每个插件使用独立通知渠道；
- 标题最多 120 字符，正文最多 1000 字符；
- Android 13+ 必须已有通知权限；
- 用户关闭通知时返回失败；
- 每个通知最多 3 个动作按钮；
- 点击动作后通过 `notification.action` 事件回传
  `notification_id` 和 `action_id`；
- 插件不能通过通知 API 修改其他应用通知。

### 8.3 网络

```java
public interface PluginNetworkApi {
    CompletableFuture<PluginHttpResponse> execute(PluginHttpRequest request);
    PluginSseConnection openSse(
        PluginSseRequest request,
        PluginSseListener listener
    );
}
```

支持方法：

- `GET`
- `POST`
- `PUT`
- `PATCH`
- `DELETE`

安全限制：

- 允许 HTTP 和 HTTPS，明文 HTTP 会在插件权限页标记为不安全；
- 域名必须在安装时声明；
- 支持精确域名和 `*.example.com` 子域规则；
- 禁止 URL userinfo；
- `GET` 和 `DELETE` 不允许请求体；
- 请求体最多 64 KB；
- 响应体最多 1 MB；
- 超时范围 1–30 秒；
- 响应只返回状态码、Content-Type 和正文；
- 不持久化 Cookie，也不共享其他插件的网络会话。

插件层不额外禁止重定向或请求 Header；重定向和 Header 的最终处理行为以
Android/HTTP 客户端为准。

插件不能用网络 API 绕过 MCP 工具权限、文件权限或用户确认。

### 8.4 SSE

`openSse()` 使用 `NETWORK` 和 HTTP Host 白名单，返回流式连接：

```java
PluginSseConnection connection = host.network().openSse(
    new PluginSseRequest("https://api.example.com/events"),
    new PluginSseListener() {
        @Override
        public void onEvent(
            PluginSseConnection connection,
            PluginSseEvent event
        ) {
            // event.getEvent(), event.getId(), event.getData(),
            // event.getRetryMs()
        }

        @Override
        public void onFailure(
            PluginSseConnection connection,
            Throwable error
        ) {
            // 网络中断、超时或事件格式错误。
        }
    }
);

// 插件停用、页面关闭或任务结束时主动释放连接。
connection.close();
```

规则：

- 支持 HTTP 和 HTTPS；
- 支持 `GET`、`POST`、`PUT`、`PATCH`、`DELETE`；
- `GET` 和 `DELETE` 不允许请求体，请求体最多 64 KB；
- 支持自定义 Header，默认 `Accept: text/event-stream`；
- 支持多行 `data:`、`event:`、`id:`、`retry:` 和注释行；
- `PluginSseEvent.getRetryMs()` 返回服务端建议的重连间隔；
- 宿主不自动重连，插件应根据业务和 `retryMs` 自行决定；
- `readTimeoutMs` 传 `0` 可关闭空闲读取超时，非零值范围为 5–300 秒；
- 单行和单个事件最多 1 MB；
- 事件和生命周期回调运行在 IO 网络线程；
- 服务端关闭流时触发 `onClosed`，请求或解析失败时触发 `onFailure`；
- 连接必须由插件主动关闭，避免长期占用线程和网络资源。

完整示例：

```java
PluginSseRequest request = new PluginSseRequest(
    PluginHttpMethod.POST,
    "http://192.168.1.20:8080/chat/stream",
    headers,
    "{\"prompt\":\"hello\"}",
    15_000,
    120_000
);
PluginSseConnection connection =
    host.network().openSse(request, listener);
```

### 8.5 WebSocket

`webSockets()` 需要 `WEBSOCKET`，返回 `PluginWebSocketApi`：

```java
public interface PluginWebSocketApi {
    PluginWebSocket connect(
        PluginWebSocketRequest request,
        PluginWebSocketListener listener
    );
}

public interface PluginWebSocket extends AutoCloseable {
    boolean isOpen();
    boolean sendText(String text);
    boolean sendBinary(byte[] data);
    boolean close(int code, String reason);
}
```

连接示例：

```java
PluginWebSocket socket = host.webSockets().connect(
    new PluginWebSocketRequest("wss://example.com/events"),
    new PluginWebSocketListener() {
        @Override
        public void onOpen(PluginWebSocket socket) {
            socket.sendText("{\"type\":\"subscribe\"}");
        }

        @Override
        public void onText(PluginWebSocket socket, String text) {
            // 消息来自不可信网络，应校验格式和长度后再处理。
        }

        @Override
        public void onFailure(PluginWebSocket socket, Throwable error) {
            // 连接或消息处理失败。
        }
    }
);
```

规则：

- 支持 `ws` 和 `wss`；
- 域名使用与 `NETWORK` 相同的安装白名单；
- `PluginWebSocketRequest` 支持自定义 Header 和 1–30 秒连接超时；
- 插件层不额外禁止重定向或握手 Header；
- 单条文本或二进制消息最多 1 MB；
- 超大消息会以 close code `1009` 关闭，并通过 `onFailure` 报告；
- 关闭码只允许 `1000` 或 `3000–4999`，原因最多 123 字节；
- 回调运行在网络线程，不得执行长任务或阻塞等待；
- 握手失败通过 `onFailure` 返回，不会导致宿主崩溃；
- 插件停用或卸载时应主动关闭全部会话；
- 不共享其他插件的 WebSocket 会话或 Cookie。

`ws` 不加密，适合局域网或不敏感数据；需要传输凭据或隐私数据时应使用 `wss`。

### 8.6 文件存储

`storage()` 需要 `STORAGE`，返回 `PluginStorageApi`：

```java
public interface PluginStorageApi {
    CompletableFuture<PluginStoredFile> writeBytes(String path, byte[] data);
    CompletableFuture<byte[]> readBytes(String path, int maxBytes);
    CompletableFuture<Void> delete(String path);
    CompletableFuture<List<PluginStoredFile>> list(String directory);

    default CompletableFuture<PluginStoredFile> writeText(
        String path,
        String text
    ) {
        return writeBytes(
            path,
            (text == null ? "" : text).getBytes(StandardCharsets.UTF_8)
        );
    }

    default CompletableFuture<String> readText(String path, int maxBytes) {
        return readBytes(path, maxBytes).thenApply(
            bytes -> new String(bytes, StandardCharsets.UTF_8)
        );
    }
}
```

存储根目录：

```text
files/plugins/<plugin-id-sha256>/storage/
```

规则：

- 插件 ID 的 SHA-256 作为目录名，避免字符替换后的命名冲突；
- 只允许相对路径；
- 拒绝绝对路径和 `..`；
- canonical path 必须留在插件私有目录；
- 单文件最多 10 MB；
- 单次读取最多 10 MB；
- 列表最多 1000 个文件；
- 写入先刷盘到同目录临时文件，再原子替换目标文件；
- 写入失败时保留原文件，不暴露半写入内容；
- 路径中的反斜杠会归一化为 `/`，然后执行越界校验；
- 不允许访问其他插件、Agent 实例或宿主私有文件；
- 卸载插件时删除其存储目录。

示例：

```java
PluginStorageApi storage = host.storage();
storage.writeText("settings.json", "{\"enabled\":true}");
String settings = storage.readText("settings.json", 64 * 1024).get();
```

`PluginStoredFile` 返回相对于插件存储根的路径、字节数和最后修改时间。
`list("")` 从存储根目录开始递归列出文件。

当前 `STORAGE` 只用于插件自己的状态、缓存、配置和小型附件。它不会把文件放入
系统的“下载”目录，也不会让插件读取用户已有文件。面向用户导出、通过系统文件选择器
读取文件、在多个插件间共享文件，都必须经过单独的高权限能力、文件选择器和用户确认；
对应能力见 `files()`、`share()` 和 `clipboard()`。插件不能通过拼接私有目录路径绕过
用户选择器。

### 8.7 Agent Tools 与 Tool Hook

`AgentToolProvider` 通过 `AgentToolPlugins.install()` 注册：

```java
public interface AgentToolProvider {
    PluginDescriptor getDescriptor();
    CompletableFuture<List<PluginToolSpec>> listToolsAsync();
    CompletableFuture<PluginToolResult> invokeAsync(PluginToolCall call);
}
```

规则：

- Tool ID 在插件内唯一，宿主转换为 `plugin_<plugin>_<tool>_<hash>`；
- 插件工具进入统一 `ToolRegistry`，本地 Agent 和 MCP 共用；
- schema 最多 64 KB，单次调用默认 30 秒、最多 120 秒；
- 结果文本最多 1 MB，Base64 图片最多 8 MB；
- mutating 工具受“插件工具”确认类别控制；
- 插件停用后调用返回 `PLUGIN_DISABLED`。

`AgentToolHookPlugin` 可在调用前修改参数或阻止：

```java
public interface AgentToolHookPlugin {
    CompletableFuture<AgentToolCallDecision> beforeToolCallAsync(
        AgentToolCallContext context
    );

    CompletableFuture<Void> afterToolCallAsync(
        AgentToolResultContext context
    );
}
```

after Hook 只观察，不允许改写模型收到的 ToolResult。展示定制应使用
`AGENT_TOOL_PRESENTATION`。

### 8.8 设置与密钥

普通设置使用 `settings()`：

```java
host.settings().putString("endpoint", "http://192.168.1.20/api");
String endpoint = host.settings().getString("endpoint", "").get();
```

密钥使用 `secrets()`，由 Android Keystore AES-GCM 加密：

```java
host.secrets().put("api_key", key);
String key = host.secrets().get("api_key", "").get();
```

两者都按插件 ID 的 SHA-256 命名空间隔离。设置 Schema 通过
`PluginSettingsSchemaParser` 解析，支持 `text`、`boolean`、`integer`、`select`
和 `secret`。

### 8.8.1 自定义设置 View

插件可以实现 `PluginSettingsViewProvider`，自己创建完整的 Android 设置界面：

```java
public final class ExamplePlugin implements
    PluginLifecycle,
    PluginSettingsViewProvider {

    @Override
    public View createSettingsView(PluginSettingsViewContext context) {
        LinearLayout layout = new LinearLayout(context.getContext());
        layout.setOrientation(LinearLayout.VERTICAL);

        Switch enabled = new Switch(context.getContext());
        enabled.setText("Enable feature");
        layout.addView(enabled);

        Button save = new Button(context.getContext());
        save.setOnClickListener(view ->
            context.getHost().settings()
                .putBoolean("enabled", enabled.isChecked())
        );
        layout.addView(save);
        return layout;
    }
}
```

规则：

- 必须声明 `SETTINGS` Capability；
- `createSettingsView()` 在 UI 线程调用；
- `getContext()` 只用于创建或 inflate View，页面关闭后不得保留；
- `getHost()` 提供设置、密钥及其它有权限的 Host API；
- 保存后可以调用 `context.close()` 回到插件中心；
- 页面关闭时宿主调用 `onSettingsViewClosed(View)`；
- 设置页是独立 Activity，系统返回后回到插件中心；
- `contributes.settings` Schema 在没有自定义 View 时作为回退表单。

### 8.9 缓存与 SQLite

`cache()` 提供带 TTL 的插件私有缓存：

```java
host.cache().putText("response", json, 300);
String cached = host.cache().getText("response").get();
```

- 单条最多 8 MB；
- TTL 单位为秒，`0` 表示不自动过期；
- 文件和元数据都按插件 ID 隔离；
- 缓存可在宿主清理空间时删除，不能当作永久数据。

`databases()` 返回 SQLite 操作：

```java
SQLiteDatabase db = host.databases().open("notes").get();
db.execSQL("CREATE TABLE IF NOT EXISTS notes(id INTEGER PRIMARY KEY, body TEXT)");
```

- 数据库位于插件私有 `databases/` 目录；
- 数据库名只允许字母、数字、`.`、`_`、`-`；
- 打开、列表和删除均为异步；
- 插件 detach 时宿主关闭全部数据库；
- 不允许访问其他插件或宿主数据库。

### 8.10 文件、分享与剪贴板

`files()` 需要 `FILE_PICKER` 或 `FILE_EXPORT`：

```java
List<PluginPickedFile> files = host.files().pickFiles(
    new PluginFilePickerRequest(List.of("text/*", "application/json"), true)
).get();

byte[] bytes = files.get(0).readBytes(1024 * 1024).get();

host.files().export(
    new PluginFileExportRequest("result.json", "application/json", bytes)
).get();
```

- 通过 SAF 系统选择器导入和另存；
- 不暴露真实路径或原始 `content://` URI；
- 单次读取上限由插件指定，导出最大 32 MB；
- 用户取消导出时 Future 以异常结束。

`share()` 支持文本和最多 10 MB 的内存文件；`clipboard()` 支持文本读写和
Android 13+ 敏感标记。

### 8.11 命令、任务与事件

`PluginCommandProvider` 注册命令，命令既可暴露给 Agent，也可供页面或插件中心调用。
命令默认按 mutating 处理，可声明只读。

`tasks().submit()` 返回可取消的 `PluginTaskHandle`：

```java
PluginTaskHandle task = host.tasks().submit(
    new PluginTaskRequest("sync", 60_000),
    context -> {
        context.reportProgress(50, "Syncing");
        context.checkCancelled();
    }
);
```

每个插件最多 8 个活跃任务、4 个并发线程。任务支持进度、取消、超时和 detach 回收。

事件使用 `events().subscribe()`，事件类型例如：

- `agent.run.started`
- `agent.run.completed`
- `agent.tool.completed`
- `notification.action`

插件发布事件时，宿主强制把 source 改写为插件 ID，不能伪造其他来源。

### 8.12 页面与 Renderer

Manifest 的 `contributes.pages` 和 `contributes.result_renderers` 注册声明式页面、
图表和自定义结果 renderer。页面 JSON 由 `PluginUiDocumentParser` 校验，支持：

- `text`
- `button`
- `image`
- `divider`
- `key_value`
- `progress`
- `list`
- `chart`
- `custom`

`AgentToolPresentation.rendererId` 可以指向插件注册的 Renderer。当前注册表、文档
解析和工具卡片协议已经完成，插件中心中的通用页面渲染器仍在接入。

插件也可以在 Manifest 中声明详情 HTML：

```json
{
  "ui": {
    "html_page": "pages/detail.html"
  }
}
```

插件中心点击插件图标后，会先显示基础信息；存在 `html_page` 时在下方嵌入该 HTML，
没有页面时只显示基础信息。HTML 只能读取插件包内文件，JavaScript 可用，但网络加载、
跨文件访问和 Android JS Bridge 默认关闭，外部链接交给内置浏览器。

### 8.13 LLM Provider

`PluginLlmProvider` 可注册自定义模型协议。用户把模型 Base URL 配置为：

```text
plugin://example-provider
```

宿主将内部 ChatProtocol 转换为 JSON messages/tools，并通过唯一的
`executeAsync(request, onTextDelta)` 调用 Provider。模型配置的「流式输出」开关决定
`request.isStreamEnabled()`；开启时插件可以通过 `Consumer<String>` 发送文本增量，
关闭时不发送增量。两种模式都返回同一个 `CompletableFuture<PluginLlmResponse>`，
其中包含文本、工具调用、停止原因和 token 用量。

插件 Provider 自己持有凭据，应通过 `secrets()` 保存。宿主不会把其他模型的 API Key
传给 Provider。

## 9. Java SDK 兼容性

### 9.1 编译要求

```text
Java: 11
minSdk: 24
compileSdk: 37
```

### 9.2 公开 ABI

- 公开类和方法使用稳定名称；
- 公开 DTO 不可继承；
- 方法参数不使用 Android Compose 类型；
- 集合返回值不可变；
- 可空返回值必须通过 Javadoc 和注解说明；
- 插件入口类必须加入 consumer keep rules；
- 删除或修改方法只能在 API 大版本发生；
- 发布 sources jar、Javadoc 和 ABI 检查报告。

### 9.3 线程模型

- 所有 Hook 返回 `CompletableFuture`；
- 宿主在后台线程等待，单 Hook 默认超时 1 秒；
- 插件可自行选择异步线程；
- 不要在主线程执行网络或长任务；
- 宿主不保证同一插件的多个 Hook 一定在同一线程执行。

## 10. 开发版安装器与 Manifest

当前可以在插件中心从 `.nestplugin` 文件安装开发版插件。安装器已经实现 Manifest 校验、
ZIP 路径和大小限制、DEX 加载、重启恢复和运行时注册。发布者签名、信任等级、隔离进程、
在线仓库和回滚仍未完成，因此不应作为正式商店安全模型。

### 10.1 `.nestplugin` 包

当前格式：

```text
example.nestplugin
├── manifest.json
├── classes.dex
├── contributions/
├── pages/
└── assets/（可选）
```

当前安装流程：

- ZIP 路径穿越检查；
- 文件大小和数量限制；
- manifest schema；
- API 版本；
- 提取到插件私有目录；
- 生成只读 `code.jar` 并通过 `DexClassLoader` 加载；
- 使用 `PluginRuntimeManager` 注册全部扩展点；
- 重启时扫描已安装目录并恢复。

尚未完成：

- 发布者签名和信任等级；
- 能力授权确认页；
- 升级/降级策略和原子回滚；
- 独立插件进程。

### 10.2 Manifest

注册文件命名为 `manifest.json`，字段风格参考 Chrome Manifest V3。当前解析器位于
`PluginManifestParser`，返回不可变的 `PluginManifest`。

```json
{
  "manifest_version": 1,
  "id": "com.example.ai.tools",
  "name": "AI Tools",
  "version": "1.2.0",
  "publisher": "Example Studio",
  "description": "Adds custom Agent tools and settings.",
  "api_version": 2,
  "min_host_version": "1.5.0",
  "entry_class": "com.example.ai.tools.AiToolsPlugin",
  "priority": 100,
  "permissions": [
    "agent_prompt",
    "agent_question",
    "agent_model_request",
    "agent_tools",
    "agent_tool_hooks",
    "agent_tool_presentation",
    "commands",
    "tasks",
    "events",
    "settings",
    "secrets",
    "file_picker",
    "file_export",
    "share",
    "clipboard",
    "ui_pages",
    "ui_renderers",
    "llm_providers",
    "network",
    "websocket",
    "storage"
  ],
  "optional_permissions": [
    "notifications",
    "android_apis"
  ],
  "host_permissions": [
    "https://api.example.com/*",
    "http://192.168.1.20:8080/*",
    "wss://events.example.com/*",
    "ws://192.168.1.20:8081/*",
    "*.socket.example.net"
  ],
  "icons": {
    "48": "icons/48.png",
    "128": "icons/128.png"
  },
  "ui": {
    "options_page": "pages/options.json",
    "html_page": "pages/detail.html"
  },
  "contributes": {
    "agent_tools": "contributions/tools.json",
    "commands": "contributions/commands.json",
    "settings": "contributions/settings.schema.json",
    "llm_providers": "contributions/llm.json",
    "pages": [
      {
        "id": "dashboard",
        "title": "Dashboard",
        "entry": "pages/dashboard.json"
      }
    ],
    "result_renderers": [
      {
        "id": "example.chart",
        "title": "Chart",
        "types": ["chart.v1"]
      }
    ]
  }
}
```

与 Chrome 插件的对应关系：

| Chrome | NestSpace | 说明 |
| --- | --- | --- |
| `manifest_version` | 相同 | 注册格式版本，当前为 `1` |
| `name`、`version`、`description` | 相同 | 插件展示和升级信息 |
| `permissions` | 相同位置 | 映射到 Java `PluginCapability` |
| `optional_permissions` | 相同位置 | 运行时按需申请 |
| `host_permissions` | 相同位置 | HTTP、SSE 和 WebSocket 域名范围 |
| `icons` | 相同位置 | 优先使用 `128`，否则使用 `48` |
| `options_page` | `ui.options_page` | 声明式设置页面 |
| `background` | `entry_class` | Java 插件入口类，不使用 Service Worker |
| `content_scripts` | `contributes` | 声明工具、命令、设置、页面和 Provider |

字段规则：

- `manifest_version` 必须为 `1`；
- `api_version` 必须等于 `PluginDescriptor.API_VERSION`；
- `id` 为 3–128 位小写字母、数字、`.`、`_` 或 `-`；
- `entry_class` 必须是合法 Java 二进制类名；
- `version`、`min_host_version` 只用于兼容判断，不参与指纹计算；
- `permissions` 不区分大小写，支持枚举名或小写形式，例如
  `AGENT_QUESTION` 和 `agent_question`；
- 未知或重复权限直接拒绝安装；
- `host_permissions` 支持 `http`、`https`、`ws`、`wss` 和 `*`；
- 裸域名同时登记到 HTTP 和 WebSocket；
- `*.example.com` 只匹配其子域，不匹配 `example.com` 本身；
- 当前 Host 规则只支持 `/*`，不支持按 API 路径做更细粒度授权；
- 声明 HTTP Host 需要 `network`，声明 WebSocket Host 需要 `websocket`；
- 目标包名和版本不写入 Manifest；支持特定目标的启动策略由插件运行时通过
  `RuntimeProxyPlugin.launchPolicies()` 提供；
- `entry_class`、图标和页面路径不能使用绝对路径或 `..`；
- `ui.html_page` 必须是插件包内的相对 `.html` 路径；
- 未知字段可以保留给未来版本，但安装器应生成警告。

解析示例：

```java
PluginManifest manifest = PluginManifestParser.parse(manifestJson);
PluginDescriptor descriptor = manifest.getDescriptor();
Set<String> httpHosts = manifest.getHttpHosts();
Set<String> webSocketHosts = manifest.getWebSocketHosts();
```

### 10.3 插件中心

插件中心作为桌面上的独立应用入口出现，不占用 Dock。它由独立 Activity 承载，
返回键或左上角返回按钮会结束插件中心并直接回到桌面，不经过“空间设置”。

页面采用应用中心式布局，直接展示已安装内容，不提供“推荐 / 已安装”切换，包括：

- **已安装插件网格**：源码插件和第三方 `.nestplugin` 放在同一个网格中，图标在上、
  名称和状态在下；
- **源码标识**：Debug 源码插件不单独分组，只在图标左下角显示“源码”角标；
- **详情弹窗**：显示名称、版本、发布者、插件 ID、Capability，并在存在
  `ui.html_page` 时嵌入插件 HTML；
- **启用与停用**：长按菜单根据当前状态显示“停用”或“启用”；停用后图标变暗并显示
  “停用”角标，插件 Hook、工具、Provider、设置页和运行时代理立即从当前进程移除；
- **长按菜单**：启用/停用、设置、重命名、分享、导出；第三方安装包额外提供删除；
- **设置 View**：插件通过 `PluginSettingsViewProvider` 返回自己的 Android View，
  宿主使用独立 Activity 承载；
- **重命名**：只改变插件中心显示名称，不修改 Manifest ID；
- **分享与导出**：源码插件和第三方插件都生成可安装的 `.nestplugin`；
- **删除**：仅第三方安装包可删除，并清理插件包、私有存储、缓存、SQLite、设置和密钥；
- **文件安装**：从系统文件选择器安装开发版 `.nestplugin`；
- **系统内置模块**：仍由宿主内部按开关运行，但不在插件中心列表展示；
- **其它已加载插件**：不属于源码、安装包或系统内置模块的当前进程插件会单独展示。

当前宿主内置模块（不显示在插件中心）：

- `builtin.agent.installation-guide`：Agent 安装问答；
- `builtin.agent.browser-tools`：浏览器 Agent 工具；
- `builtin.agent.memory-skills`：记忆与技能工具；
- `builtin.agent.scheduled-tasks`：定时任务工具和恢复；
- `builtin.mcp.tool-bridge`：MCP 工具注入本地 Agent。

通用 Application/Tinker/AppLike 和系统服务兼容是容器核心能力，不能停用。
目标包名、版本、完整类名和专用 Profile 只存在于独立插件模块中，安装后作为第三方
插件显示，不会混入系统内置列表。工具类模块启停后会立即从 Agent/MCP 工具表加入或
移除。

外部 `.nestplugin` 已可从文件安装。在线市场、自动更新、回滚、详情截图、评分和最近日志
尚未实现。

MCP 客户端可以使用 `plugin_list` 和 `plugin_manage` 完成相同的核心操作，包括从
HTTP/content URI 安装、卸载外部插件、重命名、读写设置、启停插件以及调用插件命令。

### 10.4 贡献文件

Manifest `contributes` 指向的文件已有解析器：

- `tools.json`：`PluginContributionParser.parseTools()`；
- `commands.json`：`PluginContributionParser.parseCommands()`；
- `settings.schema.json`：`PluginSettingsSchemaParser.parse()`；
- 页面：`PluginUiDocumentParser.parse()`。

示例位于：

- [NestSpace 功能完整示例](https://github.com/chuccp/NestSpace/tree/main/example-plugin)；
- [示例 Manifest](https://github.com/chuccp/NestSpace/blob/main/example-plugin/plugin/src/main/plugin-assets/manifest.json)；
- [Java 插件入口](https://github.com/chuccp/NestSpace/blob/main/example-plugin/plugin/src/main/java/com/example/runtimedemo/RuntimeDemoPlugin.java)。

插件动态行为仍由 `entry_class` 的 Java 实现完成；声明文件负责注册、权限和静态页面，
不能替代代码签名与安装校验。

### 10.5 页面宿主

声明式页面和 renderer 注册、JSON 校验已经完成。插件中心还需把 `PluginUiApi`
返回的文档映射到 Compose 组件，并接入按钮 action 和 renderer registry。

复杂页面使用受限 WebView，JS Bridge 只暴露白名单方法。插件不直接依赖 Compose。

## 11. 运行时代理

声明式启动策略由 `RuntimeProxyPlugin` 提供。需要目标 Application、ClassLoader 或
Activity 生命周期 Hook 时，插件使用 `plugin-api` 中的运行时代理契约：

```java
public interface RuntimeProxyExtensionProvider {
    PluginDescriptor getDescriptor();
    CompletableFuture<RuntimeProxyContribution> resolveAsync(
        RuntimeProxyContext context
    );
}
```

插件在 `PluginLifecycle.onAttach()` 中注册 `RuntimeProfileResolver`，
在 `onDetach()` 中反注册。Profile 可以使用：

- `RuntimeLaunchSpec` 的版本、进程、APK 和 Activity 信息；
- `RuntimeApkClassIndex` 枚举目标 DEX 类名；
- 可直接比较的完整类名；
- `RuntimePluginRuntime.withContainerClassLoader(...)`；
- Application attach、创建、目标 Activity 前和首个 Activity Hook 生命周期。

目标专用 Profile 的约束：

- 必须匹配目标包名，并使用精确或明确的模糊版本匹配器；
- 安装 Hook 前校验所需完整类名；
- `RuntimeProxyContribution` 携带目标包名、版本和参数 JSON；
- 必须有独立 API 版本；
- 容器兼容扩展只能作用于目标槽位；
- 插件异常不能导致宿主主进程崩溃；
- 卸载不能影响正在启动的容器。

发布者签名、信任等级、独立插件进程和自动回滚仍未实现，因此当前扩展 API 属于开发版
能力。完整的运行时代理代码结构见[插件编写指南](plugin-authoring.md)。

## 12. 安全模型

### 12.1 权限等级

- `AGENT_PROMPT`：中权限；
- `AGENT_QUESTION`：中权限；
- `AGENT_TOOL_HOOKS`：高权限，可修改参数或拦截工具；
- `AGENT_TOOL_PRESENTATION`：高权限；自定义 Android View 会获得短作用域视图 Context；
- `UI_PAGES`、`UI_RENDERERS`：中权限；
- `NOTIFICATIONS`：中权限；
- `SETTINGS`：高权限；自定义设置 View 会获得短作用域 Activity Context；
- `SECRETS`：高权限；
- `CACHE`：中权限；
- `DATABASE`：中权限；
- `FILE_PICKER`、`FILE_EXPORT`：高权限；
- `SHARE`、`CLIPBOARD`：高权限；
- `TASKS`：中权限；
- `EVENTS`：高权限；
- `COMMANDS`：高权限；
- `NETWORK`：高权限；
- `WEBSOCKET`：高权限；
- `ANDROID_APIS`：高权限；
- `STORAGE`：中权限；
- `AGENT_MODEL_REQUEST`：高权限；
- `AGENT_TOOLS`：高权限；
- `LLM_PROVIDERS`：高权限；
- `RUNTIME_PROXY`：高权限；
- 原生代码：最高权限。

### 12.2 插件不能获得

- 宿主或其他插件的 API Key；
- Authorization Header；
- 工具展示 View 和插件设置 View 之外的 Android Context；
- 宿主 Application 或任意 Activity；
- 插件私有存储目录之外的任意文件。
- 其他实例文件；
- 任意 shell；
- 完整 Agent 历史；
- 直接调用内部 `ToolRegistry`；
- 修改宿主权限级别。
- 任意通知 ID 或通知渠道。
- 未声明域名的网络访问。

### 12.3 加载隔离

外部插件计划采用：

- 独立插件进程；
- 受限 ClassLoader；
- Binder/JSON-RPC；
- 方法白名单；
- 超时和配额；
- 签名与信任等级；
- 失败自动停用。

## 13. 开发者模板

当前 SDK 和示例：

```text
plugin-api/
  build.gradle.kts
  consumer-rules.pro
  src/main/java/...

example-plugin/
  manifest.json
  plugin/
    src/main/java/
    src/main/plugin-assets/
```

主示例位于
[example-plugin](https://github.com/chuccp/NestSpace/tree/main/example-plugin)，
同时实现生命周期、Agent Tool、Tool Hook、Android View、自定义设置页和运行时代理。

最小 Agent 插件示例：

```java
public final class ExamplePlugin implements AgentHookPlugin {
    private static final PluginDescriptor DESCRIPTOR = new PluginDescriptor(
        "example.agent",
        "Example Agent plugin",
        "1.0.0",
        PluginDescriptor.API_VERSION,
        100,
        EnumSet.of(
            PluginCapability.AGENT_PROMPT,
            PluginCapability.AGENT_QUESTION
        )
    );

    @Override
    public PluginDescriptor getDescriptor() {
        return DESCRIPTOR;
    }

    @Override
    public CompletableFuture<AgentQuestionResult> answerAsync(
        AgentQuestionContext context
    ) {
        if (!context.getUserMessage().contains("设置")) {
            return CompletableFuture.completedFuture(
                AgentQuestionResult.notHandled()
            );
        }
        return CompletableFuture.completedFuture(
            AgentQuestionResult.handled("打开空间设置即可。")
        );
    }
}
```

## 14. 当前完成度与后续工作

| 领域 | 已完成 | 仍需完善 |
| --- | --- | --- |
| Java Hook | 统一 API、注册表、异常 fallback、动态卸载 | 二进制 ABI 报告、Hook 统计和在线诊断 |
| Agent Tool | ToolProvider、Tool Hook、MCP 共用、动态启停 | 工具调用历史和能力开关细化 |
| 设置与 UI | 自定义设置 View、工具 View、详情 HTML | 通用声明式页面宿主和受限 WebView |
| 安装器 | 文件安装、验证、DEX 加载、重启恢复、删除 | 发布者签名、信任等级、原子升级和回滚 |
| 源码插件 | Debug 自动发现、资源隔离、Run/Apply Changes、自动分享包 | IDE 热重载细化和更完整调试工具 |
| 插件中心 | 应用中心式网格、搜索、分类、详情、长按操作 | 在线市场、更新、评分和截图 |
| 运行时代理 | 启动策略、Profile、APK 类名、ClassLoader scope | 独立进程、签名、诊断和自动回滚 |

## 15. 必测项

### Java API

- Java 11 可编译；
- 无 Kotlin/Compose 传递依赖；
- R8 后 Hook 实现仍可加载；
- ABI 兼容检查；
- `CompletableFuture` 超时和异常；
- 不可变集合和长度限制。

### Agent

- 提示片段顺序；
- 问答命中、未命中和异常；
- 模型请求改写、直答和阻止；
- 工具展示 fallback；
- 工具展示 Android View 工厂、异常 fallback 和 View 生命周期；
- Agent 插件工具与 MCP 工具共用、动态启停；
- Tool Hook 改参数、阻止和 after 观察；
- 模型数据不被展示 Hook 修改；
- 插件权限关闭后 Hook 不再执行。

### Host API

- 通知权限关闭后的失败；
- 通知渠道和 tag 命名空间；
- 通知 ID 冲突隔离；
- HTTP/HTTPS 支持；
- 域名白名单和子域规则；
- 请求/响应大小限制；
- 网络超时和取消；
- SSE HTTP/HTTPS 流式请求；
- SSE 多行 data、事件 ID 和 retry；
- SSE 主动关闭、正常结束和失败回调；
- WebSocket `ws`/`wss`；
- WebSocket 文本、二进制、关闭和失败回调；
- WebSocket 消息大小和关闭原因限制；
- 未授权 Capability 拒绝访问；
- Android API 门面不能暴露原始 Context；
- 可启动应用查询上限；
- 非 HTTP(S) URL 拒绝；
- 存储路径穿越和绝对路径拒绝；
- 插件存储大小和条目限制；
- 原子写入失败恢复；
- 插件卸载后的存储清理；
- 设置和密钥命名空间隔离；
- 密钥 Android Keystore 加密；
- 缓存 TTL、隔离和清理；
- SQLite 私有目录、打开、列表和 detach 关闭；
- 文件选择器、导出取消和读取上限；
- 分享、剪贴板和敏感标记；
- 任务进度、取消、超时和 detach 回收；
- 事件来源不可伪造；
- 通知动作回传；
- 命令、UI Renderer 和 LLM Provider 注册表。
- `.nestplugin` ZIP 路径穿越、大小限制、DEX 只读加载和重启恢复；
- 应用中心式插件列表、搜索、分类、文件和详情。

### Manifest

- 必填字段和版本；
- 未知、重复和可选权限；
- HTTP 与 WebSocket Host 分类；
- `*.example.com` 子域边界；
- 目标包名、`versionCode` 和完整类名；
- 图标、设置页和资源路径；
- Chrome 风格示例 JSON。

### 运行

- 包名、精确版本和模糊版本匹配准确；
- Android API 范围；
- 重复目标策略拒绝；
- 宿主兼容启动；
- 宿主应用缺失错误；
- 插件卸载后新回合不生效。

### 安装器和插件中心

- ZIP 路径穿越；
- 签名和哈希错误；
- API 版本不兼容；
- 更新失败回滚；
- 卸载清理；
- 插件进程崩溃隔离。

## 16. 文档维护规则

- 已实现接口写“已实现”，并链接到源码；
- 设计目标写“未实现”或“计划”；
- 公开 API 示例优先使用 Java；
- 新增 Hook 必须同时说明权限、超时、长度限制和 fallback；
- 修改公开 ABI 时同步更新 API 版本、迁移说明和测试。

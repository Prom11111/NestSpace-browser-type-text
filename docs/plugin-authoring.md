# NestSpace 插件编写指南

这份手册按照插件开发者的实际工作顺序编写。你不需要先理解 NestSpace 容器的全部实现，
也不需要修改主应用。从一个独立插件模块开始，先让它加载，再逐步接入 Agent、界面和
系统能力，最后决定是否生成可独立分发的安装包。

本文面向插件实现者。平台能力清单、安全模型和历史设计说明见
[插件平台与 Java SDK](plugin-center.md)。遇到接口名称或限制不一致时，以源码中的
Java 接口为准。

## 如何使用本手册

第一次编写插件时，建议按下面顺序阅读和操作：

1. 阅读第 1、2 节，理解源码插件和安装包插件为什么使用不同的加载路径。
2. 按第 3 节复制示例，先得到一个能在插件中心出现的“Hello Plugin”。
3. 用第 4、5 节把 Manifest、入口类、权限和生命周期改正确。
4. 按要扩展的方向选择第 6 至 9 节：Agent、工具展示或设置 View。
5. 需要存储或网络时，读取第 10、11 节，并按能力最小化原则声明权限。
6. 只有目标应用确实有运行时差异时，才阅读第 13 节。
7. 提交前使用第 14 至 16 节完成调试和检查。

如果你已经有明确需求，可以直接查附录中的接口索引和示例地图；如果你想理解插件平台的
整体边界，再回到前面的叙事章节。

章节索引：

- 第 1–2 节：插件模型和加载路径；
- 第 3–5 节：创建工程、Manifest 和生命周期；
- 第 6–9 节：Agent 工具、Hook、工具 View 和设置 View；
- 第 10–12 节：Host API、网络、命令、页面和模型 Provider；
- 第 13 节：运行时代理；
- 第 14–16 节：调试、发布和设计检查；
- 附录 A–C：接口索引、示例地图和提交检查清单。

## 1. 先理解插件是什么

在 NestSpace 中，插件不是一个独立安装的 Android 应用，而是一组由宿主加载的 Java
实现和声明式资源。插件入口类实现若干稳定接口，宿主启动后创建入口类实例，把受控的
`PluginContext` 交给它，并将它注册到对应的 Hook 表。

插件和宿主之间的边界由 Manifest 决定。Manifest 声明插件身份、入口类、需要的
Capability、允许访问的网络 Host，以及图标、页面和贡献文件的位置。插件即使实现了某个
接口，如果没有声明对应能力，运行到相关 Host API 时仍会被拒绝。

插件注册后，`AgentToolProvider` 和 `PluginCommandProvider` 贡献的工具/命令会同时
进入本地 Agent 与 MCP 的 `tools/list`。远程 MCP 还可以通过通用的 `plugin_list` 和
`plugin_manage` 工具查看、安装、卸载、重命名、配置和执行插件命令。

插件通常负责四类工作。

第一类是扩展 Agent。插件可以提供提示词、在模型请求前做判断、增加工具、拦截工具调用，
或者改变工具结果在对话中的展示方式。

第二类是扩展界面。插件可以提供详情 HTML、声明式页面、结果 Renderer、工具结果
Android View，以及完整的插件设置页面。

第三类是接入外部服务。宿主提供 HTTP、SSE、WebSocket、通知、文件选择、分享、
剪贴板、后台任务和事件总线，插件不需要自己持有 Android `Activity`。

第四类是处理特定运行环境。插件可以按目标包名和版本选择启动策略，也可以使用
`runtime-plugin-api` 为目标 APK 提供版本和类名绑定的运行时代理。

## 2. 插件如何被加载

开发阶段和正式分发使用两条不同的加载路径，但两者共享同一套 Java API。

源码插件是 Debug 构建的一部分。NestSpace 根工程的 Gradle 配置会自动发现
`example-plugin/plugin` 和 `dev-plugins/*`，并把它们作为 `debugImplementation`
依赖编进宿主。插件的 Java 类进入宿主 ClassLoader，资源被放到
`assets/plugins-dev/<plugin-id>/`。应用启动时，宿主扫描 Manifest，使用
`Class.forName(entryClass, true, context.classLoader)` 创建入口类，然后交给
`PluginRuntimeManager` 注册。

这条路径不生成 DEX，也不经过 `DexClassLoader`。Android Studio 的 Run、Apply Changes、
断点和普通日志都可以直接使用。开发者修改 Java 或资源后，不需要先执行
`packagePlugin`。

安装包插件使用 `.nestplugin`。打开这个 ZIP 后，根目录有 `manifest.json` 和
`classes.dex`，其余内容是图标、页面和贡献文件。宿主把安装包解压到私有目录，把
`classes.dex` 包装成只读 `code.jar`，再通过 `DexClassLoader` 创建入口类。应用重启后，
安装器会重新扫描已安装目录并恢复插件。

Debug 构建还会为源码插件自动生成一份仅供分享和导出的 `.nestplugin`。运行时仍然使用
源码 ClassLoader，分享资产只用于把插件交给其他设备。因此源码开发不要求手工打包，
但插件中心仍然可以从长按菜单分享、导出、停用或重新启用它。

当源码插件和外部安装包使用同一个插件 ID 时，源码插件优先。宿主先加载源码插件，再恢复
外部安装包，并跳过相同 ID 的旧包。这个顺序保证开发时不会同时注册两套工具、两个设置页
或两个相反的启动策略。

## 3. 创建第一个插件

最快的开始方式是复制仓库中的示例模块。示例已经包含源码资源隔离、consumer keep rules、
运行时代理示例、设置 View、自动分享包和安装包任务。

```bash
cd /Users/cao/Documents/GitHub/NestSpace
cp -R example-plugin/plugin dev-plugins/hello-plugin
```

复制之后，先不要急着添加功能。依次修改插件身份、Java 包名、入口类名和 Gradle
namespace，确认它能在插件中心单独出现，再开始扩展。

插件模块采用 Android Library 工程。源码模式下，SDK 由宿主提供：

```kotlin
dependencies {
    compileOnly(project(":plugin-api"))

    // 只有实现运行时代理时才需要
    compileOnly(project(":runtime-plugin-api"))
}
```

不要使用 `implementation(project(":plugin-api"))`。插件和宿主必须共享同一套 API 类型；
如果把 SDK 打进插件，DEX 中可能出现两个同名接口，宿主无法正确识别实现关系。

典型目录如下：

```text
dev-plugins/hello-plugin/
├── build.gradle.kts
├── consumer-rules.pro
└── src/main/
    ├── AndroidManifest.xml
    ├── java/com/example/hello/HelloPlugin.java
    └── plugin-assets/
        ├── manifest.json
        ├── icons/icon.png
        ├── pages/detail.html
        └── contributions/
            ├── tools.json
            └── commands.json
```

`plugin-assets/manifest.json` 是 NestSpace 插件 Manifest，不是 Android 的
`AndroidManifest.xml`。前者决定插件身份和权限，后者只是 Android Library 的构建文件。

修改完成后，在 Android Studio 中运行宿主。打开桌面上的插件中心，插件应当出现在
“已安装插件”网格里，并带有“源码”角标。点击图标能打开详情，长按图标能看到设置、
重命名、分享和导出入口。

如果插件没有出现，先检查插件 ID 是否与目录冲突，再检查 `entry_class`、Gradle
namespace 和 Java 包名是否一致。宿主日志中的 `PluginSourceLoader` 会打印加载成功或
失败原因。

## 4. Manifest 是插件的注册表

一个完整 Manifest 如下：

```json
{
  "manifest_version": 1,
  "id": "com.example.hello",
  "name": "Hello Plugin",
  "version": "1.0.0",
  "publisher": "Example Studio",
  "description": "Example NestSpace plugin",
  "api_version": 2,
  "min_host_version": "1.0.0",
  "entry_class": "com.example.hello.HelloPlugin",
  "priority": 100,
  "permissions": [
    "agent_tools",
    "agent_tool_presentation",
    "settings",
    "network",
    "websocket"
  ],
  "host_permissions": [
    "https://api.example.com/*",
    "wss://example.com/*"
  ],
  "icons": {
    "128": "icons/icon.png"
  },
  "ui": {
    "html_page": "pages/detail.html"
  },
  "contributes": {
    "agent_tools": "contributions/tools.json",
    "settings": "contributions/settings.schema.json"
  }
}
```

Manifest 字段速查：

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `manifest_version` | 是 | 当前固定为 `1` |
| `id` | 是 | 插件全局唯一 ID，也是存储命名空间 |
| `name` | 是 | 插件中心显示名称 |
| `version` | 是 | 插件版本 |
| `publisher` | 否 | 发布者 |
| `description` | 否 | 插件介绍 |
| `api_version` | 是 | 当前固定为 `2` |
| `min_host_version` | 否 | 最低宿主版本 |
| `entry_class` | 是 | Java 入口类完整类名 |
| `priority` | 否 | Hook 排序，数字越大越先执行 |
| `permissions` | 否 | 必需 Capability |
| `optional_permissions` | 否 | 可选 Capability |
| `host_permissions` | 否 | HTTP、SSE、WebSocket 白名单 |
| `icons` | 否 | 插件图标映射 |
| `ui.html_page` | 否 | 插件详情 HTML |
| `ui.options_page` | 否 | 可选页面入口 |
| `contributes` | 否 | 工具、命令、设置、页面等声明文件 |

`id` 是插件、设置、缓存、数据库、通知和运行策略的命名空间。发布后不要随意修改。
`entry_class` 必须指向一个具有无参构造器的 Java 类。`priority` 只影响 Hook 的执行顺序，
数字越大越先执行，不代表权限等级。

`manifest_version` 和 `api_version` 必须与宿主支持的版本一致。新增默认方法和新增
Capability 可以保持兼容；删除方法、改变方法签名或更换类型需要提升 API 主版本。

权限名称与 Java Capability 一一对应：

| Manifest 权限 | 用途 |
| --- | --- |
| `agent_prompt` | 贡献 Agent 提示词 |
| `agent_question` | 模型请求前直接处理问答 |
| `agent_model_request` | 检查或改写模型请求 |
| `agent_tools` | 提供 Agent 工具 |
| `agent_tool_hooks` | 调用前改写或阻断工具 |
| `agent_tool_presentation` | 自定义工具结果展示 |
| `llm_providers` | 注册模型 Provider |
| `commands` | 注册命令 |
| `tasks` | 提交托管后台任务 |
| `events` | 发布和订阅事件 |
| `settings` | 普通设置和自定义设置 View |
| `secrets` | Keystore 加密密钥 |
| `cache` | TTL 缓存 |
| `database` | 私有 SQLite |
| `file_picker` | 系统文件选择器 |
| `file_export` | 用户可见文件导出 |
| `share` | 系统分享面板 |
| `clipboard` | 剪贴板 |
| `ui_pages` | 声明式页面 |
| `ui_renderers` | 结果 Renderer |
| `notifications` | 插件通知 |
| `network` | HTTP 和 SSE |
| `websocket` | WebSocket |
| `android_apis` | 受控 Android API 门面 |
| `storage` | 插件私有文件 |
| `runtime_launch_policy` | 启动策略 |
| `runtime_proxy` | 运行时代理描述 |

`host_permissions` 同时承担网络白名单作用。HTTP 和 SSE 的 Host 需要 `network`，
`ws`/`wss` 的 Host 需要 `websocket`。插件只能请求白名单内的 Host，不能通过重定向或
拼接 URL 绕过这一层声明。

`contributes` 中的文件不会自动产生业务行为。宿主会解析工具、命令、设置 Schema 和页面
声明，但真正的工具执行、命令处理和设置保存仍然由 Java 入口类完成。

`contributes` 常用文件如下：

| 字段 | 文件位置示例 | 对应能力 |
| --- | --- | --- |
| `agent_tools` | `contributions/tools.json` | `agent_tools` |
| `commands` | `contributions/commands.json` | `commands` |
| `settings` | `contributions/settings.schema.json` | `settings` |
| `llm_providers` | `contributions/llm.json` | `llm_providers` |
| `pages` | `pages/overview.json` | `ui_pages` |
| `result_renderers` | Manifest 内联对象 | `ui_renderers` |

贡献文件适合提供静态描述、默认值和页面入口。权限检查、凭据访问和真正的执行逻辑必须
由 Java 代码完成，不能只靠声明文件实现。

## 5. 插件生命周期和能力上下文

入口类通常实现 `PluginLifecycle`：

```java
public final class HelloPlugin implements PluginLifecycle {

    private PluginContext context;

    @Override
    public void onAttach(PluginContext context) {
        this.context = context;
    }

    @Override
    public void onDetach() {
        context = null;
    }
}
```

`onAttach` 是插件和宿主的连接点。此时插件已经通过 Manifest 校验，可以保存
`PluginContext`，但不要在构造器或 `onAttach` 中执行耗时网络请求。`onDetach` 必须释放
插件拥有的资源，包括网络连接、事件订阅、任务句柄、数据库和自定义 View。

`PluginContext` 不是 Android `Context`。它不暴露 Activity、Application 或系统服务。
插件通过它读取插件 ID、Manifest、Locale，并访问已声明能力的 Host API。未声明能力时，
调用对应方法会得到 `UnsupportedOperationException` 或明确的能力错误。

能力声明遵循最小化原则。一个只提供工具和设置页的插件不需要文件、剪贴板或 Android
门面权限。权限越少，安装页越容易理解，插件异常时的影响面也越小。

## 6. 编写第一个 Agent 工具

Agent 工具是最常见的插件功能。入口类实现 `AgentToolProvider`，描述工具列表，并在
模型选择工具后执行调用。

```java
package com.example.hello;

import com.coke.nestspace.plugin.*;
import org.json.JSONObject;

import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class HelloPlugin implements
    PluginLifecycle,
    AgentToolProvider {

    private static final String ID = "com.example.hello";

    private static final PluginDescriptor DESCRIPTOR = new PluginDescriptor(
        ID,
        "Hello Plugin",
        "1.0.0",
        PluginDescriptor.API_VERSION,
        100,
        EnumSet.of(PluginCapability.AGENT_TOOLS)
    );

    private PluginContext context;

    @Override
    public PluginDescriptor getDescriptor() {
        return DESCRIPTOR;
    }

    @Override
    public void onAttach(PluginContext context) {
        this.context = context;
    }

    @Override
    public void onDetach() {
        context = null;
    }

    @Override
    public CompletableFuture<List<PluginToolSpec>> listToolsAsync() {
        String schema =
            "{\n" +
            "  \"type\":\"object\",\n" +
            "  \"properties\":{\n" +
            "    \"name\":{\"type\":\"string\"}\n" +
            "  },\n" +
            "  \"required\":[\"name\"]\n" +
            "}";
        return CompletableFuture.completedFuture(List.of(
            new PluginToolSpec(
                "hello",
                "Say hello",
                "Return a greeting for the supplied name",
                schema,
                false
            )
        ));
    }

    @Override
    public CompletableFuture<PluginToolResult> invokeAsync(
        PluginToolCall call
    ) {
        String name = new JSONObject(call.getArgumentsJson())
            .optString("name", "world");
        return CompletableFuture.completedFuture(
            PluginToolResult.success("Hello, " + name)
        );
    }
}
```

工具 ID 只在当前插件内唯一。宿主会把它转换成带插件命名空间的全局工具名，因此不同插件
可以各自使用 `search` 或 `refresh`。工具的输入必须使用 JSON Schema，输出使用
`PluginToolResult`。只读工具把 `mutating` 设为 `false`，会修改外部状态、文件、数据库或
业务数据的工具应设为 `true`，让宿主继续使用工具确认流程。

工具默认超时是 30 秒，最大可以设置到 120 秒。结果文本最大 1 MB，Base64 图片最大
8 MB。复杂任务的进度和取消应该使用任务 API，而不是让一次工具调用无限阻塞。

## 7. 在 Agent 决策链中增加逻辑

Agent 不是简单地把用户消息直接发给模型。它先给插件机会处理问答，再收集提示词，
然后允许插件检查模型请求。工具执行前后还有独立的 Hook。

提示词 Hook 适合提供领域规则。例如插件可以说明自己的工具适用于哪些明确场景，避免
模型在没有请求时主动调用。提示词会进入宿主单独组织的插件区块，不能覆盖系统安全规则。

```java
@Override
public List<AgentPromptContribution> promptContributions(
    AgentPromptContext context
) {
    return List.of(new AgentPromptContribution(
        "hello-rules",
        "Hello rules",
        "Use the hello tool only when the user explicitly asks for a greeting.",
        100
    ));
}
```

问答 Hook 可以在模型请求前直接返回结果。它适合稳定的本地查询或明确的命令式问题：

```java
@Override
public CompletableFuture<AgentQuestionResult> answerAsync(
    AgentQuestionContext context
) {
    if (context.getUserMessage().trim().equals("hello")) {
        return CompletableFuture.completedFuture(
            AgentQuestionResult.handled("Hello!")
        );
    }
    return CompletableFuture.completedFuture(
        AgentQuestionResult.notHandled()
    );
}
```

一旦返回 `handled`，本次模型请求会被跳过。因此不要把需要模型理解、需要综合上下文或
可能回答错误的问题交给直接问答 Hook。

模型请求 Hook 能看到系统提示、模型名称、协议、工具名和当前轮次。它适合做审计、补充
上下文、阻断敏感请求或替换系统提示。由于它能读取完整系统提示，这属于高权限能力。

```java
@Override
public CompletableFuture<AgentModelRequestDecision> beforeRequestAsync(
    AgentModelRequestContext context
) {
    if (context.getRound() == 0 &&
        context.getUserMessage().contains("short answer")) {
        return CompletableFuture.completedFuture(
            AgentModelRequestDecision.withSystemPrompt(
                context.getSystemPrompt() + "\nKeep the answer short."
            )
        );
    }
    return CompletableFuture.completedFuture(
        AgentModelRequestDecision.proceed()
    );
}
```

工具前 Hook 可以改写参数或阻止调用。工具后 Hook 只能观察，不能改写模型已经收到的
结果。改变展示应该使用工具展示 Hook，而不是在 after Hook 中偷偷替换业务结果。

## 8. 工具结果可以使用自定义 View

默认工具卡片适合文本和简单 JSON。需要图表、图片、复杂布局或可交互控件时，插件可以
实现 `AgentToolPresentationPlugin` 并返回 `AgentToolViewFactory`。

```java
@Override
public CompletableFuture<AgentToolPresentation> presentAsync(
    AgentToolPresentationContext context
) {
    return CompletableFuture.completedFuture(
        new AgentToolPresentation(
            "Hello result",
            context.getResultText(),
            "",
            context.isSuccessful() ? "OK" : "ERROR",
            "",
            viewContext -> {
                TextView view = new TextView(viewContext.getContext());
                view.setText(context.getResultText());
                return view;
            }
        )
    );
}
```

View 工厂在 UI 线程调用。`PluginToolViewContext.getContext()` 只用于创建或 inflate
视图，不能在静态字段中保存，也不应该在卡片 detach 后继续使用。插件负责测量、深色主题、
文字可访问性和资源释放。展示失败时宿主会回退为普通卡片，因此业务结果仍应放在
`PluginToolResult.text` 中，不能只存在于自定义 View。

## 9. 设置页是一个插件自己实现的 View

设置入口不是固定表单，也不要求插件使用 Compose。宿主提供一个独立设置 Activity、标题栏
和生命周期，插件只需返回一个 Android View。

入口类实现 `PluginSettingsViewProvider`，Manifest 声明 `settings`：

```java
public final class HelloPlugin implements
    PluginLifecycle,
    PluginSettingsViewProvider {

    @Override
    public View createSettingsView(PluginSettingsViewContext context) {
        Context ui = context.getContext();

        LinearLayout layout = new LinearLayout(ui);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 48, 48, 48);

        Switch enabled = new Switch(ui);
        enabled.setText("Enable Hello Plugin");
        layout.addView(enabled);

        Button save = new Button(ui);
        save.setText("Save");
        save.setOnClickListener(view -> {
            context.getHost().settings()
                .putBoolean("enabled", enabled.isChecked())
                .thenRun(context::close);
        });
        layout.addView(save);

        context.getHost().settings()
            .getBoolean("enabled", true)
            .thenAccept(value -> layout.post(() ->
                enabled.setChecked(value)
            ));

        return layout;
    }
}
```

`PluginSettingsViewContext` 提供三件事：`getContext()` 用于构建 View，
`getHost()` 提供插件的设置、密钥和其他 Host API，`close()` 结束设置页并回到插件中心。
页面关闭后，宿主调用 `onSettingsViewClosed(View)`，插件可以在这里停止动画、取消订阅
或释放 View 级资源。

设置读取和保存应与插件实际业务使用同一个 `settings()` 命名空间。宿主使用插件 ID 的
SHA-256 派生存储前缀，因此不同插件即使使用相同 key 也不会互相覆盖。敏感值必须使用
`secrets()`，不要写进普通设置。

如果只需要简单键值参数，可以同时提供 Schema：

```json
{
  "title": "Hello settings",
  "fields": [
    {
      "id": "enabled",
      "title": "Enable plugin",
      "type": "boolean",
      "default": "true"
    },
    {
      "id": "mode",
      "title": "Mode",
      "type": "select",
      "options": ["compact", "detailed"],
      "default": "compact"
    },
    {
      "id": "api_key",
      "title": "API key",
      "type": "secret"
    }
  ]
}
```

Schema 支持 `text`、`boolean`、`integer`、`select` 和 `secret`。`secret` 字段使用
Keystore 加密存储。复杂设置仍应使用自定义 View，因为插件可以完全控制校验、布局和
交互状态。

Manifest 中仍然可以声明 `contributions.settings`。没有自定义设置 View 的插件会回退到
Schema 表单，但复杂插件更适合自己实现 View。

## 10. 使用宿主提供的服务和数据

插件不需要把所有数据都留在内存。NestSpace 提供四种常用持久化方式，分别适合不同场景。

Host API 与权限的对应关系如下：

| Host API | 权限 | 典型用途 |
| --- | --- | --- |
| `settings()` | `settings` | 插件配置 |
| `secrets()` | `secrets` | API Key、令牌 |
| `storage()` | `storage` | 插件私有文件 |
| `cache()` | `cache` | 可重建的短期数据 |
| `databases()` | `database` | 查询、索引、事务 |
| `files()` | `file_picker` / `file_export` | 用户文件 |
| `share()` | `share` | 系统分享 |
| `clipboard()` | `clipboard` | 剪贴板 |
| `notifications()` | `notifications` | 插件通知 |
| `tasks()` | `tasks` | 后台任务 |
| `events()` | `events` | 插件间事件 |
| `network()` | `network` | HTTP 和 SSE |
| `webSockets()` | `websocket` | WebSocket |
| `android()` | `android_apis` | 受控 Android 门面 |
| `ui()` | `ui_pages` / `ui_renderers` | 页面和 Renderer |
| `llm()` | `llm_providers` | 模型 Provider |

设置适合小型键值配置。读取和写入都是异步 `CompletableFuture`，插件应避免在主线程
调用 `.get()`。密钥使用 Android Keystore 加密，适合 API Key、访问令牌和会话凭据。

```java
context.settings()
    .putString("endpoint", "https://api.example.com");

context.secrets()
    .put("api_key", apiKey);
```

私有文件适合配置、JSON、日志和小型附件。存储路径必须是相对路径，单文件最大 10 MB。
宿主会拒绝绝对路径和 `..`，并把所有文件限制在插件自己的目录中。

```java
context.storage().writeText("state.json", json).get();
String state = context.storage()
    .readText("state.json", 64 * 1024)
    .get();
```

缓存适合可以重新生成的数据。每条缓存都有 TTL，宿主可以在清理空间时删除它。缓存不应
保存唯一副本。

```java
context.cache().putText("search:hello", json, 300);
String cached = context.cache().getText("search:hello").get();
```

需要查询、索引和事务时使用私有 SQLite：

```java
SQLiteDatabase db = context.databases().open("hello").get();
db.execSQL(
    "CREATE TABLE IF NOT EXISTS notes(" +
    "id INTEGER PRIMARY KEY, body TEXT)"
);
```

插件 detach 时宿主关闭数据库。数据库文件、缓存和私有文件在插件删除时一并清理。

通知、剪贴板、分享和文件选择器都通过 Host API 使用。文件选择器只返回受控读取句柄，
插件不会获得用户文件的真实路径。导出通过系统文档选择器完成，分享通过系统分享面板
完成。通知渠道按插件隔离，插件不能覆盖其他插件的通知。

选择文件并导出的完整流程可以写成：

```java
List<PluginPickedFile> picked = context.files().pickFiles(
    new PluginFilePickerRequest(List.of("text/plain"), false)
).get();

if (!picked.isEmpty()) {
    PluginPickedFile file = picked.get(0);
    byte[] bytes = file.readBytes(1024 * 1024).get();
    context.files().export(
        new PluginFileExportRequest(
            "copy-" + file.getDisplayName(),
            file.getMimeType(),
            bytes
        )
    ).get();
}
```

发送通知：

```java
context.notifications().post(
    new PluginNotification(
        "hello-ready",
        "Hello Plugin",
        "Background work completed"
    )
).get();
```

后台工作优先使用任务 API。插件提交 `PluginTaskBody` 后可以报告进度、检查取消并获取
结果。需要在插件内部传递状态时使用事件总线；发布事件的 source 会被宿主改写为当前插件
ID，不能伪造其他来源。

```java
PluginTaskHandle task = context.tasks().submit(
    new PluginTaskRequest("Refresh hello index", 60_000),
    taskContext -> {
        for (int i = 0; i < 10; i++) {
            taskContext.checkCancelled();
            taskContext.reportProgress(i * 10, "Indexing");
            Thread.sleep(200);
        }
        taskContext.reportProgress(100, "Done");
    }
);
```

事件订阅返回一个必须关闭的 `PluginSubscription`：

```java
PluginSubscription subscription = context.events().subscribe(
    Set.of("hello.updated"),
    event -> Log.d("Hello", event.getPayloadJson())
);

// onDetach
subscription.close();
```

## 11. 网络请求、SSE 和 WebSocket

网络能力由 Manifest 的 `network`、`websocket` 和 `host_permissions` 共同控制。

普通 HTTP 请求使用 `PluginHttpRequest`：

```java
PluginHttpRequest request = new PluginHttpRequest(
    PluginHttpMethod.POST,
    "https://api.example.com/hello",
    Map.of(
        "Content-Type", "application/json",
        "Authorization", "Bearer " + apiKey
    ),
    "{\"name\":\"NestSpace\"}",
    15_000
);

PluginHttpResponse response = context.network()
    .execute(request)
    .get();
```

请求体最大 64 KB。插件应把凭据从 `secrets()` 中取出，只在当前请求中使用。不要把密钥
写入普通日志，也不要依赖宿主提供 Cookie 会话；不同插件之间不共享网络会话。

SSE 使用 `openSse`。事件可能在后台网络线程到达，插件不能在回调中执行长时间阻塞任务。
连接返回 `PluginSseConnection`，页面关闭、任务结束或插件 detach 时必须主动关闭。

```java
PluginSseConnection connection = context.network().openSse(
    new PluginSseRequest("https://api.example.com/events"),
    new PluginSseListener() {
        @Override
        public void onEvent(
            PluginSseConnection connection,
            PluginSseEvent event
        ) {
            Log.d("Hello", event.getData());
        }
    }
);
```

WebSocket 使用 `webSockets()`，支持 `ws` 和 `wss`：

```java
PluginWebSocket socket = context.webSockets().connect(
    "wss://example.com/live",
    new PluginWebSocketListener() {
        @Override
        public void onText(PluginWebSocket socket, String text) {
            Log.d("Hello", text);
        }
    }
);

socket.sendText("hello");
socket.close(1000, "done");
```

单条 WebSocket 消息最大 1 MB。回调在网络线程执行，关闭码和原因仍需遵守 WebSocket
协议限制。插件不要创建无限重连线程；重连策略应由业务控制，并在 detach 时停止。

## 12. 命令、页面和模型 Provider

命令适合把插件能力暴露给 Agent、插件 UI 或其他宿主入口。实现
`PluginCommandProvider`，返回命令描述和 JSON Schema，然后在
`invokeCommandAsync` 中解析参数。命令和工具的区别是命令更偏向宿主内部动作，
工具则直接进入 Agent 工具列表。

```java
public CompletableFuture<List<PluginCommandSpec>> listCommandsAsync() {
    return CompletableFuture.completedFuture(List.of(
        new PluginCommandSpec(
            "refresh",
            "Refresh Hello",
            "Rebuild the plugin index",
            "{\"type\":\"object\",\"properties\":{}}",
            true,
            30_000
        )
    ));
}
```

声明式页面通过 `contributions.pages` 和 `PluginUiProvider` 注册。页面文件是 JSON，
由 `PluginUiDocumentParser` 解析。Renderer 用于工具展示中的特定结果类型。当前页面
注册、解析和 Renderer 数据模型已经存在，通用 Compose 页面宿主仍在完善；复杂交互
优先使用设置 View 或工具结果 View。

页面声明示例：

```json
{
  "permissions": ["ui_pages", "ui_renderers"],
  "contributes": {
    "pages": [
      {
        "id": "overview",
        "title": "Overview",
        "description": "Hello Plugin overview",
        "entry": "pages/overview.json"
      }
    ],
    "result_renderers": [
      {
        "id": "hello.card",
        "title": "Hello result",
        "types": ["hello.result.v1"]
      }
    ]
  }
}
```

LLM Provider 允许插件接入自己的模型服务。实现 `PluginLlmProvider` 时，需要提供
Provider ID、模型列表和一个统一的 `executeAsync()` 请求入口。模型配置中使用
`plugin://<provider-id>` 引用它。

模型配置中的「流式输出」开关会写入 `PluginLlmRequest.isStreamEnabled()`：

- 开启时，Provider 可以在 Future 完成前多次调用 `onTextDelta.accept()`；
- 关闭时，Provider 只完成 Future，不发送文本增量；
- 最终 `PluginLlmResponse`、异常和取消统一由 Future 表达；
- 宿主取消协程时会调用 `future.cancel(true)`，Provider 应同时关闭上游请求。

Provider 自己持有的凭据必须通过 `secrets()` 保存，宿主不会把用户已配置的其他模型
密钥传给插件。

Provider 的基本结构如下：

```java
public final class HelloProvider implements PluginLlmProvider {

    @Override
    public String getProviderId() {
        return "hello-provider";
    }

    @Override
    public String getDisplayName() {
        return "Hello provider";
    }

    @Override
    public List<PluginLlmModel> getModels() {
        return List.of(new PluginLlmModel(
            "hello-small",
            "Hello Small",
            32_000,
            false
        ));
    }

    @Override
    public CompletableFuture<PluginLlmResponse> executeAsync(
        PluginLlmRequest request,
        Consumer<String> onTextDelta
    ) {
        return CompletableFuture.supplyAsync(() -> {
            String answer = "Hello from the provider";
            if (request.isStreamEnabled()) {
                onTextDelta.accept("Hello ");
                onTextDelta.accept("from the provider");
            }
            return new PluginLlmResponse(
                answer,
                "[]",
                "stop",
                0,
                0
            );
        });
    }
}
```

## 13. 运行时代理

运行时代理处理的是目标 APK 的运行差异。

`RuntimeProxyPlugin` 只负责返回启动策略。策略直接使用目标包名和 `versionCode`；
直接传 `long` 时是精确匹配，需要兼容升级时可改用 `RuntimeVersionMatcher`。

```java
public final class HelloRuntimePlugin implements RuntimeProxyPlugin {

    @Override
    public PluginDescriptor getDescriptor() {
        return DESCRIPTOR;
    }

    @Override
    public List<RuntimeLaunchPolicy> launchPolicies() {
        return List.of(new RuntimeLaunchPolicy(
            "hello-host-fallback",
            "com.example.target",
            12345L,
            RuntimeLaunchMode.HOST_FALLBACK,
            "Use the installed host application for this target version"
        ));
    }
}
```

版本模糊匹配示例：

```java
new RuntimeLaunchPolicy(
    "hello-container",
    "com.example.target",
    RuntimeVersionMatcher.glob("74*"),
    RuntimeLaunchMode.CONTAINER,
    "Match the 74xxxx version series"
);
```

支持的匹配方式：

- `RuntimeVersionMatcher.exact(code)`：精确匹配；
- `RuntimeVersionMatcher.glob("74*")`：十进制 `versionCode` 通配符匹配；
- `RuntimeVersionMatcher.prefix("74")`：按十进制前缀匹配；
- `RuntimeVersionMatcher.range(min, max)`：闭区间匹配；
- `RuntimeVersionMatcher.anyVersion()`：忽略版本，仅依赖包名和类名条件。

`CONTAINER` 继续普通容器路径，`HOST_FALLBACK` 启动宿主已安装的对应应用，`BLOCKED`
拒绝启动。同一包名下相互重叠的版本策略只能由一个插件声明，冲突会被宿主拒绝。

| 模式 | 行为 | 适用情况 |
| --- | --- | --- |
| `CONTAINER` | 继续虚拟容器 | 普通应用或插件只做辅助 Hook |
| `HOST_FALLBACK` | 启动宿主已安装的同包名应用 | 容器路径确定不可用 |
| `BLOCKED` | 拒绝启动并返回原因 | 明确不安全或不支持 |

如果需要在目标 Application attach、创建、目标 Activity 启动前安装 Hook，插件还要
依赖 `runtime-plugin-api`，并在 `onAttach` 中注册
`RuntimeCompatibilityProfileResolver`：

```java
context = host;

RuntimeCompatibilityProfiles.register(
    PLUGIN_ID,
    (spec, loader) -> ExampleRuntimeProfile.resolve(spec, loader)
);
```

对应地在 `onDetach` 中反注册。Profile 可以检查包名和版本号，使用
`RuntimeApkClassIndex.classNames(spec.getApkPaths())` 直接校验目标 DEX 类名，并通过
`RuntimePluginRuntime.withContainerClassLoader` 在正确的 ClassLoader scope 中执行 Hook。

一个最小 Profile 骨架如下：

```java
public final class ExampleRuntimeProfile
    implements RuntimeCompatibilityProfile {

    public static RuntimeCompatibilityProfile resolve(
        RuntimeCompatLaunchSpec spec,
        ClassLoader loader
    ) {
        if (!TARGET_PACKAGE_NAME.equals(spec.getPackageName()) ||
            !TARGET_VERSION_MATCHER.matches(spec.getVersionCode())) {
            return null;
        }
        Set<String> classes = new HashSet<>(RuntimeApkClassIndex.classNames(
            spec.getApkPaths()
        ));
        return classes.containsAll(REQUIRED_CLASS_NAMES)
            ? new ExampleRuntimeProfile()
            : null;
    }

    @Override
    public String getId() {
        return "example-runtime-profile";
    }

    @Override
    public void afterApplicationCreated(
        RuntimeCompatLaunchSpec spec,
        ClassLoader loader,
        Context context,
        Application application
    ) {
        // 只在目标包名、版本和完整类名列表都匹配后执行。
    }
}
```

插件可以使用可直接阅读的完整包名和类名作为匹配条件，也可以通过构建步骤生成这些
常量。通用 Tinker、AppLike、Application attach 和系统服务代理仍然属于
`runtime-core`/`runtime-compat`，不能因为单个应用适配而修改核心分支。

## 14. 调试开发中的插件

源码插件最方便的调试方法是直接运行宿主。修改 Java 后执行 Run 或 Apply Changes，
打开插件中心，长按插件检查设置和详情。Logcat 中优先查看
`PluginSourceLoader`、`PluginInstaller` 和 `AndroidRuntime`。

插件中心长按菜单包含启用/停用、设置、重命名、分享和导出。停用后插件仍保留在网格中，
但图标变暗并显示“停用”角标；再次长按即可启用。第三方安装包还支持删除。源码插件的
分享和导出使用 Debug 构建自动生成的 `.nestplugin`，因此不需要开发者先手动打包。
删除第三方插件会清理插件包、私有存储、缓存、SQLite、设置和密钥密文。

系统内置模块仍在宿主内部按设置运行，但不会显示为插件中心列表项。它们不是可删除的
第三方插件，也不会出现在源码插件的分享流程中。

常见问题通常来自四处。入口类找不到，说明 Manifest 类名、Java 包名或 keep rules
不一致。Host API 抛 `UnsupportedOperationException`，说明 Manifest 没声明对应
Capability。网络 Host 被拒绝，说明 `host_permissions` 缺失。设置页打不开，通常是
`settings` 权限或 `PluginSettingsViewProvider` 没有注册。

| 症状 | 优先检查 |
| --- | --- |
| 插件中心没有图标 | 插件 ID、Manifest 路径、源码模块是否被 Debug 构建发现 |
| `ClassNotFoundException` | `entry_class`、Java 包名、consumer keep rules |
| 工具没有出现在 Agent 中 | `agent_tools` 权限、`AgentToolProvider`、工具 Schema |
| Host API 抛异常 | 对应 Capability 是否在 Manifest 声明 |
| 网络请求被拒绝 | `host_permissions`、HTTP/WebSocket 对应权限 |
| 设置页打开后显示错误 | Provider 注册、`settings` 权限、View 创建异常 |
| 重启后提示恢复失败 | 安装包 Manifest、DEX、入口类和手机存储目录 |
| 插件分享包无法安装 | 是否包含 `manifest.json` 和有效 `classes.dex` |

自定义 View 的问题需要额外检查线程和生命周期。View 必须在 UI 线程创建，后台数据回调
必须切回主线程。页面关闭后不能继续使用保存的 Activity Context。工具 View 不能依赖
只在第一次创建时存在的状态，因为列表滚动和重新组合可能重新创建它。

运行时代理的问题应先确认包名和版本是否命中，再检查完整类名列表。类名命中但版本号
不匹配时，Profile 应返回 `null`，而不是强行安装 Hook。只有目标确实存在运行时差异时
才增加代理逻辑，不能把正常应用也引入全局 Hook。

## 15. 分享、导出和发布

开发阶段不需要手工生成安装包。插件中心的“分享”会创建一个临时
`<plugin-id>-<version>.nestplugin`，通过系统分享面板交给其他应用。“导出”会让用户
选择保存位置。两者都要求包内有 `manifest.json` 和 `classes.dex`。

如果插件需要走独立构建流程，示例模块包含 `packagePlugin` 任务：

```bash
./gradlew :dev-plugin-hello:packagePlugin
```

复制示例后要修改 `archiveFileName`，避免仍输出 `runtime-demo-plugin.nestplugin`。
正式发布前还要确定插件签名、版本升级、回滚和权限确认策略。当前安装器是开发版，
发布者签名、信任等级和插件进程隔离尚未完成。

安装包应保持小而有边界。不要依赖复制源码中的绝对路径，不要把调试密钥写进
`plugin-assets`，也不要把仅供开发使用的测试 Host 或测试指纹带进正式包。

## 16. 提交前的设计检查

一个可维护的插件通常具备清晰的命名空间、最少的能力声明和明确的 detach 清理逻辑。
工具 ID、命令 ID、设置 key、事件类型和缓存 key 都应加业务前缀。网络凭据从
`secrets()` 读取，用户文件通过选择器读取，不再自行猜测路径。

插件错误应被局部处理，不能让一个 Hook 异常破坏整个 Agent 回合。耗时调用使用 Host
任务或受控异步执行器，网络和 WebSocket 在页面关闭或 detach 时释放。自定义 View
需要适配深色主题、屏幕旋转和字体缩放，设置 View 需要能滚动，保存后要从同一个
`settings()` 命名空间重新读取。

涉及运行时代理时，先证明问题确实来自目标运行时。能用通用容器能力解决的内容放回
容器层；只有目标包名、版本、类名或运行时结构可以决定的行为才放进插件。这样插件才是
可插拔的扩展，而不是另一套主程序。

第一次实现插件时，建议先复制
[example-plugin](https://github.com/chuccp/NestSpace/tree/main/example-plugin)
并跑通源码直开，再逐步删除不需要的能力。示例已经包含资源隔离、自动分享包、
consumer keep rules、自定义设置 View、工具 View 和运行时代理入口。

接口细节、Capability 全表和安装器限制见
[插件平台与 Java SDK](plugin-center.md)。公共 API 源码位于
[plugin-api](https://github.com/chuccp/NestSpace/tree/main/plugin-api/src/main/java/com/coke/nestspace/plugin)，
运行时代理 API 位于
[runtime-plugin-api](https://github.com/chuccp/NestSpace/tree/main/runtime-plugin-api/src/main/java/com/coke/nestspace/runtime/plugin)。

## 附录 A：插件接口索引

| 接口 | 主要用途 | 需要的权限 |
| --- | --- | --- |
| `PluginLifecycle` | 初始化和释放插件 | 无 |
| `AgentHookPlugin` | 提示词和直接问答 | `agent_prompt`、`agent_question` |
| `AgentModelHookPlugin` | 模型请求检查 | `agent_model_request` |
| `AgentToolProvider` | 提供工具 | `agent_tools` |
| `AgentToolHookPlugin` | 工具前后 Hook | `agent_tool_hooks` |
| `AgentToolPresentationPlugin` | 工具结果 View | `agent_tool_presentation` |
| `PluginSettingsViewProvider` | 自定义设置页 | `settings` |
| `PluginCommandProvider` | 命令 | `commands` |
| `PluginUiProvider` | 页面和 Renderer | `ui_pages`、`ui_renderers` |
| `PluginLlmProvider` | 模型 Provider | `llm_providers` |
| `RuntimeProxyPlugin` | 启动策略 | `runtime_launch_policy` |
| `RuntimeProxyExtensionProvider` | 运行时扩展描述 | `runtime_proxy` |

可以只实现其中一个接口，也可以在一个入口类中组合多个接口。组合时应保证所有接口使用
同一个 `PluginDescriptor` 和插件 ID。

## 附录 B：示例地图

公开示例位于：

```text
example-plugin/
├── manifest.json
├── README.md
└── plugin/
    ├── build.gradle.kts
    ├── consumer-rules.pro
    └── src/main/
        ├── java/com/example/runtimedemo/RuntimeDemoPlugin.java
        └── plugin-assets/
            ├── manifest.json
            ├── icons/
            ├── pages/
            └── contributions/
```

各文件的学习重点：

| 文件 | 内容 |
| --- | --- |
| `plugin/build.gradle.kts` | 源码直开、资源隔离、自动分享包、DEX 打包 |
| `RuntimeDemoPlugin.java` | 生命周期、工具、Hook、View、设置、Host API、运行时代理 |
| `plugin-assets/manifest.json` | 完整 Manifest、权限、Host 白名单和贡献入口 |
| `contributions/tools.json` | 工具声明示例 |
| `contributions/commands.json` | 命令声明示例 |
| `contributions/settings.schema.json` | 设置 Schema 示例 |
| `contributions/llm.json` | LLM Provider 声明示例 |
| `pages/detail.html` | 插件详情 HTML |
| `pages/overview.json` | 声明式页面示例 |

完整源码：
[example-plugin/plugin](https://github.com/chuccp/NestSpace/tree/main/example-plugin/plugin)。

## 附录 C：提交检查清单

Manifest 与构建：

- [ ] 插件 ID 唯一，未与源码插件或外部安装包冲突。
- [ ] `entry_class`、Gradle namespace 和 Java 包名一致。
- [ ] 声明的 Capability 与实际使用一致。
- [ ] 网络 Host 全部写入 `host_permissions`。
- [ ] 图标、页面和 contribution 路径相对于插件资源目录。
- [ ] consumer keep rules 保留了入口类和全部插件接口实现。

Agent 与 Hook：

- [ ] 工具 Schema 是合法 JSON，工具 ID 在插件内唯一。
- [ ] `mutating` 与工具实际副作用一致。
- [ ] Hook 超时不会阻塞 Agent。
- [ ] after-tool Hook 不修改模型结果。
- [ ] 工具展示失败时，文本结果仍能正常显示。

界面：

- [ ] 设置 View 可以在手机上滚动和操作。
- [ ] 工具 View 在深色主题、屏幕旋转和字体缩放下可读。
- [ ] 页面关闭后不再使用保存的 Activity Context。
- [ ] HTML 页面不依赖外部网络资源。
- [ ] 设置保存后，插件从同一命名空间读取新值。

网络与存储：

- [ ] HTTP、SSE 和 WebSocket 在 detach 时关闭。
- [ ] 密钥从 `secrets()` 读取，不写入日志。
- [ ] 缓存只保存可重建的数据。
- [ ] SQLite 查询和事务在后台线程执行。
- [ ] 文件路径全部是插件私有相对路径。

运行时代理：

- [ ] 启动策略使用目标包名和精确或明确的模糊 `versionCode`。
- [ ] Profile 同时校验包名、版本匹配器和完整类名列表。
- [ ] 不匹配时返回 `null`。

发布：

- [ ] 版本号已更新。
- [ ] 分享包和导出包都包含 `manifest.json` 与 `classes.dex`。
- [ ] 调试 Host、测试密钥和测试目标版本没有进入正式包。
- [ ] 安装、重启恢复、卸载和重新安装都已验证。
- [ ] 发布签名和信任策略已确认。

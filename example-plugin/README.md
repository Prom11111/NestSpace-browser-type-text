# NestSpace Runtime Demo Plugin

这是一个功能较完整的插件示例。

覆盖能力：

- Agent Prompt / Question；
- Agent Tool 和 Tool Hook；
- 自定义 Android View；
- 插件命令、声明式页面和 Renderer；
- 自定义 Android 设置 View、Keystore 密钥、缓存和私有 SQLite；
- 通知动作、剪贴板、系统分享；
- 托管任务、事件；
- 文件选择与导出；
- HTTP、SSE、WebSocket；
- LLM Provider；
- 运行时代理测试策略。

## 直接拉源码开发（推荐）

不需要先生成 `.nestplugin`。NestSpace 在 Debug 构建中会自动发现：

```bash
example-plugin/plugin
dev-plugins/*
```

直接打开 NestSpace 根工程，修改
`example-plugin/plugin/src/main/java` 或 `src/main/plugin-assets`，然后执行
Android Studio 的 Run / Apply Changes。也可以在命令行运行：

```bash
./gradlew :app:installDebug
```

插件会出现在“插件中心 → 已安装 → 源码开发插件”。这条路径不生成
`.nestplugin`，不执行 DEX 工具，也不使用 `DexClassLoader`。

源码插件的 assets 会自动隔离到：

```text
assets/plugins-dev/com.example.runtime.demo/
```

因此多个插件可以同时包含同名 `manifest.json`、`icons/` 和 `pages/`。Debug
启动时，宿主先加载源码插件，再恢复外部 `.nestplugin`；同 ID 的外部包会被遮蔽，
避免重复注册和目标启动策略冲突。

开发自己的插件时，复制 `example-plugin/plugin` 到 `dev-plugins/my-plugin`，
然后修改 Manifest ID、Gradle namespace、Java 包名和入口类。完整说明见
[`dev-plugins/README.md`](../dev-plugins/README.md)。

## 独立分发

只有需要把插件交给未包含源码的 NestSpace 安装时，才构建安装包：

```bash
./gradlew :dev-plugin-runtime-demo:packagePlugin
```

产物：

```text
build/plugin-packages/runtime-demo-plugin.nestplugin
```

在 NestSpace 桌面打开“插件中心 → 从文件安装”即可安装。

## 运行时代理

示例使用全零测试指纹，只返回 `CONTAINER` 策略，不包含目标应用逻辑。

运行时代理可以使用稳定的 `plugin-api` 和 `runtime-api`，也可以按需要提供
目标相关的兼容策略。

目标专用运行时代理只依赖稳定的 `plugin-api` 和 `runtime-api`。源码模式
下这两个模块由宿主直接提供，插件开发者无需额外打包 SDK。

## 源码插件分享

Debug 构建会自动把源码插件编译成一份仅供分享/导出的 `.nestplugin` 资源，
同时运行时仍然直接加载源码 ClassLoader。长按源码插件图标即可分享或导出，
不需要开发者手动执行 `packagePlugin`。

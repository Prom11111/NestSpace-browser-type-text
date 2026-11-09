# 运行时模块边界

NestSpace 的运行时由通用容器、Java-first 公共 SDK、运行时代理 API、控制面和 Debug
源码插件组成。边界的目标是让 Agent 扩展、目标兼容和主机 UI 使用稳定契约，而不是互相
直接调用内部实现。

```text
:app
├── :plugin-api
├── :runtime-plugin-api
├── :runtime-api
├── :runtime-core
└── :runtime-compat

:dev-plugin-runtime-demo -> example-plugin/plugin
:dev-plugin-<name>       -> dev-plugins/<name>
```

依赖方向：

```text
:runtime-compat ──> :runtime-core ──> :runtime-api
                                  ├──> :plugin-api
                                  └──> :runtime-plugin-api

:app ──> :plugin-api
     ├─> :runtime-plugin-api
     ├─> :runtime-api
     ├─> :runtime-core
     └─> :runtime-compat
```

`plugin-api` 是对外 Java SDK。它包含 Manifest、Descriptor、Capability、Agent Hook、
工具、设置 View、Host API、页面、LLM Provider 和启动策略。它不依赖 Compose、Kotlin
运行时或 `app` 内部类，Kotlin 实现也可以使用同一套 Java 接口。

`runtime-plugin-api` 是目标运行时代理的稳定 Java 边界。它向插件提供只读启动描述、
Application/Activity 生命周期 Hook、APK 类名索引和受控 ClassLoader scope。插件可以
使用版本号和不透明类名哈希确定 Profile，但通用 Tinker/AppLike/Application attach 逻辑
仍属于运行时核心。

`runtime-api` 保存容器引擎共享契约、数据模型、Application factory 注册表和宿主 hook。
`runtime-core` 负责 APK 与实例存储、Session、Activity Host、进程槽位、Provider、
生命周期、Native Facade 和 MCP UI。`runtime-compat` 负责通用目标 Application 启动链、
兼容 Application factory 和 Tinker/AppLike 解析。

`app` 是控制面，包含 Compose UI、Agent、插件中心、设置 View 宿主、安装器以及 Host API
适配。它通过 `PluginRuntimeManager` 注册插件，不在 Agent、UI 或容器代码中写目标专用
分支。

Debug 构建通过 `debugImplementation` 自动加入 `example-plugin/plugin` 和
`dev-plugins/*`。插件资源生成到 `assets/plugins-dev/<plugin-id>/`，启动时由
`PluginSourceLoader` 扫描并通过宿主 ClassLoader 实例化。Debug 构建还会生成插件分享
资产，但运行时不使用 `.nestplugin`，也不生成额外 DEX。

安装包插件由 `PluginPackageInstaller` 解压、校验 manifest 和大小限制，再通过
`DexClassLoader` 加载。源码插件优先于同 ID 的外部安装包，避免开发和旧安装包重复注册。

目标指纹、版本号、类名哈希和专用 Profile 可以由独立插件模块维护。

相关文档：

- [插件编写指南](plugin-authoring.md)
- [插件平台与 Java SDK](plugin-center.md)
- [架构与限制](architecture.md)

# 运行时模块边界

NestSpace 现在只保留两个 Gradle 模块：

```text
:app
└── :plugin-api

:dev-plugin-runtime-demo -> example-plugin/plugin
:dev-plugin-<name>       -> dev-plugins/<name>
```

`app` 同时包含控制面和运行时实现：

- APK 与实例存储；
- Session、Activity Host、进程槽位和 Provider；
- Application factory、Tinker/AppLike 启动链；
- Native Facade、运行环境诊断和 MCP UI；
- Compose UI、Agent、插件中心和设置页。

`plugin-api` 是唯一对外插件 SDK，包含：

- Manifest、Descriptor、Capability 和 Plugin Lifecycle；
- Agent Hook、Tool、命令、设置、Host API 和 LLM Provider；
- `com.coke.nestspace.runtime.plugin` 下的 `RuntimeLaunchSpec`、`RuntimeProfile`、
  `RuntimeProfileResolver`、`RuntimeProfiles`、类名索引和 ClassLoader scope。

依赖方向只有：

```text
:app ──> :plugin-api
```

源码插件在 Debug 构建中通过 `debugImplementation` 自动加入 `example-plugin/plugin`
和 `dev-plugins/*`。插件资源生成到 `assets/plugins-dev/<plugin-id>/`，启动时由
`PluginSourceLoader` 扫描并通过宿主 ClassLoader 实例化。

安装包插件由 `PluginPackageInstaller` 解压、校验 Manifest 和大小限制，再通过
`DexClassLoader` 加载。源码插件优先于同 ID 的外部安装包，避免开发和旧安装包重复注册。

相关文档：

- [插件编写指南](plugin-authoring.md)
- [插件平台与 Java SDK](plugin-center.md)
- [架构与限制](architecture.md)

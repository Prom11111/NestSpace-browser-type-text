# Debug 源码插件目录

在 `NestSpace/dev-plugins/` 下新建一个 Android Library 目录后，Gradle 会自动
把它加入 Debug 宿主：

```text
dev-plugins/
└── my-plugin/
    ├── build.gradle.kts
    ├── consumer-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/...
        └── plugin-assets/
            ├── manifest.json
            ├── icons/
            └── pages/
```

目录名只决定 Gradle 项目名。插件身份由
`src/main/plugin-assets/manifest.json` 的 `id` 决定，插件入口类由
`entry_class` 决定。多个源码插件之间必须使用不同 ID。

最快的开始方式是复制现有示例：

```bash
cp -R example-plugin/plugin dev-plugins/my-plugin
```

复制后必须修改：

1. `manifest.json` 的 `id`、`name` 和 `entry_class`；
2. `build.gradle.kts` 的 `namespace`；
3. Java 包目录和入口类名；
4. 图标、页面及 contribution 文件中的应用信息。

源码插件只需要让 Android Studio 执行 Run 或 Apply Changes。它们随 Debug APK
编译，不生成、不安装 `.nestplugin`，也不需要先构建 `plugin-api` AAR。

模块的 `plugin-assets` 必须继续作为 assets 源。示例中的 Gradle 脚本会自动把
它映射到：

```text
assets/plugins-dev/<plugin-id>/
```

不要把 `manifest.json`、`icons/` 或 `pages/` 直接放到宿主 assets 根目录，否则
多个插件会发生资源覆盖。

源码插件仅进入 Debug 构建。Release 构建不会自动带上这些模块，独立分发时仍需
生成带签名的 `.nestplugin`。

Debug 构建还会自动生成一份源码插件的 `.nestplugin` 分享资产。插件中心长按源码
插件可以直接“分享”或“导出”，不需要手工运行打包任务。正式发布版本仍应使用
独立签名流程。

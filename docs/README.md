# NestSpace 使用手册

> 在 Android 上运行应用级虚拟空间的实验性开源项目。

[快速开始](getting-started.md) · [应用安装与管理](app-manager.md) · [常见问题](troubleshooting.md) · [GitHub](https://github.com/chuccp/NestSpace)

![NestSpace 桌面](screenshots/desktop.jpg)

## 为什么选择 NestSpace

- **不用开启无障碍服务**：界面观察、点击和输入由应用内部的通道直接完成。
- **不用 adb**：安装 APK、多开、整理桌面和使用 AI 助手都能直接在手机上完成。
- **不用 root**：像普通应用一样安装即可使用，不修改系统。
- **Android 版本要求宽松**：支持 Android 7.0（API 24）及以上，7.1、15、16、17 都已验证。

## 先了解这些限制

- **应用兼容性是最大变量**：不是所有 App 都能正常运行；加固、native SDK、热更新等都可能造成问题。这类问题可以逐个适配解决，遇到欢迎提 issue。
- **同时运行的虚拟应用有数量限制**：受手机内存和容器槽位限制，最多同时运行 16 个容器进程。默认自动管理，内存紧张时会关闭最久未使用的后台容器。

## 手册导航

### 使用

| 文档 | 内容 |
| --- | --- |
| [快速开始](getting-started.md) | 安装、第一次打开、导入应用、启动和多开 |
| [应用安装与管理](app-manager.md) | APK / split 安装、校验规则、实例与数据隔离、卸载 |
| [桌面与外观](desktop.md) | 桌面图标、文件夹、排序、壁纸、运行管理 |
| [设置详解](settings.md) | 空间设置里的每个入口和选项 |
| [AI 助手](assistant.md) | 模型配置、流式对话、语音助手、自动记忆/技能起草、定时任务 |
| [内置浏览器](browser.md) | 标签页、无头模式、下载进度与删除、上传、代理、转人工 |
| [MCP 控制服务](mcp.md) | 电脑连接、Token、局域网、CLI、外部 MCP |
| [常见问题](troubleshooting.md) | 安装失败、黑屏、后台被回收、连接失败等 |

### 参考

| 文档 | 内容 |
| --- | --- |
| [架构与限制](architecture.md) | 容器实现、进程模型和已知边界 |
| [单 APK 兼容运行时设计](single-apk-android-compatibility-runtime.md) | 免 Root 条件下的统一环境、Native Facade 和分阶段改造方案 |
| [插件平台与 Java SDK](plugin-center.md) | Java SDK、Agent Tools/Hook、文件与密钥、任务/事件、UI、LLM Provider、运行指纹和插件中心计划 |
| [插件编写指南](plugin-authoring.md) | 从创建源码插件到 Manifest、Java API、设置 View、Host API、运行时代理、调试和发布 |
| [整体性能审计与修改建议](performance-audit-and-improvement-plan.md) | 容器启动、Hook、进程池、截图和 target 热路径的性能优化路线 |
| [MCP 工具参考](mcp-tools.md) | 全部 MCP 工具和参数 |
| [App / 网站知识包](knowledge-packs.md) | 知识包的格式、匹配和新增方法 |
| [从源码构建](building.md) | 环境要求、构建命令和 CI |
| [发布更新日志](releases/README.md) | Release 文件格式、发布前检查和自动发布流程 |
| [nestspace-mcp CLI](cli.md) | 命令行客户端的安装和使用 |
| [技术设计](android-virtual-space-agent-mcp.md) | 早期的完整技术方案（开发参考） |

## 约定

- 文档中的「空间设置」指桌面上的「空间设置」图标。
- 空间设置首页保留常用入口，下方按「应用与容器」「AI 助手」「系统与网络」分类；较少使用的功能放在分类的第三层。
- 「导入应用」会把宿主已安装的 APK 安装到虚拟空间，不再创建系统应用快捷方式。
- AI 助手的全屏与无头小窗、模型配置和浏览器行为以当前应用界面为准。
- NestSpace 是实验性项目，应用兼容性和同时运行数量都有边界，详见[架构与限制](architecture.md)。

## License

[Apache License 2.0](https://github.com/chuccp/NestSpace/blob/main/LICENSE)

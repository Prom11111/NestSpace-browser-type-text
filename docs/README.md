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
| [内置浏览器](browser.md) | 标签页、无头模式、下载上传、代理、转人工 |
| [MCP 控制服务](mcp.md) | 电脑连接、Token、局域网、CLI、外部 MCP |
| [常见问题](troubleshooting.md) | 安装失败、黑屏、后台被回收、连接失败等 |

### 参考

| 文档 | 内容 |
| --- | --- |
| [架构与限制](architecture.md) | 容器实现、进程模型和已知边界 |
| [MCP 工具参考](mcp-tools.md) | 全部 MCP 工具和参数 |
| [App / 网站知识包](knowledge-packs.md) | 知识包的格式、匹配和新增方法 |
| [从源码构建](building.md) | 环境要求、构建命令和 CI |
| [nestspace-mcp CLI](cli.md) | 命令行客户端的安装和使用 |
| [技术设计](android-virtual-space-agent-mcp.md) | 早期的完整技术方案（开发参考） |
| [历史方案归档](https://github.com/chuccp/NestSpace/tree/main/docs/archive) | 已被取代的设计稿 |

## 约定

- 文档中的「空间设置」指桌面上的「空间设置」图标。
- 「应用管理」「模型设置」等都是空间设置里的二级页面。
- NestSpace 是实验性项目，应用兼容性和同时运行数量都有边界，详见[架构与限制](architecture.md)。

## License

[Apache License 2.0](https://github.com/chuccp/NestSpace/blob/main/LICENSE)

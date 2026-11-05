# NestSpace

[![Build APK](https://github.com/chuccp/NestSpace/actions/workflows/build.yml/badge.svg)](https://github.com/chuccp/NestSpace/actions/workflows/build.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Docs](https://img.shields.io/badge/docs-online-blue.svg)](https://chuccp.github.io/NestSpace/)
![Android](https://img.shields.io/badge/Android-7.0%2B-3DDC84?logo=android&logoColor=white)

**在 Android 上运行应用级虚拟空间的实验性开源项目。**

NestSpace 可以把第三方 APK 安装到应用内部，创建多个相互隔离的实例，并在自带桌面上启动、整理它们。内置的 AI 助手能够观察和操作虚拟应用；NestSpace 同时对外提供 MCP 控制服务，让 Claude Code 等外部客户端从电脑上驱动设备。

> ⚠️ NestSpace 是实验性项目，建议先安装自己熟悉的 App，方便判断兼容性问题。

## 为什么选择 NestSpace

- **不用开启无障碍服务**：界面观察、点击和输入由应用内部的通道直接完成，不需要无障碍权限。
- **不用 adb**：安装 APK、多开、整理桌面和使用 AI 助手都能直接在手机上完成。连接电脑只是可选功能。
- **不用 root**：像普通应用一样安装即可使用，不修改系统。
- **对 Android 版本要求宽松**：支持 Android 7.0（API 24）及以上，Android 7.1、15、16、17 都已验证。

## 先了解这些限制

- **应用兼容性是最大变量**：不是所有 App 都能正常运行。加固、自带 native SDK、热更新等都可能造成问题；这类问题可以通过逐个适配解决，遇到欢迎提 issue。
- **同时运行的虚拟应用有数量限制**：受手机内存和容器槽位限制，最多同时运行 16 个容器进程。默认自动管理，内存紧张时会关闭最久未使用的后台容器；前台正在使用的容器不会被关闭。

## 截图

| 桌面 | AI 助手 |
| --- | --- |
| ![NestSpace 桌面](docs/screenshots/desktop.jpg) | ![AI 助手欢迎页](docs/screenshots/assistant.jpg) |

| 应用管理与多开 | 记忆与技能 |
| --- | --- |
| ![应用管理与多开](docs/screenshots/app-manager.jpg) | ![记忆与技能](docs/screenshots/memory-skills.jpg) |

## 使用说明

### 1. 安装并打开

安装 `app-debug.apk`（可以从 Releases 下载，或按下方「构建」自行打包）。打开后进入 NestSpace 桌面，桌面上有「空间设置」「AI助手」「浏览器」「运行管理」等入口。

### 2. 把系统应用放上桌面

点「空间设置」→「应用管理」→「导入桌面应用」，勾选想用的系统应用并确认。选择会保存，下次打开仍然有效。虚拟应用安装后会自动出现在桌面。

### 3. 安装一个 APK

点「空间设置」→「应用管理」→「导入 APK」，从文件选择器中选中 APK。

- 普通应用只选一个 APK。
- Split 应用要一次选中 `base.apk` 和所有 split。
- 安装前会检查包名、签名和最低系统版本；安装完成后，图标会自动出现在桌面。

### 4. 启动与多开

在「空间设置 → 应用管理」的应用列表里，选择要用的「实例 N」，点「启动」。点「多开」会创建新的实例，每个实例的文件、缓存、数据库和登录状态互不影响，因此可以同时登录两个账号。

### 5. 整理桌面

- **长按应用**：打开、应用自带的快捷方式、移到最前/最后、应用信息；如果桌面已有文件夹，还会显示「添加到文件夹」。虚拟应用可以卸载，系统应用可以从桌面移除。
- **长按空白处**：新建文件夹、模型设置、权限级别。
- **拖动**：拖动图标调整顺序；拖到另一个应用上，小面积重叠时显示文件夹虚影，松手后创建文件夹；重叠达到 30% 以上时改为插入/移位。拖到已有文件夹上即可加入。
- **文件夹**：可以打开、重命名、添加应用或解散；打开后长按应用可移出，也可以向上拖动移出。只剩一个应用的文件夹会自动解散。
- **壁纸**：「空间设置 → 桌面壁纸」可以设置或清除。

### 6. 使用 AI 助手

点桌面上的「AI助手」。没有配置模型时，内置规则 Agent 可以执行「观察」「打开设置」「点击 搜索」这类简单指令；配置模型后可以执行更复杂的多步任务。回复以流式输出，工具调用会显示为可展开的卡片。

配置入口是「空间设置 → 模型设置」，支持：

- **Claude 兼容**：Base URL、API Key、模型。
- **OpenAI 兼容**：Base URL、API Key、模型。

填写后点「测试连接」即可验证。API Key 使用 Android Keystore 加密保存。助手固定在「虚拟空间 1」中操作，不会控制手机上的其他应用。

助手会在对话后自动整理记忆，并从成功的多步骤任务中起草技能。事实和偏好直接生效；可复用的做法在标准权限下直接生效，严格权限下会先显示确认卡片。自动起草的技能默认停用，需要手动启用。

想在任意界面唤起语音助手，可以在「空间设置 → 悬浮球」打开悬浮球并授予悬浮窗权限，然后点圆点说话；首次使用需要下载离线语音模型。

### 7. 卸载虚拟应用

长按桌面上的应用图标选择「卸载」，或在「空间设置 → 应用管理」的应用行里点「卸载」。卸载会删除 APK 和该应用的全部实例数据。

## 功能一览

- **虚拟空间**：导入单 APK 或 base + split，应用级安装和启动。
- **多开与隔离**：同一应用可创建多个实例，数据目录彼此独立。
- **桌面**：系统应用按需显示，支持文件夹、拖动排序、壁纸和布局保存。
- **AI 助手**：Claude / OpenAI 兼容接口，流式回复；可用自然语言观察和操作虚拟应用。
- **自动记忆与技能**：对话后自动整理偏好和做法；从成功的多步骤任务起草技能，并跟踪使用结果。
- **内置浏览器**：多标签、后台无头标签、页面阅读、下载上传和代理。
- **电脑控制（可选）**：内置 MCP 服务，Claude Code 等客户端可以远程调用容器工具。
- **知识与技能**：内置 23 个 App、19 个网站的中文使用指南和 7 个技能包。

更细的兼容能力（Activity/Service/Provider/广播/PackageManager/通知/JobScheduler 等）和实现方式见 [架构与限制](docs/architecture.md)。

## 连接电脑（可选）

日常使用不需要电脑。如果希望 Claude Code 等 MCP 客户端远程控制 NestSpace，可以这样连接：

1. 在「空间设置 → MCP 控制服务」查看 Token，并确认服务已启动。
2. USB 连接（推荐）：

```bash
adb forward tcp:8765 tcp:8765

claude mcp add --transport http space http://127.0.0.1:8765/mcp \
  --header "Authorization: Bearer <token>"
```

3. 也可以打开「局域网访问」，电脑与手机连同一 Wi-Fi 后直接使用 `http://<手机 IP>:8765/mcp`，无需 adb。局域网模式是 HTTP + Token，不加密，只应在可信网络开启。

如果要在不打开 NestSpace 界面的情况下由 Agent 启动容器，需要授予**悬浮窗权限**：应用用一个 1×1 透明、不可聚焦的窗口获得后台 Activity 启动豁免。未授权时 `space_launch` 返回 `LAUNCH_BLOCKED`。

也可以使用仓库自带 CLI：

```bash
export NESTSPACE_MCP_URL=http://127.0.0.1:8765/mcp
export NESTSPACE_MCP_TOKEN='<token>'

tools/nestspace-mcp health
tools/nestspace-mcp tools
tools/nestspace-mcp call browser_tabs
tools/nestspace-mcp call browser_open '{"url":"https://example.com"}'
tools/nestspace-mcp shell
```

完整工具列表见 [docs/mcp-tools.md](docs/mcp-tools.md)，CLI 用法见 [tools/README.md](tools/README.md)。

## 构建

需要 JDK 21、Android SDK Platform 37.0 和 Build-Tools 36.0.0。

```bash
./gradlew :app:assembleDebug
./gradlew :testapp:assembleDebug

# 可选：用于验证 REQUIRE_SECURE_ENV 拒绝逻辑
./gradlew :testapp:assembleSecure
```

产物：

```text
app/build/outputs/apk/debug/app-debug.apk
testapp/build/outputs/apk/debug/testapp-debug.apk
testapp/build/outputs/apk/secure/testapp-secure.apk
```

`testapp` 是用于验证容器能力的示例 APK，包含多 Activity、Service、Provider、广播和 Job 测试入口，可以像普通 APK 一样导入。

## 项目结构

```text
app/       Android 主应用：容器、桌面、AI 助手、浏览器、MCP
testapp/   验证容器能力的示例 APK
tools/     nestspace-mcp CLI（零依赖 Python）
docs/      架构、技术方案、MCP 工具参考和知识包文档
.github/   CI：单测、构建 APK，打 v* 标签时发布 Release
```

## 文档

在线手册：<https://chuccp.github.io/NestSpace/>

完整使用手册见 [docs/README.md](docs/README.md)。

- [快速开始](docs/getting-started.md)
- [应用安装与管理](docs/app-manager.md)
- [桌面与外观](docs/desktop.md)
- [设置详解](docs/settings.md)
- [AI 助手](docs/assistant.md)
- [内置浏览器](docs/browser.md)
- [MCP 控制服务](docs/mcp.md)
- [常见问题](docs/troubleshooting.md)
- [架构与限制](docs/architecture.md)
- [技术方案：应用级容器与 Agent/MCP](docs/android-virtual-space-agent-mcp.md)
- [MCP 工具参考](docs/mcp-tools.md)
- [App / 网站知识包](docs/knowledge-packs.md)
- [nestspace-mcp CLI](tools/README.md)
- [历史方案归档](docs/archive/)

## 参与贡献

欢迎提交 issue 和 pull request。提交前请至少跑一遍单元测试：

```bash
./gradlew :app:testDebugUnitTest
```

文档截图统一放在 `docs/screenshots/`，建议压缩到 540px 宽、单张 150KB 以内。

## License

[Apache License 2.0](LICENSE) © 2026 chuccp

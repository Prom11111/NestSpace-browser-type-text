# 应用安装与管理

入口：**空间设置 → 应用管理**。

![应用管理与多开](screenshots/app-manager.jpg)

这个页面分为三块：

1. **安装与导入**：导入 APK、导入桌面应用、测试 APK。
2. **虚拟应用**：已经安装到 NestSpace 内部的应用。
3. **桌面应用**：当前放在桌面上的系统应用。

## 导入 APK

1. 点「导入 APK」。
2. 在系统文件选择器里选择 APK。
3. 等待解析和安装完成。

普通应用选一个 APK 即可。split 应用需要一次选中所有文件：

```text
base.apk
split_config.arm64_v8a.apk
split_config.xxhdpi.apk
...
```

只选 base 会缺少代码、native 库或资源，应用可能在启动时失败。

导入过程中会做以下检查：

| 检查 | 失败时的行为 |
| --- | --- |
| 包名 | 拒绝安装 |
| 签名一致性 | 同一包名已有记录但签名不同，返回 `SIGNATURE_MISMATCH` |
| `minSdk` | 高于当前系统版本时拒绝安装 |
| `REQUIRE_SECURE_ENV` | 目标声明了安全环境要求时返回 `SECURE_ENV_REQUIRED` |
| ABI | 安装会成功，启动时若没有匹配的 native 库返回 `ABI_MISMATCH` |

安装成功后：

- APK 会复制到 NestSpace 的应用私有目录，不依赖原文件。
- 应用会出现在桌面和「虚拟应用」列表。
- 安装记录会持久化，重启应用后仍然存在。

## 虚拟应用列表

每个应用一行，包含：

- 应用图标、名称、包名和版本。
- 实例选择器（「实例 1」「实例 2」…）。
- 「启动」：打开当前选中的实例。
- 「多开」：创建一个新实例并启动。
- 「卸载」：删除 APK 和所有实例数据。

第一次启动某个应用时会自动创建「实例 1」。之后点「多开」会依次创建实例 2、3……

### 实例与数据隔离

每个实例拥有独立的：

- `files`
- `cache`
- `code_cache`
- `databases`
- `getDir`
- SharedPreferences 命名空间
- native 库提取目录

因此同一个应用可以同时登录两个账号，两个实例之间不会读到对方的数据。不同应用的实例之间也是隔离的。

数据目录结构：

```text
/data/user/0/com.coke.nestspace/files/vspace/
  apps/<package>/...         安装的 APK
  data/<package>/<instance>/ 实例数据
```

### 同时运行数量

容器进程池默认自动管理，最多 16 个槽位。内存紧张时会关闭最久未使用的后台容器，前台容器不会被关闭。也可以在「空间设置 → 容器槽位」里设置固定上限（1–16）。

## 导入桌面应用

「导入桌面应用」只影响桌面显示，不会复制或安装应用：

1. 点「导入桌面应用」。
2. 勾选需要的已安装应用。
3. 取消勾选即可从桌面移除。

虚拟应用不需要勾选，安装后会自动显示。

## 测试 APK

「测试 APK」是给开发者验证容器能力用的快捷入口。它不会打开文件选择器，而是读取外部文件目录里的固定文件：

```text
/sdcard/Android/data/com.coke.nestspace/files/stage-b-test.apk
```

把仓库构建出的 `testapp-debug.apk` 推到这个路径后再点「测试 APK」即可。普通用户可以直接用「导入 APK」选择任意 APK，不依赖这个入口。

testapp 包含多 Activity 跳转、Service、Provider、广播、Job、PackageManager 和实例隔离测试页面，适合用来确认容器功能是否正常。

## 卸载

卸载虚拟应用会删除：

- 已安装的 APK；
- 全部实例；
- 全部实例数据、缓存和数据库；
- 该应用在 NestSpace 命名空间下的 SharedPreferences。

此操作不可恢复。系统应用可以用桌面长按菜单里的「从桌面移除」隐藏，不会删除数据；虚拟应用目前没有单独的隐藏入口。

## 常见安装问题

### `SIGNATURE_MISMATCH`

同一个包名之前安装过不同签名的 APK。先卸载旧应用再安装，或者使用相同签名重新打包。

### `SECURE_ENV_REQUIRED`

目标应用在 manifest 里声明了 `REQUIRE_SECURE_ENV`。NestSpace 不是它要求的安全环境，目前会拒绝安装。

### `ABI_MISMATCH`

APK 只包含 arm64 或 armeabi-v7a 的 native 库，当前设备架构不匹配。换用对应架构的真机，或使用带对应 ABI 的安装包。

### 启动后黑屏或闪退

通常是应用兼容性问题，例如加固、native SDK、动态加载或依赖冲突。可以先运行「空间设置 → 容器自检」，再向项目提交 issue，附上应用版本和错误信息。

### 安装成功但桌面没有图标

进入「空间设置 → 应用管理」确认应用是否在「虚拟应用」列表里，并直接在那里启动。系统应用需要先在「导入桌面应用」里勾选。

## 相关文档

- [容器槽位与权限](settings.md)
- [架构与限制](architecture.md)
- [常见问题](troubleshooting.md)

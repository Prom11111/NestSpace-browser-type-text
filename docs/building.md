# 从源码构建

## 环境要求

- JDK 21。
- Android SDK Platform 37.0。
- Android SDK Build-Tools 36.0.0。
- Windows、Linux 或 macOS。

项目自带 Gradle Wrapper，不需要单独安装 Gradle。

## 构建 APK

在仓库根目录执行：

```bash
./gradlew :app:assembleDebug
./gradlew :testapp:assembleDebug
```

Windows PowerShell 可以使用：

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :testapp:assembleDebug
```

产物：

```text
app/build/outputs/apk/debug/app-debug.apk
testapp/build/outputs/apk/debug/testapp-debug.apk
```

## 可选构建

### REQUIRE_SECURE_ENV 测试包

```bash
./gradlew :testapp:assembleSecure
```

产物：

```text
testapp/build/outputs/apk/secure/testapp-secure.apk
```

### 单元测试

```bash
./gradlew :app:testDebugUnitTest
```

只跑知识包测试：

```bash
./gradlew :app:testDebugUnitTest --tests com.coke.nestspace.knowledge.KnowledgePackTest
```

## 版本号

CI 会从 `vX.Y.Z` 标签解析版本号：

- `versionName` 取标签去掉 `v` 后的部分；
- `versionCode` = major × 10000 + minor × 100 + patch。

本地构建默认使用 `versionCode=1`、`versionName=1.0`。也可以手动传入：

```bash
./gradlew :app:assembleDebug -PversionName=1.2.3 -PversionCode=10203
```

## 安装到设备

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

不需要 adb 时，也可以把 APK 传到手机后手动安装。

## CI

`.github/workflows/build.yml` 会在以下情况构建：

- 推送到 `main`；
- 新建或更新 pull request；
- 推送 `v*` 标签；
- 手动触发 `workflow_dispatch`。

流程包括：

1. 安装 JDK 21；
2. 安装 Android SDK；
3. 运行单元测试；
4. 构建 `app` 和 `testapp` 的 debug APK；
5. 上传 `nestspace-debug` 产物；
6. 如果是 `v*` 标签，创建 GitHub Release 并附带 APK。

## 常见构建问题

### `Permission denied: ./gradlew`

Windows 上提交的 `gradlew` 可能丢失可执行位。在 Git 中恢复：

```bash
git update-index --chmod=+x gradlew
```

### 找不到 Android SDK

确认 `local.properties` 中的 `sdk.dir` 指向本机 Android SDK，或在 Android Studio 中打开项目让它自动生成。

### SDK 包缺失

用 sdkmanager 安装：

```bash
sdkmanager "platform-tools" "platforms;android-37.0" "build-tools;36.0.0"
```

### Java 版本不匹配

确认 `JAVA_HOME` 指向 JDK 21：

```bash
java -version
```

## 相关文档

- [快速开始](getting-started.md)
- [应用安装与管理](app-manager.md)

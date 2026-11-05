# 從原始碼建置

## 環境需求

- JDK 21。
- Android SDK Platform 37.0。
- Android SDK Build-Tools 36.0.0。
- Windows、Linux 或 macOS。

專案自帶 Gradle Wrapper，不需要另外安裝 Gradle。

## 建置 APK

在倉庫根目錄執行：

```bash
./gradlew :app:assembleDebug
./gradlew :testapp:assembleDebug
```

Windows PowerShell 可以使用：

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :testapp:assembleDebug
```

產物：

```text
app/build/outputs/apk/debug/app-debug.apk
testapp/build/outputs/apk/debug/testapp-debug.apk
```

## 選用建置

### REQUIRE_SECURE_ENV 測試包

```bash
./gradlew :testapp:assembleSecure
```

產物：

```text
testapp/build/outputs/apk/secure/testapp-secure.apk
```

### 單元測試

```bash
./gradlew :app:testDebugUnitTest
```

只跑知識包測試：

```bash
./gradlew :app:testDebugUnitTest --tests com.coke.nestspace.knowledge.KnowledgePackTest
```

## 版本號

CI 會從 `vX.Y.Z` 標籤解析版本號：

- `versionName` 取標籤去掉 `v` 後的部分；
- `versionCode` = major × 10000 + minor × 100 + patch。

本機建置預設使用 `versionCode=1`、`versionName=1.0`。也可以手動傳入：

```bash
./gradlew :app:assembleDebug -PversionName=1.2.3 -PversionCode=10203
```

## 安裝到裝置

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

不需要 adb 時，也可以把 APK 傳到手機後手動安裝。

## CI

`.github/workflows/build.yml` 會在以下情況建置：

- 推送到 `main`；
- 新建或更新 pull request；
- 推送 `v*` 標籤；
- 手動觸發 `workflow_dispatch`。

流程包括：

1. 安裝 JDK 21；
2. 安裝 Android SDK；
3. 執行單元測試；
4. 建置 `app` 和 `testapp` 的 debug APK；
5. 上傳 `nestspace-debug` 產物；
6. 如果是 `v*` 標籤，建立 GitHub Release 並附上 APK。

## 常見建置問題

### `Permission denied: ./gradlew`

Windows 上提交到版本庫的 `gradlew` 可能遺失可執行權限。在 Git 中恢復：

```bash
git update-index --chmod=+x gradlew
```

### 找不到 Android SDK

確認 `local.properties` 中的 `sdk.dir` 指向本機 Android SDK，或在 Android Studio 中開啟專案讓它自動產生。

### SDK 套件缺失

用 sdkmanager 安裝：

```bash
sdkmanager "platform-tools" "platforms;android-37.0" "build-tools;36.0.0"
```

### Java 版本不相符

確認 `JAVA_HOME` 指向 JDK 21：

```bash
java -version
```

## 相關文件

- [快速開始](getting-started.md)
- [應用程式安裝與管理](app-manager.md)

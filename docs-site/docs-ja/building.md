# ソースからのビルド

## 環境要件

- JDK 21。
- Android SDK Platform 37.0。
- Android SDK Build-Tools 36.0.0。
- Windows、Linux、macOS。

プロジェクトには Gradle Wrapper が同梱されているため、Gradle を別途インストールする必要はありません。

## APK のビルド

リポジトリのルートで実行します：

```bash
./gradlew :app:assembleDebug
./gradlew :testapp:assembleDebug
```

Windows の PowerShell では次のコマンドを使用できます：

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :testapp:assembleDebug
```

出力物：

```text
app/build/outputs/apk/debug/app-debug.apk
testapp/build/outputs/apk/debug/testapp-debug.apk
```

## オプションのビルド

### REQUIRE_SECURE_ENV テストパッケージ

```bash
./gradlew :testapp:assembleSecure
```

出力物：

```text
testapp/build/outputs/apk/secure/testapp-secure.apk
```

### ユニットテスト

```bash
./gradlew :app:testDebugUnitTest
```

知識パックのテストのみを実行する場合：

```bash
./gradlew :app:testDebugUnitTest --tests com.coke.nestspace.knowledge.KnowledgePackTest
```

## バージョン番号

CI は `vX.Y.Z` タグからバージョン番号を解析します。

- `versionName` はタグから `v` を除いた部分。
- `versionCode` = major × 10000 + minor × 100 + patch。

ローカルビルドではデフォルトで `versionCode=1`、`versionName=1.0` が使用されます。手動で指定することもできます：

```bash
./gradlew :app:assembleDebug -PversionName=1.2.3 -PversionCode=10203
```

## デバイスへのインストール

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

adb を使わない場合は、APK をスマートフォンに転送して手動でインストールすることもできます。

## CI

`.github/workflows/build.yml` は次の場合にビルドします。

- `main` へのプッシュ。
- pull request の作成または更新。
- `v*` タグのプッシュ。
- `workflow_dispatch` の手動実行。

フローは次のとおりです。

1. JDK 21 をインストール。
2. Android SDK をインストール。
3. ユニットテストを実行。
4. `app` と `testapp` の debug APK をビルド。
5. `nestspace-debug` 成果物をアップロード。
6. `v*` タグの場合は、GitHub Release を作成し APK を添付。

## よくあるビルドの問題

### `Permission denied: ./gradlew`

Windows 上でコミットされた `gradlew` は実行ビットが失われている場合があります。Git で復元します：

```bash
git update-index --chmod=+x gradlew
```

### Android SDK が見つからない

`local.properties` の `sdk.dir` がローカルの Android SDK を指していることを確認するか、Android Studio でプロジェクトを開いて自動生成させます。

### SDK パッケージが見つからない

sdkmanager でインストールします：

```bash
sdkmanager "platform-tools" "platforms;android-37.0" "build-tools;36.0.0"
```

### Java バージョンが一致しない

`JAVA_HOME` が JDK 21 を指していることを確認します：

```bash
java -version
```

## 関連ドキュメント

- [クイックスタート](getting-started.md)
- [アプリのインストールと管理](app-manager.md)

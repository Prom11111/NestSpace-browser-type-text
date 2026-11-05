# Building from source

## Environment requirements

- JDK 21.
- Android SDK Platform 37.0.
- Android SDK Build-Tools 36.0.0.
- Windows, Linux, or macOS.

The project includes a Gradle Wrapper, so you do not need to install Gradle separately.

## Building the APK

Run the following in the repository root:

```bash
./gradlew :app:assembleDebug
./gradlew :testapp:assembleDebug
```

In Windows PowerShell, you can use:

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :testapp:assembleDebug
```

Outputs:

```text
app/build/outputs/apk/debug/app-debug.apk
testapp/build/outputs/apk/debug/testapp-debug.apk
```

## Optional builds

### REQUIRE_SECURE_ENV test package

```bash
./gradlew :testapp:assembleSecure
```

Output:

```text
testapp/build/outputs/apk/secure/testapp-secure.apk
```

### Unit tests

```bash
./gradlew :app:testDebugUnitTest
```

To run only the knowledge pack tests:

```bash
./gradlew :app:testDebugUnitTest --tests com.coke.nestspace.knowledge.KnowledgePackTest
```

## Version numbers

CI derives the version from `vX.Y.Z` tags:

- `versionName` is the tag with the leading `v` removed;
- `versionCode` = major × 10000 + minor × 100 + patch.

Local builds use `versionCode=1` and `versionName=1.0` by default. You can also pass them manually:

```bash
./gradlew :app:assembleDebug -PversionName=1.2.3 -PversionCode=10203
```

## Installing on a device

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

If you do not need adb, you can also transfer the APK to the phone and install it manually.

## CI

`.github/workflows/build.yml` builds in the following cases:

- A push to `main`;
- A new or updated pull request;
- A push of a `v*` tag;
- A manual `workflow_dispatch` trigger.

The workflow:

1. Installs JDK 21;
2. Installs the Android SDK;
3. Runs unit tests;
4. Builds debug APKs for `app` and `testapp`;
5. Uploads the `nestspace-debug` artifact;
6. If the tag is `v*`, creates a GitHub Release with the APK attached.

## Common build issues

### `Permission denied: ./gradlew`

A `gradlew` committed from Windows may lose its executable bit. Restore it in Git:

```bash
git update-index --chmod=+x gradlew
```

### Android SDK not found

Confirm that `sdk.dir` in `local.properties` points to the local Android SDK, or open the project in Android Studio and let it generate the file automatically.

### Missing SDK packages

Install them with sdkmanager:

```bash
sdkmanager "platform-tools" "platforms;android-37.0" "build-tools;36.0.0"
```

### Java version mismatch

Confirm that `JAVA_HOME` points to JDK 21:

```bash
java -version
```

## Related documentation

- [Quick start](getting-started.md)
- [App installation & management](app-manager.md)

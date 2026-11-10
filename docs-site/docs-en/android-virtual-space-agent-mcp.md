# NestSpace: An In-House App-Level Android Container + Dual-Channel Agent/MCP Technical Design

> This document is a technical design and implementation record for developers and secondary development. For everyday use, see the [NestSpace User Guide](README.md).
>
> Project: `NestSpace` (`com.coke.nestspace`, Jetpack Compose, minSdk 24 / targetSdk 37)
> Date: 2026-11-03
> Constraints: ordinary APK, no control over the ROM; **in-house container engine, does not reuse the commercial VirtualApp**; both the local Agent and remote MCP channels must be supported.
> Status: Stage A/B + Stage C-lite + Stage C-D (independent processes/crash isolation, Job/notification virtualization, host/system broadcast bridging, Binder/UID permission model, full VPMS, cross-virtual-app queries, permission granting, multi-instance .so process isolation) are implemented and verified on Android 7.1.1 / Android 16 / Android 17 (API 37 emulator) / MuMu (Android 15, ARM bridge); the local LLM Agent (Claude/OpenAI compatible, sharing the chat protocol) is implemented and has been verified over a live network with mocks and a third-party Anthropic-compatible endpoint; full background Job/Alarm wakeups and real UID spoofing on the system side remain future enhancements.

---

## Reading path

| What you want to read first | Jump to |
|---|---|
| Conclusions, feasibility boundaries, in-house constraints | **0** |
| Requirements and scope | 1 |
| Overall container architecture, process model, hook approach | 2 |
| Key subsystems (PMS/AMS/ClassLoader/Binder/IO) | 3 |
| How the Agent operates virtual apps | 4 |
| Dual-channel local Agent + remote MCP | 5 |
| Architecture, modules, interfaces, engineering changes | 6 |
| Build order, test matrix, risks | 7–9 |

---

## 0. Conclusions

### 0.1 Technical approach

- **In-house app-level container engine**: Load an uninstalled APK inside the host app, virtualize PackageManager/ActivityManager/Binder/IO, and make the target app believe it is running in a normal system.
- **Java-layer virtualization first, native hooks at critical points**: Start with Java hooks on `Instrumentation`/`ActivityThread`/`PackageManager`/`ContextImpl`; add native hooks only for hard points such as Binder/UID/native file paths.
- **Single-process MVP → multi-process product**: First bring up a simple APK in one process, then use the host-declared `:c1..:c16` process pool for multiple instances and target processes.
- **Dual-channel Agent/MCP**: Local Agent (in-app loop) + remote Agent (MCP), sharing one `ToolRegistry`, permissions, leases, and audit.
- **AppFunctions fast path (optional)**: The host app can export AppFunctions; AppFunctions in container APKs go through a local bridge; fall back to UI automation when unavailable.
- **No commercial VirtualApp license**. VirtualApp is only an architectural reference; the implementation is **clean-room and does not copy its GPL code**.

### 0.2 Three-layer architecture

```text
┌─────────────────────────────────────────────────────────────┐
│ 3. Agent / MCP layer                                        │
│    Local Agent (in-app loop)  |  Remote Agent (MCP)         │
│    Shared ToolRegistry / leases / approval / audit          │
├─────────────────────────────────────────────────────────────┤
│ 2. Control layer                                            │
│    ContainerService (spaces/install/launch)                 │
│    Shizuku / Accessibility / Vision (UI observation/control)│
├─────────────────────────────────────────────────────────────┤
│ 1. In-house container engine                                │
│    Installer / VPMS / VAMS / ClassLoader / Resources        │
│    Binder/UID / IO Redirect / Permissions / Process Pool    │
└─────────────────────────────────────────────────────────────┘
```

### 0.3 Feasibility boundaries (let's be honest first)

**What can be done:**

- Ordinary APKs, single-process, single-instance
- Multiple instances, independent data directories
- Content/tool/social apps without strong integrity checks
- Control virtual apps with Shizuku/accessibility/vision
- Dual-channel local Agent + remote MCP

**Difficult; needs incremental work:**

- Multi-process + Binder/UID virtualization
- split APK / AAB
- 16 KB pages / native libraries
- Notifications, JobScheduler, AlarmManager, ContentProvider
- Background Activity launch (Android 10+ restrictions)
- Target app signature verification / Keystore
- Hidden API changes in Android 15–17

**Not possible / not promised:**

- True UID/SELinux isolation (app-level containers share the host UID)
- Financial/banking/payment/Play Integrity/GMS-heavy apps
- Apps with strong anti-container/anti-hook/anti-debugging
- "Every app can run"

> The right goal for an in-house implementation is not to "replace the Android system" but to "run a controlled second space with limited compatibility inside an ordinary app." Product copy should be written within these boundaries.

### 0.4 Biggest technical risks

1. **Hidden API / ART / Binder version adaptation**: Every major Android release changes `ActivityThread`, `PackageParser`, `Resources`, and Binder transactions; there is no "write it once and stay stable forever."
2. **Binder/UID virtualization**: System services see the host UID; the Java layer can fool part of the app, but system-side permissions/AppOps/accounts/payments are still judged by the host UID. This is the fundamental limitation of an app-level container.
3. **Background Activity launch**: Android 10+ restricts background launches; a virtual app launched from the host's background will be blocked. It needs a foreground service/notification/user interaction to trigger.
4. **Notifications/Job/Provider**: Ownership is determined by package name/UID at the system level; virtualizing these is much harder than Activities, and many apps' background capabilities will degrade.
5. **Keystore/signatures/Play Integrity**: Keystore aliases conflict under the same UID; signature verification/integrity checks will most likely fail.
6. **Process model**: `app_process` child processes are killed by the phantom process killer; a host-declared `:c1..:c16` process pool is more stable, with explicit handling for process reuse, memory, and crash isolation.
7. **16 KB / split APK**: Both the installer and native library loading must be adapted.
8. **Long-term maintenance**: This is a "mini Android runtime," not an ordinary app feature; it requires continuous AOSP tracking.

### 0.5 In-house ≠ unconstrained

Writing it yourself avoids the VirtualApp license, but the following constraints still apply:

- **Clean-room / GPL**: Do not copy VirtualApp's GPL code; implement with reference to AOSP (Apache-2.0) and public documentation. If team members have read GPL code, code isolation/legal review is advisable.
- **Google Play container policy**: Container apps are allowed, but you must check the target app's `REQUIRE_SECURE_ENV` and must not proxy APIs for apps outside the container.
- **Target app ToS**: Automated operation, repackaging, and bypassing verification may violate third-party app terms.
- **Native hook library licenses**: Check each of ShadowHook/ByteHook/Dobby/xDL for license and commercial terms.
- **Hidden API restrictions**: Android 9+ restricts non-SDK interfaces; different versions require different bypass strategies, and stability is your responsibility.

---

## 1. Requirements and constraints

### 1.1 Goals

- Create multiple virtual spaces inside the app.
- Install APKs in a space, launch, stop, and run multiple instances.
- Let the local Agent operate apps in a space using natural language.
- Let a remote Agent operate the same space through MCP.
- Both channels behave consistently and do not interrupt each other.

### 1.2 Constraints

- Ordinary APK; no control over the ROM/system signing.
- In-house container engine; no dependency on the commercial VirtualApp.
- Android 12–17, arm64, preferably no root.
- Local Agent models can be on-device (Gemini Nano 4/Gemma 4) or cloud (Claude API).

### 1.3 Functional scope

- Space management: create, delete, rename, list
- App management: install APKs (including splits), uninstall, list, icon/name
- Runtime management: launch, stop, foreground switching, recent tasks
- Observation: UI tree, screenshots, current package name/Activity
- Actions: tap, long-press, swipe, scroll, input, back/home/recents
- Waiting/verification: wait for text, wait for package name, wait for UI idle
- MCP: remote Agent discovers/calls tools
- Local Agent: natural-language task → tool-call loop
- Sessions: leases, approvals, audit, traces
- Skills: recording, replay, parameterization

### 1.4 Non-functional requirements

- Single-operation p50 < 500 ms, p95 < 2 s (excluding model inference)
- Data isolation between virtual spaces (logical isolation)
- Observability for crash rate, ANR, and background survival
- Structured errors for installation/launch failures
- MCP binds to loopback by default; LAN must be explicitly enabled
- Local and remote Agents have consistent tool behavior

### 1.5 Out of scope (current version)

- Financial/banking/GMS/Play Integrity apps
- Strong isolation for untrusted APKs
- System-level spaces/multi-user/work profiles
- Cloud phones/remote physical devices
- Promise of "all-app compatibility"

---

## 2. Overall in-house container architecture

### 2.1 Design goals

1. The target app's `Activity` can be created, displayed, receive input, and go through the Activity lifecycle normally.
2. The target app believes it is installed: `PackageManager` can look up its own `PackageInfo`, permissions, and signatures.
3. The target app's private data lands in an independent directory and does not pollute the host.
4. Multiple instances can coexist.
5. A crash must not take down the host.
6. The control layer can observe/control the target app's UI.

### 2.2 Process model

**Stage A: single process (MVP)**

- Load the target APK in the host main process; the target Activity runs in the host process.
- Pros: simple to implement, easy to debug, no cross-process work.
- Cons: a target app crash takes down the host; multiple instances share one process; high memory pressure.
- Suitable for: feasibility validation, simple apps.

**Stage B: host-declared `:c1..:c16` process pool (production)**

- Declare `:c1..:c16` slot processes in the host `AndroidManifest.xml`, and declare a `StubActivity`/`VClientService` for each process.
- When launching a virtual app, select an idle process and start the `StubActivity` in that process; the system creates a normal app process and `ActivityThread`.
- Initialize the container hooks in that process, load the target APK, and run the target Activity.
- Pros: it is a normal app process, unaffected by the phantom process killer; crash isolation; multiple instances can run in parallel.
- Cons: fixed process pool size; memory/launch cost; process reuse requires state cleanup.

> Do not use `Runtime.exec("app_process ...")` to start child processes: Android 12+ phantom process killer will kill them, and permissions/process ownership are more complicated.

**Process allocation strategy:**

- One process per virtual space; or one process per instance; or dynamic allocation based on memory/foreground state.
- The process pool is sized automatically from device memory with a maximum of 16; fixed mode uses the user's selected value from 1 through 16.
- Foreground instances get exclusive priority; background instances can reuse processes.
- Mark a process dirty after a crash, clean it up, then reuse it.

### 2.3 Overall diagram

```text
┌──────────────────────────────────────────────────────────────────┐
│ Host main process                                                │
│                                                                  │
│  ┌────────────────┐  ┌────────────────┐  ┌────────────────────┐  │
│  │ ContainerService│  │ ToolRegistry   │  │ Host UI / MCP      │  │
│  │ - Installer     │  │ - Local Agent  │  │ - Space launcher   │  │
│  │ - VPMS          │  │ - MCP Server   │  │ - Agent console    │  │
│  │ - VAMS          │  │ - Lease/Approval│ │ - Installer        │  │
│  │ - Process Pool  │  └────────────────┘  └────────────────────┘  │
│  └───────┬────────┘                                              │
│          │ IPC (Binder/AIDL)                                     │
│  ┌───────▼─────────────────────────────────────────────────────┐  │
│  │ Client process :c1 (normal app process)                      │  │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────────┐   │  │
│  │  │ VClient      │  │ HookManager  │  │ Target Activity  │   │  │
│  │  │ (ActivityThread)│ │ Java/Native │  │ (target APK)     │   │  │
│  │  └──────────────┘  └──────────────┘  └──────────────────┘   │  │
│  │  Virtual ClassLoader / Resources / data dirs                 │  │
│  └─────────────────────────────────────────────────────────────┘  │
│                                                                  │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │ Client processes :c1 ... :c16 (same structure)              │ │
│  └─────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────┘
```

### 2.4 Two implementation approaches

| Approach | Method | Pros | Cons |
|---|---|---|---|
| **Java-layer virtualization** (VirtualApp style) | Hook `Instrumentation`, `ActivityThread.mH`, `ApplicationPackageManager`, `ContextImpl`, and `Binder` Java methods; dynamic proxies/reflection | No native hooks; relatively easy to debug; covers most Java calls | Hidden API restrictions; cannot fool Binder/UID/native layers; adaptation required for each Android version |
| **Native hook / ART** | Hook `Binder.execTransact`, `Process.myUid`, `open`, `dlopen`, and ART methods | More powerful; handles native code and system calls | Extremely complex; ABI/version/ROM differences; high stability/security risk |

**Recommendation: Java-first, native only at critical points.**

- First version: get Activity/PMS/Resources/IO working with Java-layer hooks.
- Second version: add native hooks for Binder/UID/native paths.
- Do not start by writing a "general ART hook framework"; use a mature hook library first and get the product working.

### 2.5 Hook framework

**Java layer:**

- Reflection + dynamic proxies (`Proxy`/`InvocationHandler`) to replace interfaces such as `IPackageManager` and `IActivityManager`.
- `Unsafe`/`MethodHandles` to modify final fields.
- Hidden API exemptions: `VMRuntime.setHiddenApiExemptions` or a "FreeReflection"-style approach; Android 10+ needs per-version handling.
- Replace `ActivityThread.mH`'s `Handler.Callback` (be careful not to break system messages).
- Replace the `Instrumentation` subclass (`ActivityThread.mInstrumentation`).

**Native layer:**

- PLT/GOT hook, inline hook; prefer maintained libraries (ShadowHook, ByteHook, Dobby, xDL), checking each license.
- Key hook points: `Binder.execTransact`, `Process.myUid`, `open`/`fopen`, `dlopen`/`android_dlopen_ext`.
- Version adaptation: maintain offsets/symbol tables for each major Android release; use feature flags for staged rollout.

**Version adaptation layer:**

```kotlin
interface AndroidAdapter {
    val apiLevel: Int
    fun installPackageHooks()
    fun installActivityHooks()
    fun installResourceHooks()
    fun installBinderHooks()
    fun installNativeHooks()
}
```

One implementation per API level; put shared logic in a base; do not assume old hook points still exist on new versions.

---

## 3. Key subsystems

### 3.1 APK parsing / installer

**Input:**

- Single APK
- split APK (base + config.arm64_v8a + config.xxhdpi, etc.)
- `.apks` / `.xapk` (unpacked and handled as splits)

**Parsing:**

- Binary `AndroidManifest.xml` (AXML): package, versionCode, minSdk, targetSdk, application, activities, services, providers, receivers, permissions, meta-data, `REQUIRE_SECURE_ENV` property.
- `resources.arsc`: resource table, configurations, split resources.
- Signatures: V1/V2/V3; signer/certificate digests.
- Native libs: ABI, 16 KB alignment.
- Split metadata: `splitNames`, `splitTypes`, `configForSplit`, `isFeatureSplit`.

**Implementation options:**

- Use AOSP `PackageParser`/`ParsingPackageUtils` (hidden API, large version differences); or
- Use an open-source APK parsing library (such as ARSCLib/apk-parser) and build your own model; or
- Combine both: use the open-source library for parsing and construct runtime `PackageInfo` yourself.

**Installation:**

1. Preflight (Appendix A).
2. Copy the APK/splits into the space's private directory.
3. Parse and construct internal `PackageInfo`/`ActivityInfo`/`ServiceInfo`/`ProviderInfo`.
4. Create `data/`, `files/`, `cache/`, `databases/`, `shared_prefs/`, `lib/`.
5. Register with VPMS.
6. Create a launcher/shortcut.
7. Initialize `Application` on first launch.

**Must check:**

- `REQUIRE_SECURE_ENV=1` → refuse to load.
- Native libs not aligned to 16 KB → reject/warn on 16 KB devices.
- Incomplete split → reject.
- Low targetSdk → whether to allow it according to Android version policy.

### 3.2 Virtual PackageManager (VPMS)

Goal of PackageManager virtualization: make the target app see "itself as installed" through `Context.getPackageManager()` / `ActivityThread.getPackageManager()`.

**Methods to virtualize (at minimum):**

- `getPackageInfo` / `getApplicationInfo` / `getActivityInfo` / `getServiceInfo` / `getProviderInfo`
- `resolveActivity` / `queryIntentActivities` / `queryIntentServices`
- `getInstalledPackages` / `getInstalledApplications`
- `getPackagesForUid` / `getPackageUid` / `getNameForUid` / `getPackageGids`
- `checkPermission` / `checkSignatures` / `getPackageInfo(GET_SIGNATURES)`
- `getLaunchIntentForPackage`
- `getApplicationEnabledSetting` / `setApplicationEnabledSetting`
- `getResourcesForApplication` / `getResourcesForActivity`
- `getPackagesHoldingPermissions`

**Implementation methods:**

1. **Context-level wrapping**: Give the target app a custom `Context`/`ContextWrapper`; `getPackageManager()` returns `VPackageManager`; `getResources()` returns the target Resources; `getClassLoader()` returns the target ClassLoader.
2. **ActivityThread/IPackageManager proxy**: Replace the `IPackageManager` dynamic proxy returned by `ActivityThread.getPackageManager()`; forward non-virtual packages to the system, and query VPMS for virtual packages.
3. **ApplicationPackageManager hook**: Supplement scenarios that directly call `new ApplicationPackageManager(...)` or use static calls.

> Prioritize 1 + 2; use 3 as a fallback. `IPackageManager` is an AIDL interface whose methods change between Android versions, so the proxy must be generated/adapted per version.

**Signatures:**

- Return the target APK's real signature (if trusted), or forge it according to product policy; the latter triggers integrity checks and is not recommended.
- `checkSignatures` returns `MATCH` for itself; for system packages, return the real result.

### 3.3 Virtual ActivityManager (VAMS)

Goal: let the target app's `startActivity`/`startService`/`bindService`/`sendBroadcast` start virtual components instead of being rejected by the system.

**Core: Stub Activity approach**

1. The host Manifest declares a set of `StubActivity0..N` (optionally with `android:process=":pX"`, different `launchMode`/`taskAffinity`).
2. Hook `Instrumentation.execStartActivity`:
   - Determine whether the target Intent points to a virtual package;
   - If yes: replace the component with a `StubActivity`, and store the original Intent/target component/instance id in extras or a VAMS mapping;
   - If no: allow it through.
3. The system starts `StubActivity`; `ActivityThread` creates it.
4. Hook `Instrumentation.newActivity` / `ActivityThread.mH`:
   - Instantiate the target Activity with the target APK's `ClassLoader`;
   - Replace `StubActivity`'s instance/fields, or let `StubActivity` proxy the Activity lifecycle;
   - Inject the target `Resources`, `Context`, `Application`, and `Window`.
5. Forward Activity lifecycle callbacks to the target Activity; handle `onBackPressed`/`onNewIntent`/`onActivityResult` as well.
6. Task stack: map StubActivity back to the target Activity; show the target name/icon for `finish`/`moveTaskToBack`/recent tasks.

**Implemented: Stage C-lite (2026-11-03)**

Instead of introducing a `StubActivity0..N` array, `StageAActivity` itself is reused as a "universal Stub":

- Each target Activity corresponds to a real `StageAActivity` instance, pushed onto the stack through `host.startActivity`/`startActivityForResult`; Android's own task stack handles back navigation.
- `ContainerInstrumentation` declares AOSP's hidden method `execStartActivity(Context, IBinder, IBinder, Activity, Intent, int, Bundle)` (without adding `override`; install the HiddenApiBypass exemption first), and ART virtual dispatch reaches it at runtime.
- An explicit ComponentName in the target package (including activity-alias) → create a new `StageAActivity`, with extras carrying sessionId, target class, and the original Intent; an external Intent → hand it to the current host launch.
- `ContainerSessions` shares the target Application/ClassLoader/Resources by sessionId; each target Activity creates only its own Activity instance, Theme, and decorView.
- `finish()` uses the real host token, so it closes only the `StageAActivity` corresponding to the current target; the previous host naturally resumes; Splash → home and `startActivityForResult` result return have both been verified.
- The host forwards the lifecycle: onCreate/onStart/onRestart/onResume/onPause/onStop/onDestroy, as well as `onActivityResult` (reflection invokes the target Activity's protected method).
- Not yet implemented: IntentFilter/implicit Intent resolution, singleTop/CLEAR_TOP semantics for `onNewIntent`, Activity options/transitions, token mapping for `requestPermissions`, and recent tasks showing the target name/icon.
- Real target-app compatibility additions (verified on MuMu / Android 15):
  - The target APK may bundle AndroidX. `DexClassLoader` defaults to parent-first, causing target classes to link against the host's AndroidX and hit missing fields. `ContainerClassLoader` was changed to child-first: check the target dex first, then fall back to the host/system.
  - A target Activity's `attachBaseContext` may call `createConfigurationContext` to handle locale; the original implementation returned host resources. `ContainerContext` now rebuilds Resources with the target APK's AssetManager + a new Configuration, and returns a ContextWrapper that preserves the target package name, ClassLoader, and directories.
  - A target app may still depend on system-service binding, Provider, or Binder virtualization. Unsupported cases should produce explicit diagnostics instead of app-specific startup special cases.

**Implemented: in-container Service / Provider (Stage C-lite+)**

- Service:
  - `ContainerContext` overrides `startService`/`startForegroundService`/`bindService`/`unbindService`/`stopService`; explicit Services in the target package go to `ContainerServiceManager`, while external Services fall back to the host.
  - Target Services are instantiated with the target `ContainerClassLoader`, `attachBaseContext(ContainerContext)` is called, `Service.mApplication` is set, and `onCreate`/`onStartCommand`/`onBind`/`onUnbind`/`onDestroy` are driven directly.
  - `bindService` delivers the `IBinder` returned by `onBind` to the target `ServiceConnection` via `onServiceConnected` on the main thread; both `startActivityForResult` and Service callbacks complete inside the target process.
  - Explicit ComponentName only; implicit Service Intents are not resolved. `startForeground`/`stopSelf` may still reach the host AMS.
- ContentProvider:
  - At session startup (before Application.onCreate), create target Providers according to the manifest; `attachInfo(ContainerContext, ProviderInfo)` runs `onCreate`.
  - Use `ContentResolver.wrap(dispatcherProvider)` to obtain the target resolver; the dispatcher routes by URI authority to the local Provider, and external authorities fall back to the real resolver of the host application context.
  - Covers query/insert/update/delete/bulkInsert/getType/call/openFile/openAssetFile/openTypedAssetFile; target Providers can query each other.
  - Provider permissions, process isolation, and PackageManager meta-data queries for `FileProvider` are not virtualized.
**Implemented: Stage C-D (2026-11-04)**

- Process model: `ContainerProcessPool` pins each `package@instance` to one of `:c1..:c16`; before reusing a container slot it force-kills the slot process and waits for exit, and `ContainerSessions` rejects a second session in the same process. Uncaught target Java exceptions are swallowed by `TargetCrashGuard` and a crash marker is written; native crashes/`System.exit` kill only the slot process, leaving the main process and other instances unaffected. The next `space_launch` returns `restarted_after_crash`/`crash_reason`.
- JobScheduler: `ContainerContext.getSystemService(JOB_SCHEDULER_SERVICE)` returns `ContainerJobScheduler`; target `JobService` components are instantiated with the target ClassLoader and `onCreate`/`onStartJob`/`onStopJob` are driven, supporting minimum latency, periodic execution, backoff, and network/charging/low-battery/idle constraints (constraints that cannot be determined are treated as satisfied). Valid while the process is alive.
- Notifications: the `INotificationManager` proxy rewrites the package name to the host package, prefixes tag/channel/group with the virtual package, redraws the small icon as a bitmap, and swaps the target PendingIntent for the host `ContainerLaunchProxyActivity` trampoline; channel query results have the prefix removed.
- Broadcast bridging: actions of the target's static/dynamic Receivers are registered with the host; system sticky/normal broadcasts are injected into the container; broadcasts emitted by the target that are not scoped to itself are dispatched locally and then forwarded to the host, with an origin extra to prevent loops.
- Permissions: `checkSelfPermission`/`checkPermission`/`enforce*`/PackageManager permission queries go through the virtual grant table; `ContainerPackageManager.buildRequestPermissionsIntent`/`getPermissionControllerPackageName` are overridden and the hidden `Context.getPermissionRequestState` is declared so `requestPermissions` can be taken over by the container. Permissions declared by the host trigger a real system dialog, and results are persisted per package; MCP `space_permissions` supports list/grant/revoke.
- Full VPMS: `VirtualPackageRegistry` builds a lightweight index from `state.json` and parses other APKs in the same space on demand; `getInstalledPackages`/`getInstalledApplications`, explicit components, implicit Intents, `resolve*`/`query*`, labels/icons/Resources/launch Intents/signatures/permission declarations all cover cross-virtual-app queries. Other apps use stable synthetic UIDs.
  - Performance constraint: list/label/UID/signature lookups use the lightweight index; implicit Intents without a specified package do not scan all virtual apps, and only explicit package/component queries parse the corresponding APK. Otherwise, resolving another large APK while a resource-heavy target app starts would block the main thread.
- Compatibility constraint: the `WindowManager` dynamic proxy is enabled only for target contexts that need Toast package-name rewriting. A global proxy may be passed into JNI by a target native SDK, causing CheckJNI to report `invalid jobject` and abort the container process.
- Multi-instance `.so` isolation: different instances of the same package are assigned different container slots; native libraries are extracted into per-instance directories; when a container slot is reused, the old slot process is killed first to avoid mixing `.so` files with the same name and JNI global state.

**Other components:**

- BroadcastReceiver: static/dynamic registration; static receivers need to be resolved in VPMS, and dynamic registration requires hooking `registerReceiver`; see Stage C-D for host-side bridging.
- JobScheduler/notifications are virtualized inside the container (see above); if WorkManager goes through its own `SystemJobScheduler`, it uses the container JobScheduler, and persistent background tasks that depend on system wakeups will still degrade. `startForeground` foreground services may still reach the host AMS.

**Background Activity launch:**

- Android 10+ restricts background launches; the host must be in the foreground, or there must be a notification/foreground service/user interaction.
- Approach: transparent foreground `SpaceLaunchActivity` + notification; or launch when the user taps; do not assume a background Agent can bring up the UI at any time.
- Android 14+ adds further restrictions on background launches from `PendingIntent`; verify per version.

### 3.4 ClassLoader / Resources

**ClassLoader:**

- Use `DexClassLoader`/`PathClassLoader` to load the target APK's dex (base + splits).
- Parent ClassLoader: the host ClassLoader (can share AndroidX/Kotlin runtime) or `ClassLoader.getSystemClassLoader()`; watch for class conflicts (target apps bundling libraries with the same names).
- Multi-dex: `BaseDexClassLoader` supports a dex list; add the dex files from all splits.
- The target app's `Application`/`Activity` classes are loaded through the target ClassLoader.
- `LoadedApk` construction: reflectively create/populate `mApplicationInfo`, `mClassLoader`, `mResources`, `mAppDir`, `mNativeLibraryDir`, `mDataDir`; register with `ActivityThread.mPackages`.

**Resources:**

- Add the resource paths of the base + all splits to `AssetManager`.
- Create `Resources` with the target `DisplayMetrics`/`Configuration`; set `Resources.Theme`.
- Handle split/overlay for `resources.arsc`; resource ID conflicts must be merged by split.
- `Context.getResources()`/`getAssets()`/`getTheme()` return target resources.
- Icons/names: read from the target `ApplicationInfo`/`Resources` for the space UI.

**Application:**

- Create the target Application through `TargetApplicationFactory` in `app`, covering the regular, Tinker, and ApplicationLike startup chains.
- `attachBaseContext(targetContext)` → `onCreate()`.
- Simulate the target app's `ContentProvider` initialization order (`installContentProviders`).

### 3.5 Binder / UID virtualization (the hardest)

**Goal:**

- When the target app calls `Binder.getCallingUid()`/`Process.myUid()`/`UserHandle.myUserId()`, it sees a virtual UID/user.
- When the target app calls system services, the system considers it to be the host UID (reality), but the app side believes it is a virtual UID.

**What the Java layer can do:**

- Hook the return values of `Process.myUid()`, `Binder.getCallingUid()`, `UserHandle.myUserId()`, and `Process.myUserHandle()`.
- Hook `PackageManager.getPackagesForUid`/`getNameForUid`/`getPackageUid` to return virtual results.
- Hook `Context.getUserId()`/`getPackageName()`/`getOpPackageName()`.

**What it cannot do:**

- On the system service side (AMS/PMS/AccountManager/Location/Notification), permissions/AppOps are still judged by the host UID; without modifying the system, you cannot make the system recognize a UID that does not exist.
- The `uid`/`pid` in Binder transactions are filled in by the kernel; the Java layer cannot change them. The only way to change them is native hooking of `Binder.execTransact`/`Parcel`, which is extremely risky.
- Therefore: **an app-level container cannot provide true UID isolation**; it can only "fool the app's Java layer," not "fool the system."

**Recommendation:**

- The first version does not virtualize UID; only package/component virtualization.
- The second version adds Java hooks for `Process.myUid`/`Binder.getCallingUid` to cover common checks.
- The third version evaluates native Binder hooks; do not make them an MVP prerequisite.

### 3.6 IO / data isolation

**Paths to redirect:**

- `Context.getFilesDir()` / `getCacheDir()` / `getDir()` / `getDatabasePath()` / `getSharedPreferences()`
- `ContextImpl.getDataDir()` / `getDatabasesDir()` / `getSharedPreferencesPath()`
- `SQLiteDatabase.openDatabase` / `openOrCreateDatabase`
- `Environment.getExternalStorageDirectory()` / `getExternalFilesDir()`
- `System.loadLibrary` / `System.load` / `dlopen`
- Working directory of `Runtime.exec` (optional)

**Implementation:**

- Give the target app a `ContextWrapper` that overrides the methods above and returns the instance directory.
- For static/internal calls, hook the corresponding `ContextImpl` methods.
- For direct path calls in `SharedPreferencesImpl`/`SQLiteDatabase`, hook the open methods.
- For native file access, native hook `open`/`fopen`/`stat` (optional, later).
- One root directory per instance: `/data/user/0/<host>/spaces/<spaceId>/instances/<instanceId>/<pkg>/`.

**Notes:**

- This is not security isolation; native code/absolute paths can bypass it.
- Isolation for external storage/MediaStore is limited; state this in the product.
- Backup/restore covers only allowed directories.

### 3.7 Permissions / AppOps / notifications

**Permissions:**

- The target app's `checkPermission`/`checkCallingPermission` go through the virtual permission manager: first check the target app's manifest permissions, then check whether the host has been granted the corresponding system permission, and finally return according to product policy.
- Runtime permissions: hook `Activity.requestPermissions`/`Fragment.requestPermissions`, show a host dialog; after the user allows it, record the virtual grant and request the host permission from the system when needed.
- `AppOpsManager`: the target app's operations are recorded under the host package name/UID; for precision, hook the Java methods of `AppOpsManager` (limited).
- Maintain a permission mapping table: target permission → host permission → whether it is mandatory.

**Notifications:**

- The target app's `NotificationManager.notify` requires rewriting the `Notification`'s package/tag/id, or sending the notification with the host package name while retaining the target icon/text.
- The package/UID of `PendingIntent` must be mapped back to the virtual component; Android 12+ notification trampoline restrictions apply.
- Android 13+ `POST_NOTIFICATIONS` runtime permission is requested by the host.
- The first version may omit target notifications; provide an "in-space notification center" as a substitute.

### 3.8 Process model / phantom process

- Use host-declared `:c1..:c16` slot processes, not `app_process` child processes.
- Declare `StubActivity`/`VClientService` in `:c1..:c16`; the system creates a normal app process at launch.
- Compute the slot limit automatically from device memory, capped at 16; fixed mode uses the user's selected value from 1 through 16.
- Clean before process reuse: target Activities/Services, ClassLoader, Resources, static state, file locks.
- Slot process crash: mark dirty, restart; the host UI shows "Space restarted."
- Foreground service keepalive: host main process + current space process; avoid keeping all processes resident.
- Android 12+'s phantom process killer does not apply to normal app processes, but background processes can still be killed by LMK; use foreground services/user-visible state.

### 3.9 Native libraries / 16 KB

- Select the split by ABI at install time; extract `.so` files into the instance's `lib/` directory.
- `ApplicationInfo.nativeLibraryDir` points to that directory; `LoadedApk`/`ClassLoader` use it.
- Hook `System.loadLibrary`/`System.load`: resolve using the target app's `nativeLibraryDir`.
- 16 KB pages: check the ELF `p_align`; reject/warn if unsupported.
- `dlopen` namespaces: system libraries that target `.so` files depend on are resolved by the linker; own `.so` files use absolute paths.
- JNI: the target app's `System.loadLibrary` and `RegisterNatives` usually work under the target ClassLoader; watch for global JNI state conflicts.
- Do not load two incompatible versions of the same `.so` in the same process (multiple instances may conflict); isolate by process when necessary.

Current implementation (Stage C-D):

- `ApkInspector` scans `lib/<abi>/*.so` in the base/splits and obtains the set of available ABIs.
- `NativeLibs` selects the first match in `Build.SUPPORTED_ABIS`, extracts the `.so` files for that ABI flat into `vspace/data/<pkg>/<instance>/native-libs`, and uses a stamp cache to avoid copying again on every launch.
- `DexClassLoader(dexPath, codeCache, nativeLibsDir, parent)`, with `ApplicationInfo.nativeLibraryDir` set.
- Installation does not fail because of ABI; the check happens before launch, and if there is no matching ABI it returns `ABI_MISMATCH` with the device/app ABIs. An arm64-only APK will stop here on an x86_64 emulator.
- `dlopen`/`android_dlopen_ext` hooks enqueue newly loaded modules for incremental scanning.
- Different instances of the same package use different slots; the old slot process is terminated before reuse so same-named `.so` files and JNI global state do not mix.
- A universal ELF `p_align` preflight is not yet a hard gate; 16 KB compatibility is covered by build flags, device tests, and runtime diagnostics.

### 3.10 Background / Job / Provider / WebView

These are hard problems that cannot be avoided on the path to "productization"; a phased approach is recommended:

| Capability | Difficulty | First-version strategy |
|---|---|---|
| JobScheduler | High | Not supported; target app background tasks degrade |
| AlarmManager | High | Partial support; schedule under the host package name and forward after wakeup |
| ContentProvider | High | No cross-app providers initially; try to hook internal providers |
| Static BroadcastReceiver | Medium | VPMS resolution + host receiver forwarding |
| Notifications | Medium | Not in the first version; proxy later |
| WebView | Medium | `WebView.setDataDirectorySuffix` is process-level; multiple instances need separate processes |
| Accounts/sync | High | Not promised |
| Desktop shortcuts | Medium | Host shortcut points to StubActivity |

### 3.11 Hook / version adaptation

- **Java hook points**: `ActivityThread`, `Instrumentation`, `LoadedApk`, `ApplicationPackageManager`, `ContextImpl`, `Binder`, `Process`, `UserHandle`, `ResourcesManager`.
- **Native hook points**: `Binder.execTransact`, `Process.myUid`, `open`/`fopen`, `dlopen`/`android_dlopen_ext`.
- **Version adaptation**: one `AndroidAdapter` per API level; degrade/disable features when hook points are missing.
- **Staged rollout**: toggle by device/Android version/target app; roll back on production crashes.
- **Testing**: at least one physical device per Android version; AOSP/Pixel first, OEM devices later.

### 3.12 Security boundaries

- App-level containers share the host UID; a malicious app can bypass Java-layer redirection and read host/other-instance data.
- Install only apps you are familiar with; do not treat the container as a means of isolating untrusted apps.
- The Agent can see sensitive on-screen information; mask it by default and do not record passwords/payments by default.
- Prompt injection: on-screen text is untrusted input; use a tool allowlist + confirmation for dangerous operations.
- MCP defaults to loopback; LAN mode must be explicitly enabled + token (currently HTTP, TLS pending).

---

## 4. Control layer

### 4.1 Control channels

| Channel | Prerequisites | Observation | Actions | Latency | Android 17 risk | Suitable for |
|---|---|---|---|---|---|---|
| **Shizuku + shell** | Install Shizuku, start via wireless debugging | `uiautomator dump`, `screencap` | `input tap/swipe/text/keyevent` | 100–500 ms | Unaffected by AAPM | **Primary (if the user is willing to install Shizuku)** |
| **Shizuku + UiAutomation** | Same as above, run UiAutomation inside UserService | Node tree, screenshots | Inject events | 50–150 ms | Unaffected by AAPM | Low-latency scenarios |
| **AccessibilityService** | Manually enabled by the user | Node tree, events | `dispatchGesture`, `ACTION_SET_TEXT` | 50–100 ms | AAPM restricts non-accessibility apps | **Primary (if the user does not want to install Shizuku)** |
| **Vision + coordinates** | Screenshot permission | Pixels | Coordinate tap (requires another channel to inject) | 1–3 s | Unaffected by AAPM | Fallback for games/Canvas/Flutter |
| **Container in-process** | In-house hooks | Target View tree (when feasible) | Direct invocation/injection | Lowest | Unaffected by AAPM | Space operations; UI control to be verified |

**Recommendations:**

- Space operations go through `ContainerService` (install/launch/stop/multiple instances).
- Build two UI-control adapters: `ShizukuChannel` and `AccessibilityChannel`, selected automatically based on availability.
- Vision is only a fallback.
- Container in-process is used only for interfaces that the in-house engine can reliably obtain, and is not the primary UI-control path in the first version.

### 4.2 Observation protocol

```json
{
  "snapshot_id": "s_123",
  "space_id": "sp_1",
  "package": "com.example.app",
  "activity": ".MainActivity",
  "screen": { "width": 1080, "height": 2400, "rotation": 0 },
  "nodes": [
    {
      "id": "n_1",
      "role": "button",
      "text": "搜索",
      "desc": null,
      "bounds": [820, 120, 1040, 200],
      "clickable": true,
      "editable": false,
      "scrollable": false,
      "enabled": true,
      "focused": false,
      "selected": false
    }
  ],
  "screenshot": "sha256:...",
  "timestamp_ms": 1730000000000
}
```

Recommendations:

- Flatten nodes, keeping only nodes that are visible/clickable/editable/have text/have descriptions.
- Default `max_nodes=120`; use `detail=full` when needed.
- Screenshots are not returned by default; fetch one when vision is needed.
- Node IDs are stable within a single snapshot; across snapshots, approximate matching uses `(package, activity, viewId, text, bounds bucket)`.
- Screenshot sources: AccessibilityService.takeScreenshot → MediaProjection → PixelCopy.

### 4.3 Action protocol

| Action | Parameters | Description |
|---|---|---|
| `tap` | node_id / bounds / text | Prefer node_id |
| `long_press` | Same as above + duration | Long-press menu |
| `swipe` | from/to/duration | Swipe/page |
| `scroll` | direction/amount | Prefer `ACTION_SCROLL_FORWARD` |
| `set_text` | node_id/text | `ACTION_SET_TEXT` → clipboard paste → keystroke-by-keystroke |
| `key` | keycode | back/home/recents/enter/delete |
| `launch_app` | package/activity | Goes through ContainerService |
| `wait_for` | text/package/activity/idle | With timeout |
| `intent` | action/data/extras | Deep link/system Intent |
| `clipboard` | get/set | Sensitive operations require confirmation |

After execution, you must: observe again → verify the expected change → retry once on failure → fall back to vision/manual after another failure.

### 4.4 Vision fallback

Suitable for games, Flutter, Canvas, and some WebViews (empty/unreliable node tree).

1. Downsample screenshots to 720p/1080p.
2. A multimodal model outputs coordinates/regions, or ML Kit OCR + template matching.
3. Normalize coordinates to screen proportions.
4. Inject taps through Shizuku/accessibility.

Vision is a fallback, not the primary path; both cost and latency are high.

### 4.5 Recording / replay / skills

- Recording: `(action, snapshot_hash, timestamp, result)`.
- Generation: convert stable sequences into YAML/JSON scripts with assertions.
- Replay: check preconditions at each step; fall back to the Agent if they are not met.
- Skill library: organized by app, such as `app.send_message` and `settings.open_wifi`.
- Skills are key to reducing token cost: common tasks use scripts + verification instead of a large model.

### 4.6 Android 17 Advanced Protection Mode (AAPM)

- With AAPM enabled, non-accessibility apps cannot use AccessibilityService, and existing permissions are revoked.
- Response:
  1. Make the control layer dual-adapter: Shizuku first, accessibility second, Vision as fallback.
  2. Detect `AdvancedProtectionManager` and prompt the user to "disable AAPM or use Shizuku mode."
  3. Do not make "accessibility automation" the only selling point.
  4. When publishing to Google Play, core functionality must work without depending on accessibility.

### 4.7 Security boundaries

- The container shares the host UID; install only apps you are familiar with.
- The Agent can see sensitive on-screen information; mask it by default and do not record password/payment pages by default.
- Prompt injection: on-screen text is untrusted input; use a tool allowlist + confirmation for dangerous operations.
- MCP defaults to loopback; LAN mode must be explicitly enabled + token (currently HTTP, TLS pending).
- All install/uninstall/payment/permission changes require secondary confirmation and auditing.

---

## 5. Dual-channel Agent / MCP

### 5.1 Two control channels (both must be supported)

| Channel | Where the Agent is | Model | Connection | Pros | Cons |
|---|---|---|---|---|---|
| **Local Agent** | Inside the phone app | On-device (Gemini Nano 4 / Gemma 4 / LiteRT-LM) or cloud (Claude API) | Direct in-process calls to `ToolRegistry` | Independent, offline (on-device models), low latency | Local model capability/device support limited; cloud models need a network |
| **Remote Agent** | Outside the phone (Claude Desktop/Code/cloud) | Decided by the remote Agent | MCP over Streamable HTTP (USB/LAN) | Strongest model capability, easy to debug | Requires a PC/network; exposed surface needs authentication |

The two channels are not either/or; they share the same tools and permission model:

- Local Agent: the Agent loop runs inside the app and calls `ToolRegistry` directly.
- Remote Agent: the Agent loop runs outside the phone and calls the same `ToolRegistry` through an MCP adapter.
- To both, the app is a "tool executor + space controller."

> "Local Agent" means the Agent loop is on the phone; it does not mean the model must be on the phone. The model can be Gemini Nano 4 (on-device) or Claude API (cloud). Both are integrated through `ModelAdapter`.

### 5.2 Unified tool layer

```kotlin
data class ToolSpec(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val mutating: Boolean,
    val requiredScope: Scope
)

class ToolRegistry(
    private val tools: Map<String, RegisteredTool>,
    private val leaseManager: LeaseManager,
    private val approvalGate: ApprovalGate,
    private val audit: AuditLog
) {
    fun list(): List<ToolSpec>
    suspend fun execute(
        name: String,
        args: JsonObject,
        ctx: ToolContext
    ): ToolResult
}

data class ToolContext(
    val sessionId: String,
    val caller: Caller,        // LOCAL_AGENT / REMOTE_MCP / USER
    val spaceId: SpaceId,
    val scope: Scope
)
```

- MCP adapter: maps `ToolSpec` to MCP `tools/list` + `tools/call`, with JSON Schema validation.
- Local Agent adapter: converts the same `ToolSpec` into Gemini/Claude function declarations; execution still goes through `ToolRegistry`.
- Shared by both sides: argument validation, error codes, approval, leases, audit, traces. New tools are written only once.

### 5.3 MCP Server (remote channel)

> The local Agent does not use MCP and calls `ToolRegistry` directly; however, tool definitions, permissions, leases, and auditing are completely identical. The MCP Server is only a transport adapter for remote Agents.

Implementation strategy:

- **Base version**: built-in minimal HTTP server using `ServerSocket` (`POST /mcp`), using only `java.net`/`java.io`, compatible with API 24/25. **Do not use Ktor 3.x**: it depends on `java.nio.file` (API 26+) and will fail to start on Android 7.x.
- **Production version**: official MCP Kotlin SDK (`io.modelcontextprotocol:kotlin-sdk-server`) + Ktor 3.x; in this case minSdk must be ≥ 26, or compatibility handling is required.
- **Transport**: Streamable HTTP (`/mcp`), supporting POST, GET SSE heartbeats, and DELETE sessions; do not use stdio (Android has no stdio host).
- **State**: each client has an independent `Mcp-Session-Id`, reclaimed after 30 minutes idle; unknown/expired sessions return 404; CORS is enabled for MCP Inspector debugging.
- **Limits**: request body limit 40 MB, request headers 64 KB, 32 concurrent connections; authentication completes before reading the body, and Bearer Token comparison is constant-time.

Android side:

- Foreground service (`dataSync` or `specialUse`) + persistent notification.
- Battery optimization allowlist; keep the connection alive under Doze.
- Bind to `127.0.0.1:8765` by default; generate a random token on first launch.
- LAN mode: explicit switch + token; the current implementation is HTTP (TLS pending), and Android 17 requires `ACCESS_LOCAL_NETWORK`. The repository provides a zero-dependency CLI: `tools/nestspace-mcp`.

Connection:

```bash
# USB (recommended)
adb forward tcp:8765 tcp:8765

# Claude Code
claude mcp add --transport http space http://127.0.0.1:8765/mcp \
  --header "Authorization: Bearer <token>"
```

### 5.4 Local Agent Runtime

- `AgentLoop`: observe → plan → tool call → verify → repeat; uses the same tools as the remote Agent.
- `ModelAdapter`, choose one of three:
  - `GeminiNanoAdapter`: ML Kit GenAI Agent API (Alpha; limited device support).
  - `LiteRTAdapter`: Gemma 4 / custom models (offline, heavy).
  - `ClaudeAdapter`: the phone calls the Anthropic Messages API directly (strongest capability, requires network/key).
- Foreground service + notification; long tasks can be cancelled; hold a wake lock when the screen is off.
- UI: task input, live trace, approval dialog, emergency stop.
- Failure/timeout: fall back to the remote Agent or manual operation.

Implemented (NestSpace 0.3):

- **Shared chat protocol** (referencing go-agent-sdk's `chat.Service` / `Messages` / `Block` abstractions):
  - `ChatMessage` = role + `List<ChatBlock>`; `ChatBlock` = `Text` / `ToolUse` / `ToolResult`.
  - `ChatService.chat(ChatRequest): ChatResponse` is the provider boundary; `ChatRequest` carries model/system/messages/tools/maxTokens/temperature.
  - `AnthropicChatService` and `OpenAiChatService` are only responsible for converting between the shared model and their respective wire JSON.
  - `LlmAgentRuntime` has only one provider-neutral loop: call the model → collect `ToolUse` → execute `ToolRegistry` → return `ToolResult` → until there are no tool calls.
  - Conversations use `ChatService.streamChat` SSE streaming; both Anthropic and OpenAI-compatible endpoints assemble text and tool calls from incremental events, and the Agent loop remains unchanged.
- **Space settings → Model settings** provides both protocols; configuration is stored in the app's private directory, and the API Key is encrypted and stored with Android Keystore.
- **Claude compatible**: `POST {base}/v1/messages`, headers `x-api-key` + `anthropic-version`, native `tools` / `tool_use` / `tool_result`.
- **OpenAI compatible**: `POST {base}/v1/chat/completions`, `Authorization: Bearer`, native `tool_calls` / role=`tool` messages.
- The third-party custom template protocol has been removed; `CUSTOM` configurations saved by older versions are ignored and fall back to Claude defaults, preventing leftover custom endpoints from mixing into the new protocol.
- `LlmAgentRuntime` reuses `ToolRegistry`, with `ToolContext(sessionId, LOCAL_AGENT, "virtual-1")`, excludes session/approval tools, allows at most 16 rounds of tool calls, and truncates a single tool result to 12k characters.
- When unconfigured, it falls back to the rule-based `LocalAgentRuntime`; the rule-based Agent still retains system-space capabilities, while the large-model Agent controls only virtual-1.
- `AssistantConversation` uniformly maintains the message stream shared by the assistant page and the floating-ball voice assistant and the most recent 24 text turns; `LlmAgentRuntime.run(history = ...)` replays history before each request to support multi-turn follow-ups. The voice entry hides text input by default, automatically submits the final recognition result, and resumes listening after the reply.
- Speech recognition uses the built-in Vosk offline model (`com.alphacephei:vosk-android`) and does not go through cloud STT. The model is downloaded on first use and can also be managed in the floating-ball settings; before downloading, it concurrently probes the HF China mirror, Hugging Face, and the Vosk homepage to measure speed, chooses the fastest route, and switches automatically on failure. Chinese/English small models are cached separately; once the model is ready, `SpeechService` continuously provides partial/final results.
- `LocalSpeechEngine` caches the loaded `Model` in-process; it loads in the background immediately after download and preloads at app startup if the model is cached; the voice page only creates `Recognizer`/`SpeechService`, so it can listen as soon as it opens. When switching languages, load the new model first; keep the old model until the process exits, so it is not released early while a paused voice page still holds the old Recognizer.
- Local mocks have verified the core conversion and tool-call round trips for both protocols; a user-configured Anthropic-compatible endpoint has been verified over a live network for multi-turn tool calls with `space_list` + `space_list_apps`.
- Unit tests: `app/src/test/java/com/coke/nestspace/llm/AnthropicChatServiceTest.kt`, `OpenAiChatServiceTest.kt`.

### 5.5 Sessions and leases

```kotlin
enum class Caller { LOCAL_AGENT, REMOTE_MCP, USER }
enum class LeaseMode { EXCLUSIVE, OBSERVE }

data class ControlLease(
    val spaceId: SpaceId,
    val leaseId: String,
    val owner: Caller,
    val sessionId: String,
    val mode: LeaseMode,
    val expiresAt: Long
)
```

Rules:

- Mutating tools (tap/type/launch/install/uninstall) must acquire an `EXCLUSIVE` lease first.
- Observation tools (observe/screenshot) can be shared.
- When the other channel calls a mutating tool, return `SPACE_BUSY` (with holder information); it may queue/wait/request the user take over.
- The user can force-reclaim a lease in the UI.
- Leases are released automatically after an idle timeout; MCP disconnects/local task completion release them immediately.
- All lease changes are written to the audit log.

### 5.6 Approval flow

| Scenario | Local Agent | Remote MCP |
|---|---|---|
| Payment/uninstall/permissions/install | In-app dialog confirmation | `approval_request` + phone notification; remote Agent polls `approval_wait` |
| Read-only observation | Allowed by default | Allowed by default (requires token scope) |
| High-risk shell | Disabled by default | Disabled by default; requires user enablement + secondary confirmation |

- Approval requests enter the same `ApprovalGate`, and the UI shows "Local Agent request / Remote Agent request."
- Approval results are written to the audit log; the default on timeout is denial.

### 5.7 Tool list

**Space management**

| Tool | Purpose |
|---|---|
| `space_list` | List spaces and their status |
| `space_create` | Create a space |
| `space_delete` | Delete a space |
| `space_install_apk` | Install an APK/split into a space |
| `space_uninstall` | Uninstall |
| `space_list_apps` | List apps in a space |
| `space_launch` | Launch an app/Activity |
| `space_stop` | Stop an app |
| `space_status` | Space/foreground app status |

**UI control**

| Tool | Purpose |
|---|---|
| `ui_observe` | Return the node tree (optionally a screenshot) |
| `ui_screenshot` | Return a screenshot |
| `ui_tap` / `ui_long_press` | Tap/long-press |
| `ui_swipe` / `ui_scroll` | Swipe/scroll |
| `ui_set_text` | Input text |
| `ui_key` | Key press (back/home/recents/enter) |
| `ui_wait_for` | Wait for a condition |
| `ui_find` | Find a node in the most recent snapshot |

**App/system**

| Tool | Purpose |
|---|---|
| `app_intent` | Send an Intent/deep link |
| `sys_shell` | Shizuku shell (disabled by default, command allowlist) |
| `sys_logcat` | Read logcat (by package/pid) |
| `sys_clipboard_get/set` | Clipboard (sensitive, requires confirmation) |

**AppFunctions fast path**

| Tool | Purpose |
|---|---|
| `appfn_status` | Report availability of Android 16+/AppFunctionManager/EXECUTE_APP_FUNCTIONS |
| `appfn_list` | List AppFunctions exposed by the target package (host/in-container) |
| `appfn_execute` | Execute an AppFunction; fall back to UI automation on failure/unavailability |

**Session/approval**

| Tool | Purpose |
|---|---|
| `session_acquire` | Acquire an exclusive/observation lease for a space |
| `session_release` | Release the lease |
| `session_status` | View the current holder |
| `approval_request` | Request human confirmation |
| `approval_wait` | Wait for the approval result |

**Task/skills**

| Tool | Purpose |
|---|---|
| `task_run` | Local Agent executes a natural-language task |
| `task_status` / `task_cancel` | Query/cancel |
| `skill_list` / `skill_run` | Skill list/execution |
| `trajectory_get` | Get the execution trace |

Design principles:

- All tools carry `space_id` (except `space_list`/`space_create`).
- Return structured errors: `{code, message, retryable, hint}`.
- Install/launch are idempotent; `idempotency_key` prevents duplicates.
- Dangerous operations require `confirm=true` or human confirmation.
- `ui_observe` defaults to `max_nodes=120` and does not return screenshots.
- `sys_shell` is not exposed by default; once enabled, only allowlisted commands are permitted.

### 5.8 Tool schema example

```json
{
  "name": "space_install_apk",
  "description": "Install an APK or split APK set into a space. Validates REQUIRE_SECURE_ENV, ABI, and 16KB alignment before install.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "space_id": { "type": "string" },
      "apk_paths": {
        "type": "array",
        "items": { "type": "string" },
        "description": "Absolute paths or content URIs. For split APKs, include base + all splits."
      },
      "grant_runtime_permissions": { "type": "boolean", "default": false },
      "idempotency_key": { "type": "string" }
    },
    "required": ["space_id", "apk_paths"]
  }
}
```

### 5.9 Model selection

As of 2026-10, Anthropic first-party API prices (USD per million tokens, input/output):

| Model | Model ID | Price | Suggested use |
|---|---|---|---|
| Claude Opus 5.5 | `claude-opus-5-5` | $4 / $20 | Default; complex planning, long tasks |
| Claude Sonnet 5.5 | `claude-sonnet-5-5` | $2 / $10 | Cost-effectiveness/speed |
| Claude Haiku 5.5 | `claude-haiku-5-5` | $0.10 / $0.50 | Simple classification, waiting, summaries |
| Claude Fable 5.1 | `claude-fable-5-1` | $10 / $50 | Hardest tasks; high cost |

On-device models:

- **Gemini Nano 4 / ML Kit GenAI Agent API** (Alpha): on-device tool-call loop; limited device support.
- **Gemma 4 + LiteRT-LM**: can bring your own fine-tuned model; high integration/performance cost.

Recommendation: use Claude Opus 5.5 for the remote Agent; for the first version of the local Agent, use `ClaudeAdapter` to get the loop working, then integrate Gemini Nano 4 / Gemma 4; use Haiku 5.5 for simple substeps.

### 5.10 Agent loop and failure handling

```text
state = observe(space)
for turn in 1..MAX_TURNS:
    decision = model.decide(goal, state, tools, history)
    if decision.done: return result
    if decision.needs_user_confirmation:
        approval = approvalGate.request(decision)
        if not approval.approved: return cancelled
    result = execute(decision.tool, decision.args)
    state = result.observation ?? observe(space)
    history.append(compact(result))
```

Key points:

- Write the system prompt clearly: role, available apps, tool semantics, safety rules, output format.
- Context: compact node tree + screenshots on demand + skill hints + history summary.
- Limit rounds, tokens, and per-step timeout; support cancellation.
- Verify each step; two consecutive failures → replan; three → human takeover.
- Failure handling:

| Failure | Handling |
|---|---|
| Node not found | Observe again; fall back to text/coordinates |
| Tap has no effect | Check clickability; coordinates; wait for animation |
| Input failure | `ACTION_SET_TEXT` → clipboard → keystroke-by-keystroke |
| App crash/ANR | Kill and restart the process; alternate path; manual |
| Permission dialog | Recognize system dialogs; allow/deny/ask according to policy |
| Slow loading | `wait_for` + timeout + retry |
| Verification failure | Roll back one step; replan |

### 5.11 AppFunctions fast path (host / in-container APK)

Android 16+ AppFunctions are "system-level tool registration": apps declare capabilities with `@AppFunction`, KSP generates the service/XML schema, the system registers them, and Agents discover/execute them through `AppFunctionManager`.

**Three cases with different permissions and capabilities:**

| Scenario | Usable? | Explanation |
|---|---|---|
| **Host app exposes AppFunctions itself** | ✅ Can do | The host is the function owner and can execute its own functions; the system Agent/Gemini can also discover them |
| **APK inside the container exposes AppFunctions** | ✅ Requires a local bridge | The virtual package is not registered with the system, so `AppFunctionManager` cannot see it; parse AppFunctions metadata at install time and call the generated service/entry point inside the container process |
| **AppFunctions of other apps in the system** | ⚠️ Usually not | The caller needs `EXECUTE_APP_FUNCTIONS`, a privileged/system-level permission; ordinary APKs generally cannot obtain it |

**Architecture:**

- `ToolSource` abstraction: `BuiltinToolSource` (space/UI tools), `AppFunctionsToolSource` (host/container AppFunctions), `SkillToolSource`.
- `ToolRegistry` aggregates tools under the namespace `appfn.<package>.<functionId>`; name conflicts are distinguished by package name.
- When planning, the Agent prioritizes the fast path: `appfn_execute` → unavailable/failed → UI automation.
- The container engine parses the APK's AppFunctions XML/metadata during installation; if present, registers them with `AppFunctionsToolSource`; execution calls them inside the container process, avoiding dependence on system registration.
- The host app can also use `@AppFunction` to expose `space.list/install/launch` and `ui.observe/tap`, letting the system Agent/Gemini call the host directly. This is "export," not "import."

**Permissions/compliance:**

- Do not assume an ordinary APK can call another app's AppFunctions; `EXECUTE_APP_FUNCTIONS` is privileged.
- AppFunctions exported by the host must still go through leases/approval/audit; do not bypass the security model.
- AppFunctions inside the container are still subject to the container's Binder/UID limitations; functions that depend on system services may fail.
- AppFunctions are an optional fast path, not the only control plane; failure must fall back to UI automation.

**Base version:**

- Added `AppFunctionsBridge` detection (API 36, `AppFunctionManager`, permissions) and placeholder `appfn_status/list/execute` tools.
- Host export and in-container local bridging are deferred to Stage F.

---

## 6. Architecture

### 6.1 Layered diagram

```text
┌──────────────────────────┐        ┌──────────────────────────┐
│ Local Agent Runtime      │        │ Remote Agent             │
│ (on-device loop)         │        │ (Claude Desktop/Code/cloud)│
└────────────┬─────────────┘        └────────────┬─────────────┘
             │ ToolRegistry                      │ MCP Server
             │                                   │ (Streamable HTTP)
             └───────────────┬───────────────────┘
                             ▼
┌──────────────────────────────────────────────────────────────┐
│ UI (Compose): Spaces / Installer / Agent console / Approvals / Traces │
└──────────────────────────────┬───────────────────────────────┘
                               │
┌──────────────────────────────▼───────────────────────────────┐
│ Domain                                                       │
│  SpaceRepository  ControlRepository  AgentRepository         │
│  ToolRegistry  LeaseManager  ApprovalGate  AuditLog          │
│  AppFunctionsBridge (fast path)                              │
└───────────────┬──────────────────────────────┬───────────────┘
                │                              │
┌───────────────▼──────────────┐  ┌────────────▼───────────────┐
│ ContainerEngine              │  │ ControlChannel             │
│  Installer / VPMS / VAMS     │  │  ShizukuChannel            │
│  ClassLoader / Resources     │  │  AccessibilityChannel      │
│  Binder / IO / ProcessPool   │  │  VisionChannel             │
└───────────────┬──────────────┘  └────────────┬───────────────┘
                │                              │
┌───────────────▼──────────────────────────────▼───────────────┐
│ Infrastructure                                               │
│  Host-declared :c1..:c16     | MCP Server | Local Agent      │
│  AccessibilityService / MediaProjection / Shizuku             │
│  Room / DataStore / WorkManager                              │
└──────────────────────────────────────────────────────────────┘
```

### 6.2 Module breakdown

```text
app/
  src/main/java/com/coke/virtual/
    core/model/          # SpaceInfo, AppInfo, UiNode, UiSnapshot, Target, Condition
    core/space/          # SpaceBackend, SpaceRepository
    core/control/        # ControlChannel, AccessibilityService, ShizukuBridge
    core/tools/          # ToolRegistry, ToolSpec, validation, scopes
    core/session/        # ControlLease, SessionManager, ApprovalGate, AuditLog
    core/mcp/            # MCP server adapter (remote), auth
    core/agent/          # Shared AgentLoop, prompt, skills
    core/agent/model/    # ClaudeAdapter, GeminiNanoAdapter, LiteRTAdapter
    engine/installer/    # APK/split parsing, preflight, installation
    engine/vpms/         # Virtual PackageManager
    engine/vams/         # Virtual ActivityManager, StubActivity
    engine/classloader/  # ClassLoader/LoadedApk
    engine/resources/    # Resources/AssetManager
    engine/binder/       # Binder/UID virtualization
    engine/io/           # Data directory redirection
    engine/process/      # :c1..:c16 process pool, VClientService
    engine/hooks/        # Java/Native hooks, version adaptation
    fastpath/            # AppFunctions detection, host export, in-container local bridge
    feature/spaces/      # Compose UI: space list, app grid
    feature/installer/   # APK selection, preflight, install progress
    feature/agent/       # Agent console, traces, approvals
    service/             # ForegroundService, AccessibilityService
```

### 6.3 Key interfaces

```kotlin
// Shared by both channels: local Agent calls ToolRegistry directly; remote Agent calls the same ToolRegistry through the MCP adapter
data class ToolSpec(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val mutating: Boolean,
    val requiredScope: Scope
)

interface ToolRegistry {
    fun list(): List<ToolSpec>
    suspend fun execute(name: String, args: JsonObject, ctx: ToolContext): ToolResult
}

interface AgentRuntime {
    suspend fun run(goal: String, spaceId: SpaceId, sessionId: String): TaskResult
    fun cancel(sessionId: String)
}

data class ControlLease(
    val spaceId: SpaceId,
    val leaseId: String,
    val owner: Caller,
    val sessionId: String,
    val mode: LeaseMode,
    val expiresAt: Long
)

interface ContainerEngine {
    suspend fun install(spaceId: SpaceId, apk: ApkSource): InstallResult
    suspend fun uninstall(spaceId: SpaceId, packageName: String)
    suspend fun listApps(spaceId: SpaceId): List<AppInfo>
    suspend fun launch(spaceId: SpaceId, packageName: String, activity: String? = null): LaunchResult
    suspend fun stop(spaceId: SpaceId, packageName: String)
    suspend fun status(spaceId: SpaceId): SpaceStatus
}

interface ControlChannel {
    suspend fun observe(id: SpaceId, options: ObserveOptions = ObserveOptions()): UiSnapshot
    suspend fun screenshot(id: SpaceId): ImageBitmap
    suspend fun tap(id: SpaceId, target: Target)
    suspend fun longPress(id: SpaceId, target: Target, durationMs: Long = 800)
    suspend fun swipe(id: SpaceId, from: Point, to: Point, durationMs: Long = 300)
    suspend fun setText(id: SpaceId, target: Target, text: String)
    suspend fun key(id: SpaceId, key: Key)
    suspend fun waitFor(id: SpaceId, condition: Condition, timeoutMs: Long): Boolean
}
```

### 6.4 One complete flow

> The remote Agent goes through MCP Server → ToolRegistry; the local Agent goes directly through ToolRegistry. The Engine/Control steps below are the same for both.

```text
Agent                         MCP Server / ToolRegistry      Engine / Control
  │                              │                                │
  │ space_install_apk            │                                │
  │─────────────────────────────►│ Validate APK / REQUIRE_SECURE_ENV │
  │                              │───────────────────────────────►│ Installer
  │                              │◄───────────────────────────────│ ok
  │ space_launch                 │                                │
  │─────────────────────────────►│───────────────────────────────►│ VAMS + ProcessPool
  │                              │◄───────────────────────────────│ foreground
  │ ui_observe                   │                                │
  │─────────────────────────────►│───────────────────────────────►│ dump tree
  │◄─────────────────────────────│ nodes + screenshot hash        │
  │ ui_tap(node_id=n_7)          │                                │
  │─────────────────────────────►│───────────────────────────────►│ tap
  │ ui_wait_for(text="结果")     │                                │
  │─────────────────────────────►│───────────────────────────────►│ poll
  │◄─────────────────────────────│ matched / timeout              │
```

### 6.5 Current engineering change recommendations

1. `core/model` + `core/tools` + `core/session`: models, ToolRegistry, leases/approval first.
2. `service/SpaceAccessibilityService.kt` + `res/xml/accessibility_service_config.xml`: observation/tapping.
3. `core/control/ShizukuBridge.kt`: integrate the Shizuku API (`dev.rikka.shizuku:api` + `provider`), implement `input`/`screencap`.
4. `core/mcp/`: base version uses the built-in minimal HTTP server (ServerSocket), starting with 5 tools (observe/tap/launch/list_apps/screenshot); switch to Ktor + kotlin-sdk in production.
5. `service/McpForegroundService.kt`: foreground service + notification + token.
6. `core/agent/`: local Agent skeleton (`ClaudeAdapter` or a fake model), reusing the same ToolRegistry.
7. `engine/` + `container/`: Stage A/B single-process container, installer, multiple instances, and instance data directories are implemented (see Section 7).
8. The process pool, native libraries, Binder/UID, Provider/notification/Job support, and Android 7.1–17 adaptation are implemented; remaining work focuses on background wakeups, system-side real-UID boundaries, and broader generic compatibility samples.

---

## 7. Build order (no schedule estimates)

| Stage | Goal | Content | Completion criteria (capability gate) |
|---|---|---|---|
| **S0 Control layer + dual-channel skeleton** | First make "control" an independent layer | ToolRegistry, MCP Server, local Agent skeleton, Shizuku/accessibility, leases/approval, traces | Both remote MCP and local Agent can control an installed app to complete 3 types of tasks; tool behavior is consistent |
| **A Single-process minimal container** ✅ Verified | Get one simple APK running end to end | AXML/resource parsing, ClassLoader, Resources, StubActivity, Instrumentation/ActivityThread hooks, Context wrapping | A simple APK with no native/GMS can launch and display UI in the host process (verified on API 25 emulator) |
| **B Installer + multiple instances** ✅ Verified (API 25 / API 36) | Can install, can run multiple instances | split APK, signature verification, `REQUIRE_SECURE_ENV`, IO redirection, VPMS list/launch, instance data directories | A simple app can be installed, launched, and duplicated; data does not pollute other instances (verified with testapp + Android 16; base + synthetic split verified; resource overlay for real config splits still to be verified) |
| **C Process pool + Native** ✅ Implemented | Crash isolation, native apps | `:c1..:c16` process pool, VClientService, native library extraction/loading, 16 KB tests, Binder/UID Java hooks | Targets run in independent processes; native libraries work; 16 KB devices install with explicit diagnostics |
| **D Background/system capabilities** ✅ Core implemented | Mainstream apps usable | Notifications, static Receivers, partial Job/Alarm proxying, Providers, WebView process isolation, Android 15–17 adaptation | Notifications, broadcasts, Providers, permissions, and common background components work; full Job/Alarm wakeup after system death remains limited |
| **E Agent autonomy + safety** | Ready to ship | On-device/cloud models, skill library, verification/retry, human takeover, audit, masking, MCP authentication | Common-task success rate meets target; security review passes |
| **F AppFunctions fast path** | Optional acceleration | Host `@AppFunction` export, container APK metadata parsing, in-container local execution, permission detection, UI fallback | Host tools can be called by the system Agent; in-container AppFunctions can execute; fall back to UI when unavailable |

### 7.1 Key Stage A acceptance

Verified on an Android 7.1.1 x86 emulator (testapp APK not installed; loaded directly):

- ✅ The `Activity`'s `onCreate/onStart/onResume` execute, the layout displays, and `findViewById` works.
- ✅ Target resources take effect: `@string` and `@layout` resolve correctly; after tapping a button, the TextView updates to the target string.
- ✅ Tapping/rendering does not crash, show a black screen, or ANR.
- ✅ The target `Application`'s `attachBaseContext`/`onCreate` are integrated by Stage B (verified with testapp).
- ✅ The target `getPackageName()` and PackageManager queries are answered by the virtual package index, including cross-virtual-app VPMS lookups.
- ✅ Android 9+ hidden APIs are handled through HiddenApiBypass; current verification covers Android 7.1, 15, 16, and 17.

### 7.2 Key Stage B acceptance

Verified on an Android 7.1.1 x86 emulator:

- ✅ A single APK installs successfully and persists; a base + synthetic split APK is installed and launched via `space_install_apk`, with `split_apks` written to disk. Resource overlay for real config splits remains to be verified.
- ✅ Inconsistent signatures rejected: `SIGNATURE_MISMATCH`.
- ✅ `REQUIRE_SECURE_ENV=1` rejected: `SECURE_ENV_REQUIRED`.
- ✅ Multiple instances `instance-1` / `instance-2` with independent data directories; `file/prefs count` do not affect each other.
- ✅ After switching instances, the old instance's data is retained; after killing the process and restarting the app, installation records and instances still exist.
- ✅ Uninstall cleans up APKs, instance data, and state; the directory is empty.
- ✅ When launching the second instance, the first instance goes to the background/is replaced under the single-process model; data is not lost.
- ✅ The target Context's `getPackageName`, `getFilesDir`, `getSharedPreferences`, and `getApplicationInfo` are redirected.
- ✅ Clean desktop: by default only virtual apps + "Space settings" are shown; system apps appear only after being selected in **Space settings**; supports setting/clearing wallpaper, which persists after a restart.
- ✅ Android 16 (API 36) retest: desktop, container, multiple instances, and instance data isolation work normally.
- ✅ Android 14+ writable dex restriction: after the installed APK is made read-only, `DexClassLoader` loads it normally.
- ✅ Android 9+ hidden API: integrated HiddenApiBypass 6.2; reflection such as `Activity.attach` works; without exemption, `Activity.attach not found`.
- ✅ Android 17 (API 37) and MuMu (Android 15, ARM bridge) are part of the current verification matrix.
- ✅ Compatibility samples cover multiple instances, explicit Activities, Providers, WebView, plugin components, and native modules.
- ✅ Multi-process routing, component bridging, Binder/UID proxies, and the native environment are implemented; the runtime is no longer limited to a single-process entry Activity.
- ✅ The in-engine virtual app list/launch (not system PMS/AMS hooks), desktop entry, and target PackageManager virtualization are integrated.

Implementation locations: `app` (`engine/MiniContainerEngine.kt`, `container/ContainerContext.kt`, `container/ContainerSession.kt`, `container/ContainerProcessPool.kt`, and `ui/DesktopScreen.kt`).

### 7.3 Key Stage C acceptance (implemented)

- Two instances run in different `:c1..:c16` slot processes, and crashes do not affect each other.
- The target app's native libraries can load.
- Native library loading and diagnostics distinguish aligned, unaligned, and ABI-mismatch cases; a universal ELF `p_align` preflight remains a follow-up enhancement.
- Java hooks for `Process.myUid`/`Binder.getCallingUid` cover at least common checks (no system-side UID spoofing).

### 7.4 Key Stage D acceptance (core implemented)

- Notifications can be displayed (or explicitly degraded).
- Static Receivers can receive.
- Job/Alarm at least do not crash (degradation is acceptable).
- WebView multiple instances do not share data (independent processes).
- One device each for Android 15/16/17 passes.

### 7.5 Key Stage F acceptance (AppFunctions fast path)

- The host app exposes at least 3 tools with `@AppFunction` (such as `space_list`, `space_launch`, `ui_observe`), discoverable by the system Agent or `adb shell cmd app_function`.
- The container test APK has AppFunctions; metadata is parsed at install time; execution can call them inside the container process.
- An ordinary APK calling another system app's AppFunctions is explicitly rejected (`EXECUTE_APP_FUNCTIONS`) and falls back to UI.
- Fast-path failure does not affect UI automation.

> Each stage can be demonstrated independently; do not wait for "full functionality" before verifying. If Stage A fails, the hook approach/version adaptation has a problem; solve it before moving on.

---

## 8. Test matrix

### 8.1 Devices

| Type | Examples | Focus |
|---|---|---|
| Pixel | Pixel 8/9/10 (Android 15/16/17) | Native behavior, hidden API, AAPM |
| Samsung | S24/S25/S26 (One UI) | Background restrictions, Knox |
| Xiaomi | 14/15 (HyperOS) | Background, permissions, package management |
| OPPO/vivo | Find X / X series | Permissions, background, accessibility |
| Huawei | Devices without GMS | GMS dependence, HMS |
| 16 KB pages | New devices with Android 15+ | native library alignment |

### 8.2 Apps

- In-house test APK (minimal Activity, Resources, native library, split, Service, Receiver)
- Simple open-source apps (small tools on F-Droid)
- Common social, e-commerce, travel, and content app categories (compatibility targets, not promised)
- Games (Unity/UE, Surface/Canvas)
- Flutter apps
- WebView-heavy apps
- Banking apps (expected to fail; used to verify degradation messages)

### 8.3 Scenarios

- Installation: single APK, split APK, XAPK, low targetSdk, 16 KB
- Launch: cold start, warm start, background wakeup, instance switching
- Lifecycle: rotation, split screen, lock screen, back, recent tasks
- Permissions: first launch, runtime permissions, deny/allow
- Data: files/cache/db/sp, external storage, uninstall cleanup
- Components: Activity, Service, Receiver, Provider, Notification
- Exceptions: network loss, timeout, crash, ANR, low memory
- Dual-channel: the same task goes through the local Agent and remote MCP separately, with consistent results
- Concurrency: while the local Agent holds a lease, mutating calls from remote MCP return `SPACE_BUSY`; observation calls are unaffected
- Approval: both channels can trigger the same approval UI; timeout results in denial

### 8.4 Metrics

- Installation success rate, launch success rate, task success rate
- Per-step latency p50/p95
- Crash rate, ANR
- Token cost per task
- Mis-tap rate, human takeover rate
- Container: Activity launch success rate, resource load success rate, native library load success rate, 16 KB pass rate, process survival
- Dual-channel: tool consistency pass rate, lease conflicts/queueing/takeover count, approval timeout rate

---

## 9. Risk list

| Risk | Impact | Mitigation |
|---|---|---|
| Hidden API changes | Hooks fail, launch crashes | Version adaptation layer; staged rollout; fixed test matrix |
| Binder/UID restrictions | System services judge by host UID; some app features fail | Do not promise strong UID isolation; Java hooks cover common checks; state boundaries clearly |
| Background launch restrictions | Agent cannot bring up UI from the background | Foreground service/notification/user trigger; design "visible tasks" |
| Notifications/Job/Provider | Target app background capabilities degrade | Phased support; explain in product copy |
| Keystore/signatures/Play Integrity | Financial/strong-verification apps unusable | Do not promise; detect and warn |
| 16 KB / split APK | Installation/loading failure | Preflight; clear errors; test matrix |
| Process pool/memory | Jank or being killed with many instances | Process pool limits; foreground priority; degrade under low memory |
| Container security | Malicious apps read host/other-instance data | Install only trusted APKs; do not advertise security isolation |
| Prompt injection | Agent manipulated by on-screen content | Treat screen text as data; tool allowlist; confirm dangerous operations |
| Dual-channel concurrency | Local/remote operations interfere | Leases + sessions + user takeover |
| Local Agent model capability | On-device models weak at planning/tool calls | ClaudeAdapter fallback; on-device models only for simple tasks |
| API key leakage | Cost/abuse | Keystore storage; proxy service; rate limits |
| Automation violates ToS | Account bans | User notice; rate limiting; avoid payment/risk-control flows |
| OEM restrictions | Background/permissions/accessibility unavailable | Device matrix; fallback plans |
| Performance/battery | Poor experience | Foreground service optimization; lower screenshot frequency; wake on demand |
| MCP exposure | Controlled over LAN | Loopback by default; token; TLS; LAN switch off by default |
| Privacy | Screen content contains sensitive information | Masking; no disk persistence; audit; user can disable screenshots |
| Legal/compliance | GPL/Play/third-party ToS | Clean-room; legal review; comply with REQUIRE_SECURE_ENV |

---

## 10. Reference projects and materials

### AOSP / Android

- AOSP `ActivityThread`: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/ActivityThread.java
- AOSP `Instrumentation`: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/Instrumentation.java
- AOSP `LoadedApk`: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/LoadedApk.java
- AOSP `ContextImpl`: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/ContextImpl.java
- Android 17 developer preview: https://developer.android.com/about/versions/17
- 16 KB page size: https://developer.android.com/guide/practices/page-sizes
- Hidden API restrictions: https://developer.android.com/guide/app-compatibility/restrictions-non-sdk-interfaces

### AppFunctions

- AppFunctions overview: https://developer.android.com/ai/appfunctions
- Adding AppFunctions to an app: https://developer.android.com/ai/appfunctions/add-appfunctions
- `AppFunctionManager` reference: https://developer.android.com/reference/android/app/appfunctions/AppFunctionManager
- androidx.appfunctions: https://developer.android.com/reference/androidx/appfunctions/package-summary
- Official sample: https://github.com/android/appfunctions
- Community sample (discovery/invocation): https://github.com/FilipFan/AppFunctionsPilot

### Reference implementations (architecture reference only, GPL, do not copy)

- VirtualApp (asLody): https://github.com/asLody/VirtualApp
- BlackBox / NewBlackbox: https://github.com/ALEX5402/NewBlackbox

### Hook / Native

- ShadowHook: https://github.com/bytedance/android-inline-hook
- ByteHook: https://github.com/bytedance/bhook
- Dobby: https://github.com/jmpews/Dobby
- xDL: https://github.com/hexhacking/xDL

### Control / Agent

- Shizuku: https://github.com/RikkaApps/Shizuku
- MCP Kotlin SDK: https://github.com/modelcontextprotocol/kotlin-sdk
- MCP Inspector: https://github.com/modelcontextprotocol/inspector

### Policies

- Google Play container apps and REQUIRE_SECURE_ENV: https://support.google.com/googleplay/android-developer/answer/13609005
- Restricting container loading (Android Developers): https://developer.android.com/training/basics/intents/limit-play-loading
- Android 17 AAPM report: https://www.androidauthority.com/android-17-beta-2-advanced-protection-mode-accessibility-apps-3648860/

### On-device models

- ML Kit GenAI Agent API: https://developers.google.com/ml-kit/genai/agent/android
- Gemma 4 local Agent: https://android-developers.googleblog.com/2026/04/gemma-4-new-standard-for-local-agentic-intelligence.html

---

## Appendix A: APK preflight checklist

```text
[ ] packageName / versionCode / versionName
[ ] minSdk / targetSdk
[ ] ABI (arm64-v8a / armeabi-v7a / x86_64)
[ ] Whether it is a split APK (base + config.*)
[ ] Whether native libraries are 16 KB aligned (Android 15+)
[ ] Whether REQUIRE_SECURE_ENV=1 is declared → reject if yes
[ ] Whether it depends on GMS / Play Integrity
[ ] Whether it uses Android Keystore
[ ] Whether it uses custom signature verification
[ ] Whether it contains multiple processes/foreground services/JobScheduler
[ ] Whether it contains WebView / Flutter / Unity (affects control method)
[ ] List of dangerous permissions
[ ] Installation package signatures (whether the source is trusted)
```

## Appendix B: Hook point map

| Subsystem | Java hook | Native fallback | Version notes |
|---|---|---|---|
| PackageManager | `ApplicationPackageManager`, `IPackageManager`, `ActivityThread.getPackageManager` | `Binder.execTransact` | AIDL methods change between versions |
| Activity | `Instrumentation.execStartActivity`, `ActivityThread.mH`, `IActivityManager` | `Binder.execTransact` | Android 10+ background launch restrictions; Android 14+ PendingIntent |
| ClassLoader | `LoadedApk`, `PathClassLoader`, `DexClassLoader` | — | split/multi-dex |
| Resources | `ResourcesManager`, `AssetManager.addAssetPath` | `open` | split/overlay |
| Binder/UID | `Binder.getCallingUid`, `Process.myUid`, `UserHandle` | `Binder.execTransact` | System-side UID cannot be forged |
| IO | `ContextImpl`, `SharedPreferencesImpl`, `SQLiteDatabase` | `open`/`fopen` | scoped storage |
| Notifications | `NotificationManager`, `PendingIntent` | — | Android 12 trampoline; Android 13 permission |
| Process | Host `:c1..:c16`, `StubActivity`, `VClientService` | — | Do not use `app_process` child processes |
| Native libs | `System.loadLibrary`, `System.load` | `dlopen`/`android_dlopen_ext` | 16 KB; ABI |
| WebView | `WebView.setDataDirectorySuffix` (process-level) | — | Multiple instances need separate processes |

## Appendix C: Clean-room checklist

```text
[ ] Do not copy GPL code from VirtualApp/BlackBox
[ ] Reference AOSP (Apache-2.0) and public documentation
[ ] Record the source of each hook point (AOSP class/method/version)
[ ] Check the license of each native hook library
[ ] Legal review: GPL, Play container policy, third-party app ToS
[ ] Code isolation: people who have read GPL code do not participate in the clean-room implementation (optional but safer)
[ ] Do not claim "security isolation" in the product
[ ] Comply with REQUIRE_SECURE_ENV
```

## Appendix D: Minimal viable demo (recommended first step)

**Goal**: The same task, controlled by remote Claude Code (MCP) and the local Agent respectively, operating the phone's "Settings" app to complete "open Settings → search for WLAN → turn on WLAN."

**Scope:**

- No container
- No installer
- Only:
  1. `ToolRegistry`: 3 tools (`ui_observe`, `ui_tap`, `ui_key`), argument validation + scope
  2. `SpaceAccessibilityService` (observe + tap + back)
  3. `McpForegroundService` (built-in minimal HTTP server + token)
  4. `LocalAgentRuntime` (use `ClaudeAdapter` or a fake model first; loop inside the app)
  5. `ControlLease` + `ApprovalGate` (implement at least exclusive leases and confirmation dialogs)

**Acceptance:**

- Remote Claude Code can list/call tools and complete 3 tasks
- The local Agent can use the same tools to complete the same tasks
- Tool behavior is consistent across both channels (same input → same result/error code)
- While the local Agent holds a lease, remote mutating calls return `SPACE_BUSY`; observation calls are unaffected
- Per-step p50 < 2 s
- Structured errors on failure

Once this demo works end to end, proceed to Stage A (single-process minimal container).

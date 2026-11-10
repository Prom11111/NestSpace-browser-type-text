# Architecture & limitations

This document is intended for readers who want to understand implementation details or troubleshoot compatibility issues. For the complete roadmap and target design, see the [technical design (Chinese)](https://github.com/chuccp/NestSpace/blob/main/docs/android-virtual-space-agent-mcp.md).

## Overall structure

NestSpace consists of three parts:

- **App-level container**: Loads the target APK inside the host process and provides a virtualized Android runtime environment for the target app.
- **Desktop and space management**: Installation, multiple instances, data isolation, icon organization, and system app selection.
- **Control channel**: Binder-based UI control inside the container, the local AI assistant, and the externally facing MCP Server.

The container is not system-level virtualization; it runs inside NestSpace's own process and simulates the environment the target app needs through ClassLoader, resources, Context, and system service proxies.

## Code modules

The runtime is split into two Gradle modules with explicit boundaries:

- `runtime-api`: engine contracts, shared models, the Application factory registry, and host hooks.
- `runtime-core`: APK and instance storage, sessions, Activity hosts, process slots, Providers, lifecycle handling, the native facade, MCP UI support, hardened Application bootstrap resolution, and the compatible Application factory.

The dependency direction is `runtime-core → runtime-api`, while `app` is the control plane. Runtime backends are installed through `RuntimePluginLoader`; `app` does not call backend implementations directly.

## Installation and launch

### Installation

1. Parse the APK's manifest, resource table, and signature.
2. Validate the package name, signature consistency, `minSdk`, and `REQUIRE_SECURE_ENV`.
3. Copy the APK into the app-private directory and write the installation record; split APKs are merged into a single installation descriptor.
4. Create an instance for the app; instance data directories are separated by `data/<package>/<instance>`.

### Launch

1. Allocate a slot process from the container process pool by `package@instance`.
2. Load the target code with `DexClassLoader`, load target resources with `AssetManager`, and construct a target-aware `Context` and `Resources`.
3. Attach the target Activity to the host window through reflection and drive the full lifecycle.
4. The target's `Application` executes `attachBaseContext` and `onCreate`.

## Isolation

- Each `package@instance` is pinned to an independent host process from `:c1` to `:c16`.
- An instance's `files`, `cache`, `code_cache`, `databases`, `getDir`, and SharedPreferences use independent namespaces.
- Native libraries are extracted by ABI into the instance directory and used as the `librarySearchPath` for `DexClassLoader`; when the same instance is reused, the old process is ended first to avoid sharing JNI global state for `.so` files with the same name.
- Target Java exceptions are intercepted and recorded by a crash protection layer; native crashes or `System.exit` affect only the corresponding slot process.

The process pool is managed automatically by default and has a maximum of 16 slots; when memory is tight, background containers are reclaimed in least-recently-used order, and foreground containers are not closed. A fixed slot limit (1–16) can also be set.

## Virtualized system capabilities

- **Activity**: Driven through the full callback sequence such as `onCreate → onStart → onRestoreInstanceState → onPostCreate → onResume → onPostResume`, supporting in-container Activity navigation, the back stack, splash screens, and `startActivityForResult`.
- **Service / Provider / Broadcast**: Target Services are instantiated by the target ClassLoader; Providers are routed by authority; manifest and dynamically registered BroadcastReceivers support normal, ordered, and sticky broadcasts.
- **PackageManager**: Components, permissions, signatures, UID, labels/icons, resources, and launch Intents for the target package are answered from the APK descriptor; queries across virtual apps in the same space and implicit Intent resolution are also served by the virtual index.
- **Notifications**: Package name, tag, channel, and group are isolated by virtual package, and the small icon and PendingIntent are automatically rewritten into a form the host accepts.
- **JobScheduler**: A local scheduler is returned inside the container, target `JobService` components are driven by the target ClassLoader, and delays, periodic execution, backoff, and common constraints are supported.
- **Permissions**: Queries such as `checkSelfPermission` go through the virtual permission table; `requestPermissions` is connected to the host's real permission request, and results are persisted per package.
- **GMS**: Binder transactions to `IGmsServiceBroker` have the package name and `AttributionSource` rewritten, covering common call paths such as WebView/Chimera.
- **UI control**: Inside the container process, Binder is used to observe the view tree, tap, input text, and take screenshots, and to merge windows created by target Activities such as dialogs and bottom sheets; no accessibility service is required.

## Control and Agent

- **Local AI assistant**: Supports Claude-compatible and OpenAI-compatible endpoints and operates the `virtual-1` space through `space_*`, `ui_*`, and `browser_*` tools.
- **Remote MCP**: A built-in Streamable HTTP Server that listens only on `127.0.0.1` by default, with LAN listening available when explicitly enabled; supports multi-client sessions, SSE heartbeats, and DELETE sessions.
- **External MCP tools**: The local assistant can connect to external MCP Servers; tool names are isolated with an `mcp_<id>_` prefix and are not exposed to remote clients.
- **Browser**: A built-in multi-tab WebView; headless tabs are created by the Agent and can be taken over by the visible browser; downloads, uploads, and the proxy are shared with the container tools.

## Known limitations

- Start with apps you are familiar with; if you run into compatibility issues, it is easier to tell whether the problem is in the app itself or in container adaptation, and it is also easier to report to the project.
- Compatibility depends on the target app's app hardening, native SDKs, dynamic loading, and dependency combination; not every app can run directly, and issues need to be adapted one app at a time.
- At most 16 container processes can run at the same time, and the actual number is also limited by device memory; in automatic mode, when memory is insufficient, the least recently used background container is reclaimed.
- Job, Alarm, and broadcast bridging work only while the container process is alive; after the process is reclaimed by the system, it will not be woken by the system. Full background keepalive still requires a foreground service strategy.
- Cross-virtual-app `startActivity` still falls back to the host and currently mainly supports the query and resolution level; cross-app Provider queries only support the package's own authority.
- Android 7.x (API 24–25) does not have `ContentResolver.wrap`, so Provider virtualization is disabled on those versions; the other container capabilities remain available.
- Standalone processes such as Google Play services validate against the real UID/package name, and only some Binder interfaces are currently proxied; different apps may require additional adaptation.
- Apps containing only arm64 native libraries require an arm64 device; an x86_64 emulator returns `ABI_MISMATCH` before launch.
- A target Activity's `savedInstanceState` is stored in-process and supports same-process recreation, but is not retained across process death.
- The synthetic UID is used only for app-layer queries; the system side still sees the host UID.
- Headless launch depends on the background activity launch exemption provided by the overlay permission; without it, `space_launch` returns `LAUNCH_BLOCKED`.
- LAN MCP uses plaintext HTTP + Bearer Token without TLS and should only be enabled on trusted networks.
- The built-in browser proxy requires Android 10+ and WebView support for `PROXY_OVERRIDE`; proxies that require a username/password are not currently supported.
- External MCP clients currently support only HTTP POST (with JSON or SSE responses) and Bearer Token.
- The AI assistant is fixed to operate within the `virtual-1` space and does not control system-wide apps.

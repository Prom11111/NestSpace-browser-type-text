# Runtime module boundaries

The runtime is split into a shared execution core and a compatibility
Application backend:

```text
:app
├── :runtime-api
├── :runtime-core
└── :runtime-compat
```

Dependency direction:

```text
:runtime-compat ──> :runtime-core ──> :runtime-api

:app ──> :runtime-api
     ├─> :runtime-core
     └─> :runtime-compat
```

- `runtime-api` contains shared contracts and data models.
- `runtime-core` owns APK and instance storage, target sessions, Activity
  hosts, process slots, Provider bridges, lifecycle policy, native environment
  and MCP UI support.
- `runtime-compat` owns hardened Application bootstrap resolution,
  compatibility profiles and the compatible Application factory.
- `app` is the control plane and UI. Runtime plugins are installed through
  `RuntimePluginLoader`; the app does not call the backend implementation
  directly.

# Troubleshooting

First try **Space settings → Container self-check**. It covers basic capabilities such as launching, the interface, screenshots, isolated processes, WebView, and MCP, and produces a report you can copy.

## Installation and launch

### Import APK fails

| Message | Cause | What to do |
| --- | --- | --- |
| `SIGNATURE_MISMATCH` | An installation record exists for the same package name with a different signature | Uninstall the old app first, or rebuild the APK with the same signature |
| `SECURE_ENV_REQUIRED` | The target declares `REQUIRE_SECURE_ENV` | The current container does not meet this requirement, so it cannot be installed |
| `minSdk`-related error | The target's minSdk is higher than the current system version | Upgrade the system or use a compatible version |
| Parse failed / not a valid APK | The file is corrupted or is not an APK | Download it again and confirm that the file is intact |

### Installation succeeds but launch fails

- First check whether `ABI_MISMATCH` is returned. Apps that contain only arm64 native libraries cannot run on an x86_64 emulator.
- The target app may use app hardening, hot updates, or a bundled native SDK; such apps need to be adapted one at a time.
- Open **Container self-check** and confirm that basic container capabilities are working.
- Note the app name, version, and error information, and submit an issue.

### Black screen, white screen, or crash after launch

Common causes:

- A native library the target app depends on was not loaded;
- The target app detected an abnormal runtime environment;
- The target Activity uses a system capability that has not yet been virtualized;
- Incomplete resources or splits.

Things to try:

1. Confirm that all split APKs were imported.
2. In **Running apps**, close all processes for the app, then launch it again.
3. Try an APK from a different version or architecture.
4. Submit an issue with the **Container self-check** report and the error information.

### No app icon on the desktop

- Go to **Space settings → App management** and confirm that the app is in the **Virtual apps** list.
- If it was only removed from the desktop, reinstall it or use **Launch** in the app list.
- System apps must be selected again under **Import desktop apps**.

### Installation records disappear after a restart

Installation records are normally persisted. If you have cleared NestSpace app data, installation records and instance data are lost together and must be imported again.

## Multiple instances and memory

### Earlier apps are closed after opening several apps

This is expected memory-management behavior:

- The container process pool is managed automatically by default and has a maximum of 16 slots.
- When memory is tight, the least recently used background containers are closed first; foreground containers are not closed.
- You can set a fixed limit in **Space settings → Container slots**.
- In **Running apps**, you can manually close processes you no longer need to free memory.

### Is instance data lost?

Ending a process does not delete data. App data is stored in `files/vspace/data/<package>/<instance>/` and is used again the next time the app starts.

### Can data be shared between instances?

No. Each instance's files, cache, databases, code_cache, and SharedPreferences namespaces are independent. To share data, you can only use the app's own account sync or export features.

## Background and headless operation

### `LAUNCH_BLOCKED`

Starting a container without the NestSpace interface open requires the background activity launch exemption provided by the overlay permission:

1. Open **Space settings → MCP control service**.
2. Grant the "Display over other apps" permission as prompted.
3. Call `space_launch` again.

### The app is killed by the system after going to the background

Android reclaims processes according to memory and background policies. NestSpace can only guarantee that a foreground container is not closed by its own slot management; it cannot prevent the system from reclaiming the entire app. If you need long-term background operation, disable battery optimization and allow background activity.

### Scheduled tasks do not run

- Confirm that the task is enabled.
- Allow NestSpace to run in the background and disable power-saving restrictions on alarms.
- Check whether the system restricts exact alarm permission.
- Scheduling resumes after the next start.

## AI assistant

### Rule mode works but the large model does not respond

- In **Space settings → Model settings**, confirm that a configuration has been saved and selected.
- Tap **Test connection** to confirm that the endpoint is available.
- Check the Base URL, API Key, and model name.
- Check error messages in the conversation and **Token usage**.

### Tool call limit reached

By default, a single conversation turn can make at most 128 tool calls. For complex tasks, increase **Max tool rounds** or split the task into several steps.

### The assistant does not operate the target app

The assistant is fixed to "virtual space 1". Confirm that the app is installed and launched and is not occupied by another instance.

### No voice recognition result

- Grant recording permission.
- Confirm that the offline speech model has been downloaded.
- In **Space settings → Floating dot**, switch the speech model and try again.
- If the first download fails, try a different model source.

## Browser

### The page does not open or stays blank

- Check the network connection.
- If a proxy is enabled, confirm that the proxy address and port are correct, or temporarily disable the proxy.
- Some sites block WebView; try opening the same address in the system browser to verify.
- When a page requires sign-in or a CAPTCHA, handle it with the hand-to-user flow.

### The proxy does not work

- Requires Android 10+ and WebView support for `PROXY_OVERRIDE`.
- Enter only the host name or IP in the server address; do not include `http://`, a port, or a slash.
- Proxies that require a username/password are not supported.
- After changing the settings, reopen the browser tab.

### Download fails

- Confirm that the system and NestSpace have permission to write to the download directory.
- Check the available storage space.
- Some sites use Blob URLs or protected links, which WebView may not be able to download directly.

## MCP

### Connection refused

- Confirm that the service shows that it is running in **MCP control service**.
- In USB mode, confirm that `adb forward tcp:8765 tcp:8765` has been run.
- In LAN mode, confirm the same Wi-Fi network, the correct IP, and that local network permission has been granted.

### 401 / 404

- 401: The Token is incorrect or the `Authorization: Bearer <token>` header is missing.
- 404: The path is incorrect or the session has expired. The address should end with `/mcp`; sessions are reclaimed after 30 minutes of inactivity, so simply reconnect.

### The computer connects but nothing changes on the phone

- Confirm that you are calling container tools and that the target app has been launched in `virtual-1`.
- If background launch is needed, check the overlay permission.
- Check the error information returned by the MCP tool.

## Data and reset

### Where is the data

```text
/data/user/0/com.coke.nestspace/files/vspace/
  apps/                      Installed APKs
  data/<package>/<instance>/ Instance data
```

### How to fully reset

Clear NestSpace app data in system settings, or uninstall NestSpace from the system. Both delete:

- All virtual apps and instance data;
- The desktop layout and wallpaper;
- Model configurations, memories, skills, and scheduled tasks;
- The MCP Token.

### How to delete only one app

**Space settings → App management** → **Uninstall** in the app row. This deletes only that app and all of its instance data.

## When reporting an issue, include

- Phone model and Android version;
- NestSpace version (**Space settings → About**);
- Target app name and version;
- The **Container self-check** report;
- The exact steps you took and any error messages.

## Related documentation

- [Architecture & limitations](architecture.md)
- [App installation & management](app-manager.md)
- [MCP control service](mcp.md)

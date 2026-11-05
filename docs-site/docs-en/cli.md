# nestspace-mcp CLI

`tools/nestspace-mcp` is an MCP client implemented using only the Python standard library. It supports Streamable HTTP, one-shot tool calls, and an interactive shell. No third-party Python packages need to be installed.

## Preparing the connection

First start the service in **Space settings → MCP control service** and copy the Token.

USB (recommended):

```bash
adb forward tcp:8765 tcp:8765
export NESTSPACE_MCP_URL=http://127.0.0.1:8765/mcp
export NESTSPACE_MCP_TOKEN='<token>'
```

LAN (explicitly enabled in settings; HTTP + Token, no TLS, trusted networks only):

```bash
export NESTSPACE_MCP_URL=http://<phone-LAN-IP>:8765/mcp
export NESTSPACE_MCP_TOKEN='<token>'
```

The Token can also be read from a file:

```bash
tools/nestspace-mcp --token-file /path/to/token health
```

The CLI never prints the Token.

## Common commands

```bash
tools/nestspace-mcp health
tools/nestspace-mcp tools
tools/nestspace-mcp call browser_tabs
tools/nestspace-mcp call browser_open '{"url":"https://example.com"}'
tools/nestspace-mcp call browser_open --arg url=https://example.com --arg headless=false
tools/nestspace-mcp call browser_click --arg text=More --json
tools/nestspace-mcp shell
```

### `--arg`

Use `--arg key=value` to pass parameters. Values are recognized automatically:

- `true` / `false` / `null`;
- Integers and floating-point numbers;
- JSON arrays and objects.

If a value must remain a string, pass a JSON object as a positional argument:

```bash
tools/nestspace-mcp call ui_set_text '{"text":"123","package":"com.example.app"}'
```

### `--json`

Prints the raw MCP result, including image data. Without `--json`:

- Text content is printed directly;
- Image blocks are shown only as a summary.

### `shell`

The interactive mode lets you call tools one after another. Entering `disconnect` sends `DELETE /mcp` to close the current session.

### `setup`

Prints copyable environment variables and a `claude mcp add` command without exposing the Token.

## Sessions

- The client stores the `Mcp-Session-Id` returned by the server.
- After the service restarts (for example, when switching LAN mode), old sessions return 404, and the CLI automatically reinitializes and retries once.
- One CLI process corresponds to one session; `shell` mode reuses the same session throughout.

## Security

- Connects to `127.0.0.1` by default.
- LAN mode listens on `0.0.0.0`, and all tool calls require a Bearer Token.
- Traffic is plaintext HTTP and should only be used on trusted Wi-Fi / LAN networks.
- After using LAN mode, turn it off in **Space settings → MCP control service**.

## Example: install and launch an APK

```bash
# After downloading an APK to the phone's Downloads directory, install it via MCP
tools/nestspace-mcp call space_install_apk \
  --arg apk_uri=content://com.android.providers.downloads.documents/document/1234

# List installed virtual apps
tools/nestspace-mcp call space_list_installed

# Launch
tools/nestspace-mcp call space_launch --arg package_name=com.example.app

# Observe the interface
tools/nestspace-mcp call ui_observe
```

For exact parameters, refer to `tools/nestspace-mcp tools` and the [MCP tool reference](mcp-tools.md).

## Related documentation

- [MCP control service](mcp.md)
- [MCP tool reference](mcp-tools.md)

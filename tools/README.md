# NestSpace MCP CLI

`nestspace-mcp` is a small standard-library Python client for the NestSpace
MCP server. It supports MCP Streamable HTTP, one-shot tool calls and an
interactive shell.

## Connection

USB (loopback) is the default and safest mode:

```bash
adb forward tcp:8765 tcp:8765
export NESTSPACE_MCP_URL=http://127.0.0.1:8765/mcp
export NESTSPACE_MCP_TOKEN='<token from NestSpace > 空间设置 > MCP 控制服务>'
```

LAN mode (explicitly enabled in the same settings page; HTTP + bearer token,
no TLS, trusted networks only):

```bash
export NESTSPACE_MCP_URL=http://<phone-lan-ip>:8765/mcp
export NESTSPACE_MCP_TOKEN='<token>'
```

The token can also be read from a file with `--token-file PATH`. It is never
printed by the CLI.

## Usage

```bash
tools/nestspace-mcp health
tools/nestspace-mcp tools
tools/nestspace-mcp call browser_tabs
tools/nestspace-mcp call browser_open '{"url":"https://example.com"}'
tools/nestspace-mcp call browser_open --arg url=https://example.com --arg headless=false
tools/nestspace-mcp call browser_click --arg text=More --json
tools/nestspace-mcp shell
```

`--arg` values are auto-typed (`true`/`false`/`null`, integers, floats and
JSON arrays/objects). Pass a JSON object as the positional argument when a
value must stay a string.

`--json` prints the raw MCP result, including images. Without it, text
content is printed directly and image blocks are summarized.

`setup` prints shell exports and a `claude mcp add` command without exposing
the token.

The client keeps the server's `Mcp-Session-Id`. If the server restarts (for
example after toggling LAN mode) and returns 404 for an expired session, the
CLI reinitializes and retries once automatically. In `shell`, `disconnect`
sends `DELETE /mcp` for the current session.

## LAN security

LAN mode binds the MCP server to `0.0.0.0`; the bearer token is required for
tool calls. Traffic is plain HTTP, so enable it only on a trusted Wi-Fi/LAN.
On Android 17+ the app also requests `ACCESS_LOCAL_NETWORK`; if it is denied,
the server stays on `127.0.0.1`.

The default remains loopback-only. Disable LAN mode in
`空间设置 > MCP 控制服务` when it is no longer needed.

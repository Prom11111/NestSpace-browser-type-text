# MCP control service

NestSpace includes an MCP (Model Context Protocol) Server that exposes container control capabilities to MCP clients on your computer, such as Claude Code. It is not needed for everyday phone use; it is an optional capability for advanced users and automation scenarios.

## Starting the service

Entry point: **Space settings → MCP control service**.

- The service starts automatically with the NestSpace process.
- After a device restart, it is started automatically via `BOOT_COMPLETED`.
- The page lets you start and stop it manually and view its running status.

It listens by default on:

```text
http://127.0.0.1:8765/mcp
```

It binds only to the local loopback address, so a computer cannot access it directly; use USB port forwarding or enable LAN mode.

## Token

A Bearer Token is generated on first start. You can view and copy it in **MCP control service → Connection info**.

- All tool calls require `Authorization: Bearer <token>`.
- Do not share the Token on public channels.
- The Token remains the same after the service is stopped and started again (unless app data is cleared).

## USB connection (recommended)

1. Connect the phone to the computer with USB and confirm that `adb devices` lists the device.
2. Forward the port:

```bash
adb forward tcp:8765 tcp:8765
```

3. Add the MCP Server in Claude Code:

```bash
claude mcp add --transport http space http://127.0.0.1:8765/mcp \
  --header "Authorization: Bearer <token>"
```

Other MCP clients can connect in the same way as long as they support Streamable HTTP and custom headers.

## LAN connection

If you do not want to use USB:

1. Connect the phone and the computer to the same Wi-Fi network.
2. Turn on **LAN access** in **MCP control service**.
3. Android 17+ requests the `ACCESS_LOCAL_NETWORK` permission; if it is denied, the service falls back to listening only on `127.0.0.1`.
4. Use the address shown on the page:

```text
http://<phone-LAN-IP>:8765/mcp
```

Note: LAN mode is **HTTP + Token, without TLS**. Use it only on trusted networks, and never expose it on public Wi-Fi or the public internet.

## Starting containers in the background

If you want an Agent to start containers without opening the NestSpace interface, you need to grant the **overlay permission**:

- NestSpace uses a 1×1 transparent, non-focusable, non-touchable window to obtain Android's background activity launch (BAL) exemption.
- Without this permission, `space_launch` returns `LAUNCH_BLOCKED`.
- The **MCP control service** page shows the background launch status and an entry point for granting permission.

The Floating dot is a separate visible overlay and is not the same as this hidden window; turning off the Floating dot does not affect background launch.

## CLI

The repository includes a zero-dependency Python client:

```bash
export NESTSPACE_MCP_URL=http://127.0.0.1:8765/mcp
export NESTSPACE_MCP_TOKEN='<token>'

tools/nestspace-mcp health
tools/nestspace-mcp tools
tools/nestspace-mcp call browser_tabs
tools/nestspace-mcp call browser_open '{"url":"https://example.com"}'
tools/nestspace-mcp shell
```

`--arg` automatically recognizes `true` / `false` / `null`, integers, floating-point numbers, and JSON; `--json` outputs the raw result. For detailed usage, see [nestspace-mcp CLI](cli.md).

## External MCP tools (the opposite direction)

**Space settings → MCP tools** configures **external** MCP Servers for NestSpace's own AI assistant to use; they are a separate feature from the control service NestSpace exposes:

- Supports multiple Streamable HTTP endpoints;
- Uses Bearer Token authentication;
- Tool names are exposed to the local assistant with an `mcp_<short-service-id>_` prefix;
- They are not registered in NestSpace's `ToolRegistry`, so remote clients cannot see them.

## Sessions and limits

- Each client uses its own `Mcp-Session-Id`, which is reclaimed after 30 minutes of inactivity.
- Unknown or expired sessions return 404.
- Request body limit 40 MB, request header limit 64 KB, and 32 concurrent connections.
- Supports `initialize`, `tools/list`, `tools/call`, GET SSE heartbeats, and DELETE sessions.
- For the tool list, see [MCP tool reference](mcp-tools.md).

## Common issues

### Connection refused

- Confirm that **MCP control service** shows that it is running.
- In USB mode, confirm that `adb forward tcp:8765 tcp:8765` has been run.
- In LAN mode, confirm that the phone and computer are on the same network and that the local network permission has been granted on Android 17+.
- Confirm that the address is `http://.../mcp` and that `/mcp` has not been omitted.

### Returns 401

The Token is incorrect or the `Authorization` header is missing. Copy the Token again and confirm that quoting and spaces in the command are correct.

### Returns 404

- The address path is incorrect.
- The session has expired (after 30 minutes of inactivity). Reconnect; the CLI automatically reinitializes and retries once.

### Tools work but containers cannot be launched

If `space_launch` returns `LAUNCH_BLOCKED`, grant the overlay permission and try again, or keep NestSpace in the foreground.

### The LAN address is not shown

First confirm that **LAN access** is turned on; Android 17+ requires the `ACCESS_LOCAL_NETWORK` permission. AP isolation on some routers prevents the phone and computer from reaching each other.

## Security recommendations

- Keep it listening only on `127.0.0.1` by default.
- Enable LAN mode only temporarily on trusted networks and turn it off when finished.
- Do not map port 8765 to the public internet.
- If the Token is leaked, clear NestSpace app data to generate a new one.

## Related documentation

- [MCP tool reference](mcp-tools.md)
- [nestspace-mcp CLI](cli.md)
- [Architecture & limitations](architecture.md)

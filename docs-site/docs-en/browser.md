# Built-in browser

NestSpace includes a multi-tab browser built on WebView, with a **Browser** entry on the desktop. It shares the same browser capabilities with the AI assistant: the assistant can open tabs, read pages, click, and download in the background, and you can take over at any time.

## Opening the browser

- **Browser** on the desktop.
- The AI assistant calls `browser_open` to open the visible browser (`headless=false`).
- By default, the assistant opens **headless tabs**, which you can see and take over from the tab bar of the visible browser.

## Tabs

- Multiple tabs are supported, with up to 10 retained at the same time.
- The tab bar lets you switch, close, and create tabs.
- Closing the current tab selects an adjacent tab.
- Headless tabs are created by the assistant. After you open the visible browser, you can select and take them over directly without losing page state.
- All tabs share the same process but each has its own WebView.

## Address bar

- Enter a full URL to open a page.
- Entering ordinary text runs a Bing search.
- Back, forward, and refresh are supported.

## Headless mode

When the assistant calls `browser_open`, `headless=true` is the default:

- Pages load in the background without taking up the foreground;
- The assistant can use `tab_id` to read, click, and navigate multiple pages separately;
- When the user needs to handle a CAPTCHA or sign in, the assistant can call `browser_hand_to_user` to switch the page to the visible browser.

## Downloads

- Downloads triggered by a page are saved to the system **Download** directory.
- On Android 10+, they are saved through MediaStore, returning the file name, path, size, and a `content://` URI.
- A downloaded APK can be selected directly in **App management → Import APK**, or passed to `space_install_apk`.
- The assistant can call `browser_download` to download a URL directly, or wait for a page-triggered download to finish.

## Uploads

- Upload buttons on a page open the system file picker.
- The assistant can call `browser_upload` to complete the currently pending file selection with a local path or a `content://` URI.
- You can also select a file manually.

## CAPTCHAs, sign-in, and anti-bot measures

When a page shows a CAPTCHA, human verification, login wall, or anti-bot block:

- The assistant detects the `needs_user` / `user_reason` / `challenge` indicators.
- It calls `browser_hand_to_user` to switch the tab to the visible browser and hand it to you.
- After you tap **Done**, the assistant receives the current URL, title, body text, and whether the page is still blocked.

The assistant does not attempt to bypass CAPTCHAs or login restrictions.

## Proxy

Entry points:

- The gear icon in the upper-right corner of the browser.
- **Space settings → Browser proxy**.

Configuration options:

- Enable/disable;
- Type (HTTP / SOCKS);
- Server address (enter only the host name or IP, without a scheme, port, or slash);
- Port;
- Addresses that bypass the proxy (comma-separated, optional).

Notes:

- All tabs and browser downloads share the same proxy.
- Requires Android 10+ and WebView support for `PROXY_OVERRIDE`.
- Proxies that require username/password authentication are not currently supported.
- The configuration is restored automatically after restarting the app.
- You can also configure it with the MCP tool `nestspace_browser_proxy`.

## Limitations

- Pages run in WebView and are not equivalent to the system browser; some sites detect WebView or restrict functionality.
- Sign-in state depends on WebView cookies; clearing NestSpace data also clears it.
- Anti-bot measures, CAPTCHAs, and sign-in must be handled by the user; the assistant only hands them over.
- The proxy forwards traffic in plaintext and does not terminate TLS.

## Related documentation

- [AI assistant](assistant.md)
- [MCP tool reference](mcp-tools.md)

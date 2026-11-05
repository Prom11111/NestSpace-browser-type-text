# App / website knowledge packs

NestSpace provides the AI assistant with a set of short, hand-written Chinese usage guides covering common apps and websites. The first time the assistant enters an app or page, it injects the corresponding guide into the tool result; at other times the guides do not consume prompt tokens.

Currently built in:

- 23 apps: Alipay, Amap, Bilibili, BOSS Zhipin, DeepSeek, DingTalk, Douyin, Feishu, JD.com, Kuaishou, Meituan, 12306, Pinduoduo, QQ, QQ Mail, Taobao, Tencent Video, Toutiao, WeChat, Weibo, Xianyu, Xiaohongshu, Zhihu.
- 19 websites: 12306, Alipay, Amap, Bilibili, Ctrip, DeepSeek, Douyin, Ele.me, JD.com, Kuaishou, Meituan, NetEase Cloud Music, Pinduoduo, QQ, Taobao, Weibo, Xiaohongshu, Zhihu, BOSS Zhipin.

## Directory structure

```text
app/src/main/assets/knowledge/
  apps/<id>/APP.md
  sites/<domain>/SITE.md
```

- `apps/<id>` uses a stable short name, such as `bilibili` or `pinduoduo`.
- `sites/<domain>` uses a registrable domain, such as `bilibili.com` or `jd.com`.
- Directory names must match `name` / `domains` in the front matter.

## File format

### App guide

```markdown
---
name: bilibili
display: Bilibili
packages: [tv.danmaku.bili]
aliases: [Bilibili, bilibili]
domains: [bilibili.com]
---
## What it is
One or two sentences explaining what this app does.

## Entry points and common paths
- Stable entry point names, tab names, page titles.
- Common URL patterns or search paths.

## Notes
- Steps that require sign-in, CAPTCHA, payment, or user confirmation.
- Risky or irreversible actions.

## How to tell it is done
- Which page or text indicates that the task is complete.
```

### Website guide

```markdown
---
name: bilibili.com
display: Bilibili website
domains: [bilibili.com]
---
## What it is
...
```

Website guides use `domains` instead of `packages`.

## Field descriptions

| Field | Required | Description |
| --- | --- | --- |
| `name` | Yes | Unique identifier; use a short name for apps and a domain for websites |
| `display` | Yes | User-facing name |
| `packages` | Required for apps | Android package names; multiple values are allowed |
| `aliases` | No | Aliases used for matching and display |
| `domains` | Required for websites | Registrable domains; multiple values are allowed |

Rules:

- App guides must have `packages`; website guides must have `domains`.
- Package names and domains must be unique across all guides.
- Keep the body within 4000 characters; it is injected verbatim.

## Writing principles

- Describe **stable** things: button text, tab names, URL patterns, page titles. Do not hard-code coordinates, prices, or one-off content.
- Make clear which steps require user involvement: sign-in, CAPTCHAs, payments, permissions, sending messages, deleting data.
- Do not teach the assistant to bypass CAPTCHAs, sign-in, payment confirmation, or platform restrictions.
- State the "done criteria" clearly, for example which page or which text should appear.
- The current screen always takes precedence over the guide; the guide can mention this as well.

## Matching and injection

`KnowledgeStore` loads these files at runtime and matches them as follows:

- `space_launch` / `space_multi_open`: By package name.
- `ui_observe`: By the `package` parameter or the package name in the current snapshot.
- `browser_open` / `browser_navigate`: By the host of the resulting URL, with the most specific domain taking precedence (`search.bilibili.com` matches `bilibili.com`).

A matched guide is:

- Returned as the `guide` field of the JSON result;
- Or appended after the plain-text result;
- Injected only once per run (local assistant) or per MCP session, to avoid repeatedly consuming context.

## Adding a guide

1. Create the directories:

   ```text
   app/src/main/assets/knowledge/apps/<id>/APP.md
   app/src/main/assets/knowledge/sites/<domain>/SITE.md
   ```

2. Fill in the front matter and body.
3. Run the knowledge pack tests:

   ```bash
   ./gradlew :app:testDebugUnitTest --tests com.coke.nestspace.knowledge.KnowledgePackTest
   ```

4. Rebuild the APK. No index or code changes are needed.

## Common issues

### The guide is not injected

- Confirm that the package name or domain matches the front matter exactly.
- Confirm that the body does not exceed 4000 characters.
- `ui_observe` needs the correct `package`, or the page snapshot itself must have a package name.
- Browser matching is by host; confirm that the URL's domain is listed in `domains`.

### The guide content is too long

Keep only stable entry points, notes, and completion criteria. Leave detailed steps for the assistant to observe on the current screen.

### Multiple guides match the same page

Domain matching chooses the most specific one; package names should remain unique, so avoid using the same package name in multiple guides.

## Related documentation

- [AI assistant](assistant.md)
- [MCP tool reference](mcp-tools.md)

# App / 网站知识包

NestSpace 给 AI 助手准备了一批简短的、人工编写的中文使用指南，覆盖常见 App 和网站。助手第一次进入某个应用或页面时，会把对应指南注入到工具结果里；其他时候不占用提示词。

当前内置：

- 23 个 App：支付宝、高德地图、哔哩哔哩、BOSS直聘、DeepSeek、钉钉、抖音、飞书、京东、快手、美团、12306、拼多多、QQ、QQ邮箱、淘宝、腾讯视频、今日头条、微信、微博、闲鱼、小红书、知乎。
- 19 个网站：12306、支付宝、高德、哔哩哔哩、携程、DeepSeek、抖音、饿了么、京东、快手、美团、网易云音乐、拼多多、QQ、淘宝、微博、小红书、知乎、BOSS直聘。

## 目录结构

```text
app/src/main/assets/knowledge/
  apps/<id>/APP.md
  sites/<domain>/SITE.md
```

- `apps/<id>` 使用稳定的短名，例如 `bilibili`、`pinduoduo`。
- `sites/<domain>` 使用可注册的域名，例如 `bilibili.com`、`jd.com`。
- 目录名要和 front matter 里的 `name` / `domains` 一致。

## 文件格式

### App 指南

```markdown
---
name: bilibili
display: 哔哩哔哩
packages: [tv.danmaku.bili]
aliases: [B站, 哔哩哔哩, bilibili]
domains: [bilibili.com]
---
## 是什么
一两句话说明这个应用是做什么的。

## 入口与常见路径
- 稳定的入口名称、Tab 名称、页面标题。
- 常见的 URL 规则或搜索路径。

## 注意
- 需要登录、验证码、支付或用户确认的步骤。
- 有风险或不可逆的操作。

## 怎样算完成
- 用什么页面或文字可以判断任务完成。
```

### 网站指南

```markdown
---
name: bilibili.com
display: 哔哩哔哩网页版
domains: [bilibili.com]
---
## 是什么
...
```

网站指南用 `domains` 代替 `packages`。

## 字段说明

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `name` | 是 | 唯一标识，App 用短名，网站用域名 |
| `display` | 是 | 给用户看的名称 |
| `packages` | App 必填 | Android 包名，可以多个 |
| `aliases` | 否 | 别名，用于匹配和展示 |
| `domains` | 网站必填 | 可注册域名，可以多个 |

规则：

- App 指南必须有 `packages`；网站指南必须有 `domains`。
- 包名和域名在所有指南里必须唯一。
- 正文控制在 4000 字符以内，会原样注入。

## 编写原则

- 描述**稳定**的东西：按钮文字、Tab 名称、URL 规则、页面标题。不要写死坐标、价格或一次性的内容。
- 明确哪些步骤需要用户参与：登录、验证码、支付、权限、发送消息、删除数据。
- 不要教助手绕过验证码、登录、支付确认或平台限制。
- 写清楚「完成标准」，例如出现哪个页面、哪段文字。
- 当前屏幕永远优先于指南；指南里也可以注明这一点。

## 匹配与注入

`KnowledgeStore` 在运行时加载这些文件，并按以下方式匹配：

- `space_launch` / `space_multi_open`：按包名。
- `ui_observe`：按 `package` 参数或当前快照的包名。
- `browser_open` / `browser_navigate`：按结果 URL 的 host，最具体的域名优先（`search.bilibili.com` 会匹配 `bilibili.com`）。

匹配到的指南会：

- 作为 JSON 结果的 `guide` 字段返回；
- 或附加在纯文本结果后面；
- 每次运行（本地助手）或每个 MCP 会话只注入一次，避免重复占用上下文。

## 新增一个指南

1. 创建目录：

   ```text
   app/src/main/assets/knowledge/apps/<id>/APP.md
   app/src/main/assets/knowledge/sites/<domain>/SITE.md
   ```

2. 填写 front matter 和正文。
3. 跑知识包测试：

   ```bash
   ./gradlew :app:testDebugUnitTest --tests com.coke.nestspace.knowledge.KnowledgePackTest
   ```

4. 重新构建 APK。不需要改索引或代码。

## 常见问题

### 指南没有被注入

- 确认包名或域名与 front matter 完全一致。
- 确认正文没有超过 4000 字符。
- `ui_observe` 需要带上正确的 `package`，或页面快照本身有包名。
- 浏览器按 host 匹配，确认 URL 的域名在 `domains` 里。

### 指南内容太长

只保留稳定的入口、注意事项和完成标准。详细步骤交给助手观察当前界面。

### 多个指南匹配同一页面

域名匹配会选最具体的一个；包名应保持唯一，避免同一包名出现在多个指南里。

## 相关文档

- [AI 助手](assistant.md)
- [MCP 工具参考](mcp-tools.md)

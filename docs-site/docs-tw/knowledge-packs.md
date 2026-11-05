# App / 網站知識包

NestSpace 給 AI 助手準備了一批簡短的、人工編寫的中文使用指南，涵蓋常見 App 和網站。助手第一次進入某個應用程式或頁面時，會把對應指南注入到工具結果裡；其他時候不佔用提示詞。

目前內建：

- 23 個 App：支付寶、高德地圖、嗶哩嗶哩、BOSS直聘、DeepSeek、釘釘、抖音、飛書、京東、快手、美團、12306、拼多多、QQ、QQ信箱、淘寶、騰訊視頻、今日頭條、微信、微博、閒魚、小紅書、知乎。
- 19 個網站：12306、支付寶、高德、嗶哩嗶哩、攜程、DeepSeek、抖音、餓了麼、京東、快手、美團、網易雲音樂、拼多多、QQ、淘寶、微博、小紅書、知乎、BOSS直聘。

## 目錄結構

```text
app/src/main/assets/knowledge/
  apps/<id>/APP.md
  sites/<domain>/SITE.md
```

- `apps/<id>` 使用穩定的短名，例如 `bilibili`、`pinduoduo`。
- `sites/<domain>` 使用可註冊的網域名稱，例如 `bilibili.com`、`jd.com`。
- 目錄名稱要和 front matter 裡的 `name` / `domains` 一致。

## 檔案格式

### App 指南

```markdown
---
name: bilibili
display: 嗶哩嗶哩
packages: [tv.danmaku.bili]
aliases: [B站, 嗶哩嗶哩, bilibili]
domains: [bilibili.com]
---
## 是什麼
一兩句話說明這個應用程式是做什麼的。

## 入口與常見路徑
- 穩定的入口名稱、Tab 名稱、頁面標題。
- 常見的 URL 規則或搜尋路徑。

## 注意
- 需要登入、驗證碼、付款或使用者確認的步驟。
- 有風險或不可逆的操作。

## 怎樣算完成
- 用什麼頁面或文字可以判斷任務完成。
```

### 網站指南

```markdown
---
name: bilibili.com
display: 嗶哩嗶哩網頁版
domains: [bilibili.com]
---
## 是什麼
...
```

網站指南用 `domains` 代替 `packages`。

## 欄位說明

| 欄位 | 必填 | 說明 |
| --- | --- | --- |
| `name` | 是 | 唯一識別，App 用短名，網站用網域名稱 |
| `display` | 是 | 給使用者看的名稱 |
| `packages` | App 必填 | Android 套件名稱，可以多個 |
| `aliases` | 否 | 別名，用於匹配和顯示 |
| `domains` | 網站必填 | 可註冊網域名稱，可以多個 |

規則：

- App 指南必須有 `packages`；網站指南必須有 `domains`。
- 套件名稱和網域名稱在所有指南裡必須唯一。
- 正文控制在 4000 字元以內，會原樣注入。

## 撰寫原則

- 描述**穩定**的東西：按鈕文字、Tab 名稱、URL 規則、頁面標題。不要寫死座標、價格或一次性的內容。
- 明確哪些步驟需要使用者參與：登入、驗證碼、付款、權限、傳送訊息、刪除資料。
- 不要教助手繞過驗證碼、登入、付款確認或平台限制。
- 寫清楚「完成標準」，例如出現哪個頁面、哪段文字。
- 目前畫面永遠優先於指南；指南裡也可以註明這一點。

## 匹配與注入

`KnowledgeStore` 在執行時載入這些檔案，並按以下方式匹配：

- `space_launch` / `space_multi_open`：依套件名稱。
- `ui_observe`：依 `package` 參數或目前快照的套件名稱。
- `browser_open` / `browser_navigate`：依結果 URL 的 host，最具體的網域名稱優先（`search.bilibili.com` 會匹配 `bilibili.com`）。

匹配到的指南會：

- 作為 JSON 結果的 `guide` 欄位傳回；
- 或附加在純文字結果後面；
- 每次執行（本機助手）或每個 MCP 工作階段只注入一次，避免重複佔用上下文。

## 新增一個指南

1. 建立目錄：

   ```text
   app/src/main/assets/knowledge/apps/<id>/APP.md
   app/src/main/assets/knowledge/sites/<domain>/SITE.md
   ```

2. 填寫 front matter 和正文。
3. 跑知識包測試：

   ```bash
   ./gradlew :app:testDebugUnitTest --tests com.coke.nestspace.knowledge.KnowledgePackTest
   ```

4. 重新建置 APK。不需要修改索引或程式碼。

## 常見問題

### 指南沒有被注入

- 確認套件名稱或網域名稱與 front matter 完全一致。
- 確認正文沒有超過 4000 字元。
- `ui_observe` 需要帶上正確的 `package`，或頁面快照本身有套件名稱。
- 瀏覽器依 host 匹配，確認 URL 的網域名稱在 `domains` 裡。

### 指南內容太長

只保留穩定的入口、注意事項和完成標準。詳細步驟交給助手觀察目前介面。

### 多個指南匹配同一頁面

網域名稱匹配會選最具體的一個；套件名稱應保持唯一，避免同一個套件名稱出現在多個指南裡。

## 相關文件

- [AI 助手](assistant.md)
- [MCP 工具參考](mcp-tools.md)

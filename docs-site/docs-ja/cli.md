# nestspace-mcp CLI

`tools/nestspace-mcp` は Python 標準ライブラリのみで実装された MCP クライアントで、Streamable HTTP、単発のツール呼び出し、対話型シェルに対応しています。サードパーティの Python パッケージをインストールする必要はありません。

## 接続の準備

まず「空间设置」（スペース設定）→「MCP 控制服务」（MCP 制御サービス）でサービスを起動し、Token をコピーします。

USB（推奨）：

```bash
adb forward tcp:8765 tcp:8765
export NESTSPACE_MCP_URL=http://127.0.0.1:8765/mcp
export NESTSPACE_MCP_TOKEN='<token>'
```

LAN（設定で明示的に有効化。HTTP + Token、TLS なし、信頼できるネットワークのみ）：

```bash
export NESTSPACE_MCP_URL=http://<手机局域网 IP>:8765/mcp
export NESTSPACE_MCP_TOKEN='<token>'
```

Token はファイルから読み込むこともできます：

```bash
tools/nestspace-mcp --token-file /path/to/token health
```

CLI は Token を出力しません。

## よく使うコマンド

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

`--arg key=value` でパラメータを渡すと、値は自動的に認識されます。

- `true` / `false` / `null`。
- 整数と浮動小数点数。
- JSON の配列とオブジェクト。

特定の値を文字列として維持する必要がある場合は、位置パラメータで JSON オブジェクトを渡します：

```bash
tools/nestspace-mcp call ui_set_text '{"text":"123","package":"com.example.app"}'
```

### `--json`

生の MCP 結果を出力します。画像データも含まれます。`--json` を付けない場合：

- テキスト内容はそのまま出力されます。
- 画像ブロックは概要のみ表示されます。

### `shell`

対話モードに入ると、続けてツールを呼び出せます。`disconnect` を入力すると `DELETE /mcp` を送信して現在のセッションを閉じます。

### `setup`

コピー可能な環境変数と `claude mcp add` コマンドを出力します。Token は公開しません。

## セッション

- クライアントはサーバーが返す `Mcp-Session-Id` を保存します。
- サービスを再起動（LAN モードへの切り替えなど）すると、古いセッションは 404 を返します。CLI は自動的に再初期化して 1 回再試行します。
- 1 つの CLI プロセスは 1 つのセッションに対応します。`shell` モードでは同じセッションをずっと再利用します。

## セキュリティ

- デフォルトでは `127.0.0.1` に接続します。
- LAN モードは `0.0.0.0` を待ち受け、すべてのツール呼び出しに Bearer Token が必要です。
- 通信は平文 HTTP です。信頼できる Wi-Fi / LAN でのみ使用してください。
- LAN モードを使い終わったら、「スペース設定 → MCP 制御サービス」でオフにしてください。

## 例：APK のインストールと起動

```bash
# 下载 APK 到手机下载目录后，用 MCP 安装
tools/nestspace-mcp call space_install_apk \
  --arg apk_uri=content://com.android.providers.downloads.documents/document/1234

# 查看已安装的虚拟应用
tools/nestspace-mcp call space_list_installed

# 启动
tools/nestspace-mcp call space_launch --arg package_name=com.example.app

# 观察界面
tools/nestspace-mcp call ui_observe
```

具体的なパラメータは `tools/nestspace-mcp tools` と [MCP ツールリファレンス](mcp-tools.md) を参照してください。

## 関連ドキュメント

- [MCP 制御サービス](mcp.md)
- [MCP ツールリファレンス](mcp-tools.md)

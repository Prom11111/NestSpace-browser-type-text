# MCP 制御サービス

NestSpace には MCP（Model Context Protocol）Server が内蔵されており、コンテナ制御機能を PC 上の MCP クライアント（Claude Code など）に公開します。スマートフォンの日常利用ではまったく必要ありません。上級ユーザーや自動化のシナリオ向けのオプション機能です。

## サービスの起動

入口：**「空间设置」（スペース設定）→「MCP 控制服务」（MCP 制御サービス）**。

- サービスは NestSpace のプロセスとともに自動的に起動します。
- デバイスの再起動後は `BOOT_COMPLETED` によって自動的に起動されます。
- ページ上で手動で起動／停止でき、実行状態を確認できます。

デフォルトの待ち受け：

```text
http://127.0.0.1:8765/mcp
```

ローカルのループバックアドレスにのみバインドされるため、PC から直接アクセスできません。USB ポート転送を行うか、LAN モードを有効にする必要があります。

## Token

初回起動時に Bearer Token が生成されます。「MCP 制御サービス」→「连接信息」（接続情報）で確認とコピーができます。

- すべてのツール呼び出しには `Authorization: Bearer <token>` が必要です。
- Token を公開チャネルに送信しないでください。
- サービスを停止して再起動しても Token は変わりません（アプリデータを消去した場合を除く）。

## USB 接続（推奨）

1. スマートフォンを USB で PC に接続し、`adb devices` でデバイスが表示されることを確認します。
2. ポートを転送します：

```bash
adb forward tcp:8765 tcp:8765
```

3. Claude Code に MCP Server を追加します：

```bash
claude mcp add --transport http space http://127.0.0.1:8765/mcp \
  --header "Authorization: Bearer <token>"
```

ほかの MCP クライアントでも、Streamable HTTP とカスタム Header をサポートしていれば同様に接続できます。

## LAN 接続

USB を使いたくない場合：

1. スマートフォンと PC を同じ Wi-Fi に接続します。
2. 「MCP 制御サービス」で「局域网访问」（LAN アクセス）をオンにします。
3. Android 17 以降では `ACCESS_LOCAL_NETWORK` 権限を要求します。拒否すると、サービスは `127.0.0.1` のみの待ち受けに戻ります。
4. ページに表示されるアドレスを使用します：

```text
http://<手机局域网 IP>:8765/mcp
```

注意：LAN モードは **HTTP + Token で、TLS はありません**。信頼できるネットワークでのみ使用し、公共 Wi-Fi や公衆インターネットに公開しないでください。

## バックグラウンドでのコンテナ起動

NestSpace の画面を開かずに Agent からコンテナを起動したい場合は、**オーバーレイ権限**を付与する必要があります。

- NestSpace は 1×1 の透明で、フォーカスもタッチもできないウィンドウを使用して、Android のバックグラウンド Activity 起動（BAL）の例外許可を取得します。
- 権限がない場合、`space_launch` は `LAUNCH_BLOCKED` を返します。
- 「MCP 制御サービス」ページにバックグラウンド起動の状態と権限付与の入口が表示されます。

フローティングドットは独立した表示可能なオーバーレイであり、この隠しウィンドウとは別物です。フローティングドットをオフにしてもバックグラウンド起動には影響しません。

## CLI

リポジトリには依存関係のない Python クライアントが同梱されています。

```bash
export NESTSPACE_MCP_URL=http://127.0.0.1:8765/mcp
export NESTSPACE_MCP_TOKEN='<token>'

tools/nestspace-mcp health
tools/nestspace-mcp tools
tools/nestspace-mcp call browser_tabs
tools/nestspace-mcp call browser_open '{"url":"https://example.com"}'
tools/nestspace-mcp shell
```

`--arg` は `true` / `false` / `null`、整数、浮動小数点数、JSON を自動的に認識します。`--json` は生の結果を出力します。詳細な使い方は [nestspace-mcp CLI](cli.md) を参照してください。

## 外部 MCP ツール（逆向き）

「スペース設定 → MCP 工具」（MCP ツール）で設定するのは**外部** MCP Server で、NestSpace 自身の AI アシスタントが使用します。NestSpace が外部に提供する制御サービスとは、2 つの独立した機能です。

- 複数の Streamable HTTP エンドポイントに対応。
- Bearer Token を使用。
- ツール名は `mcp_<サービス短 ID>_` プレフィックスを付けてローカルアシスタントに提供。
- NestSpace の `ToolRegistry` には登録されず、リモートクライアントからは見えません。

## セッションと制限

- 各クライアントは独立した `Mcp-Session-Id` を使用し、30 分間アイドル状態が続くと回収されます。
- 不明または期限切れのセッションには 404 を返します。
- リクエストボディの上限は 40 MB、リクエストヘッダーの上限は 64 KB、同時接続数は 32 です。
- `initialize`、`tools/list`、`tools/call`、GET SSE ハートビート、DELETE セッションに対応しています。
- ツール一覧は [MCP ツールリファレンス](mcp-tools.md) を参照してください。

## よくある質問

### 接続が拒否される

- 「MCP 制御サービス」が実行中と表示されていることを確認します。
- USB モードでは `adb forward tcp:8765 tcp:8765` を実行したことを確認します。
- LAN モードでは、スマートフォンと PC が同じネットワークにあり、Android 17 以降で LAN 権限が付与されていることを確認します。
- アドレスが `http://.../mcp` であることを確認し、`/mcp` を省略しないでください。

### 401 が返される

Token が正しくないか、`Authorization` Header が付いていません。Token をコピーし直し、コマンド内の引用符とスペースが正しいことを確認してください。

### 404 が返される

- アドレスのパスが正しくありません。
- セッションが期限切れです（30 分間アイドル）。再接続すれば、CLI が自動的に再初期化して 1 回再試行します。

### ツールは使えるがコンテナを起動できない

`space_launch` が `LAUNCH_BLOCKED` を返す場合は、オーバーレイ権限を付与してから再試行するか、NestSpace をフォアグラウンドに保ってください。

### LAN アドレスが表示されない

まず「LAN アクセス」がオンになっていることを確認します。Android 17 以降では `ACCESS_LOCAL_NETWORK` の付与が必要です。一部のルーターでは AP アイソレーションにより、スマートフォンと PC が相互に通信できなくなります。

## セキュリティの推奨事項

- デフォルトでは `127.0.0.1` のみを待ち受ける状態を維持してください。
- LAN モードは信頼できるネットワークでのみ一時的に有効にし、使用後はオフにしてください。
- ポート 8765 を公衆インターネットにマッピングしないでください。
- Token が漏洩した場合は、NestSpace のアプリデータを消去すると再生成できます。

## 関連ドキュメント

- [MCP ツールリファレンス](mcp-tools.md)
- [nestspace-mcp CLI](cli.md)
- [アーキテクチャと制限](architecture.md)

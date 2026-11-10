# NestSpace：Android 独自開発アプリレベルのコンテナ + デュアルチャネル Agent/MCP 技術設計

> 本文は技術設計と実装の記録であり、開発者および二次開発者向けです。日常的な使い方については [NestSpace ユーザーマニュアル](README.md) を参照してください。
>
> プロジェクト：`NestSpace`（`com.coke.nestspace`、Jetpack Compose、minSdk 24 / targetSdk 37）
> 日付：2026-11-03
> 制約：通常の APK であり、ROM は制御できない；**独自開発のコンテナエンジンで、VirtualApp 商用版は再利用しない**；ローカル Agent とリモート MCP のデュアルチャネルを両方サポートする。
> 状態：Stage A/B + Stage C-lite + Stage C-D（独立プロセス/クラッシュ分離、Job/通知仮想化、ホスト/システムブロードキャストブリッジ、Binder/UID 権限モデル、完全な VPMS、仮想アプリ間クエリ、権限付与、マルチインスタンス .so プロセス分離）は実装済みで、Android 7.1.1 / Android 16 / Android 17（API 37 エミュレーター）/ MuMu(Android 15, ARM bridge) で検証済み；ローカル LLM Agent（Claude/OpenAI 互換、共通 chat プロトコル）は実装済みで、mock とサードパーティの Anthropic 互換エンドポイントに対して実ネットワークで検証済み；完全なバックグラウンド Job/Alarm ウェイクアップとシステム側の真の UID 偽装は今後の拡張に属する

---

## 読み進め方

| 最初に読みたい内容 | 参照先 |
|---|---|
| 結論、実現可能性の境界、独自開発の制約 | **0** |
| 要件とスコープ | 1 |
| コンテナ全体のアーキテクチャ、プロセスモデル、フックの方針 | 2 |
| 主要サブシステム（PMS/AMS/ClassLoader/Binder/IO） | 3 |
| Agent による仮想アプリの操作方法 | 4 |
| ローカル Agent + リモート MCP のデュアルチャネル | 5 |
| アーキテクチャ、モジュール、インターフェース、プロジェクト改修 | 6 |
| 構築順序、テストマトリクス、リスク | 7–9 |

---

## 0. 結論

### 0.1 技術方針

- **独自開発のアプリレベルのコンテナエンジン**：ホストアプリ内で未インストールの APK を読み込み、PackageManager/ActivityManager/Binder/IO を仮想化し、対象アプリに自分が通常のシステム上で動作していると認識させる。
- **Java 層の仮想化を優先し、重要な箇所はネイティブフック**：まず `Instrumentation`/`ActivityThread`/`PackageManager`/`ContextImpl` の Java フックで動作させる；Binder/UID/ネイティブファイルパスなどの難所ではネイティブフックを導入する。
- **単一プロセス MVP → マルチプロセス製品**：まず 1 つのプロセスで簡単な APK を動作させ、その後ホストが宣言する `:c1..:c16` プロセスプールで複数インスタンスと対象プロセスを実行する。
- **デュアルチャネル Agent/MCP**：ローカル Agent（アプリ内ループ）+ リモート Agent（MCP）で、同じ `ToolRegistry`、権限、リース、監査を共有する。
- **AppFunctions ファストパス（任意）**：ホストアプリは AppFunctions をエクスポートできる；コンテナ内 APK の AppFunctions はローカルブリッジ経由で動作する；利用できない場合は UI 自動化にフォールバックする。
- **VirtualApp の商用ライセンスは利用しない**。VirtualApp はアーキテクチャの参考にとどめ、**クリーンルーム実装とし、その GPL コードはコピーしない**。

### 0.2 3 層アーキテクチャ

```text
┌─────────────────────────────────────────────────────────────┐
│ 3. Agent / MCP レイヤー                                     │
│    ローカル Agent（アプリ内ループ） | リモート Agent（MCP） │
│    ToolRegistry / リース / 承認 / 監査を共有                │
├─────────────────────────────────────────────────────────────┤
│ 2. 制御レイヤー                                             │
│    ContainerService（仮想空間/インストール/起動）           │
│    Shizuku / Accessibility / Vision（UI 観測と操作）        │
├─────────────────────────────────────────────────────────────┤
│ 1. 独自開発コンテナエンジン                                 │
│    Installer / VPMS / VAMS / ClassLoader / Resources        │
│    Binder/UID / IO Redirect / Permissions / Process Pool    │
└─────────────────────────────────────────────────────────────┘
```

### 0.3 実現可能性の境界（まず正直に）

**できること：**

- 通常の APK、単一プロセス、単一インスタンス
- マルチインスタンス、独立したデータディレクトリ
- 強力な完全性検証のないコンテンツ/ツール/SNS 系アプリ
- Shizuku/アクセシビリティ/視覚による仮想アプリの制御
- ローカル Agent + リモート MCP のデュアルチャネル

**難しいが、項目ごとに取り組む必要があること：**

- マルチプロセス + Binder/UID 仮想化
- split APK / AAB
- 16 KB page / ネイティブライブラリ
- 通知、JobScheduler、AlarmManager、ContentProvider
- バックグラウンドからの Activity 起動（Android 10+ の制限）
- 対象アプリの署名検証 / Keystore
- Android 15–17 の hidden API の変更

**できない/約束しないこと：**

- 真の UID/SELinux 分離（アプリレベルのコンテナはホスト UID を共有する）
- 金融/銀行/決済/Play Integrity/GMS に強く依存するアプリ
- 強力なアンチコンテナ/アンチフック/アンチデバッグアプリ
- 「すべてのアプリが動作する」

> 独自開発の正しい目標は「Android システムを置き換える」ことではなく、「通常のアプリ内で、制御された、互換性が限られた第 2 の仮想空間を動作させる」ことである。製品の説明文はこの境界に沿って書くべきである。

### 0.4 最大の技術リスク

1. **Hidden API / ART / Binder のバージョン適応**：Android はメジャーバージョンごとに `ActivityThread`、`PackageParser`、`Resources`、Binder トランザクションを変更する；「一度書けば長期間安定する」ものはない。
2. **Binder/UID 仮想化**：システムサービスから見えるのはホスト UID である；Java 層である程度ごまかせても、システム側の権限/AppOps/アカウント/決済などは依然としてホスト UID で判定される。これはアプリレベルのコンテナの根本的な制限である。
3. **バックグラウンドからの Activity 起動**：Android 10+ ではバックグラウンド起動が制限される；仮想アプリをホストのバックグラウンドから起動するとブロックされる。フォアグラウンドサービス/通知/ユーザー操作によるトリガーが必要である。
4. **通知/Job/Provider**：システムはパッケージ名/UID で帰属を判断する；これらを仮想化するのは Activity よりはるかに難しく、多くのアプリのバックグラウンド機能は劣化する。
5. **Keystore/署名/Play Integrity**：同じ UID では Keystore alias が競合する；署名検証/完全性検証は高い確率で失敗する。
6. **プロセスモデル**：`app_process` 子プロセスは phantom process killer に kill される；ホストが宣言する `:c1..:c16` プロセスプールの方が安定し、プロセス再利用、メモリ、クラッシュ分離を明示的に処理する。
7. **16 KB / split APK**：インストーラーとネイティブライブラリの読み込みの両方で適応が必要である。
8. **長期的な保守**：これは「ミニ Android ランタイム」であり、通常のアプリ機能ではない；AOSP を継続的に追跡する必要がある。

### 0.5 独自開発 ≠ 制約なし

自分で書く場合に VirtualApp のライセンスは不要だが、以下の制約は依然として存在する：

- **クリーンルーム / GPL**：VirtualApp の GPL コードをコピーしない；AOSP（Apache-2.0）と公開ドキュメントを参考に実装する。チームが GPL コードを見たことがある場合は、コード分離/法務評価を行うのが望ましい。
- **Google Play のコンテナポリシー**：コンテナアプリは許可されるが、対象アプリの `REQUIRE_SECURE_ENV` を確認する必要があり、コンテナ外のアプリの API を代理してはならない。
- **対象アプリの ToS**：自動操作、再パッケージング、検証回避はサードパーティアプリの規約に違反する可能性がある。
- **ネイティブフックライブラリのライセンス**：ShadowHook/ByteHook/Dobby/xDL などは個別にライセンスと商用条件を確認する必要がある。
- **Hidden API の制限**：Android 9+ では非 SDK インターフェースに制限がある；バージョンごとに異なる回避戦略が必要で、安定性は自己責任である。

---

## 1. 要件と制約

### 1.1 目標

- アプリ内に複数の「仮想空間」を作成する。
- 仮想空間内で APK をインストール、起動、停止、マルチインスタンス化する。
- ローカル Agent が自然言語で仮想空間内のアプリを操作する。
- リモート Agent が MCP 経由で同じ仮想空間を操作する。
- 2 つのチャネルは動作が一致し、互いに中断しない。

### 1.2 制約

- 通常の APK であり、ROM/システム署名は制御できない。
- 独自開発のコンテナエンジン；VirtualApp 商用版に依存しない。
- Android 12–17、arm64、可能な限り root しない。
- ローカル Agent のモデルはオンデバイス（Gemini Nano 4/Gemma 4）またはクラウド（Claude API）でよい。

### 1.3 機能スコープ

- 仮想空間管理：作成、削除、名前変更、一覧
- アプリ管理：APK のインストール（split を含む）、アンインストール、一覧、アイコン/名前
- 実行管理：起動、停止、フォアグラウンド切り替え、最近のタスク
- 観測：UI ツリー、スクリーンショット、現在のパッケージ名/Activity
- 操作：タップ、長押し、スワイプ、スクロール、入力、戻る/Home/最近のタスク
- 待機/検証：テキスト待ち、パッケージ名待ち、画面アイドル待ち
- MCP：リモート Agent によるツールの発見/呼び出し
- ローカル Agent：自然言語タスク → ツール呼び出しループ
- セッション：リース、承認、監査、軌跡
- スキル：記録、再生、パラメータ化

### 1.4 非機能要件

- 単一操作 p50 < 500 ms、p95 < 2 s（モデル推論を除く）
- 仮想空間間のデータ分離（論理的分離）
- クラッシュ率、ANR、バックグラウンド生存を観測可能
- インストール/起動失敗時に構造化エラーを返す
- MCP はデフォルトで loopback にのみバインドし、LAN は明示的に有効化する
- ローカル Agent とリモート Agent のツール動作が一致する

### 1.5 対象外（現行バージョン）

- 金融/銀行/GMS/Play Integrity 系アプリ
- 信頼できない APK の強力な分離
- システムレベルの仮想空間/マルチユーザー/ワークプロファイル
- クラウドスマートフォン/リモート実機
- 「すべてのアプリと互換」という約束

---

## 2. 独自開発コンテナの全体アーキテクチャ

### 2.1 設計目標

1. 対象アプリの `Activity` が正常に作成、表示、入力受付、Activity ライフサイクル実行できる。
2. 対象アプリが自分をインストール済みと認識する：`PackageManager` で自分の `PackageInfo`、権限、署名を照会できる。
3. 対象アプリのプライベートデータが独立したディレクトリに保存され、ホストを汚染しない。
4. 複数のインスタンスが共存できる。
5. クラッシュがホストを巻き込まない。
6. 制御レイヤーが対象アプリの UI を観測/操作できる。

### 2.2 プロセスモデル

**フェーズ A：単一プロセス（MVP）**

- ホストのメインプロセスで対象 APK を読み込む；対象 Activity はホストプロセスで動作する。
- 利点：実装が簡単で、デバッグが容易で、プロセス間通信が不要。
- 欠点：対象アプリのクラッシュがホストを巻き込む；マルチインスタンスが 1 つのプロセスを共有する；メモリ負荷が大きい。
- 適する用途：実現可能性の検証、簡単なアプリ。

**フェーズ B：ホストが宣言する `:c1..:c16` プロセスプール（製品版）**

- ホストの `AndroidManifest.xml` に `:c1..:c16` のスロットプロセスを宣言し、各プロセスに `StubActivity`/`VClientService` を宣言する。
- 仮想アプリを起動するとき、空いているプロセスを選択し、そのプロセス内の `StubActivity` を起動する；システムは通常の app プロセスと `ActivityThread` を作成する。
- そのプロセス内でコンテナフックを初期化し、対象 APK を読み込み、対象 Activity を実行する。
- 利点：通常の app プロセスであるため phantom process killer の影響を受けない；クラッシュ分離；マルチインスタンスを並列実行できる。
- 欠点：プロセスプールのサイズが固定；メモリ/起動コスト；プロセス再利用時に状態をクリーンアップする必要がある。

> `Runtime.exec("app_process ...")` で子プロセスを起動することは推奨しない：Android 12+ の phantom process killer に kill され、権限/プロセスの帰属もより複雑になる。

**プロセス割り当て戦略：**

- 仮想空間ごとに 1 プロセス；またはインスタンスごとに 1 プロセス；またはメモリ/フォアグラウンド状態に応じて動的に割り当てる。
- プロセスプールの上限はデバイスのメモリから自動計算し、最大 16 個とする。固定モードではユーザーが選んだ 1〜16 の値を使用する。
- フォアグラウンドのインスタンスは優先的に独占；バックグラウンドのインスタンスはプロセスを再利用できる。
- プロセスがクラッシュしたら dirty とマークし、クリーンアップしてから再利用する。

### 2.3 全体図

```text
┌──────────────────────────────────────────────────────────────────┐
│ Host main process                                                │
│                                                                  │
│  ┌────────────────┐  ┌────────────────┐  ┌────────────────────┐  │
│  │ ContainerService│  │ ToolRegistry   │  │ Host UI / MCP      │  │
│  │ - Installer     │  │ - Local Agent  │  │ - Space launcher   │  │
│  │ - VPMS          │  │ - MCP Server   │  │ - Agent console    │  │
│  │ - VAMS          │  │ - Lease/Approval│ │ - Installer        │  │
│  │ - Process Pool  │  └────────────────┘  └────────────────────┘  │
│  └───────┬────────┘                                              │
│          │ IPC (Binder/AIDL)                                     │
│  ┌───────▼─────────────────────────────────────────────────────┐  │
│  │ Client process :c1 (normal app process)                      │  │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────────┐   │  │
│  │  │ VClient      │  │ HookManager  │  │ Target Activity  │   │  │
│  │  │ (ActivityThread)│ │ Java/Native │  │ (target APK)     │   │  │
│  │  └──────────────┘  └──────────────┘  └──────────────────┘   │  │
│  │  Virtual ClassLoader / Resources / data dirs                 │  │
│  └─────────────────────────────────────────────────────────────┘  │
│                                                                  │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │ Client processes :c1 ... :c16 (same structure)              │ │
│  └─────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────┘
```

### 2.4 2 つの実装ルート

| ルート | 方法 | 利点 | 欠点 |
|---|---|---|---|
| **Java 層仮想化**（VirtualApp 方式） | `Instrumentation`、`ActivityThread.mH`、`ApplicationPackageManager`、`ContextImpl`、`Binder` の Java メソッドをフック；動的プロキシ/リフレクション | ネイティブフック不要；デバッグが比較的容易；大部分の Java 呼び出しをカバーできる | Hidden API の制限；Binder/UID/ネイティブ層はごまかせない；Android バージョンごとに適応が必要 |
| **ネイティブフック / ART** | `Binder.execTransact`、`Process.myUid`、`open`、`dlopen`、ART メソッドをフック | より強力；ネイティブコードとシステムコールを処理できる | 非常に複雑；ABI/バージョン/ROM の差異；安定性/セキュリティリスクが高い |

**推奨：Java ファースト、ネイティブは重要箇所のみ。**

- 第 1 版：Java 層フックで Activity/PMS/Resources/IO を動作させる。
- 第 2 版：Binder/UID/ネイティブパスに対してネイティブフックを実装する。
- 最初から「汎用 ART フックフレームワーク」を書かない；まず成熟したフックライブラリを使い、ビジネスロジックを動作させる。

### 2.5 フックフレームワーク

**Java 層：**

- リフレクション + 動的プロキシ（`Proxy`/`InvocationHandler`）で `IPackageManager`、`IActivityManager` などのインターフェースを置き換える。
- `Unsafe`/`MethodHandles` で final フィールドを変更する。
- Hidden API の除外：`VMRuntime.setHiddenApiExemptions` または「FreeReflection」系の手法；Android 10+ ではバージョンごとに対応が必要。
- `ActivityThread.mH` の `Handler.Callback` 置き換え（システムメッセージを壊さないよう注意）。
- `Instrumentation` サブクラスの置き換え（`ActivityThread.mInstrumentation`）。

**ネイティブ層：**

- PLT/GOT フック、インラインフック；メンテナンスされているライブラリ（ShadowHook、ByteHook、Dobby、xDL）を優先し、ライセンスを個別に確認する。
- 主要なフックポイント：`Binder.execTransact`、`Process.myUid`、`open`/`fopen`、`dlopen`/`android_dlopen_ext`。
- バージョン適応：Android のメジャーバージョンごとに offset/シンボルテーブルを保守する；フィーチャーフラグで段階的にリリースする。

**バージョン適応レイヤー：**

```kotlin
interface AndroidAdapter {
    val apiLevel: Int
    fun installPackageHooks()
    fun installActivityHooks()
    fun installResourceHooks()
    fun installBinderHooks()
    fun installNativeHooks()
}
```

各 API level に 1 つの実装を用意する；共通ロジックは base に抽出する；新しいバージョンで古いフックポイントが残っていると仮定しない。

---

## 3. 主要サブシステム

### 3.1 APK 解析 / インストーラー

**入力：**

- 単一 APK
- split APK（base + config.arm64_v8a + config.xxhdpi など）
- `.apks` / `.xapk`（展開後に split として処理）

**解析：**

- バイナリ `AndroidManifest.xml`（AXML）：package、versionCode、minSdk、targetSdk、application、activities、services、providers、receivers、permissions、meta-data、`REQUIRE_SECURE_ENV` property。
- `resources.arsc`：リソーステーブル、設定、split リソース。
- 署名：V1/V2/V3；署名者/証明書ダイジェスト。
- ネイティブライブラリ：ABI、16 KB アラインメント。
- split メタデータ：`splitNames`、`splitTypes`、`configForSplit`、`isFeatureSplit`。

**実装の選択：**

- AOSP の `PackageParser`/`ParsingPackageUtils` を使用する（hidden API で、バージョン差異が大きい）；または
- オープンソースの APK 解析ライブラリ（ARSCLib/apk-parser など）で独自モデルを構築する；または
- 両者の併用：解析はオープンソースライブラリ、実行時の `PackageInfo` は自前で構築する。

**インストール：**

1. 事前チェック（付録 A）。
2. APK/splits を仮想空間のプライベートディレクトリにコピーする。
3. 解析して内部の `PackageInfo`/`ActivityInfo`/`ServiceInfo`/`ProviderInfo` を構築する。
4. `data/`、`files/`、`cache/`、`databases/`、`shared_prefs/`、`lib/` を作成する。
5. VPMS に登録する。
6. ランチャー/ショートカットを作成する。
7. 初回起動時に `Application` を初期化する。

**必須チェック：**

- `REQUIRE_SECURE_ENV=1` → 読み込みを拒否。
- 16 KB にアラインされていないネイティブライブラリ → 16 KB デバイスでは拒否/警告。
- 不完全な split → 拒否。
- 低い targetSdk → 許可するかどうかは Android バージョンのポリシーに従う。

### 3.2 PackageManager 仮想化（VPMS）

目標：PackageManager 仮想化により、対象アプリが `Context.getPackageManager()` / `ActivityThread.getPackageManager()` を通じて「自分がインストール済み」であると認識できるようにする。

**仮想化が必要なメソッド（最低限）：**

- `getPackageInfo` / `getApplicationInfo` / `getActivityInfo` / `getServiceInfo` / `getProviderInfo`
- `resolveActivity` / `queryIntentActivities` / `queryIntentServices`
- `getInstalledPackages` / `getInstalledApplications`
- `getPackagesForUid` / `getPackageUid` / `getNameForUid` / `getPackageGids`
- `checkPermission` / `checkSignatures` / `getPackageInfo(GET_SIGNATURES)`
- `getLaunchIntentForPackage`
- `getApplicationEnabledSetting` / `setApplicationEnabledSetting`
- `getResourcesForApplication` / `getResourcesForActivity`
- `getPackagesHoldingPermissions`

**実装方法：**

1. **Context レベルのラッピング**：対象アプリにカスタム `Context`/`ContextWrapper` を渡し、`getPackageManager()` は `VPackageManager` を返す；`getResources()` は対象の Resources を返す；`getClassLoader()` は対象の ClassLoader を返す。
2. **ActivityThread/IPackageManager プロキシ**：`ActivityThread.getPackageManager()` が返す `IPackageManager` の動的プロキシを置き換える；非仮想パッケージはシステムに転送し、仮想パッケージは VPMS を照会する。
3. **ApplicationPackageManager フック**：`new ApplicationPackageManager(...)` を直接呼ぶ場合や静的呼び出しのケースを補完する。

> 1 + 2 を優先；3 はフォールバックとする。`IPackageManager` は AIDL インターフェースであり、Android バージョン間でメソッドが変わるため、プロキシはバージョンごとに生成/適応する必要がある。

**署名：**

- 対象 APK の実際の署名を返す（信頼できる場合）、または製品ポリシーに応じて偽造する；後者は完全性検証を引き起こすため推奨しない。
- `checkSignatures` は自分に対して `MATCH` を返す；システムパッケージに対しては実際の結果を返す。

### 3.3 仮想 ActivityManager（VAMS）

目標：対象アプリの `startActivity`/`startService`/`bindService`/`sendBroadcast` がシステムに拒否されず、仮想コンポーネントに起動できるようにする。

**核心：Stub Activity 方式**

1. ホストの Manifest に `StubActivity0..N` を宣言する（`android:process=":pX"`、異なる `launchMode`/`taskAffinity` を指定可能）。
2. `Instrumentation.execStartActivity` をフックする：
   - 対象 Intent が仮想パッケージを指しているか判定する；
   - はい：component をいずれかの `StubActivity` に置き換え、元の Intent/対象コンポーネント/インスタンス id を extras または VAMS マッピングに保存する；
   - いいえ：そのまま通す。
3. システムが `StubActivity` を起動する；`ActivityThread` がそれを作成する。
4. `Instrumentation.newActivity` / `ActivityThread.mH` をフックする：
   - 対象 APK の `ClassLoader` で対象 Activity をインスタンス化する；
   - `StubActivity` のインスタンス/フィールドを置き換える、または `StubActivity` に Activity ライフサイクルを代理させる；
   - 対象の `Resources`、`Context`、`Application`、`Window` を注入する。
5. Activity ライフサイクルコールバックを対象 Activity に転送する；`onBackPressed`/`onNewIntent`/`onActivityResult` も処理する。
6. タスクスタック：StubActivity を対象 Activity にマッピングし直す；`finish`/`moveTaskToBack`/最近のタスクでは対象の名前/アイコンを表示する。

**実装済み：Stage C-lite（2026-11-03）**

`StubActivity0..N` の配列は導入せず、`StageAActivity` 自体を「汎用 Stub」として再利用している：

- 各対象 Activity は 1 つの実在する `StageAActivity` インスタンスに対応し、`host.startActivity`/`startActivityForResult` でスタックに積まれる；戻る操作は Android 自身のタスクスタックが担当する。
- `ContainerInstrumentation` は AOSP の隠しメソッド `execStartActivity(Context, IBinder, IBinder, Activity, Intent, int, Bundle)` を宣言する（`override` を付けず、先に HiddenApiBypass の例外をインストールする）。実行時は ART の仮想ディスパッチによってそれが呼ばれる。
- 対象パッケージ内の明示的 ComponentName（activity-alias を含む）→ 新しい `StageAActivity` を作成し、extras に sessionId、対象クラス、元の Intent を格納する；外部 Intent → 現在のホストの起動に委ねる。
- `ContainerSessions` は sessionId ごとに対象 Application/ClassLoader/Resources を共有する；各対象 Activity は自分の Activity インスタンス、Theme、decorView のみを作成する。
- `finish()` は実際の host token を使用するため、現在の対象に対応する `StageAActivity` のみを閉じ、前のホストが自然に復元される；Splash → ホーム画面、`startActivityForResult` の結果返却はいずれも検証済み。
- Activity ライフサイクルはホストが転送する：onCreate/onStart/onRestart/onResume/onPause/onStop/onDestroy、および `onActivityResult`（リフレクションで対象 Activity の protected メソッドを呼び出す）。
- 未実装：IntentFilter/暗黙的 Intent の解決、`onNewIntent` の singleTop/CLEAR_TOP セマンティクス、Activity options/トランジション、`requestPermissions` の token マッピング、最近のタスクでの対象名/アイコン表示。
- 実アプリ互換性の補足（MuMu / Android 15 で検証）：
  - 対象 APK が AndroidX を同梱している場合がある。`DexClassLoader` はデフォルトで parent-first であり、対象クラスがホストの AndroidX にリンクされ、フィールド欠落が発生する。`ContainerClassLoader` を child-first に変更し、まず対象 dex を検索し、次にホスト/システムにフォールバックする。
  - 対象 Activity の `attachBaseContext` は `createConfigurationContext` を呼び出して locale を処理する場合がある；元の実装はホストのリソースを返していた。`ContainerContext` は現在、対象 APK の AssetManager + 新しい Configuration で Resources を再構築し、対象のパッケージ名、ClassLoader、ディレクトリを保持する ContextWrapper を返す。
  - 対象アプリはシステムサービスバインディング、Provider、Binder の仮想化に依存する場合がある。未対応時は個別アプリ向けの起動特例ではなく、明確な診断を出力する。

**実装済み：コンテナ内 Service / Provider（Stage C-lite+）**

- Service：
  - `ContainerContext` は `startService`/`startForegroundService`/`bindService`/`unbindService`/`stopService` をオーバーライドする；対象パッケージの明示的 Service は `ContainerServiceManager` に渡し、外部 Service はホストにフォールバックする。
  - 対象 Service は対象の `ContainerClassLoader` でインスタンス化し、`attachBaseContext(ContainerContext)`、`Service.mApplication` の設定を行い、`onCreate`/`onStartCommand`/`onBind`/`onUnbind`/`onDestroy` を直接駆動する。
  - `bindService` は `onBind` が返す `IBinder` をメインスレッドの `onServiceConnected` 経由で対象の `ServiceConnection` に渡す；`startActivityForResult` と Service コールバックはいずれも対象プロセス内で完了する。
  - 明示的 ComponentName のみ；暗黙的 Service Intent は未解決。`startForeground`/`stopSelf` は依然としてホストの AMS に到達する可能性がある。
- ContentProvider：
  - Session 起動時（Application.onCreate の前）に manifest に従って対象 Provider を作成し、`attachInfo(ContainerContext, ProviderInfo)` が `onCreate` を実行する。
  - `ContentResolver.wrap(dispatcherProvider)` で対象 resolver を取得する；dispatcher は URI authority に従ってローカル Provider にルーティングし、外部 authority はホストアプリケーションコンテキストの実際の resolver にフォールバックする。
  - query/insert/update/delete/bulkInsert/getType/call/openFile/openAssetFile/openTypedAssetFile をオーバーライドする；対象 Provider 同士は互いにクエリできる。
  - Provider 権限、プロセス分離、`FileProvider` の PackageManager meta-data クエリは仮想化されていない。
**実装済み：Stage C-D（2026-11-04）**

- プロセスモデル：`ContainerProcessPool` は各 `package@instance` を `:c1..:c16` のいずれかのコンテナスロットに固定する；スロットプロセスを再利用する前に強制 kill して終了を待機し、`ContainerSessions` はプロセス内で 2 つ目の session を拒否する。対象 Java の未捕捉例外は `TargetCrashGuard` が握りつぶしてクラッシュマークを書き込む；ネイティブクラッシュ/`System.exit` はスロットプロセスのみを kill し、メインプロセスと他のインスタンスは影響を受けない。次回の `space_launch` は `restarted_after_crash`/`crash_reason` を返す。
- JobScheduler：`ContainerContext.getSystemService(JOB_SCHEDULER_SERVICE)` は `ContainerJobScheduler` を返す；対象の `JobService` は対象の ClassLoader でインスタンス化し、`onCreate`/`onStartJob`/`onStopJob` を駆動し、最小遅延、周期、バックオフ、ネットワーク/充電/低バッテリー/アイドル制約をサポートする（判定不能な制約は満たされたものとして処理する）。プロセス存続中のみ有効。
- 通知：`INotificationManager` プロキシはパッケージ名をホストのパッケージに変更し、tag/channel/group に仮想パッケージのプレフィックスを追加し、small icon を bitmap に再描画し、対象の PendingIntent をホストの `ContainerLaunchProxyActivity` 中継アクティビティに差し替える；channel クエリ結果からはプレフィックスを除去する。
- ブロードキャストブリッジ：対象の静的/動的 Receiver の action はホストに登録される；システムの sticky/通常ブロードキャストはコンテナに注入され、対象が送出した自身のスコープ外のブロードキャストはローカルに配信した後、引き続きホストへ送信され、ループバック防止用の origin extra が付与される。
- 権限：`checkSelfPermission`/`checkPermission`/`enforce*`/PackageManager の権限クエリは仮想権限テーブルを参照する；`ContainerPackageManager.buildRequestPermissionsIntent`/`getPermissionControllerPackageName` を書き換え、隠しメソッド `Context.getPermissionRequestState` を宣言することで、`requestPermissions` をコンテナが引き継げるようにする。ホストが宣言した権限は実際のシステムダイアログを表示し、結果はパッケージごとに永続化される；MCP の `space_permissions` は list/grant/revoke をサポートする。
- 完全な VPMS：`VirtualPackageRegistry` は `state.json` から軽量インデックスを構築し、必要に応じて同じ仮想空間内の他の APK を解析する；`getInstalledPackages`/`getInstalledApplications`、明示的コンポーネント、暗黙的 Intent、`resolve*`/`query*`、ラベル/アイコン/Resources/起動 Intent/署名/権限宣言は、いずれも仮想アプリ間でカバーされる。他のアプリは安定した合成 UID を使用する。
  - パフォーマンス制約：一覧/ラベル/UID/署名は軽量インデックスを使用する；パッケージ未指定の暗黙的 Intent ではすべての仮想アプリをスキャンせず、明示的なパッケージ/コンポーネントのクエリ時のみ対応する APK を解析する。そうしないと、リソースの多い対象アプリの起動中に別の大容量 APK を解析してメインスレッドをブロックしてしまう。
- 互換性制約：`WindowManager` 動的プロキシは、Toast のパッケージ名書き換えが必要な対象コンテキストでのみ有効化する。グローバルプロキシは対象のネイティブ SDK が JNI に渡す可能性があり、CheckJNI が `invalid jobject` を報告してコンテナプロセスを abort する。
- マルチインスタンス `.so` 分離：同じパッケージの異なるインスタンスには異なるコンテナスロットを割り当てる；ネイティブライブラリはインスタンスディレクトリごとに抽出する；スロットプロセスの再利用時は先に古いプロセスを kill し、同名 `.so` と JNI グローバル状態の混用を避ける。

**その他のコンポーネント：**

- BroadcastReceiver：静的/動的登録；静的 receiver は VPMS で解析する必要があり、動的登録は `registerReceiver` をフックする；ホスト側のブリッジは Stage C-D を参照。
- JobScheduler/通知はコンテナ内で仮想化済み（上記参照）；WorkManager が自身の `SystemJobScheduler` を使う場合はコンテナの JobScheduler が使用され、システムのウェイクアップに依存する永続バックグラウンドタスクは依然として劣化する。`startForeground` のフォアグラウンドサービスは依然としてホストの AMS に到達する可能性がある。

**バックグラウンドからの Activity 起動：**

- Android 10+ ではバックグラウンド起動が制限される；ホストがフォアグラウンドであるか、通知/フォアグラウンドサービス/ユーザー操作が必要である。
- 方針：`SpaceLaunchActivity` 透明フォアグラウンド Activity + 通知；またはユーザーのタップ時に起動する；バックグラウンドの Agent がいつでも画面を起動できると仮定しない。
- Android 14+ では `PendingIntent` によるバックグラウンド起動に追加の制限があるため、バージョンごとに検証が必要である。

### 3.4 ClassLoader / Resources

**ClassLoader：**

- `DexClassLoader`/`PathClassLoader` で対象 APK の dex（base + splits）を読み込む。
- 親 ClassLoader：ホストの ClassLoader（AndroidX/Kotlin ランタイムを共有できる）または `ClassLoader.getSystemClassLoader()`；クラス衝突（対象アプリが同名ライブラリを同梱）に注意。
- マルチ dex：`BaseDexClassLoader` は dex リストをサポートする；split の dex をすべて追加する。
- 対象アプリの `Application`/`Activity` クラスは対象の ClassLoader で読み込む。
- `LoadedApk` の構築：リフレクションで `mApplicationInfo`、`mClassLoader`、`mResources`、`mAppDir`、`mNativeLibraryDir`、`mDataDir` を作成/設定する；`ActivityThread.mPackages` に登録する。

**Resources：**

- `AssetManager` に base + すべての split のリソースパスを追加する。
- 対象の `DisplayMetrics`/`Configuration` で `Resources` を作成する；`Resources.Theme` を設定する。
- `resources.arsc` の split/overlay を処理する；リソース id の衝突は split ごとにマージする。
- `Context.getResources()`/`getAssets()`/`getTheme()` は対象のリソースを返す。
- アイコン/名前：対象の `ApplicationInfo`/`Resources` から読み取り、仮想空間の UI に使用する。

**Application：**

- `app` の `TargetApplicationFactory` で対象 Application を作成し、通常・Tinker・ApplicationLike の起動チェーンを処理します。
- `attachBaseContext(targetContext)` → `onCreate()`。
- 対象アプリの `ContentProvider` 初期化順序を模擬する（`installContentProviders`）。

### 3.5 Binder / UID 仮想化（最も難しい）

**目標：**

- 対象アプリが `Binder.getCallingUid()`/`Process.myUid()`/`UserHandle.myUserId()` を呼び出したとき、仮想 UID/user が見える。
- 対象アプリがシステムサービスを呼び出すとき、システムはそれをホスト UID と認識する（現実）が、アプリ側は自分が仮想 UID であると認識する。

**Java 層でできること：**

- `Process.myUid()`、`Binder.getCallingUid()`、`UserHandle.myUserId()`、`Process.myUserHandle()` の戻り値をフックする。
- `PackageManager.getPackagesForUid`/`getNameForUid`/`getPackageUid` をフックして仮想結果を返す。
- `Context.getUserId()`/`getPackageName()`/`getOpPackageName()` をフックする。

**できないこと：**

- システムサービス側（AMS/PMS/AccountManager/Location/Notification）は依然としてホスト UID で権限/AppOps を判定する；システムを変更せずに、存在しない UID をシステムに認識させることはできない。
- Binder トランザクション内の `uid`/`pid` はカーネルが設定する；Java 層では変更できない。変更するにはネイティブで `Binder.execTransact`/`Parcel` をフックするしかなく、リスクが極めて高い。
- したがって：**アプリレベルのコンテナは真の UID 分離を提供できない**；「アプリの Java 層をごまかす」ことはできても、「システムをごまかす」ことはできない。

**推奨：**

- 第 1 版では UID 仮想化を行わない；package/component 仮想化のみ行う。
- 第 2 版では `Process.myUid`/`Binder.getCallingUid` に Java フックを適用し、一般的なチェックをカバーする。
- 第 3 版でネイティブ Binder フックを評価する；MVP の前提条件にはしない。

### 3.6 IO / データ分離

**リダイレクトが必要なパス：**

- `Context.getFilesDir()` / `getCacheDir()` / `getDir()` / `getDatabasePath()` / `getSharedPreferences()`
- `ContextImpl.getDataDir()` / `getDatabasesDir()` / `getSharedPreferencesPath()`
- `SQLiteDatabase.openDatabase` / `openOrCreateDatabase`
- `Environment.getExternalStorageDirectory()` / `getExternalFilesDir()`
- `System.loadLibrary` / `System.load` / `dlopen`
- `Runtime.exec` の作業ディレクトリ（任意）

**実装：**

- 対象アプリに `ContextWrapper` を渡し、上記のメソッドをオーバーライドしてインスタンスディレクトリを返す。
- 静的/内部呼び出しに対しては、`ContextImpl` の対応するメソッドをフックする。
- `SharedPreferencesImpl`/`SQLiteDatabase` の直接パス呼び出しに対しては、オープンメソッドをフックする。
- ネイティブのファイルアクセスに対しては、`open`/`fopen`/`stat` をネイティブフックする（任意、後期）。
- 各インスタンスに 1 つのルートディレクトリ：`/data/user/0/<host>/spaces/<spaceId>/instances/<instanceId>/<pkg>/`。

**注意：**

- これはセキュリティ分離ではない；ネイティブコード/絶対パスで回避できる。
- 外部ストレージ/MediaStore の分離は限定的である；製品上で明示する必要がある。
- バックアップ/リストアは許可されたディレクトリのみを対象とする。

### 3.7 権限 / AppOps / 通知

**権限：**

- 対象アプリの `checkPermission`/`checkCallingPermission` は仮想権限マネージャーを経由する：まず対象アプリの manifest 権限を調べ、次にホストが対応するシステム権限を付与済みか調べ、最後に製品ポリシーに従って返す。
- 実行時権限：`Activity.requestPermissions`/`Fragment.requestPermissions` をフックし、ホストがダイアログを表示する；ユーザーが許可したら仮想権限を記録し、必要に応じてシステムにホスト権限を申請する。
- `AppOpsManager`：対象アプリの操作はホストのパッケージ名/UID で記録される；正確さが必要な場合は `AppOpsManager` の Java メソッドをフックする（限定的）。
- 権限マッピングテーブルを保守する必要がある：対象権限 → ホスト権限 → 必須かどうか。

**通知：**

- 対象アプリの `NotificationManager.notify` は `Notification` の package/tag/id を書き換えるか、ホストのパッケージ名で通知を送りつつ対象のアイコン/文言を保持する必要がある。
- `PendingIntent` の package/UID は仮想コンポーネントにマッピングし直す必要がある；Android 12+ の通知 trampoline 制限に注意。
- Android 13+ の `POST_NOTIFICATIONS` 実行時権限はホストが申請する。
- 第 1 版では対象の通知をサポートしないことも可能；代替として「空间内通知中心」（仮想空間内通知センター）を提供する。

### 3.8 プロセスモデル / phantom process

- ホストが宣言する `:c1..:c16` スロットプロセスを使用し、`app_process` 子プロセスは使用しない。
- `StubActivity`/`VClientService` を `:c1..:c16` に宣言し、起動時にシステムが通常の app プロセスを作成する。
- スロット上限はデバイスのメモリから自動計算し、最大 16 個とする。固定モードではユーザーが選んだ 1〜16 の値を使用する。
- プロセス再利用前のクリーンアップ：対象 Activity/Service、ClassLoader、Resources、静的状態、ファイルロック。
- プロセスクラッシュ：dirty とマークして再起動する；ホスト UI に「空间已重启」（仮想空間が再起動されました）を表示する。
- フォアグラウンドサービスによる維持：ホストのメインプロセス + 現在の仮想空間プロセス；すべてのプロセスを常駐させない。
- Android 12+ の phantom process killer は通常の app プロセスには適用されないが、バックグラウンドプロセスは LMK に kill される；フォアグラウンドサービス/ユーザーに見える状態を使用する。

### 3.9 ネイティブライブラリ / 16 KB

- インストール時に ABI ごとに split を選択する；`.so` をインスタンスの `lib/` ディレクトリに展開する。
- `ApplicationInfo.nativeLibraryDir` はそのディレクトリを指す；`LoadedApk`/`ClassLoader` がそれを利用する。
- `System.loadLibrary`/`System.load` をフックする：対象アプリの `nativeLibraryDir` に従って解決する。
- 16 KB page：ELF の `p_align` をチェックする；サポートしていない場合は拒否/警告する。
- `dlopen` 名前空間：対象 `.so` が依存するシステムライブラリは linker が解決する；自前の `.so` は絶対パスを使用する。
- JNI：対象アプリの `System.loadLibrary` と `RegisterNatives` は通常、対象の ClassLoader 下で動作する；グローバル JNI 状態の衝突に注意する。
- 同じプロセスに 2 つのバージョン非互換の同じ `.so` を読み込まない（マルチインスタンスで衝突する可能性がある）；必要に応じてプロセス分離する。

現在の実装（Stage C-D）：

- `ApkInspector` は base/split 内の `lib/<abi>/*.so` をスキャンし、利用可能な ABI の集合を取得する。
- `NativeLibs` は `Build.SUPPORTED_ABIS` に従って最初に一致するものを選択し、その ABI の `.so` をフラットに `vspace/data/<pkg>/<instance>/native-libs` へ抽出し、stamp キャッシュで毎回の起動時の重複コピーを避ける。
- `DexClassLoader(dexPath, codeCache, nativeLibsDir, parent)`、および `ApplicationInfo.nativeLibraryDir` を設定する。
- インストールは ABI を理由に失敗しない；起動前にチェックし、一致する ABI がなければ `ABI_MISMATCH` を返し、デバイス/アプリの ABI を示す。arm64-only APK は x86_64 エミュレーターではここで停止する。
- `dlopen`/`android_dlopen_ext` フックは、新しく読み込まれたモジュールを増分スキャンキューへ送る。
- 同じパッケージの異なるインスタンスは異なるスロットを使用する。スロット再利用前に旧プロセスを終了するため、同名 `.so` と JNI グローバル状態は混在しない。
- 汎用 ELF `p_align` の事前チェックはまだ強制ゲートではない；16 KB 互換性はビルド設定、デバイステスト、実行時診断でカバーする。

### 3.10 バックグラウンド / Job / Provider / WebView

これらは「製品化」で避けて通れない難所であり、段階的に進めることを推奨する：

| 機能 | 難易度 | 第 1 版の方針 |
|---|---|---|
| JobScheduler | 高 | 非サポート；対象アプリのバックグラウンドタスクは劣化 |
| AlarmManager | 高 | 部分サポート；ホストのパッケージ名でスケジュールし、ウェイクアップ後に転送 |
| ContentProvider | 高 | まずアプリ間 provider は非サポート；内部 provider はフックを試みる |
| 静的 BroadcastReceiver | 中 | VPMS 解析 + ホスト receiver への転送 |
| 通知 | 中 | 第 1 版では未対応；後続で代理 |
| WebView | 中 | `WebView.setDataDirectorySuffix` はプロセス単位；マルチインスタンスには独立プロセスが必要 |
| アカウント/同期 | 高 | 約束しない |
| デスクトップショートカット | 中 | ホストのショートカットが StubActivity を指す |

### 3.11 フック / バージョン適応

- **Java フックポイント**：`ActivityThread`、`Instrumentation`、`LoadedApk`、`ApplicationPackageManager`、`ContextImpl`、`Binder`、`Process`、`UserHandle`、`ResourcesManager`。
- **ネイティブフックポイント**：`Binder.execTransact`、`Process.myUid`、`open`/`fopen`、`dlopen`/`android_dlopen_ext`。
- **バージョン適応**：API level ごとに 1 つの `AndroidAdapter`；フックポイントが存在しない場合は機能を降格/無効化する。
- **段階的リリース**：デバイス/Android バージョン/対象アプリごとに切り替える；オンラインクラッシュ時はロールバック可能。
- **テスト**：Android バージョンごとに最低 1 台の実機；AOSP/Pixel を優先し、OEM は後から補う。

### 3.12 セキュリティ境界

- アプリレベルのコンテナはホスト UID を共有する；悪意あるアプリは Java 層のリダイレクトを回避してホスト/他のインスタンスのデータを読める。
- 自分がよく知っているアプリのみをインストールすることを推奨する；コンテナを信頼できないアプリの分離手段と見なさない。
- Agent は画面の機密情報を見ることができる；デフォルトでマスキングし、デフォルトでパスワード/決済を記録しない。
- プロンプトインジェクション：画面テキストは信頼できない入力である；ツールホワイトリスト + 危険操作の確認。
- MCP はデフォルトで loopback；LAN モードは明示的に有効化 + token（現在は HTTP、TLS は未対応）。

---

## 4. 制御レイヤー

### 4.1 制御経路

| 経路 | 前提 | 観測 | アクション | 遅延 | Android 17 リスク | 適用 |
|---|---|---|---|---|---|---|
| **Shizuku + shell** | Shizuku をインストールし、ワイヤレスデバッグで起動 | `uiautomator dump`、`screencap` | `input tap/swipe/text/keyevent` | 100–500 ms | AAPM の影響を受けない | **主力（ユーザーが Shizuku のインストールを許容する場合）** |
| **Shizuku + UiAutomation** | 同上、UserService 内で UiAutomation を実行 | ノードツリー、スクリーンショット | イベント注入 | 50–150 ms | AAPM の影響を受けない | 低遅延シーン |
| **AccessibilityService** | ユーザーが手動で有効化 | ノードツリー、イベント | `dispatchGesture`、`ACTION_SET_TEXT` | 50–100 ms | AAPM は非アクセシビリティ系アプリを制限する | **主力（Shizuku をインストールしたくない場合）** |
| **Vision + 座標** | スクリーンショット権限 | ピクセル | 座標タップ（別経路で注入が必要） | 1–3 s | AAPM の影響を受けない | ゲーム/Canvas/Flutter のフォールバック |
| **コンテナ in-process** | 独自開発フック | 対象 View ツリー（可能な場合） | 直接呼び出し/注入 | 最小 | AAPM の影響を受けない | 仮想空間操作；UI 制御は検証待ち |

**推奨：**

- 仮想空間操作は `ContainerService`（インストール/起動/停止/マルチインスタンス）を経由する。
- UI 制御は 2 つの adapter を用意する：`ShizukuChannel` と `AccessibilityChannel`、可用性に応じて自動選択する。
- Vision はフォールバックのみ。
- コンテナ in-process は独自開発エンジン内部で安定して取得できるインターフェースにのみ使用し、第 1 版の UI 制御の主経路にはしない。

### 4.2 観測プロトコル

```json
{
  "snapshot_id": "s_123",
  "space_id": "sp_1",
  "package": "com.example.app",
  "activity": ".MainActivity",
  "screen": { "width": 1080, "height": 2400, "rotation": 0 },
  "nodes": [
    {
      "id": "n_1",
      "role": "button",
      "text": "搜索",
      "desc": null,
      "bounds": [820, 120, 1040, 200],
      "clickable": true,
      "editable": false,
      "scrollable": false,
      "enabled": true,
      "focused": false,
      "selected": false
    }
  ],
  "screenshot": "sha256:...",
  "timestamp_ms": 1730000000000
}
```

推奨：

- ノードはフラット化し、可視/クリック可能/編集可能/テキストあり/説明ありのノードのみ保持する。
- デフォルト `max_nodes=120`；必要に応じて `detail=full`。
- スクリーンショットはデフォルトでは送信しない；視覚が必要なときのみ取得する。
- ノード id は単一 snapshot 内で安定；snapshot 間では `(package, activity, viewId, text, bounds bucket)` で近似マッチングする。
- スクリーンショットの取得元：AccessibilityService.takeScreenshot → MediaProjection → PixelCopy。

### 4.3 アクションプロトコル

| アクション | パラメータ | 説明 |
|---|---|---|
| `tap` | node_id / bounds / text | node_id を優先 |
| `long_press` | 同上 + duration | 長押しメニュー |
| `swipe` | from/to/duration | スワイプ/ページ送り |
| `scroll` | direction/amount | `ACTION_SCROLL_FORWARD` を優先 |
| `set_text` | node_id/text | `ACTION_SET_TEXT` → クリップボード貼り付け → キー入力 |
| `key` | keycode | back/home/recents/enter/delete |
| `launch_app` | package/activity | ContainerService を経由 |
| `wait_for` | text/package/activity/idle | タイムアウト付き |
| `intent` | action/data/extras | ディープリンク/システム Intent |
| `clipboard` | get/set | 機密操作は確認が必要 |

実行後は必須：再観測 → 期待する変化を検証 → 失敗時は 1 回リトライ → さらに失敗したら視覚/人手にフォールバック。

### 4.4 視覚フォールバック

ゲーム、Flutter、Canvas、一部の WebView（ノードツリーが空/信頼できない）に適用する。

1. スクリーンショットを 720p/1080p にダウンサンプリングする。
2. マルチモーダルモデルが座標/領域を出力する、または ML Kit OCR + テンプレートマッチング。
3. 座標を画面比率に正規化する。
4. Shizuku/アクセシビリティ経由でタップを注入する。

視覚はフォールバックであり、主経路ではない；コストと遅延の両方が高い。

### 4.5 記録 / 再生 / スキル

- 記録：`(action, snapshot_hash, timestamp, result)`。
- 生成：安定したシーケンスをアサーション付きの YAML/JSON スクリプトに変換する。
- 再生：各ステップで前提条件をチェックする；満たさない場合は Agent にフォールバックする。
- スキルライブラリ：アプリごとに整理する（例：`app.send_message`、`settings.open_wifi`）。
- スキルはトークンコストを下げる鍵である：よくあるタスクはスクリプト+検証で処理し、大規模モデルを使わない。

### 4.6 Android 17 Advanced Protection Mode（AAPM）

- AAPM を有効にすると、非アクセシビリティ系アプリは AccessibilityService を使用できなくなり、既存の権限も取り消される。
- 対応：
  1. 制御レイヤーをデュアル adapter にする：Shizuku 優先、アクセシビリティ次点、Vision フォールバック。
  2. `AdvancedProtectionManager` を検出し、ユーザーに「AAPM を無効にするか Shizuku モードを使用する」よう促す。
  3. 「アクセシビリティ自動化」を唯一のセールスポイントにしない。
  4. Google Play に公開する際、コア機能はアクセシビリティに依存せず動作できる必要がある。

### 4.7 セキュリティ境界

- コンテナはホスト UID を共有する；自分がよく知っているアプリのみをインストールすることを推奨する。
- Agent は画面の機密情報を見ることができる；デフォルトでマスキングし、デフォルトでパスワード/決済ページを記録しない。
- プロンプトインジェクション：画面テキストは信頼できない入力である；ツールホワイトリスト + 危険操作の確認。
- MCP はデフォルトで loopback；LAN モードは明示的に有効化 + token（現在は HTTP、TLS は未対応）。
- すべてのインストール/アンインストール/決済/権限変更は二重確認し、監査する。

---

## 5. デュアルチャネル Agent / MCP

### 5.1 2 つの制御チャネル（両方サポート）

| チャネル | Agent の場所 | モデル | 接続方式 | 利点 | 欠点 |
|---|---|---|---|---|---|
| **ローカル Agent** | スマートフォンアプリ内 | オンデバイス（Gemini Nano 4 / Gemma 4 / LiteRT-LM）またはクラウド（Claude API） | プロセス内で `ToolRegistry` を直接呼び出す | 独立、オフライン（オンデバイスモデル）、低遅延 | ローカルモデルの能力/機種が制限される；クラウドモデルはネットワークが必要 |
| **リモート Agent** | スマートフォン外（Claude Desktop/Code/クラウド） | リモート Agent が決定 | MCP over Streamable HTTP（USB/LAN） | モデル能力が最も高い、デバッグが容易 | PC/ネットワークが必要；公開面の認証が必要 |

2 つのチャネルは二者択一ではなく、同じツールと権限モデルを共有する：

- ローカル Agent：Agent ループはアプリ内にあり、`ToolRegistry` を直接呼び出す。
- リモート Agent：Agent ループはスマートフォン外にあり、MCP adapter 経由で同じ `ToolRegistry` を呼び出す。
- アプリは両者にとって「ツール実行器 + 仮想空間コントローラー」である。

> 「ローカル Agent」は Agent ループがスマートフォン上にあることを指し、モデルが必ずスマートフォン上にあることを意味しない：モデルは Gemini Nano 4（オンデバイス）でも Claude API（クラウド）でもよい。どちらも `ModelAdapter` 経由で接続する。

### 5.2 統一ツール層

```kotlin
data class ToolSpec(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val mutating: Boolean,
    val requiredScope: Scope
)

class ToolRegistry(
    private val tools: Map<String, RegisteredTool>,
    private val leaseManager: LeaseManager,
    private val approvalGate: ApprovalGate,
    private val audit: AuditLog
) {
    fun list(): List<ToolSpec>
    suspend fun execute(
        name: String,
        args: JsonObject,
        ctx: ToolContext
    ): ToolResult
}

data class ToolContext(
    val sessionId: String,
    val caller: Caller,        // LOCAL_AGENT / REMOTE_MCP / USER
    val spaceId: SpaceId,
    val scope: Scope
)
```

- MCP adapter：`ToolSpec` を MCP の `tools/list` + `tools/call` にマッピングし、JSON Schema で検証する。
- ローカル Agent adapter：同じ `ToolSpec` を Gemini/Claude の function declaration に変換し、実行は引き続き `ToolRegistry` を経由する。
- 両方で共有：パラメータ検証、エラーコード、承認、リース、監査、軌跡。新しいツールは 1 回書くだけでよい。

### 5.3 MCP Server（リモートチャネル）

> ローカル Agent は MCP を経由せず、`ToolRegistry` を直接呼び出す；ただし両者のツール定義、権限、リース、監査は完全に一致する。MCP Server はリモート Agent のトランスポート適応層にすぎない。

実装戦略：

- **基本版**：組み込みの `ServerSocket` 最小 HTTP サーバー（`POST /mcp`）。`java.net`/`java.io` のみを使用し、API 24/25 と互換。**Ktor 3.x は使用しない**：`java.nio.file`（API 26+）に依存するため、Android 7.x では起動に失敗する。
- **製品版**：公式 MCP Kotlin SDK（`io.modelcontextprotocol:kotlin-sdk-server`）+ Ktor 3.x；この場合 minSdk は 26 以上が必要、または互換処理を行う。
- **Transport**：Streamable HTTP（`/mcp`）。POST、GET SSE ハートビート、DELETE セッションをサポート；stdio は使用しない（Android には stdio host がない）。
- **状態**：各クライアントは独立した `Mcp-Session-Id` を持ち、30 分のアイドルで回収；未知/期限切れのセッションには 404 を返す；MCP Inspector のデバッグ用に CORS を提供。
- **制限**：リクエストボディ上限 40 MB、リクエストヘッダー 64 KB、同時接続 32；認証は body を読む前に完了し、Bearer Token は定数時間比較を使用する。

Android 側：

- フォアグラウンドサービス（`dataSync` または `specialUse`）+ 常駐通知。
- バッテリー最適化ホワイトリスト；Doze 中も接続を維持。
- デフォルトで `127.0.0.1:8765` にバインド；初回起動時にランダム token を生成。
- LAN モード：明示的なスイッチ + token；現在の実装は HTTP（TLS 未対応）、Android 17 では `ACCESS_LOCAL_NETWORK` が必要。リポジトリはゼロ依存 CLI を提供：`tools/nestspace-mcp`。

接続：

```bash
# USB（推奨）
adb forward tcp:8765 tcp:8765

# Claude Code
claude mcp add --transport http space http://127.0.0.1:8765/mcp \
  --header "Authorization: Bearer <token>"
```

### 5.4 ローカル Agent ランタイム

- `AgentLoop`：observe → plan → tool call → verify → repeat；リモート Agent と同じツールセットを使用する。
- `ModelAdapter` は 3 択：
  - `GeminiNanoAdapter`：ML Kit GenAI Agent API（Alpha；対応デバイスは限定的）。
  - `LiteRTAdapter`：Gemma 4 / カスタムモデル（オフライン、重量級）。
  - `ClaudeAdapter`：スマートフォンから Anthropic Messages API を直接呼び出す（能力が最も高い、ネットワーク/key が必要）。
- フォアグラウンドサービス + 通知；長時間タスクはキャンセル可能；画面オフ時は wake lock を保持。
- UI：タスク入力、リアルタイム軌跡、承認ダイアログ、緊急停止。
- 失敗/タイムアウト：リモート Agent または人手にフォールバック。

実装済み（NestSpace 0.3）：

- **共通 chat プロトコル**（go-agent-sdk の `chat.Service` / `Messages` / `Block` 抽象を参考）：
  - `ChatMessage` = role + `List<ChatBlock>`；`ChatBlock` = `Text` / `ToolUse` / `ToolResult`。
  - `ChatService.chat(ChatRequest): ChatResponse` が provider 境界である；`ChatRequest` は model/system/messages/tools/maxTokens/temperature を保持する。
  - `AnthropicChatService` と `OpenAiChatService` は共有モデルと各自の wire JSON の相互変換のみを担当する。
  - `LlmAgentRuntime` は provider-neutral なループを 1 つだけ持つ：モデルを呼び出す → `ToolUse` を収集する → `ToolRegistry` を実行する → `ToolResult` を返す → ツール呼び出しがなくなるまで繰り返す。
  - 対話は `ChatService.streamChat` の SSE ストリーミング出力を使用する；Anthropic と OpenAI 互換エンドポイントはいずれも増分イベントからテキストとツール呼び出しを組み立て、Agent ループは変わらない。
- 「设置」（設定）→ **「大模型 / 本地 Agent」（大規模モデル / ローカル Agent）** は 2 つのプロトコルを提供する；設定はアプリのプライベートディレクトリに保存され、API Key は Android Keystore で暗号化して保存される。
- **Claude 互換**：`POST {base}/v1/messages`、header `x-api-key` + `anthropic-version`、ネイティブ `tools` / `tool_use` / `tool_result`。
- **OpenAI 互換**：`POST {base}/v1/chat/completions`、`Authorization: Bearer`、ネイティブ `tool_calls` / role=`tool` メッセージ。
- サードパーティのカスタムテンプレートプロトコルは削除済み；旧バージョンで保存された `CUSTOM` 設定は無視され、Claude のデフォルト値にフォールバックする。残存するカスタム endpoint が新しいプロトコルに混入するのを避けるためである。
- `LlmAgentRuntime` は `ToolRegistry` を再利用し、`ToolContext(sessionId, LOCAL_AGENT, "virtual-1")` を渡し、session/approval ツールを除外し、最大 16 ターンのツール呼び出し、単一ツール結果は 12k 文字で切り詰める。
- モデルが未設定または利用できない場合、アシスタントはモデル設定を促し、ルールベースのコマンドやツール呼び出しを実行しない。
- `AssistantConversation` はアシスタントページとフローティングボール音声アシスタントが共有するメッセージストリームと直近 24 テキストターンを統一的に管理する；`LlmAgentRuntime.run(history = ...)` は各リクエスト前に履歴を再生し、マルチターンの追加質問を実現する。音声入口はデフォルトでテキスト入力を隠し、最終認識結果を自動送信し、応答後に自動でリスニングを再開する。
- 音声認識は内蔵の Vosk オフラインモデル（`com.alphacephei:vosk-android`）を使用し、クラウド STT を経由しない。モデルは初回使用時にダウンロードされ、フローティングボール設定でも管理できる；ダウンロード前に HF 中国ミラー、Hugging Face、Vosk 公式サイトのトップページへ並行アクセスして速度を測定し、最も応答の速い回線を選択し、失敗時は自動的に切り替える。中国語/English の小モデルはそれぞれキャッシュされ、モデル準備完了後は `SpeechService` が partial/final 結果を継続的に提供する。
- `LocalSpeechEngine` はプロセス内で読み込み済みの `Model` をキャッシュする；ダウンロード完了後すぐにバックグラウンドで読み込み、アプリ起動時にモデルがキャッシュ済みならプリロードも行う。音声ページは `Recognizer`/`SpeechService` のみを作成し、開けばすぐにリスニングできる。言語切り替え時は先に新しいモデルを読み込む；古いモデルはプロセス終了まで保持し、一時停止中の音声ページが古い Recognizer を保持している間に早期解放されるのを避ける。
- ローカル mock で両プロトコルのコア変換とツール呼び出し往復をそれぞれ検証済み；ユーザー設定の Anthropic 互換エンドポイントはエミュレーターの実ネットワークで `space_list` + `space_list_apps` のマルチターンツール呼び出しを検証済み。
- ユニットテスト：`app/src/test/java/com/coke/nestspace/llm/AnthropicChatServiceTest.kt`、`OpenAiChatServiceTest.kt`。

### 5.5 セッションとリース

```kotlin
enum class Caller { LOCAL_AGENT, REMOTE_MCP, USER }
enum class LeaseMode { EXCLUSIVE, OBSERVE }

data class ControlLease(
    val spaceId: SpaceId,
    val leaseId: String,
    val owner: Caller,
    val sessionId: String,
    val mode: LeaseMode,
    val expiresAt: Long
)
```

ルール：

- 変更系ツール（tap/type/launch/install/uninstall）は先に `EXCLUSIVE` リースを取得する必要がある。
- 観測系ツール（observe/screenshot）は共有可能。
- 別チャネルが変更系ツールを呼び出すと `SPACE_BUSY`（holder 情報付き）を返す；キューイング/待機/ユーザーへの引き継ぎ要求が可能。
- ユーザーは UI からリースを強制回収できる。
- リースはアイドルタイムアウトで自動解放される；MCP 切断/ローカルタスク終了時は即座に解放する。
- すべてのリース変更を監査ログに記録する。

### 5.6 承認フロー

| シナリオ | ローカル Agent | リモート MCP |
|---|---|---|
| 決済/アンインストール/権限/インストール | アプリ内ダイアログで確認 | `approval_request` + スマートフォン通知；リモート Agent は `approval_wait` をポーリング |
| 読み取り専用観測 | デフォルトで許可 | デフォルトで許可（token scope が必要） |
| 高リスク shell | デフォルトで無効 | デフォルトで無効；ユーザーが有効化 + 二重確認が必要 |

- 承認リクエストは同じ `ApprovalGate` に入り、UI には「本地 Agent 请求 / 远程 Agent 请求」（ローカル Agent リクエスト / リモート Agent リクエスト）と表示される。
- 承認結果は監査に記録される；タイムアウトはデフォルトで拒否。

### 5.7 ツール一覧

**仮想空間管理**

| ツール | 役割 |
|---|---|
| `space_list` | 仮想空間とその状態を一覧表示 |
| `space_create` | 仮想空間を作成 |
| `space_delete` | 仮想空間を削除 |
| `space_install_apk` | APK/split を仮想空間にインストール |
| `space_uninstall` | アンインストール |
| `space_list_apps` | 仮想空間内のアプリを一覧表示 |
| `space_launch` | アプリ/Activity を起動 |
| `space_stop` | アプリを停止 |
| `space_status` | 仮想空間/フォアグラウンドアプリの状態 |

**UI 制御**

| ツール | 役割 |
|---|---|
| `ui_observe` | ノードツリーを返す（スクリーンショットは任意） |
| `ui_screenshot` | スクリーンショットを返す |
| `ui_tap` / `ui_long_press` | タップ/長押し |
| `ui_swipe` / `ui_scroll` | スワイプ/スクロール |
| `ui_set_text` | テキスト入力 |
| `ui_key` | キー（back/home/recents/enter） |
| `ui_wait_for` | 条件待ち |
| `ui_find` | 直近の snapshot からノードを検索 |

**アプリ/システム**

| ツール | 役割 |
|---|---|
| `app_intent` | Intent/ディープリンクを送信 |
| `sys_shell` | Shizuku shell（デフォルト無効、コマンドホワイトリスト） |
| `sys_logcat` | logcat を読み取る（パッケージ/pid 指定） |
| `sys_clipboard_get/set` | クリップボード（機密、確認が必要） |

**AppFunctions ファストパス**

| ツール | 役割 |
|---|---|
| `appfn_status` | Android 16+/AppFunctionManager/EXECUTE_APP_FUNCTIONS の可用性を報告 |
| `appfn_list` | 対象パッケージが公開する AppFunctions を一覧表示（ホスト/コンテナ内） |
| `appfn_execute` | AppFunction を実行；失敗/利用不可時は UI 自動化にフォールバック |

**セッション/承認**

| ツール | 役割 |
|---|---|
| `session_acquire` | 仮想空間の排他/観測リースを取得 |
| `session_release` | リースを解放 |
| `session_status` | 現在の holder を確認 |
| `approval_request` | 人手による確認を要求 |
| `approval_wait` | 承認結果を待機 |

**タスク/スキル**

| ツール | 役割 |
|---|---|
| `task_run` | ローカル Agent が自然言語タスクを実行 |
| `task_status` / `task_cancel` | 照会/キャンセル |
| `skill_list` / `skill_run` | スキル一覧/実行 |
| `trajectory_get` | 実行軌跡を取得 |

設計原則：

- すべてのツールに `space_id` を持たせる（`space_list`/`space_create` を除く）。
- 構造化エラーを返す：`{code, message, retryable, hint}`。
- インストール/起動は冪等；`idempotency_key` で重複を防ぐ。
- 危険操作は `confirm=true` または人手による確認が必須。
- `ui_observe` はデフォルト `max_nodes=120`、スクリーンショットを返さない。
- `sys_shell` はデフォルトで公開しない；有効化後はホワイトリストのコマンドのみ許可。

### 5.8 ツール schema の例

```json
{
  "name": "space_install_apk",
  "description": "Install an APK or split APK set into a space. Validates REQUIRE_SECURE_ENV, ABI, and 16KB alignment before install.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "space_id": { "type": "string" },
      "apk_paths": {
        "type": "array",
        "items": { "type": "string" },
        "description": "Absolute paths or content URIs. For split APKs, include base + all splits."
      },
      "grant_runtime_permissions": { "type": "boolean", "default": false },
      "idempotency_key": { "type": "string" }
    },
    "required": ["space_id", "apk_paths"]
  }
}
```

### 5.9 モデル選択

2026-10 時点の Anthropic 第一者 API 価格（米ドル/百万 token、入力/出力）：

| モデル | Model ID | 価格 | 推奨用途 |
|---|---|---|---|
| Claude Opus 5.5 | `claude-opus-5-5` | $4 / $20 | デフォルト；複雑な計画、長いタスク |
| Claude Sonnet 5.5 | `claude-sonnet-5-5` | $2 / $10 | コストパフォーマンス/速度 |
| Claude Haiku 5.5 | `claude-haiku-5-5` | $0.10 / $0.50 | 簡単な分類、待機、要約 |
| Claude Fable 5.1 | `claude-fable-5-1` | $10 / $50 | 最も難しいタスク；コスト高 |

オンデバイスモデル：

- **Gemini Nano 4 / ML Kit GenAI Agent API**（Alpha）：オンデバイスのツール呼び出しループ；対応デバイスは限定的。
- **Gemma 4 + LiteRT-LM**：独自のファインチューニングモデルを持ち込める；統合/パフォーマンスのコストが高い。

推奨：リモート Agent は Claude Opus 5.5；ローカル Agent は第 1 版でまず `ClaudeAdapter` でループを動作させ、その後 Gemini Nano 4 / Gemma 4 を接続する；簡単なサブステップには Haiku 5.5 を使用する。

### 5.10 Agent ループと失敗処理

```text
state = observe(space)
for turn in 1..MAX_TURNS:
    decision = model.decide(goal, state, tools, history)
    if decision.done: return result
    if decision.needs_user_confirmation:
        approval = approvalGate.request(decision)
        if not approval.approved: return cancelled
    result = execute(decision.tool, decision.args)
    state = result.observation ?? observe(space)
    history.append(compact(result))
```

要点：

- システムプロンプトに明確に書く：役割、利用可能なアプリ、ツールの意味、セキュリティルール、出力形式。
- コンテキスト：コンパクトなノードツリー + 必要に応じたスクリーンショット + スキルヒント + 履歴要約。
- ターン数、token、単一ステップのタイムアウトを制限する；キャンセルをサポート。
- 各ステップを検証する；2 回連続失敗 → 再計画；3 回 → 人手に引き継ぐ。
- 失敗処理：

| 失敗 | 処理 |
|---|---|
| ノードが見つからない | 再 observe；テキスト/座標でフォールバック |
| タップが効かない | クリック可能か確認；座標；アニメーション待ち |
| 入力失敗 | `ACTION_SET_TEXT` → クリップボード → キー入力 |
| アプリクラッシュ/ANR | プロセスを kill して再起動；代替パス；人手 |
| 権限ダイアログ | システムダイアログを識別；ポリシーに従い許可/拒否/確認 |
| 読み込みが遅い | `wait_for` + タイムアウト + リトライ |
| 検証失敗 | 前のステップに戻る；再計画 |

### 5.11 AppFunctions ファストパス（ホスト / コンテナ内 APK）

Android 16+ の AppFunctions は「システムレベルのツール登録」である：アプリは `@AppFunction` で機能を宣言し、KSP が service/XML schema を生成し、システムに登録される；Agent は `AppFunctionManager` 経由で発見/実行する。

**3 つのケースで権限と能力が異なる：**

| シナリオ | 利用可否 | 説明 |
|---|---|---|
| **ホストアプリ自身が AppFunctions を公開** | ✅ 可能 | ホストは function owner であり、自分の関数を実行できる；システム Agent/Gemini も発見できる |
| **コンテナ内 APK が AppFunctions を公開** | ✅ ローカルブリッジが必要 | 仮想パッケージはシステムに登録されないため、`AppFunctionManager` から見えない；インストール時に AppFunctions メタデータを解析し、コンテナプロセス内で生成された service/entry point を呼び出す |
| **システム内の他のアプリの AppFunctions** | ⚠️ 通常は不可 | 呼び出し側に `EXECUTE_APP_FUNCTIONS` が必要で、これは特権/システムレベルの権限である；通常の APK では通常取得できない |

**アーキテクチャ：**

- `ToolSource` 抽象：`BuiltinToolSource`（仮想空間/UI ツール）、`AppFunctionsToolSource`（ホスト/コンテナ AppFunctions）、`SkillToolSource`。
- `ToolRegistry` がツールを集約し、名前空間は `appfn.<package>.<functionId>`；同名衝突はパッケージ名で区別する。
- Agent は計画時にファストパスを優先する：`appfn_execute` → 利用不可/失敗 → UI 自動化。
- コンテナエンジンは APK インストール時にその AppFunctions XML/metadata を解析する；存在する場合は `AppFunctionsToolSource` に登録する；実行時はコンテナプロセス内で呼び出し、システム登録への依存を避ける。
- ホストアプリも `@AppFunction` で `space.list/install/launch`、`ui.observe/tap` を公開し、システム Agent/Gemini からホストを直接呼び出せるようにできる。これは「エクスポート」であり「インポート」ではない。

**権限/コンプライアンス：**

- 通常の APK がサードパーティアプリの AppFunctions を呼び出せると仮定しない；`EXECUTE_APP_FUNCTIONS` は privileged である。
- ホストがエクスポートする AppFunctions もリース/承認/監査を経由する必要がある；セキュリティモデルをバイパスしない。
- コンテナ内 AppFunctions は依然としてコンテナの Binder/UID 制限を受ける；システムサービスに依存する関数は失敗する可能性がある。
- AppFunctions は任意のファストパスであり、唯一の制御面ではない；失敗時は必ず UI 自動化にフォールバックする。

**基本版：**

- `AppFunctionsBridge` の検出（API 36、`AppFunctionManager`、権限）と `appfn_status/list/execute` ツールのプレースホルダーを追加済み。
- ホストのエクスポート、コンテナ内のローカルブリッジは Stage F に置く。

---

## 6. アーキテクチャ

### 6.1 レイヤー図

```text
┌──────────────────────────┐        ┌──────────────────────────┐
│ ローカル Agent ランタイム│        │ リモート Agent（Claude） │
│ (オンデバイスループ)     │        │ (Desktop/Code/クラウド)  │
└────────────┬─────────────┘        └────────────┬─────────────┘
             │ ToolRegistry                      │ MCP Server
             │                                   │ (Streamable HTTP)
             └───────────────┬───────────────────┘
                             ▼
┌──────────────────────────────────────────────────────────────┐
│ Compose UI: 仮想空間 / インストーラー / Agent / 承認 / 軌跡  │
└──────────────────────────────┬───────────────────────────────┘
                               │
┌──────────────────────────────▼───────────────────────────────┐
│ Domain                                                       │
│  SpaceRepository  ControlRepository  AgentRepository         │
│  ToolRegistry  LeaseManager  ApprovalGate  AuditLog          │
│  AppFunctionsBridge (fast path)                              │
└───────────────┬──────────────────────────────┬───────────────┘
                │                              │
┌───────────────▼──────────────┐  ┌────────────▼───────────────┐
│ ContainerEngine              │  │ ControlChannel             │
│  Installer / VPMS / VAMS     │  │  ShizukuChannel            │
│  ClassLoader / Resources     │  │  AccessibilityChannel      │
│  Binder / IO / ProcessPool   │  │  VisionChannel             │
└───────────────┬──────────────┘  └────────────┬───────────────┘
                │                              │
┌───────────────▼──────────────────────────────▼───────────────┐
│ Infrastructure                                               │
│  Host-declared :c1..:c16     | MCP Server | Local Agent      │
│  AccessibilityService / MediaProjection / Shizuku            │
│  Room / DataStore / WorkManager                              │
└──────────────────────────────────────────────────────────────┘
```

### 6.2 モジュール構成

```text
app/
  src/main/java/com/coke/virtual/
    core/model/          # SpaceInfo, AppInfo, UiNode, UiSnapshot, Target, Condition
    core/space/          # SpaceBackend, SpaceRepository
    core/control/        # ControlChannel, AccessibilityService, ShizukuBridge
    core/tools/          # ToolRegistry, ToolSpec, validation, scopes
    core/session/        # ControlLease, SessionManager, ApprovalGate, AuditLog
    core/mcp/            # MCP server adapter (remote), auth
    core/agent/          # Shared AgentLoop, prompt, skills
    core/agent/model/    # ClaudeAdapter, GeminiNanoAdapter, LiteRTAdapter
    engine/installer/    # APK/split の解析、事前チェック、インストール
    engine/vpms/         # 仮想 PackageManager
    engine/vams/         # 仮想 ActivityManager、StubActivity
    engine/classloader/  # ClassLoader/LoadedApk
    engine/resources/    # Resources/AssetManager
    engine/binder/       # Binder/UID 仮想化
    engine/io/           # データディレクトリのリダイレクト
    engine/process/      # :c1..:c16 プロセスプール、VClientService
    engine/hooks/        # Java/ネイティブフック、バージョン適応
    fastpath/            # AppFunctions 検出、ホストエクスポート、コンテナ内ローカルブリッジ
    feature/spaces/      # Compose UI: 仮想空間一覧、アプリグリッド
    feature/installer/   # APK 選択、事前チェック、インストール進捗
    feature/agent/       # Agent コンソール、軌跡、承認
    service/             # ForegroundService, AccessibilityService
```

### 6.3 主要インターフェース

```kotlin
// デュアルチャネル共用：ローカル Agent は ToolRegistry を直接呼び出し；リモート Agent は MCP adapter 経由で同じ ToolRegistry を呼び出す
data class ToolSpec(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val mutating: Boolean,
    val requiredScope: Scope
)

interface ToolRegistry {
    fun list(): List<ToolSpec>
    suspend fun execute(name: String, args: JsonObject, ctx: ToolContext): ToolResult
}

interface AgentRuntime {
    suspend fun run(goal: String, spaceId: SpaceId, sessionId: String): TaskResult
    fun cancel(sessionId: String)
}

data class ControlLease(
    val spaceId: SpaceId,
    val leaseId: String,
    val owner: Caller,
    val sessionId: String,
    val mode: LeaseMode,
    val expiresAt: Long
)

interface ContainerEngine {
    suspend fun install(spaceId: SpaceId, apk: ApkSource): InstallResult
    suspend fun uninstall(spaceId: SpaceId, packageName: String)
    suspend fun listApps(spaceId: SpaceId): List<AppInfo>
    suspend fun launch(spaceId: SpaceId, packageName: String, activity: String? = null): LaunchResult
    suspend fun stop(spaceId: SpaceId, packageName: String)
    suspend fun status(spaceId: SpaceId): SpaceStatus
}

interface ControlChannel {
    suspend fun observe(id: SpaceId, options: ObserveOptions = ObserveOptions()): UiSnapshot
    suspend fun screenshot(id: SpaceId): ImageBitmap
    suspend fun tap(id: SpaceId, target: Target)
    suspend fun longPress(id: SpaceId, target: Target, durationMs: Long = 800)
    suspend fun swipe(id: SpaceId, from: Point, to: Point, durationMs: Long = 300)
    suspend fun setText(id: SpaceId, target: Target, text: String)
    suspend fun key(id: SpaceId, key: Key)
    suspend fun waitFor(id: SpaceId, condition: Condition, timeoutMs: Long): Boolean
}
```

### 6.4 1 回の完全なフロー

> リモート Agent は MCP Server → ToolRegistry；ローカル Agent は直接 ToolRegistry を経由する。以下の Engine/Control のステップは両者で同じである。

```text
Agent                         MCP Server / ToolRegistry      Engine / Control
  │                              │                                │
  │ space_install_apk            │                                │
  │─────────────────────────────►│ APK/REQUIRE_SECURE_ENV 検証     │
  │                              │───────────────────────────────►│ Installer
  │                              │◄───────────────────────────────│ ok
  │ space_launch                 │                                │
  │─────────────────────────────►│───────────────────────────────►│ VAMS + ProcessPool
  │                              │◄───────────────────────────────│ foreground
  │ ui_observe                   │                                │
  │─────────────────────────────►│───────────────────────────────►│ dump tree
  │◄─────────────────────────────│ nodes + screenshot hash        │
  │ ui_tap(node_id=n_7)          │                                │
  │─────────────────────────────►│───────────────────────────────►│ tap
  │ ui_wait_for(text="结果")     │                                │
  │─────────────────────────────►│───────────────────────────────►│ poll
  │◄─────────────────────────────│ matched / timeout              │
```

### 6.5 現在のプロジェクトへの改修提案

1. `core/model` + `core/tools` + `core/session`：モデル、ToolRegistry、リース/承認を先に実装する。
2. `service/SpaceAccessibilityService.kt` + `res/xml/accessibility_service_config.xml`：観測/タップ。
3. `core/control/ShizukuBridge.kt`：Shizuku API（`dev.rikka.shizuku:api` + `provider`）を接続し、`input`/`screencap` を実行する。
4. `core/mcp/`：基本版は組み込みの最小 HTTP サーバー（ServerSocket）を使用し、まず 5 つのツール（observe/tap/launch/list_apps/screenshot）；製品版は Ktor + kotlin-sdk に置き換える。
5. `service/McpForegroundService.kt`：フォアグラウンドサービス + 通知 + token。
6. `core/agent/`：ローカル Agent の骨格（`ClaudeAdapter` または偽モデル）で、同じ ToolRegistry を再利用する。
7. `engine/` + `container/`：Stage A/B の単一プロセスコンテナ、インストーラー、マルチインスタンス、インスタンスデータディレクトリは実装済み（第 7 節参照）。
8. プロセスプール、ネイティブライブラリ、Binder/UID、Provider/通知/Job、Android 7.1–17 適応は実装済み；今後はバックグラウンド起動、システム側の実 UID 境界、より広い汎用互換サンプルに重点を置く。

---

## 7. 構築順序（工期は設定しない）

| フェーズ | 目標 | 内容 | 完了基準（能力ゲート） |
|---|---|---|---|
| **S0 制御レイヤー + デュアルチャネル骨格** | まず「制御」を独立したレイヤーにする | ToolRegistry、MCP Server、ローカル Agent 骨格、Shizuku/アクセシビリティ、リース/承認、軌跡 | リモート MCP とローカル Agent の両方がインストール済みアプリを制御して 3 種類のタスクを完了できる；ツール動作が一致する |
| **A 単一プロセス最小コンテナ** ✅ 検証済み | 簡単な APK を 1 つ動作させる | AXML/リソース解析、ClassLoader、Resources、StubActivity、Instrumentation/ActivityThread フック、Context ラッピング | ネイティブ/GMS なしの簡単な APK がホストプロセスで起動し UI を表示できる（API 25 エミュレーターで検証済み） |
| **B インストーラー + マルチインスタンス** ✅ 検証済み（API 25 / API 36） | インストールでき、複数インスタンスを実行できる | split APK、署名検証、`REQUIRE_SECURE_ENV`、IO リダイレクト、VPMS 一覧/起動、インスタンスデータディレクトリ | 簡単なアプリをインストール、起動、マルチインスタンス化できる；データが互いに汚染されない（testapp + Android 16 で検証済み；base+合成 split は検証済み、実際の config split リソース overlay は検証待ち） |
| **C プロセスプール + ネイティブ** ✅ 実装済み | クラッシュ分離、ネイティブアプリ | `:c1..:c16` プロセスプール、VClientService、ネイティブライブラリの抽出/読み込み、16 KB テスト、Binder/UID Java フック | 対象は独立プロセスで動作；ネイティブライブラリが正常；16 KB デバイスでは明確な診断とともにインストール可能 |
| **D バックグラウンド/システム機能** ✅ 主要部分実装済み | 主要アプリが利用可能 | 通知、静的 Receiver、Job/Alarm の部分代理、Provider、WebView プロセス分離、Android 15–17 適応 | 通知、ブロードキャスト、Provider、権限、一般的なバックグラウンドコンポーネントが利用可能；システムによる終了後の完全な Job/Alarm 起動は制限付き |
| **E Agent 自律性 + セキュリティ** | リリース可能 | オンデバイス/クラウドモデル、スキルライブラリ、検証/リトライ、人手引き継ぎ、監査、マスキング、MCP 認証 | よくあるタスクの成功率が基準を満たす；セキュリティレビュー通過 |
| **F AppFunctions ファストパス** | 任意の高速化 | ホストの `@AppFunction` エクスポート、コンテナ APK メタデータ解析、コンテナ内ローカル実行、権限検出、UI フォールバック | ホストツールをシステム Agent が呼び出せる；コンテナ内 AppFunctions を実行できる；利用不可時は UI にフォールバック |

### 7.1 Stage A の主要な受け入れ確認

Android 7.1.1 x86 エミュレーターで検証（testapp APK は未インストールで、直接読み込み）：

- ✅ `Activity` の `onCreate/onStart/onResume` が実行され、レイアウトが表示され、`findViewById` が正常に動作する。
- ✅ 対象リソースが有効：`@string`、`@layout` が正常に解析され、ボタンをタップすると TextView が対象文字列に更新される。
- ✅ タップ/レンダリングでクラッシュ、黒画面、ANR が発生しない。
- ✅ 対象 `Application` の `attachBaseContext`/`onCreate` は Stage B で接続済み（testapp で検証）。
- ✅ 対象の `getPackageName()` と PackageManager クエリは仮想パッケージインデックスから返され、仮想アプリ間の VPMS クエリにも対応する。
- ✅ Android 9+ の hidden API は HiddenApiBypass で処理する。現在の検証対象は Android 7.1、15、16、17。

### 7.2 Stage B の主要な受け入れ確認

Android 7.1.1 x86 エミュレーターで検証：

- ✅ 単一 APK のインストールが成功し永続化される；base + 合成 split APK を `space_install_apk` でインストールして起動でき、`split_apks` がディスクに保存される。実際の config split のリソース overlay は依然として検証待ち。
- ✅ 署名不一致は拒否：`SIGNATURE_MISMATCH`。
- ✅ `REQUIRE_SECURE_ENV=1` は拒否：`SECURE_ENV_REQUIRED`。
- ✅ マルチインスタンス `instance-1` / `instance-2`、データディレクトリは独立；`file/prefs count` は互いに影響しない。
- ✅ インスタンス切り替え後も旧インスタンスのデータは保持される；プロセスを kill してアプリを再起動しても、インストール記録とインスタンスは存在する。
- ✅ アンインストールで APK、インスタンスデータ、state がクリーンアップされる；ディレクトリは空になる。
- ✅ 2 つ目のインスタンス起動時、1 つ目のインスタンスは単一プロセスモデルでバックグラウンドに切り替わる/置き換えられる；データは失われない。
- ✅ 対象 Context の `getPackageName`、`getFilesDir`、`getSharedPreferences`、`getApplicationInfo` はリダイレクト済み。
- ✅ クリーンなデスクトップ：デフォルトでは仮想アプリ + 「空间管理」（仮想空間管理）のみ表示；「系统应用」（システムアプリ）は仮想空間管理でチェックして初めてデスクトップに表示される；壁紙の設定/クリアをサポートし、再起動後も保持される。
- ✅ Android 16（API 36）で再テスト：デスクトップ、コンテナ、マルチインスタンス、インスタンスデータ分離が正常。
- ✅ Android 14+ の書き込み可能 dex 制限：インストール後の APK を読み取り専用にしても、`DexClassLoader` が正常に読み込める。
- ✅ Android 9+ hidden API：HiddenApiBypass 6.2 を導入し、`Activity.attach` などのリフレクションが利用可能；例外を適用しないと `Activity.attach not found`。
- ✅ Android 17（API 37）と MuMu（Android 15、ARM bridge）は現在の検証マトリクスに含まれる。
- ✅ 互換性サンプルはマルチインスタンス、明示的 Activity、Provider、WebView、プラグインコンポーネント、ネイティブモジュールをカバーする。
- ✅ マルチプロセスルーティング、コンポーネントブリッジ、Binder/UID プロキシ、ネイティブ環境を実装済み；単一プロセスの入口 Activity に限定されない。
- ✅ エンジン内の仮想アプリ一覧/起動（システム PMS/AMS フックではない）、デスクトップ入口、対象 PackageManager の仮想化を統合済み。

実装場所：`app` の `engine/MiniContainerEngine.kt`、`container/ContainerContext.kt`、`container/ContainerSession.kt`、`container/ContainerProcessPool.kt`、および `ui/DesktopScreen.kt`。

### 7.3 Stage C の主要な受け入れ確認（実装済み）

- 2 つのインスタンスが異なる `:c1..:c16` スロットプロセスにあり、クラッシュが互いに影響しない。
- 対象アプリのネイティブライブラリが読み込める。
- ネイティブライブラリの読み込み結果と診断は、アライン済み、未アライン、ABI 不一致を区別できる；汎用 ELF `p_align` の事前チェックは今後の拡張とする。
- `Process.myUid`/`Binder.getCallingUid` の Java フックが少なくとも一般的なチェックをカバーする（システム側の UID 偽装は行わない）。

### 7.4 Stage D の主要な受け入れ確認（主要部分実装済み）

- 通知が表示できる（または明確に降格する）。
- 静的 Receiver が受信できる。
- Job/Alarm が少なくともクラッシュしない（劣化は許容）。
- WebView のマルチインスタンスでデータが混ざらない（独立プロセス）。
- Android 15/16/17 の各 1 台で通過する。

### 7.5 Stage F の主要な受け入れ確認（AppFunctions ファストパス）

- ホストアプリが `@AppFunction` で少なくとも 3 つのツール（例：`space_list`、`space_launch`、`ui_observe`）を公開し、システム Agent または `adb shell cmd app_function` が発見できる。
- コンテナ内テスト APK が AppFunctions を持ち、インストール時にメタデータを解析でき、実行時にコンテナプロセス内で呼び出せる。
- 通常の APK がシステム内の他のアプリの AppFunctions を呼び出すと明確に拒否され（`EXECUTE_APP_FUNCTIONS`）、UI にフォールバックする。
- ファストパスの失敗が UI 自動化に影響しない。

> 各フェーズは独立してデモできる；「全機能」を待ってから検証しない。Stage A が失敗する場合、フックの方針/バージョン適応に問題があることを示すので、先に解決してから次へ進む。

---

## 8. テストマトリクス

### 8.1 デバイス

| 種類 | 例 | 注目点 |
|---|---|---|
| Pixel | Pixel 8/9/10（Android 15/16/17） | ネイティブ動作、hidden API、AAPM |
| Samsung | S24/S25/S26（One UI） | バックグラウンド制限、Knox |
| Xiaomi | 14/15（HyperOS） | バックグラウンド、権限、パッケージ管理 |
| OPPO/vivo | Find X / X シリーズ | 権限、バックグラウンド、アクセシビリティ |
| Huawei | GMS なしモデル | GMS 依存、HMS |
| 16 KB page | Android 15+ の新機種 | ネイティブライブラリのアラインメント |

### 8.2 アプリ

- 独自開発テスト APK（最小 Activity、Resources、ネイティブライブラリ、split、Service、Receiver）
- オープンソースの簡単なアプリ（F-Droid の小規模ツール）
- 一般的なソーシャル、EC、旅行、コンテンツ系アプリのカテゴリ（互換性目標、保証はしない）
- ゲーム（Unity/UE、Surface/Canvas）
- Flutter アプリ
- WebView 主体のアプリ
- 銀行アプリ（失敗を想定し、降格通知の検証に使用）

### 8.3 シナリオ

- インストール：単一 APK、split APK、XAPK、低い targetSdk、16 KB
- 起動：コールドスタート、ウォームスタート、バックグラウンドからの起動、マルチインスタンス切り替え
- Activity ライフサイクル：回転、分割画面、画面ロック、戻る、最近のタスク
- 権限：初回起動、実行時権限、拒否/許可
- データ：files/cache/db/sp、外部ストレージ、アンインストール時のクリーンアップ
- コンポーネント：Activity、Service、Receiver、Provider、Notification
- 異常：ネットワーク切断、タイムアウト、クラッシュ、ANR、低メモリ
- デュアルチャネル：同じタスクをローカル Agent とリモート MCP でそれぞれ実行し、結果が一致する
- 同時実行：ローカル Agent がリースを保持しているとき、リモート MCP の変更系呼び出しは `SPACE_BUSY` を返す；観測系は影響を受けない
- 承認：両チャネルが同じ承認 UI をトリガーできる；タイムアウトは拒否

### 8.4 指標

- インストール成功率、起動成功率、タスク成功率
- 単一ステップ遅延 p50/p95
- クラッシュ率、ANR
- タスクあたりの token コスト
- 誤タップ率、人手引き継ぎ比率
- コンテナ：Activity 起動成功率、リソース読み込み成功率、ネイティブライブラリ読み込み成功率、16 KB 通過率、プロセス生存
- デュアルチャネル：ツール一貫性通過率、リース競合/キューイング/引き継ぎ回数、承認タイムアウト率

---

## 9. リスク一覧

| リスク | 影響 | 緩和 |
|---|---|---|
| Hidden API の変更 | フックが無効化、起動クラッシュ | バージョン適応レイヤー；段階的リリース；固定テストマトリクス |
| Binder/UID の制限 | システムサービスがホスト UID で判定し、一部アプリ機能が異常 | 強力な UID 分離を約束しない；Java フックで一般的なチェックをカバー；境界を明確化 |
| バックグラウンド起動制限 | Agent がバックグラウンドから画面を起動できない | フォアグラウンドサービス/通知/ユーザートリガー；「可視タスク」を設計 |
| 通知/Job/Provider | 対象アプリのバックグラウンド機能が劣化 | 段階的サポート；製品説明に明記 |
| Keystore/署名/Play Integrity | 金融/強検証アプリが利用不可 | 約束しない；検出後に通知 |
| 16 KB / split APK | インストール/読み込み失敗 | 事前チェック；明確なエラー；テストマトリクス |
| プロセスプール/メモリ | マルチインスタンスのカクつき、kill | プロセスプール上限；フォアグラウンド優先；低メモリ時の降格 |
| コンテナセキュリティ | 悪意あるアプリがホスト/他のインスタンスのデータを読む | 信頼できる APK のみインストール；セキュリティ分離を宣伝しない |
| プロンプトインジェクション | Agent が画面内容に操作される | 画面テキストをデータとして扱う；ツールホワイトリスト；危険操作の確認 |
| デュアルチャネル同時実行 | ローカル/リモートが同時操作し、互いに中断 | リース + セッション + ユーザー引き継ぎ |
| ローカル Agent モデル能力 | オンデバイスモデルの計画/ツール呼び出しが弱い | ClaudeAdapter フォールバック；オンデバイスは簡単なタスクのみ |
| API key 漏洩 | 費用/濫用 | Keystore 保存；プロキシサービス；レート制限 |
| 自動化が ToS に違反 | アカウント停止 | ユーザーへの告知；レート制限；決済/リスク管理に触れない |
| OEM 制限 | バックグラウンド/権限/アクセシビリティが利用不可 | 機種マトリクス；降格案 |
| パフォーマンス/バッテリー | 体験が悪い | フォアグラウンドサービス最適化；スクリーンショット頻度削減；オンデマンド起動 |
| MCP 公開 | LAN から制御される | デフォルト loopback；token；TLS；LAN スイッチをオフ |
| プライバシー | 画面内容に機密情報が含まれる | マスキング；ディスクに保存しない；監査；ユーザーがスクリーンショットを無効化可能 |
| 法務/コンプライアンス | GPL/Play/サードパーティ ToS | クリーンルーム；法務評価；REQUIRE_SECURE_ENV の遵守 |

---

## 10. 参考プロジェクトと資料

### AOSP / Android

- AOSP `ActivityThread`：https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/ActivityThread.java
- AOSP `Instrumentation`：https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/Instrumentation.java
- AOSP `LoadedApk`：https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/LoadedApk.java
- AOSP `ContextImpl`：https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/ContextImpl.java
- Android 17 開発者プレビュー：https://developer.android.com/about/versions/17
- 16 KB page size：https://developer.android.com/guide/practices/page-sizes
- Hidden API の制限：https://developer.android.com/guide/app-compatibility/restrictions-non-sdk-interfaces

### AppFunctions

- AppFunctions 概要：https://developer.android.com/ai/appfunctions
- アプリへの AppFunctions 追加：https://developer.android.com/ai/appfunctions/add-appfunctions
- `AppFunctionManager` リファレンス：https://developer.android.com/reference/android/app/appfunctions/AppFunctionManager
- androidx.appfunctions：https://developer.android.com/reference/androidx/appfunctions/package-summary
- 公式サンプル：https://github.com/android/appfunctions
- コミュニティサンプル（発見/呼び出し）：https://github.com/FilipFan/AppFunctionsPilot

### 参考実装（アーキテクチャ参考のみ、GPL、コピー禁止）

- VirtualApp（asLody）：https://github.com/asLody/VirtualApp
- BlackBox / NewBlackbox：https://github.com/ALEX5402/NewBlackbox

### フック / ネイティブ

- ShadowHook：https://github.com/bytedance/android-inline-hook
- ByteHook：https://github.com/bytedance/bhook
- Dobby：https://github.com/jmpews/Dobby
- xDL：https://github.com/hexhacking/xDL

### 制御 / Agent

- Shizuku：https://github.com/RikkaApps/Shizuku
- MCP Kotlin SDK：https://github.com/modelcontextprotocol/kotlin-sdk
- MCP Inspector：https://github.com/modelcontextprotocol/inspector

### ポリシー

- Google Play コンテナアプリと REQUIRE_SECURE_ENV：https://support.google.com/googleplay/android-developer/answer/13609005
- コンテナ読み込みの制限（Android Developers）：https://developer.android.com/training/basics/intents/limit-play-loading
- Android 17 AAPM の報道：https://www.androidauthority.com/android-17-beta-2-advanced-protection-mode-accessibility-apps-3648860/

### オンデバイスモデル

- ML Kit GenAI Agent API：https://developers.google.com/ml-kit/genai/agent/android
- Gemma 4 ローカル Agent：https://android-developers.googleblog.com/2026/04/gemma-4-new-standard-for-local-agentic-intelligence.html

---

## 付録 A：APK 事前チェックリスト

```text
[ ] packageName / versionCode / versionName
[ ] minSdk / targetSdk
[ ] ABI（arm64-v8a / armeabi-v7a / x86_64）
[ ] split APK かどうか（base + config.*）
[ ] ネイティブライブラリが 16 KB アラインされているか（Android 15+）
[ ] REQUIRE_SECURE_ENV=1 を宣言しているか → はいなら拒否
[ ] GMS / Play Integrity に依存しているか
[ ] Android Keystore を使用しているか
[ ] カスタム署名検証を使用しているか
[ ] マルチプロセス/フォアグラウンドサービス/JobScheduler を含むか
[ ] WebView / Flutter / Unity を含むか（制御方式に影響）
[ ] 危険な権限の一覧
[ ] インストールパッケージの署名（信頼できるソースか）
```

## 付録 B：フックポイントマップ

| サブシステム | Java フック | ネイティブフォールバック | バージョン注意 |
|---|---|---|---|
| PackageManager | `ApplicationPackageManager`、`IPackageManager`、`ActivityThread.getPackageManager` | `Binder.execTransact` | AIDL メソッドはバージョンごとに変化 |
| Activity | `Instrumentation.execStartActivity`、`ActivityThread.mH`、`IActivityManager` | `Binder.execTransact` | Android 10+ バックグラウンド起動制限；Android 14+ PendingIntent |
| ClassLoader | `LoadedApk`、`PathClassLoader`、`DexClassLoader` | — | split/multi-dex |
| Resources | `ResourcesManager`、`AssetManager.addAssetPath` | `open` | split/overlay |
| Binder/UID | `Binder.getCallingUid`、`Process.myUid`、`UserHandle` | `Binder.execTransact` | システム側 UID は偽装できない |
| IO | `ContextImpl`、`SharedPreferencesImpl`、`SQLiteDatabase` | `open`/`fopen` | scoped storage |
| 通知 | `NotificationManager`、`PendingIntent` | — | Android 12 trampoline；Android 13 権限 |
| プロセス | ホスト `:c1..:c16`、`StubActivity`、`VClientService` | — | `app_process` 子プロセスを使用しない |
| ネイティブライブラリ | `System.loadLibrary`、`System.load` | `dlopen`/`android_dlopen_ext` | 16 KB；ABI |
| WebView | `WebView.setDataDirectorySuffix`（プロセス単位） | — | マルチインスタンスには独立プロセスが必要 |

## 付録 C：クリーンルームチェックリスト

```text
[ ] VirtualApp/BlackBox の GPL コードをコピーしない
[ ] AOSP（Apache-2.0）と公開ドキュメントを参考にする
[ ] 各フックポイントの出典（AOSP クラス/メソッド/バージョン）を記録する
[ ] ネイティブフックライブラリのライセンスを個別に確認する
[ ] 法務評価：GPL、Play コンテナポリシー、サードパーティアプリ ToS
[ ] コード分離：GPL コードを見た人物はクリーンルーム実装に参加しない（任意だがより安全）
[ ] 製品で「セキュリティ分離」を謳わない
[ ] REQUIRE_SECURE_ENV を遵守する
```

## 付録 D：最小実行可能 Demo（まずこれを推奨）

**目標**：同じタスクを、リモート Claude Code（MCP）とローカル Agent がそれぞれスマートフォン上の「设置」（設定）アプリを制御して、「設定を開く → WLAN を検索 → WLAN を有効化」を完了する。

**スコープ：**

- コンテナは作らない
- インストーラーは作らない
- 以下のみ：
  1. `ToolRegistry`：3 つのツール（`ui_observe`、`ui_tap`、`ui_key`）、パラメータ検証 + scope
  2. `SpaceAccessibilityService`（observe + tap + back）
  3. `McpForegroundService`（組み込みの最小 HTTP サーバー + token）
  4. `LlmAgentRuntime`（先にモデルを設定；ツールループはアプリ内）
  5. `ControlLease` + `ApprovalGate`（少なくとも排他リースと確認ダイアログを実装）

**受け入れ確認：**

- リモート Claude Code がツールを一覧/呼び出しでき、3 つのタスクを完了できる
- ローカル Agent が同じツールセットで同じタスクを完了できる
- 2 つのチャネルのツール動作が一致する（同じ入力 → 同じ結果/エラーコード）
- ローカル Agent がリースを保持しているとき、リモートの変更系呼び出しは `SPACE_BUSY` を返す；観測系は影響を受けない
- 単一ステップ p50 < 2 s
- 失敗時に構造化エラーを返す

この Demo が動作したら、Stage A（単一プロセス最小コンテナ）に進む。

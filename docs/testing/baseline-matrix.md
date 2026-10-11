# ベースラインテストマトリクス

## ローカル決定論的チェック

| チェック | 期待結果 | コマンド |
| --- | --- | --- |
| Preflight の完全一致入力 | APKM、全 split、manifest、DEX/native library、正式署名が一致 | `python3 scripts/preflight_reference.py --apksigner /path/to/apksigner` |
| Preflight 回帰テスト | zip slip、重複/想定外 entry、サイズ/展開率制限、descriptor/signature parser を拒否/検証 | `python3 -m unittest tests/test_preflight_reference.py` |
| 不正な入力 | 任意の hash、entry、package、version、SDK、split、署名不一致で非 0 | CI fixture またはローカル隔離コピー |

本物の APKM は Git とテスト fixture に含めない。preflight は読み取り専用入力を使い、展開物を残さない。

## 2026-09-01から2026-09-02の統合検証

exact LINE 26.11.0 arm64-v8a APKMへMorphe Desktop CLI 1.14.0でLinimalを適用した。

| チェック | 結果 |
| --- | --- |
| `./gradlew --no-daemon test buildAndroid` | Pass |
| exact APKM preflight | Pass |
| Morphe patch、rebuild、sign | Pass |
| embedded Patch Status 33件 | 全件 `OK` かつ expectedとactualが一致 |
| Shopping、Home Performance Ad、Agent iとLINE AIの追加9 PatchId | 全件 `OK` |
| patched DEX本数 | 14本 |
| 全DEXの `dexdump -f` | Pass |
| 全DEXの `d8 --min-api 32` | Pass |
| extension DEX内の非Linimal class | 0件 |
| extension DEX内のKotlin class | 0件 |
| `LinimalSettingsActivity` | manifest登録済みで `android:exported=false` |

Home headerの最初の統合適用では、高位parameter register `v26` を非range invokeへ渡したためMorpheがinvalid registerを報告した。hook呼出しを `invoke-static/range { p1 .. p1 }` へ修正した。再適用では警告が消え、Home headerを含む全Patch Statusが `OK` になった。

最初の実機起動ではSmart Channel rebindの注入がmodel参照 `v0` をboolean結果で上書きし、`rb0.c.invoke()` のoffset `0xBA` でART `VerifyError`になった。rebind hookをrenderer objectの同型変換へ変更した。OFFまたはcleanup失敗時は元rendererを返し、抑制成功時だけnullを返す。これによりmodel registerを変更しない。

修正後のpatched DEXでは次のflowを確認した。

```text
rendererForBinding(Object): Object
move-result-object renderer
if-eqz renderer, afterBind
invoke-interface renderer, model
```

XIG07、arm64-v8a、Android SDK 36へ上書きinstallした。cold startは成功し、processを維持した。`cmd package compile -f -m verify`も成功し、logcatのVerifier errorは0件だった。

その後Walletタブを初めて表示した際、`WalletV3GrandDesignHeaderView.o()` のoffset `0x1F`でART `VerifyError`が発生した。Agent i stateを`Object`戻り値のhookへ通した直後に具体型が失われ、`setAgentIButtonState`が要求する`WalletV3GrandDesignHeaderView$a`と一致しなかったことが原因である。hookの`move-result-object`直後へ専用state型の`check-cast`を追加した。

修正版ではpatched DEXが次の順序であることを確認した。

```text
adjustButtonState(Object): Object
move-result-object state
check-cast state, WalletV3GrandDesignHeaderView$a
setAgentIButtonState(state)
```

修正版も全33 Patch Statusが`OK`でexpectedとactualが一致した。全14 DEXの`dexdump -f`と`d8 --min-api 32`が成功した。同じ署名鍵でXIG07へ上書きinstallした後、ミニアプリタブを選択してWallet headerを実際にinflateしてもprocessは維持された。logcatのWallet `VerifyError`と`FATAL EXCEPTION`は0件であり、`cmd package compile -f -m verify`も成功した。

patched APKと抽出DEXは一時ディレクトリだけに置く。Gitへ追加しない。

## 2026-09-02 第1弾バグ修正の実機検証

実機レビューで受けた8件のうち、バグ修正6件を第1弾として実装し XIG07 へ上書き install した。

| チェック | 結果 |
| --- | --- |
| `./gradlew --no-daemon test buildAndroid` | Pass |
| exact APKM preflight | Pass |
| embedded Patch Status 34件 | 全件 `OK` かつ expectedとactualが一致 |
| patched DEX本数 | 14本 |
| 全DEXの `dexdump -f` | Pass |
| 全DEXの `d8 --min-api 32` | Pass |
| 新規DEXのclass構成 | 114件。Linimal 82件とstripされたLINE本体 32件。Kotlin class 0件 |
| 上書きinstall | Pass |
| ART全DEX verify | Pass |
| cold start | Pass。`FATAL EXCEPTION` と `VerifyError` は0件 |

preflight はbuild-tools 37.0.0のapksignerでのみ成功する。36.1.0と36.0.0は base.apk のv3.2署名を報告せず失敗する。

`--verify-with-sdk` を付けるとMorpheのcross-DEX検証が19件を報告して停止する。すべてLINE本体に元からあるWear SDKとSentryのoptional依存であり、元APKの全DEXにも定義がない。Linimalの変更とは無関係のため、当該オプションを外して適用し、dexdumpとd8を手動で実行した。

### 指摘6件の実測

| 指摘 | 結果 | 実測 |
| --- | --- | --- |
| 1 Smart Channel の空白 | 解消 | `smart_ch_content_view` が消え、`banner_container` は `[0,438][1220,458]` の20pxのみ。トーク一覧の先頭が y=803 から y=458 へ 345px 上へ詰まった |
| 2 設定ラベルの重なり | 解消 | inset 修正後にヘッダーが `[0,138][1220,320]` へ収まり、戻るボタン `[0,151][156,307]` のタップで Chat から Root へ戻る |
| 3 chip bar | 解消 | `chat_ui_ai_talk_suggestion_chip_bar` が消え、入力欄が `[404,2370]` から `[404,2514]` へ 144px 下がった |
| 4 検索欄右の Agent i | 解消 | ボタンのノードが消え、検索ボックスの右端が 1036 から 1167 へ広がった |
| 7 設定の Agent i 行 | 解消 | 個人情報が 年齢確認 で終わり、バックアップ・引き継ぎ が上に詰まった |
| 8 システムバック | 解消 | Chat ページから Root ページへ戻る |

指摘2は最初の適用では部分解消だった。ActionBarなしテーマにしたことでedge-to-edgeになり、window insetがcontentへ適用されずヘッダーがstatus barと重なって戻るボタンを押せなかった。rootへ `WindowInsets.Type.systemBars()` と `displayCutout()` のinsetをpaddingとして入れて解消した。

再適用後の確認では、Patch Status 34件すべて `OK`、install と cold start 成功、Chatからsystem backでRoot、Rootからsystem backでLINE設定への遷移、ヘッダー戻るボタンでのChatからRootへの遷移をいずれも確認した。

指摘7が実機で効かなかった真因は注入位置だった。dexlib2の `addInstruction(index, insn)` は新しい `MethodLocation` を挿入し、既存locationが `Label` を保持したまま後ろへずれる。boxingの合流点へ注入したため `goto` がhookを飛び越え、true経路がそのまま `Boolean.TRUE` を返していた。注入位置を唯一の `return-object` 直前へ移し、分岐先アドレスと一致しないことをshape検証へ追加した。

### 設定OFFでの復元確認

利用者の許可を得て、主要featureを一時的にOFFへ切り替え、公式の表示へ戻ることを確認した後にONへ戻した。

| Feature | OFFでの実測 |
| --- | --- |
| Smart Channel | `banner_container_root` が `[0,438][1220,803]`、`smart_ch_content_view` が `[0,438][1220,783]` へ復元 |
| トーク一覧の Agent i | 検索欄が `[52,298][1036,438]` へ戻り、Agent i ボタン `[1034,299][1190,438]` が復元 |
| 設定の Agent i | `Agent i` 行 `[166,2108][343,2182]` が復元し、`バックアップ・引き継ぎ` が `[0,2231][1220,2400]` へ下がる |
| チャット入力欄の Agent i | `chat_ui_ai_talk_suggestion_chip_bar` が復元 |

## 2026-09-02 Home Feed広告の追加修正

Home下部に残っていた `GcsAdModuleController` の動画広告を、既存の「ホーム内の広告を表示しない」設定へ統合した。

| チェック | 結果 |
| --- | --- |
| `./gradlew --no-daemon test buildAndroid` | Pass |
| exact APKM preflight、Morphe適用 | Pass |
| embedded Patch Status 38件 | 全件 `OK` かつ expectedとactualが一致。新規GCS Ad gateは `1/1` |
| 全14 DEXの `dexdump -f` / `d8 --min-api 32` | Pass |
| 上書きinstall、ART全DEX verify、cold start | Pass。`FATAL EXCEPTION` と `VerifyError` は0件 |
| Home初回表示 | `ad_view`、`ad_header`、`sponsor`、`gcs_video_view`、`video_view` は0件 |
| タブ往復、pull-to-refresh後 | 同じ広告ノードは0件 |

広告request、database、expiration、trackerは変更せず、専用controllerが表示用に生成したsingleton item listだけをON時に空にする。

## 2026-09-02 第2弾新機能とHome広告拡張の実機検証

第2弾の2機能とHome広告の拡張を含む最終ビルドを XIG07、arm64-v8a、Android SDK 36 へ上書き install し、LINE 26.11.0 patched として確認した。

| Linimal設定 | featureId | 設定ページ |
| --- | --- | --- |
| ホーム投稿カード | `linimal.home-feed-post-cards` | Home |
| プレミアム設定行 | `linimal.premium-settings-row` | General |

| チェック | 結果 |
| --- | --- |
| 端末上のAPKから直接読んだ embedded Patch Status 38件 | 全件 `OK` かつ expectedとactualが一致 |
| 全14 DEXの `dexdump -f` | Pass |
| 全14 DEXの `d8 --min-api 32` | Pass |
| 上書きinstall、ART全DEX verify、cold start | Pass。`FATAL EXCEPTION` と `VerifyError` は0件 |

### 対象別の成立数

| 対象 | 結果 |
| --- | --- |
| ホーム投稿カード | `OK` 3/3 |
| プレミアム設定行 | `OK` 2/2 |
| Home広告 module-gate | `OK` 1/1 |
| Home広告 catalog-gate | `OK` 2/2 |
| Home広告 gcs-ad-module-gate | `OK` 1/1 |

### 実機での実測

| 対象 | 実測 |
| --- | --- |
| ホーム投稿カード | ホーム下部の動画カードと `友だち追加` の見出し行が消えた |
| Home広告 | 4画面ぶんスクロールしても広告ノードは0件 |
| プレミアム設定行 | LINE設定から `LYPプレミアム` 行が消え、以降の行が上に詰まった |

### 設定OFFでの復元確認

利用者の許可を得て、2機能を一時的にOFFへ切り替え、公式の表示へ戻ることを確認した後にONへ戻した。

| Feature | OFFでの実測 |
| --- | --- |
| ホーム投稿カード | ホーム下部の動画カードと見出し行が元の表示へ復元 |
| プレミアム設定行 | LINE設定の `LYPプレミアム` 行が元の位置へ復元 |

プレミアム設定行は実機で LYP variant のみ表示されるため、LINE variant は未確認である。

## 2026-10-10 「既読をつけずに読む」の閲覧セッション修正

一時 pause と30分経過で解除していた抑制を、検証済みトーク Activity の閲覧セッションへ移した。
対象APKで `chatHistoryRequest`、公開 `getChatId`、standard / CLEAR_TOP の起動経路を確認した。

| チェック | 結果 |
| --- | --- |
| Gradle 全テスト・`buildAndroid` | Pass。キャッシュ済み依存を使った `--offline --no-daemon` 実行 |
| JVM smoke: pause / resume、確定後30分超 | Pass。どちらも `shouldBlock=true` |
| JVM smoke: token による構成変更移管、古い destroy | Pass。新しい所有者の抑制を維持 |
| JVM smoke: 終了後の通常再入室、未確認予約の期限、OFF→ON | Pass。どれも `shouldBlock=false` |
| exact APKM への Morphe Desktop CLI 1.14.0 適用・rebuild | Pass。署名せず一時APKとして生成 |
| embedded Patch Status | 53件すべて `OK`、expected と actual が一致。session patch は `2/2` |
| トーク画面の生成DEX | `super.onCreate` → `onChatCreated` → `SDK_INT` の順。registers=16、hook は range で `v14 .. v15` を渡す |
| 全14 DEX の `dexdump -f` / `d8 --min-api 32` | Pass |
| 実機の相手側既読・ローカル未読バッジ・Android lifecycle 配送 | 未実施。ADB 接続端末なし |

最初の統合適用は新session patchだけ `ERROR` だった。Fingerprint の filters が命令順に評価されるのに、
`chatUiComponentProvider` を `getIntent` / `getExtras` より先に並べたことが原因だった。
対象DEXで確認した順序へ修正し、再適用で `OK 2/2` と実際の hook 注入を確認した。
Morphe の「Applied」ログだけでは成功扱いせず、embedded status と生成DEXも確認した。

APK・抽出DEX・解析用コードは一時ディレクトリだけに置き、検証後に削除した。

## 2026-10-12 通知から既読をつけずに開く

独立した初期 OFF の設定を既読ページへ追加した。
通知 request の marker を起動 dispatch 時に確認し、メニュー設定 OFF でも既読なしセッションを開始する。

| 確認項目 | 結果 |
| --- | --- |
| Java17 / offline の `test buildAndroid :patches:generatePatchesList` | Pass |
| 通知のみ ON、通常入室、30分超の閲覧、構成変更、OFF→ON、終了の JVM smoke | Pass |
| 対象26.11.0 APKM の unsigned Morphe 統合適用 | Pass |
| embedded patch-status | 57件すべて OK。通知 entry 4/4、送信1/1、ローカル1/1、session2/2 |
| 全14 DEX の `dexdump -f` / `d8 --min-api 32` | Pass |
| 実機の設定表示・独立ON/OFF・再入場時の保存 | Pass。XIG07 / Android16（SDK36）、通知のみONを確認 |
| 同一署名での上書きinstall・ログイン状態維持・ART verify | Pass。データ消去なし、`cmd package compile -f -m verify` 成功 |
| 実際のメッセージ通知tap・一時背景移行後の復帰 | Pass。対象トークのメッセージ表示、同一process維持 |
| 通知から閲覧して閉じた後のローカル未読 | Pass。トーク一覧の対象行に未読数1を確認 |
| 通知から閲覧したときの相手側既読・通知バッジ | Pass。ユーザーが送信側の既読なし・通知バッジ正常を確認 |
| 閉じた後の通常再入室での解除 | Pass。対象行の未読数が消え、送信側にも既読が付いたことをユーザーが確認 |

Gradle の実行JDK26では Android JdkImageTransform が失敗したため、repository 指定のJava17で実行した。
既読ページの1行だけという旧カタログテストは表示内容の固定なので削除し、
設定の独立保存・必須patch不足・入口別失効・通常再入室などの動作を検証した。
一時APK・DEX・逆コンパイル結果・smoke scriptは検証後に削除した。

実機確認後は既存設定（メニューON、通知OFF、自動既読停止OFF）へ戻した。
同一processのログ上で `FATAL EXCEPTION` / `VerifyError` は0件。

## 実機成立性ゲート

| ケース | 公式版リファレンス | Linimal統合APK | 結果 |
| --- | --- | --- | --- |
| install / cold launch | Pass | Pass | XIG07へ上書きinstallし、RegistrationActivityまで起動。process維持と重大crash 0件を確認 |
| ART全DEX verify | 未実施 | Pass | `cmd package compile -f -m verify` 成功。Verifier error 0件 |
| login / session restore | Pass | Pending | 端末が未登録状態のため、隔離テストアカウントでの確認が必要 |
| Linimal設定画面と各ON/OFF | 対象外 | Pass | 2026-09-02の第1弾実機検証で全ページの遷移、戻る動作、主要featureのON/OFF切替と復元を確認 |
| text/image/file の送受信 | Pass | Pending | 双方向で確認 |
| foreground/background notification と tap | Pass | Pending | 通知permissionを含む |
| deep link | Pass | Pending | fallbackも確認 |
| audio/video call | Pass | Pending | 実端末間で確認 |
| native library load / restart / process death | Pass | Pending | cold launchはPass。main UI到達後のrestartとprocess deathは未確認 |

`Pending` は未成立を意味し、成功扱いにしない。主要機能に失敗した場合は feature hook の実装へ進まない。

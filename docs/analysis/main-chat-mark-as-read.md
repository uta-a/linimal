# 通常トークの「既読にする」経路と未読バッジ

対象: LINE 26.11.0 (versionCode 261100124) arm64-v8a

## 要点

メイン 1:1 / グループの「既読にする」は 1 つのメソッドに集約されている。難読化名では
`Lq33/e;->d(J, Ljava/lang/String;, Z)V`。このメソッドの中で、ローカルの未読状態の更新と、
相手へ既読を伝える RPC の送信が、この順に実行される。

```
off=0000  if-eqz p4, +0c                          p4(Z) が false なら次を飛ばす
off=0004  invoke-interface Lu13/l;->Y(chatId)V    ローカル未読のクリア
off=0058  invoke-interface Lu13/l;->Q0(J, chatId)V 既読位置 read_up の前進
off=0067  invoke-interface TalkServiceClient->j1(I, String, String)V  sendChatChecked
以降       Lq33/f; の再送キュー (SharedPreferences, PROCESSING / FAILED / SUCCEEDED)
```

registers=10 / ins=5。レジスタ割り当ては `v5=this`, `v6:v7=J`(既読位置のメッセージ ID),
`v8=chatId`, `v9=Z`。

**ローカル更新がサーバ送信より先に、同じメソッドの中で走る。** これが「既読をつけずに読む」で
未読バッジが消えていた原因だった。従来 Linimal が止めていたのは `j1` だけで、その手前の
`Y` / `Q0` は素通りしていた。

## 各呼び出しの中身

| 呼び出し | 実体 | 効果 |
| --- | --- | --- |
| `Lu13/l;->Y(String)V` | `MainChatDataModule.clearUnreadCountBlocking` | 未読件数を消す |
| `Lu13/l;->Q0(J, String)V` | `Lu13/o;->Q0` → `Lw13/i0;->j` | `UPDATE chat SET read_up=? WHERE chat_id=? AND (read_up IS NULL OR read_up='' OR (CAST(read_up AS INTEGER)\|\|''=read_up AND CAST(read_up AS INTEGER) < ?))` |
| `TalkServiceClient->j1(I, String, String)V` | Thrift RPC `sendChatChecked` | 相手へ既読を伝える |

未読は mainchatdata の SQLite `chat` テーブルで表現される。列名レジストリは enum `Lc23/d;` で、
`i=chat_id`, `l=message_count`, `m=read_message_count`, `u=read_up`,
`z=unread_type_and_count`。`unread_count` という列は現行スキーマには無い（同名の列を持つ
`Lda1/c$c;` は旧スキーマの enum、`square_unread_count` は OpenChat、
`notification_center_unread_count` は別機能で、いずれも通常トークのバッジとは無関係）。

## 同定の根拠

難読化名には依存していない。

- `Lu13/l;->Y` の中で組み立てられる continuation クラスの `@DebugMetadata`（難読化後 `Llb8/e;`）に
  `c = "com.linecorp.line.mainchatdata.MainChatDataModule$clearUnreadCountBlocking$1"`,
  `f = "MainChatDataModule.kt"` が残っている。
- `Lq33/e;->d` は、Linimal の「自動既読の停止」が使う `outboundGateFingerprint` が対象にしている
  メソッドそのもの。`returnType = V`, `parameters = (J, String, Z)` に加え、
  `Y(String)V` → `run()V` → `HashMap.get` → `HashMap.put` → `Q0(J, String)V` → `j1` →
  `SharedPreferences$Editor.remove` → `putLong` という参照の順序で識別する。

## 呼び出し元

`Lq33/e;->d` へ到達するのは 2 つの Runnable。

- `Lq33/c;->run()` … `Lq33/e;->e(String)Lip7/w;`（RxJava Single）が生成する。
  `Lq33/e;->a(chatId)J` で既読位置を取り、0 でなければ `d(id, chatId, true)` を呼ぶ。
- `Lq33/e$d;->run()` … `Lq33/e;->b(String, String)` が生成する。ロック画面の未読表示経路。

`Lq33/e;->e(String)` の呼び出し元には次がある（`@DebugMetadata` の `c` で復元）。

- `com.linecorp.line.chat.ui.bridge.data.message.readreceipt.MainChatMarkAsReadExecutor`
  （難読化後 `Lv11/a;->a(String, Continuation)`）。ChatHistory の DI モジュール `Ld11/e;->A(Z)`
  が生成する。トーク画面側の経路で、Linimal の `ReadReceiptHooks` がすでに注入されている。
- `com.linecorp.line.chatlist.viewmodel.ChatListContextMenuDialogModel$markAsReadMainChat`
  （トーク一覧の長押し「既読にする」）
- `com.linecorp.line.chattab.chatsubtab.ChatSubTabActionRequestProcessor`（一括既読）
- `jp.naver.line.android.access.remote.LineAccessServiceForNotification`（通知）
- `com.linecorp.line.search.external.SearchExternalChatUpdaterImpl$markAsReadChat`（検索）

## ServiceChat / Square と混同しないこと

DEX の文字列表にある `markAsReadToLocal-tqTIUe0` や `markAsRead-ZOqLxn8` は
`@DebugMetadata` の `m` フィールド由来で、いずれも
`com.linecorp.line.servicechat.impl.*`（公式アカウント、gRPC）に属する。こちらは remote → local
の順で、通常トークとは分岐の向きが逆。`markAsReadRemote-pTD61uY` は `SquareChatMarkAsReadBoImpl`
で OpenChat 用。通常トークの未読バッジとは無関係。

## Linimal の注入

- `readReceiptOutboundGatePatch`（自動既読の停止）… 同メソッドの命令 index 6、`Y` と chat-list
  Runnable の合流点の直後へ注入する。ローカル更新は元どおり実行したうえで RPC だけを止める。
- `readWithoutReceiptLocalReadBlockPatch`（既読をつけずに読む）… 同メソッドの命令 0 へ注入し、
  対象トークだけ本体を実行せず return void する。ローカル更新と RPC がまとめて止まる。

2 つは同じ `outboundGateFingerprint` を共有し、`dependsOn` のチェーン上で
`readReceiptOutboundGatePatch` が先に走る。あちらは素の命令列を前提に index を数えるため、
順序を入れ替えてはならない。

## 「既読をつけずに読む」の閲覧セッション境界

### 対象APKで確認した入室経路

- `ChatHistoryActivityLaunchActivity` は Intent の Parcelable `chatHistoryRequest` を引き継ぎ、
  `Llv7/n;->a(Intent, Z)V` が `ChatHistoryActivity` を起動する。
- この起動の flags は `67108864`（`FLAG_ACTIVITY_CLEAR_TOP`）だけ。トーク画面の manifest は
  `launchMode` を指定していない（standard）。`ChatHistoryActivity` と直近の基底
  `Lbv7/b;` / `Lo38/c;` は `onNewIntent` を override しない。
  standard と CLEAR_TOP の組合せでは、既存のトーク画面への通常再入室も新しい `onCreate` を通る。
- `ChatHistoryActivity.onCreate(Bundle)V` は registers=16、`p0=v14`、`p1=v15`。
  命令0は `invoke-super/range {p0 .. p1}`、命令1は `Build.VERSION.SDK_INT` の `sget`。
  `chatDataComponentProvider`、`Activity.getIntent()`、`Intent.getExtras()`、
  `chatUiComponentProvider` の順に参照が現れる。
- Parcelable `Lcom/linecorp/line/chat/request/ChatHistoryRequest;` の公開
  `getChatId()String` は `iget-object v0, v0, chatId:String` → `return-object v0`。
  runtime はこの公開 getter を反射で呼び、LINE 型を extension のコンパイル依存に持ち込まない。

`readWithoutReceiptSessionPatch` は onCreate と getter の両方を一意・安全と確認してから、
super 直後に `ReadWithoutReceiptHooks.onChatCreated(Activity, Bundle)` を注入する。
scratch register は使わず、`invoke-static/range {p0 .. p1}` で既存 parameter をそのまま渡す。
挿入位置が分岐・switch・例外 handler の入口なら変更しない。必須 PatchId は
`linimal.patch.read-without-receipt-session`、expected target count は2（画面入口と getter）。

### 状態の寿命

- メニュー操作または通知からの起動で作る予約だけを `SystemClock.elapsedRealtime()` に基づく30分の期限で管理する。
- 検証済みのトーク生成と lifecycle callback 登録に成功した予約は、Activity 所有の確定セッションにする。
  前面 Activity を既読 worker から推測する旧方式は使わない。
- pause/resume、終了ではない stop、別 Activity の画像表示は解除条件ではない。
  確定セッションには時間経過による期限を適用しない。
- 所有者の finishing stop または構成変更以外の destroy で解除する。
  別のトーク画面の新規生成は、同じ chatId でも以前の確定セッションを終了する。
- save callback が Linimal 専用 token を Bundle に保存し、構成変更の destroy で所有者を外す。
  新画面は token・chatId・移管待ち状態が一致する場合だけ所有者を引き継ぐ。
- snapshot/CAS と再利用しない token で、古い終了・期限切れ・復元通知が新しい状態を消さないようにする。
  設定 OFF・適用不可・設定読取り/書込み失敗は予約と確定状態を破棄し、再有効化しても復活させない。

Android の実際の lifecycle 配送と相手側の既読、ローカル未読バッジの成立性は実機確認が必要。
JVM の状態遷移や DEX の静的検証だけで実機確認済みとは扱わない。

## 通知タップからの既読なし入室（2026-10-12）

対象APKの `ChatHistoryMessageNotificationContentIntentReceiver$a` は、メッセージ通知用
PendingIntent の作成時にトーク request の `BackNavigationArgs.isFromNotification` を true にする。
通知の tap は通常 `PendingIntent.getActivity` で `ChatHistoryActivityLaunchActivity` を開く。
別の broadcast 経路でも receiver は内包 Intent を取り出して Activity を起動するだけで、
通知の返信・既読処理を行う `LineAccessServiceForNotification` とは入口が異なる。

起動 dispatcher `Llv7/n;->a(Intent, Z)V` は `shouldLaunchMainActivity`、
`shouldLaunchChatHistoryActivityOnly`、`ShouldRequestCatHistoryActivityLaunch`、
`CHAT_LIST_MOVE_ALL_TAB` の組合せで一意に同定する。対象APKでは registers=10、p1=v8。
先頭は p1 の `Object.getClass()`。入口に `invoke-static/range {p1 .. p1}` で
`NotificationReadWithoutReceiptHooks.onChatLaunch(Intent)` を注入し、
MainActivity 経由・直接起動の分岐に入る前に予約する。元の dispatch は変更しない。

通知 marker の反射契約は以下をすべて検証してから注入する。

- 公開 `ChatHistoryRequest.getBackNavigation()` が this.backNavigation をそのまま返す。
- `BackNavigationArgs(isFromNotification=` を持つ model が Object を直接継承し、
  公開・非 static の boolean field が1つだけである。
- `ChatHistoryRequest.writeToParcel` の `isFromNotification` キー直後でその field を読み、
  `BaseBundle.putBoolean(String, Z)` の value 引数として保存している。

難読化された field 名を runtime に固定しない。取得した field は cache し、
marker が一意でなければ元の処理を続ける。PendingIntent 作成時には予約しない。
通知の設定はメニュー設定から独立した初期 OFF の boolean。
通知専用 feature に entry・送信抑制・ローカル抑制・閲覧セッションの4つの status を記録し、
どれかが未適用なら機能を無効にする。後ろ3つは既存の安全な注入を共有する。

セッションは通知/メニューの入口を保持し、対応する設定だけで有効性を判定する。
片方を OFF にしてももう片方のセッションは解除しないが、OFF にした入口の状態は破棄する。
通常入室、終了、構成変更、未確定予約の失効は既存のセッション境界を使用する。
Square/OpenChat 等の別既読経路はこの機能の対象外。
実機の通知配送・相手側既読・未読バッジは未確認。

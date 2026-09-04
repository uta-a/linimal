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

- `readReceiptOutboundGatePatch`（自動既読の停止）… 同メソッドの命令 index 5、`Y` と chat-list
  Runnable の合流点へ注入する。ローカル更新は元どおり実行したうえで RPC だけを止める。
- `readWithoutReceiptLocalReadBlockPatch`（既読をつけずに読む）… 同メソッドの命令 0 へ注入し、
  対象トークだけ本体を実行せず return void する。ローカル更新と RPC がまとめて止まる。

2 つは同じ `outboundGateFingerprint` を共有し、`dependsOn` のチェーン上で
`readReceiptOutboundGatePatch` が先に走る。あちらは素の命令列を前提に index を数えるため、
順序を入れ替えてはならない。

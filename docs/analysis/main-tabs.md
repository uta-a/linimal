# 下部タブの解析

## 対象

- LINE 26.16.0
- versionCode 261600106
- package `jp.naver.line.android`
- arm64-v8a

## 結論

LINE の下部タブは、provider method（26.16.0 では `mg8.d.a()`）が組み立てる一つのタブ一覧から作られ、その一覧を ViewPager の adapter、タブ位置の変換、下部ボタン、バッジ、VOOM 判定など多くの処理が共有する。Linimal はこの**一覧に触れない**。変えるのは次の 2 点だけである。

1. 下部ボタンの表示。隠すタブのボタンを、一覧にないタブと同じ非表示の経路に乗せる
2. 隠したタブへ向かう遷移。行き先を、元の一覧の先頭（ホーム）へ置き換える

制御単位は次の 4 設定とする。

- ショッピング
- ニュース
- ウォレット
- アプリ

VOOM（`TIMELINE`）はサービス終了に伴い、設定なしで常に隠す（後述）。

## タブ一覧の流れ

```text
mg8.d.a()                         タブ一覧を生成する provider（公式のまま）
  → mg8.d.b()                     unmodifiableList にして cache
     ├ ViewPager adapter p0       ページの並び
     ├ mg8.d.c(tab) / e(index)    タブと位置の相互変換。見つからない tab は index 0
     ├ rm6.d.a() / g48.i.G()      VOOM の 2 階層判定、MINI の有無の判定など
     └ ng8.p                      下部ボタン。押されたボタンの位置を ViewPager へ渡す
```

`MainActivityTabManager`（`jp.naver.line.android.activity.main.c`）の constructor は `dVar.f250147e = dVar.a()` で cache を直接作り直す。

## 生成順

`mg8.d.a()` は次の順で一覧を作る。

| 位置 | enum item | 条件 |
| --- | --- | --- |
| 1 | `HOME` / `HOME26` / `GLOBALHOME` | ホームの種類に応じて一つ |
| 2 | `CHAT` | 常に追加 |
| 3 | `COMMERCE` / `COMMERCE_TW` / `SQUARE` / `TIMELINE` | 条件に応じて最大一つ |
| 4 | `NEWS` または `NEWS_ROW` と `CALL` | ニュースの種類と通話タブの条件 |
| 5 | `MINI` または `WALLET` | `MINI` があればそこで return し、`WALLET` は追加しない |

回線制限時は `List.of(HOME, CHAT)` を早期に返す。

## Linimal設定と隠す item

| 設定 | 隠す名前 | 保存キー |
| --- | --- | --- |
| なし（常に隠す） | `TIMELINE` | なし |
| ショッピング | `COMMERCE`, `COMMERCE_TW` | `linimal.tab.shopping` |
| ニュース | `NEWS`, `NEWS_ROW` | `linimal.feature.news` |
| ウォレット | `WALLET` | `linimal.feature.wallet` |
| アプリ | `MINI` | `linimal.tab.mini` |

extension は LINE 側の enum へ compile-time link せず、`Enum.name` で判定する。下部ボタンの descriptor enum（`ng8.e`）と、タブ enum（`jp.naver.line.android.activity.main.a`）は同じ名前の要素を持つため、どちらも同じ名前の集合で判定する。設定を読めないときは 4 設定を OFF とみなし、`TIMELINE` だけを隠す。引数が想定外のとき、例外が起きたときは元の値を返す。

## 以前の注入位置と不具合（issue #14）

v0.11.0 までは、下部ボタンを作る `ng8.p` の constructor で input list だけを絞っていた。ViewPager の adapter と `mg8.d.c()` / `e()` は元の一覧を使い続けたため、ボタンの位置とページの位置がずれた。

ボタンは絞り込み後の一覧での位置を `onBottomNavigationBarClicked(index)` へ渡し、`viewPager.w(index)` は元の一覧の位置として解釈する。ニュースを隠すと「アプリ」ボタンが index 3 を渡し、元の一覧の index 3 にあるニュースが開いた。隠したタブより後ろにあるボタンはすべて一つ前のページを開き、選択中のハイライトも同じだけずれていた。

その後、一覧の生成元（`mg8.d.a()`）で絞る方式を試した。位置のずれは解消するが、`mg8.d.b()` を読む他の処理が「そのタブは存在しない」と受け取る副作用があるため採用しなかった（後述）。

## 現在の注入位置

注入先は 4 か所である。ボタンの位置を決める 2 か所は `ng8.p` の constructor、遷移先を決める 2 か所は `MainActivityTabManager` にある。

### 1. 下部ボタンの位置（`ng8.p` constructor）

constructor は descriptor enum の全要素を回り、`final Integer num = (Integer) this.f261297i.get(eVar2)` で、そのボタンが元の一覧で占める位置を取り出す。`num == null` のとき LINE は、ボタンとスペーサーを `GONE` にし、click listener を付けず、テーマに既定を使う。これは一覧にないタブ（たとえばショッピングのない端末の `COMMERCE`）と同じ公式の経路である。

`Integer` へ cast した直後に次を挿入する。

```smali
invoke-static {vE, vN}, Ldev/utaa/linimal/extension/features/MainTabHooks;->visibleButtonPosition(Ljava/lang/Enum;Ljava/lang/Integer;)Ljava/lang/Integer;
move-result-object vN
```

`vE` は descriptor の要素、`vN` は位置である。隠すボタンでは null を返す。隠さないボタンの位置は変えないので、押したときに渡る位置は ViewPager のページと一致する。`f261296h` / `f261297i`（ボタンと位置の対応）は元の一覧のままである。

隠したボタンの view は `GONE` のまま残り、`c()`（バッジ更新）、`d()`（選択表示）、`b.a()`（テーマ）はその view を更新する。非表示の view への更新であり、一覧にないタブでも同じ経路を通るため影響しない。バッジの有無を記録する `ng8.b` は公式のままである。

### 2. ウォレットの tooltip（`ng8.p` constructor の末尾）

constructor の末尾は `this.f261292d.contains(WALLET)` が true のとき、ウォレットボタンに紐づく `ng8.e0` を作り、tooltip の表示を開始する（`ng8/d0.java`）。ウォレットのボタンを隠したままこれを作ると、見えない view へ tooltip を出そうとする。

`contains` の結果を分岐する `if-eqz` の直前に次を挿入し、ウォレットを隠しているときは、一覧に `WALLET` がないときと同じ「作らない」経路に寄せる。

```smali
invoke-static {vR}, Ldev/utaa/linimal/extension/features/MainTabHooks;->keepsWalletTooltip(Z)Z
move-result vR
```

### 3. 復元する位置（`MainActivityTabManager` constructor）

constructor は、保存した Bundle の `MainActivityTabManager.currentPosition`、なければ指定されたタブの位置（`dVar.c(aVar)`）を、`this.f213961r`（現在の位置）へ保存する。この `iput` は 2 つの分岐の合流先である。

`iput` を次の 3 命令に置き換える。手前へ挿入すると、label が元の `iput` に残るため分岐した経路が hook を飛び越す。置き換えれば label は hook を指す。

```smali
invoke-static {vTabs, vPosition}, Ldev/utaa/linimal/extension/features/MainTabHooks;->visibleTabPosition(Ljava/util/List;I)I
move-result vPosition
iput vPosition, vThis, <this.f213961r>
```

`vTabs` は `ng8.p` の constructor へ渡す元の一覧（`mg8.d.b()`）と同じ register である。その位置のタブを隠すなら 0 を返す。直後の `dVar.e(position)` 以降（COMMERCE の判定、`bVar.a(...)`、`p0` の作成）も補正後の位置で進む。

### 4. 他の画面から開くタブ（`MainActivityTabManager.h(tab, intent)`）

`MainActivity` の `onCreate`（初期タブ）と `onNewIntent`（deeplink）が、開きたいタブを渡して呼ぶ。内部で `int iC = this.f213946c.c(tab)` から ViewPager の位置を決め、現在の位置と同じなら ViewPager を動かさずイベントだけを出す。

位置だけを 0 にすると、現在がホームのとき「隠したタブの再選択」として扱われ、`APP_MAIN_TAB_ID` に隠したタブが記録され、`og8.b(hidden, hidden)` が送られる。`Home26Fragment.onSelectedTabChanged` はこのイベントでホームの state を初期化する。さらに `WALLET` / `MINI` / `TIMELINE` の分岐が、使われない extra を activity の intent へ書く。

そのため位置ではなく、**メソッドの先頭で引数のタブ自体**を置き換える。先頭では引数以外の register がまだ何も持っていないので、v0 と v1 を一時領域に使う。

```smali
iget-object v0, p0, <this.f213946c>
const/4 v1, 0
invoke-virtual {v0, v1}, Lmg8/d;->e(I)Ljp/naver/line/android/activity/main/a;
move-result-object v0
invoke-static {tab, v0}, Ldev/utaa/linimal/extension/features/MainTabHooks;->visibleTab(Ljava/lang/Enum;Ljava/lang/Enum;)Ljava/lang/Enum;
move-result-object tab
check-cast tab, Ljp/naver/line/android/activity/main/a;
```

隠すタブなら元の一覧の先頭（`e(0)`、ホーム）を返す。以降の処理は、すべてホームを開く要求として進む。hook は `Enum` を返すため、後続の `c(tab)` が要求する型へ `check-cast` で戻す。

## 洗い出した遷移経路

ViewPager の位置は `viewPager.w(...)`（`setCurrentItem`）で決まる。`c.java` 内の呼び出しは次の 2 か所だけで、`getCurrentItem` は読み取りのみである。

| 経路 | 内容 | 扱い |
| --- | --- | --- |
| 下部ボタンの click（`c.a.invoke`） | `ng8.p` が登録する listener。位置は `f261297i` の値 | 隠すボタンには listener がないので押せない |
| `h(tab, intent)` | 初期タブ、deeplink、`onNewIntent` | 注入 4 で引数のタブを置き換える |
| constructor の初期位置 | 保存 Bundle の位置、指定タブの位置 | 注入 3 |
| `MainActivity.onSaveInstanceState` | `f213961r` を保存する | `f213961r` は補正済みなので、隠した位置は保存されない |
| `onPageSelected`（`I(int)`） | ViewPager が選んだページを通知する | ページが決まった後の通知。位置を決める経路ではない |

`h.a(context, intent)`（deeplink の `TAB_ID`、設定の追加タブ、最後に使ったタブ `APP_MAIN_TAB_ID` から初期タブを決める）の戻り値は、`MainActivity` の初期タブとして `h()` と constructor へ渡る。両方を補正するため、隠したタブを最後に使っていても、設定で既定の追加タブにしていても、ホームで起動する。

## 一覧を共有する他の消費者

一覧は公式のままなので、次の処理は隠したタブも「存在する」ものとして公式どおり動く。

| 消費者 | 内容 | 影響 |
| --- | --- | --- |
| `g48.i.G()` | 一覧に `MINI` があるか | 影響なし。ウォレットのキャンペーン取得識別子（`MINI` / `WALLET`）も公式のまま |
| `ng8.b` | 下部タブのバッジ状態の記録 | 影響なし。隠したタブのバッジも公式どおり記録される |
| `mg8.d.c(tab)` | タブから位置への変換 | 影響なし。`f213961r == c(TIMELINE)` の比較（ホーム表示中に VOOM 表示中と誤判定する経路）も起きない |
| `rm6.d.a()` | VOOM 2 階層表示の判定 | 影響なし。`TIMELINE` / `COMMERCE` / `COMMERCE_TW` を含む一覧のままなので診断ログ `LINEAND_205011` も出ない |
| ViewPager adapter `p0` | ページの並び | 隠したタブのページも adapter に残る。選択される経路はふさいだが、現在のページの隣にある隠したタブは、ViewPager の先読みで画面外に作られる（未対応。「残るリスク」を参照） |

## Fingerprint

難読化された class/method 名は条件にしない。

- descriptor enum: `<clinit>` が `TIMELINE`, `NEWS`, `NEWS_ROW`, `WALLET`, `MINI`, `COMMERCE`, `COMMERCE_TW` を文字列で持ち、`VOOM` 等の定数と各ボタンの resource ID（`bnb_timeline` など）を順に読む。親 class が `Enum`
- `ng8.p` の constructor: 上で見つけた descriptor の `values()` を呼ぶ、`MainBottomNavigationStateViewModel` と `View`、`List` を引数に持つ constructor
- `MainActivityTabManager` の constructor: 文字列 `MainActivityTabManager.currentPosition` を持つ
- `h(tab, intent)`: 同じ class にある、引数が (タブ, `Intent`) で `intentNavigateParam`, `WALLET_MODULE_RECEIVED_SCHEME`, `TIMELINE_NAVIGATION` を持つ method

それぞれ一意に一致することを要求する。注入前に命令列の形を検証し、**1 か所でも合わなければ何も書き換えず** `ERROR` を記録する。

- ボタンの位置: `aget-object vE`（配列の直前の定義が descriptor の `values()` の結果）、`iget-object`（自 class の `LinkedHashMap` field）、`get`、`move-result-object`、`check-cast Integer` の連続。register が v0〜v15。注入位置が分岐先・例外 handler の先頭でない
- ウォレット tooltip: `iget-object`（自 class の `List` field）、`sget-object WALLET`（descriptor とは別の型）、`List.contains`、`move-result`、`if-eqz` の連続
- 復元する位置: `ng8.p` の `invoke-direct/range` から List の register を求め、Bundle key の読み出し以降で最初の `iput`（自 class の `int` field）の直後が、位置を引数に取り enum を返す `invoke-virtual` であること。List の register が `iput` まで再定義されない
- 開くタブ: 引数 (タブ, `Intent`)、v0 と v1 が引数でない（local が 2 つ以上）、タブの register が v15 以下、`c(タブ)` の直前に `this` の field（`mg8.d`）を読む。`e(int)` の戻り型が引数のタブ型と一致する

## Patch Status

feature 単位は次のとおり。

```text
linimal.shopping
linimal.news
linimal.wallet
linimal.mini
```

PatchId は次のとおり。

```text
linimal.patch.main-tab-shopping
linimal.patch.main-tab-news
linimal.patch.main-tab-wallet
linimal.patch.main-tab-mini
```

4 件とも同じ 4 か所の注入を共有し、expected target は各 4 件とする。fingerprint が一意でなければ件数に応じて `TARGET_NOT_FOUND` または `ERROR`、形が合わなければ `ERROR`（actual 4）にする。

## VOOM の扱い

VOOM のサービス終了に伴い、下部タブの VOOM 設定を削除し、`TIMELINE` を設定なしで常に隠す。Linimal の他の機能と違い、ON / OFF はできない。

既存インストールの `linimal.feature.voom` は読まれないまま残る。

## 不採用

- 一覧の生成元（`mg8.d.a()`）で絞る方式。位置のずれは解消するが、`mg8.d.b()` を読む他の処理に副作用が出る。`rm6.d.a()` が `TIMELINE` / `COMMERCE` / `COMMERCE_TW` のないことを異常として診断ログ `LINEAND_205011` を出す。`g48.i.G()` が `MINI` の有無で、ウォレットのキャンペーン取得識別子を変える。`ng8.b` が隠したタブのバッジを記録しなくなる。`c(TIMELINE)` が見つからず 0 を返すため、`f213961r == c(TIMELINE)` がホーム表示中に成り立つ
- 下部ボタンの constructor だけで一覧を絞る方式（issue #14 の原因）
- ボタンが渡す位置を元の一覧の位置へ戻す方式。`ng8.p` 内の位置の使い方をすべて合わせる必要があり、注入箇所が増える
- BottomNavigationView 全体を非表示にする方式
- View ID だけを実行時に探して `GONE` にする方式
- 遷移先を位置（`c(tab)` の結果）だけ 0 に置き換える方式。現在がホームのとき、隠したタブの再選択イベントが出る
- ウォレットとショッピング、ニュースとショッピングを同一設定にする方式
- enum ordinal を hard-code する方式
- 元の list を in-place で変更する方式
- selected tab preference を Linimal 側で変更する方式

## 検証

- 単体テスト: 4 か所の shape 判定と注入後の命令列（分岐先が hook を指すこと、拒否する形）を dexlib2 の命令列で確認。extension 側は判定・位置の置き換え・設定を読めないときの fail-open を確認
- exact APKM への適用で 71 件すべて `OK`。下部タブ 4 件は expected 4 / actual 4（`MainTabButtonsHiddenAndRequestsRedirected`）
- 適用後の `ng8.p` と `MainActivityTabManager` を逆コンパイルし、次を確認
  - 位置の取得（`Integer` cast）の直後に `visibleButtonPosition`
  - `contains(WALLET)` の直後に `keepsWalletTooltip`
  - 位置の分岐の合流先（`r14` への `visibleTabPosition`）に 2 つの経路が合流する
  - `h()` の先頭で `e(0)` と `visibleTab`、続けて型を戻す `check-cast`
- 適用後に一覧を作る `mg8.d` に hook が入っていないこと
- `ng8.p` と `MainActivityTabManager` を含む DEX が d8 を通ること

## 未検証事項と残るリスク

- 実機で各タブを隠したときのボタンの並び、ハイライト、遷移先
- 台湾 region（`COMMERCE_TW`）の実機表示
- 隠したタブを指す deeplink の実機挙動
- 隠したタブが現在のページの隣にあるときの先読み。`MainActivityViewPager` は `offscreenPageLimit` を変えない（既定の 1）ため、たとえば現在がチャットで次の位置が隠したニュースなら、`p0.m(position)` がニュースの Fragment を作る。画面外で、選択はされないが、Fragment は生成される。一覧の生成元で絞る方式にはなかった、この方式に固有の副作用である。抑えるには `p0.m` で隠すタブに空の Fragment を返す注入が別に必要になる
- ViewPager 自身が保存・復元する位置（`ViewPager.onRestoreInstanceState`）。設定でタブを隠す前に、そのタブを開いたまま activity が再生成されると、復元時に隠したページが選ばれる可能性がある。この経路は LINE のコードを通らないため hook していない
- 設定画面から戻った直後は、既に表示中の隠したタブのページがそのまま残る。activity を作り直すと反映される
- 起動時の分析イベント（`LineApplication` が初期タブを送る箇所）は、補正前のタブを送る
- `COMMERCE` / `COMMERCE_TW` の deeplink は、`MainActivity.onNewIntent` が scheme を各タブの ViewModel へ渡す。隠していても ViewModel には渡る（`h()` を通らない）

# Linimal 設定階層の実装

## 対象

- LINE 26.11.0
- versionCode 261100124
- package `jp.naver.line.android`
- Linimal Settings Activity `dev.utaa.linimal.extension.settings.LinimalSettingsActivity`

## 目的

現在の設定画面は全機能とPatch Statusを一枚に並べる。機能追加により項目が増えたため、場所別の子ページへ分ける。

一つのActivity内でページスタックを管理する。新しいActivity、Fragment、Service、Providerは追加しない。

## ページ構成

```text
Linimal
├─ General
├─ Agent i
├─ Tabs
├─ Home
├─ Chat
└─ Patch Status
```

内部のpage IDは次のとおり。

```text
ROOT
GENERAL
AGENT_I
TABS
HOME
CHAT
PATCH_STATUS
```

## Root page

RootにはSwitchを直接置かない。各categoryのsummaryと右向きchevronを持つrowを置く。

| Category | 内容 |
| --- | --- |
| General | Premium、外部ブラウザ |
| Agent i | 確認済みのAgent i入口を場所別に表示 |
| Tabs | VOOM、Shopping、NEWS、Wallet |
| Home | Home内の広告、おすすめ、話題 |
| Chat | Smart Channel、Calendar、LINE GIFT、LINE Pay、通常チャット既読 |
| Patch Status | 全patchの適用結果 |

Category rowの高さは最低56dpとする。左側にtitleとsummaryを置く。右側にchevronを置く。

## 子ページ

各子ページは共通の構造を使う。

```text
56dp header
→ ScrollView
→ vertical content
→ 24dp bottom padding
```

Headerは左に48dpのBack領域を持つ。titleは現在のpage名を表示する。

contentはLINE風のsection headerとrowを使う。既存palette、dark mode、LINE green、ripple、16sp title、13sp summaryを再利用する。

## 機能の所属

### General

- Premiumの案内を表示しない
- 通常リンクを外部ブラウザで開く

### Agent i

- Home上部ナビゲーション
- Chat information menu
- Wallet mini-tab header
- Main Settings list
- Chat composer
- Message context menuのAI Edit
- Chat gallery viewerのLINE AI image edit

候補だけのbackend codeや表示不能surfaceは置かない。確認済み入口一件につき独立Switchを置く。Commerce、Search、Home AI Matomeは候補のため表示しない。

### Tabs

- VOOM
- Shopping
- NEWS
- Wallet

ShoppingとWalletは別rowにする。

### Home

- Home内の広告
- おすすめ
- 話題

Home内の広告とSmart Channelは別rowにする。

### Chat

- Smart Channel
- Calendar
- LINE GIFT
- LINE Pay
- 通常チャットの自動既読停止

既読設定はboolean featureではなく `ReadReceiptMode` を使う。UI上は現在どおりSwitchとして表示する。

### Patch Status

- report metadata
- feature status
- patch record
- expected target count
- actual target count
- reason

Status reportを読めない場合もこのpage自体は開ける。読取失敗の説明を表示する。

## FeatureCatalog

各entryに所属pageを追加する。

```text
LinimalFeature
featureId
SettingsPage
title
summary
```

`installedEntries` はPatch Statusのfeature IDに存在するentryだけを返す。さらにpageで絞り込むAPIを追加する。

```text
installedEntriesForPage
```

Root categoryはそのpageに表示可能なentryがなくても固定で表示する案を基本とする。対象patchが未適用の場合もPatch Statusへ到達できるようにする。

機能rowは対応featureの必須PatchId集合が完全に存在し、全件 `OK` の場合だけ操作可能にする。runtime側の `PatchStatusRequirements` がFeatureIdごとの必須集合を保持する。1件でも欠落または余分なrecordがあればFeature Statusを `ERROR` とする。feature IDがreportにない場合はrowを表示しない。

## Navigation state

pure Javaの `SettingsNavigation` がpage pathを持つ。

主な操作は次のとおり。

```text
current
push
pop
canPop
serialize
restore
```

規則は次のとおり。

- 初期pageはROOT
- ROOT以外をpushできる
- 現在pageと同じpageは重複pushしない
- popは一段だけ戻す
- ROOTでpopを要求した場合はActivityを終了する
- 不正page IDはROOTへ戻す
- 空pathはROOTへ戻す
- ROOTで始まらないpathはROOTへ戻す
- pathが過剰に深い場合はROOTへ戻す

現在の階層は一段だけだがpath形式で保存する。今後の孫page追加でもstate形式を変えずに済む。

## Back動作

| 状態 | Header Back | system Back |
| --- | --- | --- |
| ROOT | Activity終了 | Activity終了 |
| 子ページ | 一段pop | 一段pop |

子ページからpopした後はRootを再renderする。

AndroidのBack dispatchは対象APIと現在のActivity基盤に合わせる。最低限 `onBackPressed` から同じnavigation処理へ集約する。

## State保存

`onSaveInstanceState` にpage pathを保存する。

```text
linimal.settings.page_path
```

`onCreate` では次の順序で復元する。

1. Config bootstrap
2. palette作成
3. Patch Status読取
4. page path復元
5. current page render

復元値はtrusted dataとして扱わずstrictに検証する。不正値ではROOTへfail-safe復元する。

Switch変更中の一時状態は保存しない。保存後のConfigを再読込してrenderする。

## Render設計

Activityのroot viewはpage変更ごとに再構築できるようにする。

```text
renderPage
→ createHeader
→ createContent
→ setContentView
→ renderRuntimeConfig
```

page変更前に古いrow referenceをclearする。

```text
featureRows.clear
readReceiptSummary = null
readReceiptToggle = null
readReceiptPatchStatus = null
```

古いViewとlistenerをfieldから保持しない。

Patch StatusはActivity作成時に一度読む。runtimeでpatch内容は変わらないためである。Config healthは各render時に読み直す。

## UI helper

Activity内のprivate helperへ次を分離した。

- header
- category row
- Switch row
- status row
- unavailable message
- layout parameter
- ripple
- Switch tint

NavigationとFeatureCatalogのdecision logicはpure Java型へ分離した。Android View層は描画とevent接続だけを担う。

右向きchevronはprogrammatic `ChevronDrawable` として追加した。既存 `BackArrowDrawable` と同じstroke、density、paletteを使う。

## Security境界

Settings Activityは引き続き非公開とする。

```text
android:exported="false"
android:excludeFromRecents="true"
intent-filterなし
```

追加しないものは次のとおり。

- exported component
- deep link
- custom permission
- network endpoint
- telemetry
- dynamic code loading
- file chooser
- WebView
- credential input

Configへの書込みは `LinimalConfig` を通す。ActivityからSharedPreferencesを直接操作しない。

Patch Status JSONのstrict parserとsize上限を維持する。StatusのreasonをHTMLとしてrenderしない。

## Accessibility

- Backとchevronにcontent descriptionを付ける
- row全体を押してSwitchを変更できる
- disabled rowは誤ってtoggleしない
- textを固定heightへ閉じ込めない
- summaryは複数行を許可する
- ScrollViewを全子pageで使う
- dark modeでcontrastを維持する
- 大きな文字でもtitleとSwitchが重ならないことを実機確認する

## Test

### SettingsNavigation

- 初期pageがROOT
- pushでcurrentが変わる
- 同一pageの重複pushを拒否
- 子pageからpopでROOT
- ROOTのpopが終了要求を返す
- serializeとrestoreが一致
- unknown pageでROOT
- ROOTを欠くpathでROOT
- 過剰な深さでROOT

### FeatureCatalog

- 全entryが一つのpageへ所属
- ShoppingとWalletが別entry
- Home topとSmart Channelが別entry
- Agent i入口が場所別entry
- installed featureだけを返す
- page filterが他pageを混ぜない
- entry順序を維持する

### Activity

pure Javaで分けられないView動作は実機で確認する。

- 全pageが開く
- 全pageがスクロールする
- Header Backとsystem Backが一致
- 回転後に同じpageへ戻る
- process recreation後に同じpageへ戻る
- lightとdark
- 大きな文字
- unavailable featureが操作不能
- Status読取失敗時もPatch Statusが開く

## 不採用

- categoryごとにActivityを増やす方式
- Fragment dependencyを追加する方式
- Compose dependencyを追加する方式
- rootへ全Switchを残す方式
- SharedPreferencesへpage stateを永続化する方式
- exported Activityへ変更する方式
- Patch Statusを読めないとActivity全体を閉じる方式

## 未検証事項

- Android targetで使う最終Back API
- 大きな文字での最小row height
- category summaryの最終文言
- empty categoryを表示する最終条件
- screen readerでのSwitch row読み上げ順

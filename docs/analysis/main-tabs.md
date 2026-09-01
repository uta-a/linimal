# 下部タブの解析

## 対象

- LINE 26.11.0
- versionCode 261100124
- package `jp.naver.line.android`
- arm64-v8a
- reference APKM SHA-256 `be1147ccd3a20c61ac1e9bed93ce918fcc1cb0c5966af3ca4d504ea9da6bf2e6`

## 結論

LINE の下部タブは descriptor enum の list から組み立てられる。Linimal は mapping より前に input list を絞り込むことで対象タブだけを除外できる。

制御単位は次の4設定とする。

- VOOM
- Shopping
- NEWS
- Wallet

Shopping と Wallet は別の enum item である。同じ設定にまとめない。

## タブ inventory

| Linimal設定 | enum item | View ID | resource名 | 備考 |
| --- | --- | --- | --- | --- |
| VOOM | `TIMELINE` | `0x7f0b03d9` | `bnb_timeline` | field名は `VOOM` |
| Shopping | `COMMERCE` | `0x7f0b03c7` | `bnb_commerce` | 日本向け表示は `gnb_commerce` |
| Shopping | `COMMERCE_TW` | `0x7f0b03c9` | `bnb_commerce_tw` | 台湾向け表示は `tw_commerce_tab_gnb` |
| NEWS | `NEWS` | `0x7f0b03d3` | `bnb_news` | 通常のNEWS |
| NEWS | `NEWS_ROW` | `0x7f0b03d4` | `bnb_news_row` | row variant |
| Wallet | `WALLET` | `0x7f0b03db` | `bnb_wallet` | Shoppingとは独立 |

`COMMERCE` と `COMMERCE_TW` は同時表示ではなくregionに応じた相互排他 variant とみなす。一つのShopping設定で両方を対象にする。

`NEWS` と `NEWS_ROW` も一つのNEWS設定で対象にする。

## 現在の注入位置

下部タブを受け取る constructor の input `List` を instance fieldへ保存する直前にhookを入れる。

```text
input List
→ MainTabHooks.filterTabs
→ List fieldへのIPUT_OBJECT
→ descriptor mapping
→ bottom navigation
```

OFF の設定しかない場合は元の `List` instance を返す。ON が一つ以上ある場合だけ新しい `ArrayList` を作り、対象 itemを除外する。

元listは変更しない。未知itemも変更しない。残すitemの順序とidentityを維持する。

## 現在の fingerprint

### Descriptor enum

次を複合して一意に特定する。

- method名 `<clinit>`
- static constructor
- superclass `Ljava/lang/Enum;`
- strings `TIMELINE`, `NEWS`, `NEWS_ROW`, `WALLET`
- field `VOOM`
- field `NEWS`
- field `NEWS_ROW`
- field `WALLET`
- resource literal `0x7f0b03d9`
- resource literal `0x7f0b03d3`
- resource literal `0x7f0b03d4`
- resource literal `0x7f0b03db`

Shopping追加時は次も必須anchorにする。

- string `COMMERCE`
- string `COMMERCE_TW`
- resource literal `0x7f0b03c7`
- resource literal `0x7f0b03c9`
- enum fieldまたはresource nameとの対応

難読化class名は主要条件にしない。

### Consumer constructor

現在確認しているsignatureは次のとおり。

```text
Ljp/naver/line/android/activity/main/MainActivity;
Ljp/naver/line/android/activity/main/q;
Lmb2/a;
Landroid/view/View;
Lm16/m;
Ljava/util/List;
Lxy7/d;
Ljp/naver/line/android/activity/main/c$a;
```

追加条件は次のとおり。

- public constructor
- descriptor enumの `values` 呼出し
- method register countからp0と全parameterの絶対registerを算出
- 唯一のList parameterがparameter index 5
- p0を低位localへ移すobject moveがmethod先頭で1件
- List parameterを低位localへ移すobject moveが直後に1件
- copy後からstoreまで両localが再定義されない
- store sourceが検証済みList copy register
- store receiverが検証済みp0 copy register
- input listを同じownerのList fieldへ `IPUT_OBJECT`
- 条件を満たすList field storeがconstructor内で1件
- 注入に使うcopy registerが狭いregister範囲

実装では `MainActivity`、`View`、`List` の安定型とparameter位置を固定する。難読化された五つのobject型は `L` wildcardとして扱う。descriptor enumの `values` 呼出しとList field storeの関係で一意性を検証する。

exact constructorのregister countは25である。p0は `v16`、List parameterは `v22` へ入る。method先頭でp0を `v0` へ、Listを `v1` へcopyし、`iput-object v1, v0` で保存する。runtime hookはprovenanceを検証した低位copy `v1` へ注入する。

## Runtime判定

extensionはLINE側enumへcompile-time linkしない。`Enum.name` で対象を判定する。

| 設定 | 除外する名前 |
| --- | --- |
| VOOM | `TIMELINE` |
| Shopping | `COMMERCE`, `COMMERCE_TW` |
| NEWS | `NEWS`, `NEWS_ROW` |
| Wallet | `WALLET` |

例外時は元listを返す。

## 独立性

四つの設定は独立して保存する。

```text
linimal.feature.voom
linimal.tab.shopping
linimal.feature.news
linimal.feature.wallet
```

WalletをONにしてもShoppingは残す。ShoppingをONにしてもWalletは残す。

各機能は独立したFeatureIdとPatchIdを持つ。ただしbytecode transformは共有する。共有transformのfingerprint、opcode、register、reference shapeをすべて検証した後だけ一度注入する。

一つでも共有targetが不安全なら何も変更しない。部分注入のまま一部featureだけをOKにしない。

## Patch Status

予定するfeature単位は次のとおり。

```text
linimal.voom
linimal.shopping
linimal.news
linimal.wallet
```

予定するPatchIdは次のとおり。

```text
linimal.patch.main-tab-voom
linimal.patch.main-tab-shopping
linimal.patch.main-tab-news
linimal.patch.main-tab-wallet
```

一つのconstructor targetを共有するためexpected targetは各PatchIdで1件とする。descriptor enumまたはconsumer constructorが0件なら `TARGET_NOT_FOUND` とする。複数一致またはshape不一致なら `ERROR` とする。

## 選択中タブの扱い

filterはタブlistの生成時に動く。抑制対象が保存済みselected tabだった場合のfallbackはLINE側の既存navigation logicへ委ねる。

Linimalはselected tab preference、deep link router、navigation historyを書き換えない。

実機で次を確認する。

- 抑制中のタブが直前に選択されていたcold start
- 各タブを個別にONへ変更した直後
- 全設定OFFへ戻した直後
- LINE内部deeplinkから対象surfaceへ進む場合
- regionで `COMMERCE_TW` が選ばれる場合

## 不採用

- BottomNavigationView全体を非表示にする方式
- View IDだけを実行時に探して `GONE` にする方式
- WalletとShoppingを同一設定にする方式
- NEWSとShoppingを同一設定にする方式
- enum ordinalをhard-codeする方式
- descriptorの難読化class名だけをfingerprintにする方式
- 元listをin-placeで変更する方式
- selected tab preferenceをLinimal側で変更する方式

## Expected cardinality

- descriptor enum 1件
- consumer constructor 1件
- list field store 1件
- injected transform 1件

Shopping追加後もactual countは各1件であることをexact APKM上で確認した。`COMMERCE`、`COMMERCE_TW` と `0x7f0b03c7`、`0x7f0b03c9` の対応も一致した。

## 自動検証

- 全設定OFFで元listと同一instance
- VOOMだけONで `TIMELINE` だけ除外
- ShoppingだけONで `COMMERCE` と `COMMERCE_TW` だけ除外
- NEWSだけONで `NEWS` と `NEWS_ROW` だけ除外
- WalletだけONで `WALLET` だけ除外
- ShoppingとWalletの四組合せ
- null inputをnullのまま返す
- enum以外と未知enumを残す
- 元listを変更しない
- 順序とitem identityを維持する
- Config参照例外で元listを返す

## 未検証事項

- 台湾regionの実機表示
- 抑制対象が選択済みだった場合の公式fallback先
- deeplinkから非表示surfaceへ到達できるか

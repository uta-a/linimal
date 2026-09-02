# Home 広告の解析

## 対象

- LINE 26.11.0
- versionCode 261100124
- package `jp.naver.line.android`
- arm64-v8a
- reference APKM SHA-256 `be1147ccd3a20c61ac1e9bed93ce918fcc1cb0c5966af3ca4d504ea9da6bf2e6`

## 結論

Home 内には Smart Channel とは別に、GCS Home Performance Ad と Home Feed の汎用 GCS Ad がある。
default module catalog の Performance Ad は middle と bottom の2 entryを対象にし、汎用 GCS Ad は
専用controllerが生成する広告item listを対象にする。

主な表示経路は次のとおり。

```text
GcsHomePerformanceAdModuleController
→ GcsHomePerformanceAdViewData
→ singleton item list
→ module Flow
→ home_gcs_performance_ad_banner_row

GcsAdModuleController
→ AdModel
→ singleton item list
→ gcs_ad_section
```

既存の Smart Channel は ChatList と Chat tab の上部にある別 surface である。Home Performance Ad を消すために Smart Channel の保存値や renderer を変更しても目的を満たさない。

Home 広告の表示期限は remote response の expiration から供給される。Home 広告用の固定30日 literal は確認できなかった。期限を最大値へ変更する方式や database を書き換える方式は採用しない。

各広告view dataをsingleton listへ変換した直後にruntime gateを置く。さらに Home default module catalog から
`home-content-server_home-performance-ad-middle` と
`home-content-server_home-performance-ad-bottom` を除外する。ONではどちらの広告rowもadapterへ供給されない。

## 30日表示しない文言

固定 resource `call_main_checkbox_dontshow30days` は存在する。

- resource ID `0x7f150913`
- 日本語 `30日間表示しない`

この resource は VoIP anti-fraud dialog の `anti_fraud_dialog_layout` で使われる。Home 広告や Smart Channel への参照は確認できなかった。

Home の Lad SDK に存在する close 文言は次のとおり。

- `lad_common_ad_close`
- `lad_timeline_hide_ad_post`
- `lad_timeline_hide_ad_account`
- `lad_common_ad_report`

Home 広告の close は local LadDatabase の広告 row 削除と tracker 送信に分かれる。固定TTLの保存処理ではない。

## Home Performance Ad の根拠

### Source metadata

```text
com.linecorp.line.home.ui.impl.performancead.GcsHomePerformanceAdModuleController
```

reference descriptor は次の系統にある。難読化名は fingerprint の主要条件にしない。

```text
Lec2/b;
Lec2/g;
Lec2/m;
```

`Lec2/g;` は `GcsHomePerformanceAdViewData` を表し `Lyj2/c;` の広告 model を保持する。

### Layout

```text
home_gcs_performance_ad_banner_row
```

compiled layoutのrootは `FrameLayout` である。内部のLad SDK rendererがimage、video、title、description、advertiser、more buttonを構成する。

関連 layout は次のとおり。

```text
ladsdk_home_bigbanner_image_ad_view
ladsdk_home_bigbanner_video_ad_view
```

### 広告 model

`Lyj2/c;` には次の情報がある。

- responseId
- uaid
- expirationTimeSec
- inventoryKey
- adType
- visualFormat
- productId
- title
- description
- image
- video
- advertiser
- popupButton
- feedbackHide
- feedbackReport
- tracker
- slots

remote model の expiration が runtime の expirationTimeSec と database の expiration_time に対応する。

## Close と保存経路

表示側は `LadMuteView` を使う。

```text
LadMuteView
→ wl2.x
→ yj2.t
→ bk2.b
→ gk2.e
→ jk2.h
```

local database では次の削除が行われる。

```sql
DELETE FROM ads WHERE rid_uaid = ?
```

同時に close tracker が送信される。Linimal はこの local database と tracker を変更しない。

LadDatabase の `ads` table は次の情報を持つ。

- rid_uaid
- inventory_key
- ad_total
- ad_order
- ad
- state
- expiration_time

state は `USABLE` と `USED` を持つ。expiration、reusable、min interval、response time により cache と request の扱いが決まる。

## 採用する抑制位置

優先順位は次のとおり。

1. nullable module または view data の生成直後
2. module を list や adapter へ追加する直前
3. Flow emission を表示 list へ渡す直前
4. renderer bind の入口

上流 gate は再emit、rebind、タブ再入場、回転、process recreation のたびに評価される位置を選ぶ。

ON 時は Performance Ad と汎用 GCS Ad のitemだけを除外する。他の Home module の順序とobject identityを維持する。

OFF 時は元の object と list instance をそのまま使う。

## Slot collapse

追加していない。

compiled resourceを再確認した結果 `home_gcs_performance_ad_banner_row` のrootは `FrameLayout` だった。LadAdView専用rootとしてのattachment、reuse、OFF復元を安全に証明できなかった。

汎用Home containerをcollapseすると別moduleを巻き込む可能性がある。そのためcatalogと各専用item listのgateだけを採用した。

ONへ変更した瞬間に既にattach済みのrowを強制削除しない。次の専用Flow emissionとadapter更新からitemを供給しない。初回表示ではrowが作られる前にgateを通る。

## Fingerprint

次を複合する。

- `GcsHomePerformanceAdModuleController$createViewDataFlow$$inlined$map$1$2` のDebugMetadata
- source file `GcsHomePerformanceAdModuleController.kt`
- `GcsHomePerformanceAdViewData(advertise=` のtoString contract
- model field type `Lyj2/c;`
- `home_gcs_performance_ad_banner_row` のliteral `0x7f0e0405`
- controllerのLayoutInflater path
- view dataへのCHECK_CAST
- singleton List factory
- Flow collectorのemit
- return typeとparameter type
- exact APKM上の一意cardinality
- `GcsAdModuleController$createViewDataFlow$1` のDebugMetadata
- source file `GcsAdModuleController.kt`
- `gcs_ad_section` のliteral `0x7f0e036d`

catalog gate は上記2 ID、`home_performance_ad`、前後の Home module ID、7要素の array-to-List
factory と return shape を組み合わせる。Performance Ad Flow、catalog、汎用 GCS Ad list の3 gateを変更前に
検証し、1つでも不安全ならどこにも注入しない。

難読化された `ec2` 名だけでは一致させない。

## 不採用

- `call_main_checkbox_dontshow30days` の変更
- remote expiration の変更
- `expiration_time` の書き換え
- LadDatabase の直接変更
- close tracker の無効化
- impression tracker の無効化
- 広告 request とresponseの遮断
- 共通 network stack の遮断
- render後の単純な `removeView` だけの実装
- Home全体のcontainerを非表示にする実装

## Cardinality

exact APKMで次を確認した。

- default module catalog entry 2件（middle / bottom）
- source metadata continuation 1件
- view data class 1件
- module controller row factory 1件
- Flow list gate 1件
- 汎用 GCS Ad list gate 1件

Patch Statusは次のとおり。

```text
linimal.patch.home-top-ad-catalog-gate
OK
expected 2
actual 2

linimal.patch.home-top-ad-module-gate
OK
expected 1
actual 1

linimal.patch.home-gcs-ad-module-gate
OK
expected 1
actual 1
```

全targetのmatch数とopcode shapeを変更前に検証する。どれか不安全なら同surfaceへ一切注入しない。

## 実機確認

ON で次を確認する。

- Home初回表示で middle / bottom の広告がない
- 空白、divider、marginが残らない
- pull-to-refreshで戻らない
- Home再選択で戻らない
- 他タブとの往復で戻らない
- 画面回転で戻らない
- background復帰で戻らない
- process recreationで戻らない
- Flow再emitとRecyclerView rebindで戻らない

OFF では公式の広告表示、close、再表示条件が戻ることを確認する。

## 未検証事項

- 実機でONへ変更した直後の既存row更新タイミング
- 初回表示で空白が残らないこと
- ONからOFFへ変更した後の公式item再供給
- 実機上のA/B test差分
- Homeのregion差分

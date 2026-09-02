# Linimal

Linimal は、公式 LINE Android クライアントへ runtime 設定で切り替え可能な変更を適用する、Morphe ベースの GPL-3.0 パッチバンドルです。

LINE の通信、認証、通知、通話、メッセージ送受信などの主要機能は公式実装を利用し、不要な UI や機能だけをユーザーが制御できる構成を目指します。Linimal は独立したプロジェクトであり、LINE および Morphe の公式プロジェクトではありません。

## Reference version

```text
Application  : LINE
Package      : jp.naver.line.android
LINE Version : 26.11.0
Version Code : 261100124
Architecture : arm64-v8a
Input Format : APKM
Patch System : Morphe
```

v1 開発中は上記の reference version のみを対象とします。

## 必要環境

- Java 17
- Gradle Wrapper が依存関係を取得できるネットワーク環境
- Morphe の GitHub Packages を読むための認証情報

認証情報をプロジェクトへ保存しないでください。`gpr.user` と `gpr.key` は `~/.gradle/gradle.properties` に設定するか、`GITHUB_ACTOR` と `GITHUB_TOKEN` を環境変数で渡します。

## ビルド

```sh
./gradlew buildAndroid
```

生成される Morphe パッチバンドルは `patches/build/libs/patches-*.mpp` に出力されます。runtime extension は `extensions/linimal.mpe` としてこのバンドルへ同梱されます。

## Morphe への追加

Linimal は公式 Morphe のパッチソースには含まれないため、パッチソースとして自分で追加します。

対象は reference version の APKM のみです。Morphe には APKM をそのまま渡し、base と ABI/density split を分解しないでください。

### パッチソースとして追加 (Android)

Android 端末で以下をタップすると Morphe Manager が開き、確認のうえで Linimal がパッチソースとして追加されます。以降は Morphe のパッチ一覧に `Linimal` が表示されます。

[**➕ Linimal を Morphe に追加**](https://morphe.software/add-source?github=uta-a/linimal)

> [!IMPORTANT]
> このリンクは、リポジトリが public であり、かつ `.mpp` を添付した GitHub リリースが存在する場合にのみ機能します。どちらかが欠けていると Morphe はバンドルを取得できません。

パッチソースとして追加すると、新しいリリースを Morphe が自動で検出します。以下のローカル `.mpp` を使う方法は自動更新されません。

### ローカル `.mpp` から追加 (Morphe Manager)

1. パッチソース画面を開く
2. `+` から `Local` を選ぶ
3. 端末へ転送した `patches-*.mpp` を選択する
4. LINE の APKM を選び、`Linimal` を適用する

### Morphe Desktop (GUI)

1. Morphe Desktop を起動する
2. 設定で Expert mode を有効にする
3. LINE の APKM を読み込む
4. Patch source で `LOCAL PATCH FILE` から `patches/build/libs/patches-*.mpp` を選ぶ
5. `Linimal` を選択して実行する

パッチを繰り返しビルドする場合は、設定で Developer options を有効にし、ソースを `patches/build/libs/` フォルダーへ向けます。フォルダー内の最新の `.mpp` が自動的に読み込まれるため、再ビルドのたびにファイルを選び直す必要がなくなります。

### Morphe Desktop (CLI)

```sh
java -jar morphe-desktop-*-all.jar patch \
  --patches patches/build/libs/patches-0.1.0.mpp \
  --out line-linimal.apk \
  "line-apk/jp.naver.line.android_26.11.0-261100124_2arch_7dpi_b4f7cc253b4eab6903c1c27496682626_apkmirror.com.apkm"
```

Morphe Desktop は Java 21 以上を必要とします。ビルド用の Java 17 とは別要件です。

`--force` は APK のバージョン互換チェックを飛ばすため、Linimal では使用しないでください。reference version 以外への適用は検証していません。

### 適用後

LINE の設定画面の一番下に `Linimal` が追加されます。ここから Linimal の設定と、各パッチの適用状況を確認できます。

Linimal のすべての機能は Morphe のパッチ選択ではなく、LINE 内の Linimal 設定で切り替えます。設定が OFF のときは公式 LINE の挙動を維持します。

## 機能

| 分類 | 機能 | 初期値 | 内容 |
| --- | --- | --- | --- |
| General | Premium 誘導の抑制 | ON | 送信取消時に表示される LINE Premium の案内を表示しません。 |
| General | Premium 設定行の抑制 | ON | LINE 設定のプレミアム行を表示しません。LYPプレミアムとLINEプレミアムの地域 variant 2件が対象で、行ごと消えて下が詰まります。 |
| General | 外部ブラウザ | OFF | 通常チャット本文の http と https の外部リンクだけを端末のブラウザで開きます。LINE、ログイン、決済のリンクは元のままです。 |
| Agent i | Home header | ON | Home 上部ナビゲーションの Agent i を表示しません。 |
| Agent i | Chat information | ON | Chat information menu の Manage account を表示しません。 |
| Agent i | Wallet header | ON | Wallet mini-tab header の Agent i を表示しません。 |
| Agent i | Main Settings | ON | LINE 設定の Agent i または LINE AI services を表示しません。 |
| Agent i | Chat list search | ON | トーク一覧の検索欄右にある Agent i を表示しません。検索欄がその分だけ広がります。 |
| Agent i | Chat composer | ON | 通常チャット入力欄の Agent i in chat と AI Talk Suggestions の入口ボタン、および入力欄の下に並ぶ返信提案の chip bar を表示しません。 |
| Agent i | Message context menu | ON | メッセージ長押しmenuの AI Edit を表示しません。 |
| Agent i | Gallery viewer | ON | Chat gallery viewer の LINE AI image edit button を表示しません。 |
| Tabs | VOOM | ON | 下部タブから VOOM を取り除きます。 |
| Tabs | Shopping | ON | 下部タブから Shopping を取り除きます。 |
| Tabs | NEWS | ON | 下部タブから NEWS を取り除きます。 |
| Tabs | Wallet | OFF | 下部タブから Wallet を取り除きます。 |
| Tabs | アプリ | OFF | 下部タブからアプリを取り除きます。 |
| Home | Home 内の広告 | ON | GCS Home Performance Ad の middle / bottom module と Home Feed の広告カードを表示対象から除外します。広告通信と保存期限は変更しません。 |
| Home | おすすめ | ON | Home のおすすめ枠を表示しません。 |
| Home | 話題 | ON | Home の話題とトレンド枠を表示しません。 |
| Home | 投稿カード | ON | Home 下部の VOOM 投稿カードを表示しません。アカウント名や友だち追加の見出し行を含めてカードごと消します。おすすめと話題とは別枠で、投稿カードを描く3種類の module をまとめて対象にします。 |
| Home | 特集枠 | ON | Home の特集グリッドを表示しません。見出し行と並んだ動画カードをまとめて消します。おすすめ、話題、投稿カードとは別枠です。Home の抑制設定がすべて ON のときは、フィード既定ページの読み込み表示も併せて消します。 |
| Chat | Smart Channel | ON | Chat tab上部のSmart Channelを表示しません。 |
| Chat | Calendar | ON | Chat の追加menuからCalendarを取り除きます。 |
| Chat | LINE GIFT | ON | Chat の追加menuからLINE GIFTを取り除きます。 |
| Chat | LINE Pay | ON | Chat の追加menuからLINE Payを取り除きます。 |
| Chat | 自動既読の停止 | OFF | 通常チャットの自動既読送信を止めます。手動既読操作だけを一回送信します。 |

設定画面は `General`、`Agent i`、`Tabs`、`Home`、`Chat`、`Patch Status` に分かれています。各ページはスクロールでき、戻る操作と画面回転後のページ復元に対応します。

対象が一意に特定できなかった機能は自動的に無効となり、設定画面でも操作できません。適用状況は Linimal 設定内の Patch Status に表示されます。

Premium の抑制は案内表示と LINE 設定の行表示だけを対象とします。送信取消時の案内も設定のプレミアム行も presentation 層だけを抑制します。課金資格、購読 API、決済通信は変更しません。広告抑制も広告 request、response、database、expiration、trackerを変更しません。

Agent i と LINE AI は確認できたUI入口だけを場所別に抑制します。backend、subscription、billing、conversation data、common navigator、networkを変更しません。Commerce top navigation、Search、Home AI Matomeは到達可能な入口と確認できていないため対象外です。

自動既読の停止は1対1、GROUP、ROOMの通常チャットが対象です。OpenChat、Service Chat、AI Characterは対象外です。

> [!NOTE]
> 再署名した LINE は公式版とは別の署名になるため、公式アプリへ上書きインストールできません。公式アプリのデータも引き継げません。検証には本番アカウントではなく、データ消失を許容できる端末とテストアカウントを使用してください。

## ディレクトリ

```text
patches/             Morphe パッチ定義と fingerprint
extensions/linimal/  LINE APK へ注入する runtime コード
reference/           Reference APKM の公開可能なメタデータ
line-apk/            ローカルの APKM 入力。Git 管理対象外
docs/                互換性、検証、セキュリティ資料
```

## セキュリティ

次の情報はリポジトリへ含めません。

- 公式または変更済みの APK、APKM、APKS、XAPK
- keystore、署名鍵、証明書の秘密情報
- GitHub Packages の認証情報
- LINE のアカウント情報、token、cookie、メッセージ、端末データ
- 逆コンパイル結果や一時的な解析出力

`line-apk/` は読み取り専用のローカル入力として扱います。Reference metadata と hash だけを `reference/` に保存します。

## ライセンス

Linimal は [GNU General Public License v3.0](LICENSE) で提供します。Morphe の名称および商標に関する追加条件は [NOTICE](NOTICE) を参照してください。

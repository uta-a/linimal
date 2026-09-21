# Linimal

Linimal は、公式 LINE Android クライアント向けの Morphe パッチバンドルです（GPL-3.0）。広告や使わない機能の表示を消し、既読の送信を制御できるようにします。すべての機能は LINE の設定画面から個別に ON / OFF でき、OFF のときは公式 LINE と同じ動作になります。

Linimal は独立したプロジェクトであり、LINE および Morphe の公式プロジェクトではありません。

## 対象

| 項目 | 値 |
| --- | --- |
| アプリ | LINE（`jp.naver.line.android`） |
| バージョン | 26.11.0（versionCode 261100124） |
| アーキテクチャ | arm64-v8a |
| 入力形式 | APKM |

上記以外のバージョンには対応していません。Morphe には APKM をそのまま渡してください。`--force` での適用は想定していません。

## 導入

### Morphe Manager（Android）

Android 端末で次のリンクを開くと、Morphe Manager に Linimal がパッチソースとして追加されます。新しいリリースは Morphe が自動で検出します。

[**➕ Linimal を Morphe に追加**](https://morphe.software/add-source?github=uta-a/linimal)

`.mpp` ファイルから追加する場合は、パッチソース画面の `+` から `Local` を選び、[リリース](https://github.com/uta-a/linimal/releases)の `patches-*.mpp` を指定します。この方法では自動更新されません。

LINE の APKM を選び、`Linimal` を適用します。

### Morphe Desktop（CLI）

Java 21 以上が必要です。

```sh
java -jar morphe-desktop-*-all.jar patch \
  --patches patches-*.mpp \
  --out line-linimal.apk \
  <LINE の APKM>
```

### 公式 LINE からの移行

パッチを当てた LINE は公式版と署名が違うため、公式アプリに上書きインストールできません。次の順で移行します。

1. [MicroG-RE の準備](#microg-re-でトークをバックアップする)を済ませる。
2. 公式 LINE で、トークを Google ドライブへバックアップする。
3. 公式 LINE をアンインストールし、Linimal を適用した LINE をインストールする。
4. ログインし、Google ドライブから復元する。

## 使い方

LINE の設定画面の一番下に `Linimal` が追加されます。設定は `広告`、`Agent i・LINE AI`、`表示を消す`、`既読`、`一般` の 5 ページに分かれています。

初期値は新規インストール時の値です。更新しても、それまでの設定は引き継がれます。LINE の変更などで対象を特定できなかった機能は、自動で無効になり、設定画面でも操作できなくなります。

## 機能

| ページ | 設定 | 初期値 | 内容 |
| --- | --- | --- | --- |
| 広告 | Smart Channel の広告を表示しない | ON | トーク一覧上部の Smart Channel を表示しません。 |
| 広告 | ホーム内の広告を表示しない | ON | ホームの広告枠とフィード内の広告カードを表示しません。 |
| Agent i・LINE AI | ホーム上部の Agent i を表示しない | ON | |
| Agent i・LINE AI | ウォレット上部の Agent i を表示しない | ON | |
| Agent i・LINE AI | トーク一覧の検索欄の Agent i を表示しない | ON | 検索欄がその分だけ広がります。 |
| Agent i・LINE AI | チャット情報の Agent i を表示しない | ON | |
| Agent i・LINE AI | チャット入力欄の Agent i を表示しない | ON | 入力欄の Agent i と AI の返信提案を表示しません。 |
| Agent i・LINE AI | メッセージ長押しメニューの LINE AI を表示しない | ON | |
| Agent i・LINE AI | 写真・動画表示画面の LINE AI を表示しない | ON | |
| Agent i・LINE AI | 設定画面の Agent i を表示しない | ON | |
| 表示を消す | VOOM / ショッピング / ニュース / ウォレット / アプリ を表示しない | OFF | 下部タブから取り除きます。 |
| 表示を消す | AI Friends / カレンダー / オープンチャット を表示しない | OFF | トーク一覧上部のアイコンを表示しません。 |
| 表示を消す | カレンダー / LINE ギフト / LINE Pay を表示しない | OFF | トークの ＋ メニューから取り除きます。 |
| 表示を消す | おすすめ / 話題 / ホームの投稿カード / 特集枠 / 最近の履歴 を表示しない | OFF | ホームの各枠を表示しません。 |
| 既読 | 既読をつけずに読むをメニューに追加 | OFF | トーク一覧の長押しメニューから開いたトークは、開いている間だけ既読をつけません。未読バッジも残ります。 |
| 既読 | 通常チャットの自動既読を停止 | OFF | 通常のトークで自動の既読送信を止め、手動の既読操作のときだけ送ります。オープンチャットなどは対象外です。 |
| 一般 | Premium の案内を表示しない | OFF | 送信取消時の LINE Premium の案内を表示しません。 |
| 一般 | 設定のプレミアムを表示しない | OFF | LINE の設定画面のプレミアムの行を表示しません。 |
| 一般 | リンクを外部ブラウザで開く | OFF | トーク本文の http / https のリンクを端末のブラウザで開きます。ログインや決済のリンクは元のままです。 |
| 一般 | アプリを閉じていても通知を受け取る | ON | 下記を参照してください。 |
| 一般 | MicroG-RE でトークをバックアップする | ON | 下記を参照してください。 |

広告、Premium、Agent i・LINE AI は表示を消すだけで、広告の配信、課金、LINE の通信は変更しません。

### アプリを閉じていても通知を受け取る

パッチを当てた LINE は署名が変わるため、そのままではアプリを閉じている間に通知が届きません。この設定は、通知の登録時に Google へ送る署名情報を公式 LINE のものに置き換えます。特別な準備は不要です。

### MicroG-RE でトークをバックアップする

パッチを当てた LINE は、そのままでは Google ドライブへのバックアップと復元ができません。この設定は、その認証だけを [MicroG-RE](https://github.com/MorpheApp/MicroG-RE) 経由で行います。使うには次の準備が必要です。

1. [MicroG-RE の公式リリース](https://github.com/MorpheApp/MicroG-RE/releases)をインストールする。公式リリース以外（別の fork や自分でビルドしたもの）は使われません。
2. MicroG-RE に、LINE のバックアップに使う Google アカウントを追加する。

MicroG-RE がない状態でバックアップしようとすると、導入を案内する通知が表示されます。

> [!WARNING]
> 通知とバックアップの 2 つの機能は、Google に対してアプリの署名を公式版と偽って申告します。Google や LINE の利用規約に抵触する可能性があり、Google アカウントへの影響も否定できません。また、Google 側や MicroG-RE の変更で使えなくなることがあります。

## 開発

ビルドには Java 17 と、Morphe の GitHub Packages を読むための認証情報が必要です。`~/.gradle/gradle.properties` に `gpr.user` と `gpr.key` を設定するか、環境変数 `GITHUB_ACTOR` と `GITHUB_TOKEN` で渡します。認証情報はリポジトリに置かないでください。

```sh
./gradlew test buildAndroid
```

パッチバンドルは `patches/build/libs/patches-*.mpp` に出力されます。APK、APKM、署名鍵、逆コンパイル結果はコミットしません。設計判断は [docs/adr](docs/adr)、解析は [docs/analysis](docs/analysis) にあります。

## ライセンス

[GNU General Public License v3.0](LICENSE) で提供します。Morphe の名称と商標に関する追加条件は [NOTICE](NOTICE) を参照してください。

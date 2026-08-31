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

ビルドした `.mpp` を Morphe へ読み込ませて LINE へ適用します。Linimal は公式 Morphe のパッチソースには含まれないため、ローカルのパッチバンドルとして自分で追加します。

対象は reference version の APKM のみです。Morphe には `line-apk/` に置いた APKM をそのまま渡し、base と ABI/density split を分解しないでください。

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

### Morphe Manager (Android)

1. パッチソース画面を開く
2. `+` から `Local` を選ぶ
3. 端末へ転送した `patches-*.mpp` を選択する
4. LINE の APKM を選び、`Linimal` を適用する

ローカルソースは自動更新されません。新しくビルドしたら `.mpp` を差し替えます。

### 適用後

Linimal のすべての機能は Morphe のパッチ選択ではなく、LINE 内の Linimal 設定で切り替えます。設定が OFF のときは公式 LINE の挙動を維持します。

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

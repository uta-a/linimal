# ADR 0003: Google ドライブ連携の認証を MicroG-RE へ向ける

- 状態: 提案（PoC の実機検証の結果で採否を決める）
- 日付: 2026-09-22
- 関連: [ADR 0001](0001-reference-and-signing-policy.md) 決定 4、[ADR 0002](0002-fis-certificate-header.md)、[解析](../analysis/google-drive-backup-auth.md)

## 背景

再署名した LINE では、トークのバックアップで Google アカウントを選ぶ段階で失敗する。LINE は旧 Google Sign-In（`com.google.android.gms.auth.GOOGLE_SIGN_IN`）と、`com.google.android.gms.auth.GetToken` による `oauth2:` token の要求で Google ドライブの `drive.appdata` scope を得る。端末本体の Google Play services は、自分のプロセスで LINE の実際の署名を読み、公式証明書で登録された OAuth client と照合する。

ADR 0002 の FIS と違い、検査に使う値を LINE 自身が計算しないため、LINE 内の書き換えでは直せない。LSPatch の signature bypass もプロセス内の偽装なので届かない。

MicroG-RE（`app.revanced.android.gms`、microG GmsCore の fork）は、呼び出し元アプリの manifest にある meta-data `app.revanced.android.gms.SPOOFED_PACKAGE_SIGNATURE` の値を、Google に送る署名 SHA-1 として使う（`PackageSpoofUtils.kt`）。Sign-In も token の要求も同じ処理を通る。

検討した選択肢:

| 方式 | 評価 |
| --- | --- |
| LINE 内の書き換え / LSPatch | 検査が Google Play services のプロセスで行われるため届かない |
| MicroG-RE へ GMS 全体を向ける | ReVanced 版 YouTube と同じ方式で前例があるが、通知・位置情報まで MicroG-RE に依存し、変更範囲が大きい |
| MicroG-RE へ認証だけを向ける | 変更は認証の呼び出し箇所と meta-data に限られる。端末本体の Google Play services と併用して動くかは未確認 |
| root の Mount install | 署名が変わらないため問題自体が起きない。Linimal の配布形態とは別物 |
| 非対応とする | 変更はないが、再署名版ではバックアップできない |

## 決定（案）

1. 再署名で失われた Google ドライブ連携を戻す目的に限り、Google Sign-In と `GetToken` の要求だけを MicroG-RE へ向ける。ADR 0001 決定 4 と、認証を変更しないという制約の例外として扱う。
2. LINE の manifest に `app.revanced.android.gms.SPOOFED_PACKAGE_SIGNATURE` を追加し、値は `Constants.LINE_ORIGINAL_CERTIFICATE_SHA1`（ADR 0002 と共有）を小文字にしたものとする。パッケージ名は変えないので `SPOOFED_PACKAGE_NAME` は追加しない。
3. `"com.google.android.gms"` は GMS client 全体の共有定数なので一括置換しない。解析で特定した認証の呼び出し箇所だけ、パッケージ名と action を extension の hook の戻り値に差し替える。FCM、FIS、位置情報など他の GMS、LINE の認証・LEGY、Drive REST の通信内容は変更しない。
4. runtime 設定で切り替える。OFF、未初期化、MicroG-RE が未導入、例外時は hook が元の値（`com.google.android.gms` と元の action）を返す。
5. fingerprint が一意に定まらない、差し替える register の値を確認できない場合は変更せず、ERROR / TARGET_NOT_FOUND を記録する。

## 結果（見込み）

- MicroG-RE を導入し、MicroG-RE に Google アカウントを追加した利用者は、再署名版でも Google ドライブへのバックアップと復元ができる見込みになる。実機で確認するまでは見込みにとどまる。
- アプリの身元を Google の OAuth client の照合に対して偽ることになる。ADR 0002 より踏み込んだ偽装で、Google や LINE の利用規約に抵触する可能性があり、Google アカウントへの影響も否定できない。README にその旨を明記する。
- MicroG-RE は申告された署名を検証しない。meta-data を書けるのはパッチ適用者だけ、という前提が信頼境界になる。
- 利用者は Linimal 以外の第三者アプリ（MicroG-RE）を信頼して導入する必要がある。

## PoC の結果（2026-09-22）

実験用パッチ（`patches/.../features/googleauth/GoogleAuthMicrogRoutingPatch.kt`）で、SDK 36 の端末で次を確認した。詳細は[解析](../analysis/google-drive-backup-auth.md)。

- バックアップは Google Sign-In を使わず、`GoogleAuthUtil` の token 要求だけを使う。差し替えたのは、GoogleAuthServiceClient を使うかの判定（MicroG-RE があれば false）と、`GetToken` の bind 先の 2 か所。
- 復元とバックアップがどちらも完了した。MicroG-RE は v3 lineage の root の SHA-1 で申告し、Google は `drive.appdata` の token を発行した。
- bind 先の署名検査はなかった。MicroG-RE は上書きについて利用者の確認を求めなかった。
- 公式 LINE 26.14.0 で作ったバックアップを PoC 版で復元できた。

## 未確認事項

- 認証だけを MicroG-RE へ向け、他を端末本体の Google Play services のままにした状態の長期的な両立。
- token の更新と、MicroG-RE がアクセス許可を求める場合の表示。
- Google 側の検証の変更で効かなくなる可能性。
- 再署名版で作ったバックアップを公式 LINE で復元できるか。
- SDK 32 以下の端末。

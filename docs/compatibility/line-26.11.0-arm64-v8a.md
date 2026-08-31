# LINE 26.11.0 arm64-v8a 互換性契約

## 許可する入力

Linimal v1 は次だけを許可します。

- `jp.naver.line.android`
- versionName `26.11.0`、versionCode `261100124`
- minSdk 32、targetSdk 36
- SHA-256 が `reference/line-26.11.0-arm64-v8a.json` と完全一致する APKM

base APK、`split_config.arm64_v8a.apk`、および 7 種すべての density split は必須です。base manifest の `requiredSplitTypes` は `base__abi,base__density` でなければなりません。APKM の全 split（armeabi-v7a を含む）は原本契約の SHA-256 で検証します。arm64-v8a 以外をパッチ対象として許可するものではありません。

## 成立性ゲート

preflight の成功は入力同一性と元署名を確認するだけであり、変更後アプリの成立を意味しません。no-op/probe patch の後、arm64 実機で同一鍵による split 一式の再署名・install・起動を確認し、少なくともログイン、session restore、送受信、通知、deep link、音声/ビデオ通話、native library load、process death と再起動を公式版と比較します。

署名または integrity 検査が主要機能を妨げた場合、回避を暗黙に追加せず go/no-go とします。別 versionCode、`version = null`、armeabi-v7a のみの入力、将来版は v1 で拒否します。

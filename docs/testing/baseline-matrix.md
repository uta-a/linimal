# ベースラインテストマトリクス

## ローカル決定論的チェック

| チェック | 期待結果 | コマンド |
| --- | --- | --- |
| Preflight の完全一致入力 | APKM、全 split、manifest、DEX/native library、正式署名が一致 | `python3 scripts/preflight_reference.py --apksigner /path/to/apksigner` |
| Preflight 回帰テスト | zip slip、重複/想定外 entry、サイズ/展開率制限、descriptor/signature parser を拒否/検証 | `python3 -m unittest tests/test_preflight_reference.py` |
| 不正な入力 | 任意の hash、entry、package、version、SDK、split、署名不一致で非 0 | CI fixture またはローカル隔離コピー |

本物の APKM は Git とテスト fixture に含めない。preflight は読み取り専用入力を使い、展開物を残さない。

## 実機成立性ゲート

| ケース | 公式版リファレンス | no-op パッチ済み split 一式 | 結果 |
| --- | --- | --- | --- |
| install / launch | Pass | Pending | split 全体を同一鍵で再署名して検証 |
| login / session restore | Pass | Pending | 隔離テストアカウントのみ |
| text/image/file の送受信 | Pass | Pending | 双方向で確認 |
| foreground/background notification と tap | Pass | Pending | 通知 permission を含む |
| deep link | Pass | Pending | fallback も確認 |
| audio/video call | Pass | Pending | 実端末間で確認 |
| native library load / restart / process death | Pass | Pending | arm64 実機で確認 |

`Pending` は未成立を意味し、成功扱いにしない。主要機能に失敗した場合は feature hook の実装へ進まない。

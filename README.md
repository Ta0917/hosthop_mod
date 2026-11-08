# HostHop

Minecraft共有ワールドMODの調査・開発プロジェクト。現時点ではMOD本体ではなく、排他制御のローカル検証のみです。本番ワールドには使用しないでください。

- [技術調査と未解決事項](docs/feasibility.md)
- `python -m unittest discover -s poc -v` でSQLiteのリースモデルを検証できます。
- `mod/` はNeoForge 26.2.0.6-beta用の最小起動コードです。ビルドとテストサーバーでの読み込みを確認済みですが、共有機能はありません。
- `poc/snapshot_manifest.py` は停止済みコピーの全ファイルをSHA-256で記録・検証します。一貫したスナップショットの作成やバックアップ機能ではありません。

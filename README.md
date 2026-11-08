# HostHop

Minecraft共有ワールドMODの調査・開発プロジェクト。現時点ではMOD本体ではなく、排他制御のローカル検証のみです。本番ワールドには使用しないでください。

- [技術調査と未解決事項](docs/feasibility.md)
- `python -m unittest discover -s poc -v` でSQLiteのリースモデルを検証できます。
- `mod/` はNeoForge 26.2.0.6-beta用の最小起動コードです。ビルドとテストサーバーでの読み込みを確認済みですが、共有機能はありません。
- `poc/snapshot_manifest.py` は停止済みコピーの全ファイルをSHA-256で記録・検証します。一貫したスナップショットの作成やバックアップ機能ではありません。
- `poc/offline_backup.py` は停止済みコピーのファイルを新規ZIPに保存・検証し、新しいフォルダーに復元する実験です。プレイ中のワールドや本番運用には使えません。
- `poc/local_catalog.py` は検証済みZIPとSQLiteのリース・世代更新を組み合わせたローカル実験です。クラウド同期や実用的な分断対策ではありません。
- `poc/E4mcMessageProbe.java` は既知のe4mc JARの割当メッセージをオフラインで調査します。トンネル接続やアドレスの自動取得機能ではありません。

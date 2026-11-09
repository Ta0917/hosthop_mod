# HostHop

Minecraft共有ワールドMODの開発プロジェクト。実Drive・Cloudflareを使うテスト用コピーで、別PCの友人の参加と、友人へのホスト交代後のワールド変更・友人のデータの引き継ぎを確認しています。交代後の元ホストの再参加や長時間の安定性は未確認です。本番ワールドにはまだ使用しないでください。

- [技術調査と未解決事項](docs/feasibility.md)
- `python -m unittest discover -s poc -v` でSQLiteのリースモデルを検証できます。
- `mod/` はNeoForge 26.2.0.6-beta用のMODです。クラウドの最新版とサイズ・SHA-256が一致するローカルの保存ファイルがある場合は、再ダウンロードせずに利用します。異常終了後の復旧は一部のみ対応し、ゲストの自動再接続は実際のホスト交代では未検証です。
- `poc/snapshot_manifest.py` は停止済みコピーの全ファイルをSHA-256で記録・検証します。一貫したスナップショットの作成やバックアップ機能ではありません。
- `poc/offline_backup.py` は停止済みコピーのファイルを新規ZIPに保存・検証し、新しいフォルダーに復元する実験です。プレイ中のワールドや本番運用には使えません。
- `poc/local_catalog.py` は検証済みZIPとSQLiteのリース・世代更新を組み合わせたローカル実験です。クラウド同期や実用的な分断対策ではありません。
- `poc/d1/` と `service/` はCloudflare D1向け管理APIと検証です。テスト用の実環境で認証、リースと保存世代の更新を確認しました。[設定の生成](service/PROVISIONING.md)を参照してください。
- `poc/uuid_audit.py` と `poc/player_progress.py` はコピーしたプレイヤー情報の読み取り専用調査です。個人情報を含む選択結果はGit管理しません。
- `poc/E4mcMessageProbe.java` は既知のe4mc JARの割当メッセージをオフラインで調査します。トンネル接続やアドレスの自動取得機能ではありません。

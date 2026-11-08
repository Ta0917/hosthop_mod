# D1向け単一文の排他制御実験

本番サービスではありません。`acquire.sql`・`renew.sql` は条件付きリース取得・更新、`schema.sql`・`commit.sql` は単一INSERTとトリガーによる世代更新・操作記録を試します。取得要求の記録と現在状態を照合するSQLも候補としてありますが、認証付きAPIでの安全な再試行は未実装です。信頼できる時刻の保証、クラウド上のファイル検証、メンバー管理、分断された旧ホストの停止、容量管理もありません。このコードをデプロイしたり実ワールドの保護に使ったりしないでください。

`wrangler.toml` はローカル検証専用のダミーIDで、Cloudflare上にリソースを作成しません。`--remote` は使いません。

検証時はWrangler 4.158.0を使用しました。例えば `npm install --prefix .tools/d1-tool wrangler@4.158.0 --no-audit --no-fund` で作業領域内に用意できます。Miniflare側はこのWranglerに含まれる5系の互換変換APIを使用しています。

ローカルWranglerを用意した環境では、`node poc/d1/run_local_probe.mjs .tools/d1-tool/node_modules/wrangler/bin/wrangler.js` で、再送・期限切れ・エラー時のロールバックを検証できます。テスト用データはGit管理対象外の `.tools/d1-runs` に残ります。

`node poc/d1/run_binding_probe.mjs .tools/d1-tool/node_modules/miniflare` はローカルMiniflareのD1バインディングで、番号付きパラメーターと同時要求を検証します。ネットワーク経由のサービスや本番D1のテストではありません。

# ローカル設定の生成（開発段階）

この手順は、既に準備した管理サービスのHTTPS URL、Google OAuthデスクトップクライアント、共有Driveフォルダーを使って、管理APIの設定と参加者別の招待コードを生成する。サービスの配備やGoogleアカウントの設定は行わない。実Google Driveでの権限・同期は未検証で、この手順だけではプレイを開始できない。

事前準備として、CloudflareのWorkersとD1、Google CloudプロジェクトのDrive API、デスクトップアプリ用OAuthクライアント、参加者が編集可能な共有Driveフォルダーが必要。Google側のOAuth同意画面をテストモードにする場合は、参加者のGoogleアカウントをテストユーザーに追加する。Googleの設定手順は[デスクトップ向けPicker](https://developers.google.com/workspace/drive/picker/guides/desktop-mobile-picker)と[Drive APIの有効化](https://developers.google.com/workspace/guides/enable-apis)を参照。無料枠やアカウントの課金設定は配備前に確認する。

1. `provision.example.json` をGit管理外の安全な場所へコピーし、各項目を実際の値にする。`members` は参加者ごとに異なる英数字・`_`・`-` の識別子を指定する。
2. リポジトリのルートで `node service/provision.mjs <設定ファイル> <未作成の出力フォルダー>` を実行する。出力先の上書きはできない。
3. 出力の `members-secrets.json` と `worlds-secrets.json` はそれぞれWorkerの `MEMBERS_JSON` と `WORLDS_JSON` に設定するJSON。`invite-識別子.txt` は対応する参加者専用の招待コード。`initialize-world.sql` は空のD1データベースに `poc/d1/schema.sql` を適用した後、ワールドを1回だけ登録するためのSQL。

設定ファイルと招待コードは秘密情報を含む。Gitに追加せず、安全な方法で本人にだけ渡す。出力フォルダー内のファイル権限は実行環境に依存するので、Windowsでは保存先フォルダーのアクセス権を自分で確認する。生成が途中で失敗した場合、部分的な出力は使用せず、別の未作成フォルダーでやり直す。招待コードの失効・再発行操作は未実装。

参加者を追加する場合は、現在デプロイされている設定と一致する元の設定ファイルおよび最新の `members-secrets.json` を使い、`node service/add-member.mjs <設定ファイル> <最新members-secrets.json> <新しい識別子> <未作成の出力フォルダー>` を実行する。既存メンバーの認証情報を残した新しい設定と、新参加者の招待コードを別フォルダーに生成する。生成だけではサービスは変更されない。反映後は新しい設定を次回の追加時にも使い、古い設定で上書きしない。

Driveの `drive.file` 権限はファイル単位なので、別のメンバーが作成した最新バックアップにアクセスできない場合に備え、共有ワールド画面に「最新データへのアクセスを許可」を用意している。フォルダーを連携した後、この操作でブラウザーに表示された最新バックアップを選択する。新しいバックアップが作成された後は再度選択が必要な場合がある。実アカウント間での共有・認可は未検証。

管理APIの配備には `node service/build.mjs` で生成したWorkerと `service/wrangler.example.toml` を使う。D1を新規作成し、生成されたIDをGit管理外の `service/wrangler.toml` に設定してから、空のデータベースにスキーマと初期ワールドSQLを順に適用する。既存データベースにスキーマを再適用しない。[CloudflareのD1手順](https://developers.cloudflare.com/d1/get-started/)では、実際のデータベースへのSQL適用に `--remote` が必要とされている。Workerのシークレットに上記JSONを登録し、生成したURLを招待コードの `authority` に設定する。配備と実アカウントでの確認はまだ実施していない。

期限切れの停止済みホストを復旧するWorkerには、`poc/d1/recover_stopped_migration.sql` を先に適用する。これは追加のテーブルとトリガーだけを作り、既存のワールドや保存記録を削除しない。新規データベースにもスキーマの後で適用する。実環境への適用後にWorkerを配備し、必ず隔離ワールドで確認する。復旧操作は、元のホストとリレーの停止をクライアントが確認した場合に限る。サーバー側の期限切れだけでは停止を証明できない。

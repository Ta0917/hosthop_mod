# HostHop

**Share one Minecraft world with a few friends and take turns hosting it.**
HostHop saves the world to your own Google Drive folder when the host leaves. The next player continues from the latest data. No dedicated server needed. Windows only. Experimental.

少人数の友達どうしで1つのワールドを共有する MOD です。
ホストが退出すると最新のワールドが Google Drive に保存されます。
次の人がその続きからホストを引き継げます。専用サーバーは要りません。

> ⚠ **実験的なソフトウェアです。** 大切なワールドは必ず先にバックアップしてください。
> 動作するのは **Windows のみ** です。

## できること

- ワールドを Drive の指定フォルダーに保存して復元します
- ホストを交代しながら同じワールドを続けられます（同時にホストできるのは1人だけです）
- プレイ中は5分ごとに自動でバックアップします
- 異常終了したあとに前回のデータを復旧できます
- 参加者どうしの MOD 構成の食い違いを警告します

## 必要なもの

- Windows 10/11
- Minecraft Java Edition 26.2 と NeoForge 26.2.0.6-beta
- 全員が同じ HostHop の JAR と同じ MOD 構成
- 全員の Google アカウント
- ホストになる人は **e4mc 6.2.3**（外から接続するための MOD）
- グループを作る人（主催者）は Cloudflare アカウント（無料）と Node.js 20 以上

## 仕組み

ワールドの保存先は参加者が共有する Google Drive のフォルダーです。
誰がホストかを管理する小さなサーバーは主催者が自分の Cloudflare に置きます。
外部サービスは Google Drive と Cloudflare と e4mc の3つです。どれかが止まると遊べません。

---

# セットアップ（ゼロから）

## 第1部 主催者の準備（最初に1回だけ）

操作はすべて PowerShell で行います。

### 1. サーバー用ファイルを用意する
次のリンクから `hosthop-server.zip` をダウンロードして展開します。
https://github.com/Ta0917/hosthop/releases/download/server-v0.2.0/hosthop-server.zip
展開したフォルダーを「作業フォルダー」と呼びます。以降のコマンドはそこで実行します。

### 2. Cloudflare にログインする
```
npm install wrangler
npx wrangler login
```
ブラウザーが開くので Cloudflare にログインして許可します。

### 3. データベース（D1）を作る
```
npx wrangler d1 create hosthop
```
表示された `database_id` をメモします。

### 4. 設定ファイルを作る
`service\wrangler.example.toml` を `service\wrangler.toml` としてコピーします。
中の `REPLACE_WITH_DATABASE_ID` を先ほどの `database_id` に書き換えます。

### 5. データベースを準備する
次の6つが順番に実行されます。
```
foreach ($f in 'schema','recover_stopped_migration','checkpoint_migration','checkpoint_dependencies_migration','maintenance_migration','compatibility_migration') {
  npx wrangler d1 execute DB --remote --config service/wrangler.toml --file "poc/d1/$f.sql"
}
```

### 6. サーバー（Worker）を配備する
```
node service/build.mjs
npx wrangler deploy --config service/wrangler.toml
```
最後に `https://hosthop.（あなたのサブドメイン）.workers.dev` というURLが表示されます。**メモします。**

### 7. Google Cloud を設定する
1. Google Cloud コンソールでプロジェクトを作ります。
2. 「API とサービス」で **Google Drive API** を有効にします。
3. 「Google Auth Platform」を開きます。
   - ブランディング: アプリ名とサポートメールを入力します。
   - 対象: 「外部」を選びます。
   - データアクセス: スコープは `https://www.googleapis.com/auth/drive.file` だけを追加します。
4. 「クライアント」で OAuth クライアントを作ります。種類は **デスクトップアプリ** です。
   **クライアントID** と **クライアントシークレット** をメモします。

公開ステータスについて:
- **テスト中**: 参加者全員の Google アカウントを「テストユーザー」に追加します。**7日ごとに Drive の連携をやり直す必要があります。**
- **本番**: やり直しは不要です。公開にはホームページとプライバシーポリシーのURLが必要です。

### 8. Drive にフォルダーを作る
1. Google Drive に保存用のフォルダーを作ります。
2. 参加者全員に **編集者** として共有します。
3. フォルダーを開いたURLの末尾（`folders/` のあと）が **フォルダーID** です。メモします。

### 9. 招待コードを作る
`service\provision.example.json` を `provision.json` としてコピーして書き換えます。
- `authority`: 手順6のURL
- `world`: ワールドの名前（英数字と `_` と `-` だけ）
- `drive_folder`: 手順8のフォルダーID
- `oauth_client_id` と `oauth_client_secret`: 手順7でメモした値
- `members`: 参加者の名前のリスト（英数字と `_` と `-` だけ）

```
node service/provision.mjs provision.json out
```
`out` フォルダーには参加者ごとの招待コード（`invite-名前.txt`）ができます。**フォルダーごと秘密です。**

### 10. サーバーに登録する
```
npx wrangler d1 execute DB --remote --config service/wrangler.toml --file out/initialize-world.sql
npx wrangler deploy --config service/wrangler.toml --secrets-file out/worker-secrets.json
```

### 11. 招待コードを配る
`out\invite-名前.txt` を **本人にだけ** 渡します。他の人に見せたり公開したりしないでください。

## 第2部 参加者の準備（全員）

1. HostHop の JAR を `mods` フォルダーに入れます。ホストになる人は e4mc も入れます。
2. Minecraft を起動して「マルチプレイ」を開きます。
3. 画面の右上の **HostHop** を押して「共有ワールド」を開きます。
4. 招待コードの全文をコピーします。
5. 「**クリップボードの招待コードを登録**」を押します。「招待情報を登録しました」と出れば成功です。
6. 「**Google Driveを連携**」を押します。ブラウザーで自分のアカウントにログインして許可します。
7. 主催者が共有したフォルダーを選びます。

## 第3部 最初のホスト（1人だけ）

1. 元にするワールドを自分の `saves` フォルダーに用意します。
2. 共有ワールドの画面の「初期ワールドのフォルダー名（初回のみ）」に、そのフォルダー名を入力します。
3. 「**共有ワールドに参加**」を押します。
4. ワールドが Drive にアップロードされます。数分かかります。
5. ワールドが開いたらホストとして始まります。

初回のホスト以外の人は、この欄を空のままにします。

## 第4部 ふだんの遊び方

- 誰もホストしていないときに「共有ワールドに参加」を押すと、自分がホストになります。
- ホストがいるときに同じボタンを押すと、そのワールドに入れます。
- ホストはメニューの「保存してタイトルへ」で退出します。**「共有ワールドを保存しました」と出るまで Minecraft を閉じないでください。**
- ホストを交代するときは、次の人が先に「**最新データへのアクセスを許可**」を押します。ブラウザーで最新のバックアップを選びます。そのあとで「共有ワールドに参加」を押します。

---

# 困ったとき

- **ホストする前に Google ドライブの連携を完了してください**
  → 「Google Driveを連携」を押します。
- **招待コードが無効か別のワールドが登録済みです**
  → 別の招待コードが登録済みです。`config\hosthop\credentials.dat` を別の場所へ移してから登録し直します。
- **ホストの起動中、または復旧が必要です**
  → ホストが準備中のことがあります。1分待ってもう一度押します。変わらなければ古い招待コードが残っていないか確認します。
- **再ホストする前に復旧が必要です**
  → 前回の保存が終わっていません。「共有へ復旧」を押します。**復旧のあとそのままワールドが開きます。**
- **不明または不完全なコマンドです**
  → このワールドではコマンドを使えない設計です。
- **ホスト起動に失敗しました**
  → `logs\latest.log` の `HostHop preparation failed during` の行を確認します。
- **Drive の連携が1週間で切れる**
  → Google の同意画面が「テスト中」です。本番にすると起きません。連携した日時は `config\hosthop\drive-token-history.log` に残ります。

# 注意と制限

- 同時にホストになれるのは1人だけです。
- 交代のたびに「最新データへのアクセスを許可」が必要になることがあります。
- Windows のみ対応です。
- 個人が趣味で作っている実験的なソフトウェアです。データの破損や巻き戻りが起こる可能性があります。
- プライバシーポリシー: https://hosthop.iroprobox.com/privacy.html
- 利用規約: https://hosthop.iroprobox.com/terms.html
- 不具合の報告: https://github.com/Ta0917/hosthop/issues

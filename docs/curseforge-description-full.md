# HostHop

**Share one Minecraft world with a few friends and take turns hosting it.**
When the host leaves, HostHop saves the latest world to your own Google Drive folder. The next player continues from that data. No dedicated server needed.

> ⚠ **Experimental software.** Always back up any world you care about first.
> HostHop works on **Windows only**.

*(A Japanese version of this description follows below.)*

## Features

- Saves the world to a chosen Drive folder and restores it from there
- Keep playing the same world while the host changes (only one player can host at a time)
- Automatic backup every 5 minutes while playing
- Recover the previous data after a crash
- Shows how your mods and configs differ from the host's, and lets you join anyway
- Refuses to host if you lack mods the world needs (the world is left unchanged)
- Faster hand-overs: only the changes are transferred, with progress shown

## Requirements

- Windows 10/11
- Minecraft Java Edition 26.2 and NeoForge 26.2.0.6-beta
- The same HostHop JAR for everyone, and preferably the same mod setup (client-only mods are fine)
- A Google account for every player
- **e4mc 6.2.3** for anyone who hosts (lets others connect from outside)
- For the group organizer: a (free) Cloudflare account and Node.js 20 or newer

## How it works

The world is stored in a Google Drive folder shared by the players.
A small server that tracks who is hosting runs on the organizer's own Cloudflare account.
HostHop depends on three external services: Google Drive, Cloudflare and e4mc. If any of them is down, you cannot play.

---

# Setup (from scratch)

## Part 1: Organizer preparation (once)

All commands are run in PowerShell.

### 1. Get the server files
Download `hosthop-server.zip` from the link below and extract it.
https://github.com/Ta0917/hosthop/releases/download/server-v0.3.0/hosthop-server.zip
The extracted folder is called the "working folder". Run all following commands there.

### 2. Log in to Cloudflare
```
npm install wrangler
npx wrangler login
```
A browser opens. Log in to Cloudflare and allow access.

### 3. Create the database (D1)
```
npx wrangler d1 create hosthop
```
Note the `database_id` that is shown.

### 4. Create the config file
Copy `service\wrangler.example.toml` to `service\wrangler.toml`.
Replace `REPLACE_WITH_DATABASE_ID` inside it with your `database_id`.

### 5. Prepare the database
This runs the following six files in order.
```
foreach ($f in 'schema','recover_stopped_migration','checkpoint_migration','checkpoint_dependencies_migration','maintenance_migration','compatibility_migration') {
  npx wrangler d1 execute DB --remote --config service/wrangler.toml --file "poc/d1/$f.sql"
}
```

### 6. Deploy the server (Worker)
```
node service/build.mjs
npx wrangler deploy --config service/wrangler.toml
```
At the end a URL like `https://hosthop.(your-subdomain).workers.dev` is shown. **Note it.**

### 7. Set up Google Cloud
1. Create a project in the Google Cloud console.
2. Under "APIs & Services", enable the **Google Drive API**.
3. Open "Google Auth Platform".
   - Branding: enter an app name and a support email.
   - Audience: choose "External".
   - Data access: add only the scope `https://www.googleapis.com/auth/drive.file`.
4. Under "Clients", create an OAuth client of type **Desktop app**.
   Note the **Client ID** and **Client secret**.

About the publishing status:
- **Testing**: add every player's Google account as a "Test user". **The Drive link must be redone every 7 days.**
- **In production**: no need to relink. Publishing requires a home page URL and a privacy policy URL.

### 8. Create a folder in Drive
1. Create a folder for the saves in Google Drive.
2. Share it with every player as **Editor**.
3. The end of the folder URL (after `folders/`) is the **folder ID**. Note it.

### 9. Create invitation codes
Copy `service\provision.example.json` to `provision.json` and edit it:
- `authority`: the URL from step 6
- `world`: the world name (letters, digits, `_` and `-` only)
- `drive_folder`: the folder ID from step 8
- `oauth_client_id` and `oauth_client_secret`: the values from step 7
- `members`: the list of player names (letters, digits, `_` and `-` only)

```
node service/provision.mjs provision.json out
```
The `out` folder now contains an invitation code for each player (`invite-NAME.txt`). **Keep the whole folder secret.**

### 10. Register with the server
```
npx wrangler d1 execute DB --remote --config service/wrangler.toml --file out/initialize-world.sql
npx wrangler deploy --config service/wrangler.toml --secrets-file out/worker-secrets.json
```

### 11. Hand out the invitation codes
Give each `out\invite-NAME.txt` **only to that player**. Never show or publish it to anyone else.

## Part 2: Player preparation (everyone)

1. Put the HostHop JAR in your `mods` folder. Anyone who hosts also adds e4mc.
2. Start Minecraft and open "Multiplayer".
3. Press **HostHop** at the top right to open "Shared World".
4. Copy the whole invitation code.
5. Press "**Register invitation from clipboard**". If "Invitation registered" appears, it worked.
6. Press "**Authorize Google Drive**". Log in to your account in the browser and allow access.
7. Select the folder the organizer shared.

## Part 3: The first host (one player only)

1. Put the starting world in your own `saves` folder.
2. In the Shared World screen, enter that folder name in "Initial world folder (first host only)".
3. Press "**Join shared world**".
4. The world is uploaded to Drive. This takes a few minutes.
5. When the world opens, you are the host.

Everyone other than the first host leaves this box empty.

## Part 4: Everyday play

- If nobody is hosting, pressing "Join shared world" makes you the host.
- If someone is hosting, the same button joins their world.
- The host leaves with "Save and Quit to Title". **Do not close Minecraft until "Shared world saved successfully" appears.**
- To change hosts, the previous host leaves and waits for the save, then the next player presses "Join shared world".
- The first time (or when access is needed again), the browser opens to ask for access to the shared files. Select **all** the files shown and allow access. You can also do this beforehand with "**Allow access to latest snapshot**". Normal hand-overs afterwards do not open the browser.

---

# Troubleshooting

- **Authorize Google Drive before hosting**
  → Press "Authorize Google Drive".
- **Invitation invalid or another world is already registered**
  → A different invitation code is registered. Move `config\hosthop\credentials.dat` somewhere else and register again.
- **A host is starting or needs recovery**
  → The host may still be getting ready. Wait a minute and press again. If nothing changes, check that no old invitation code is left over.
- **Recovery is required before hosting again**
  → The previous save did not finish. Press "Recover to shared". **The world opens right after recovery.**
- **Unknown or incomplete command**
  → Commands are disabled in this world by design.
- **Host startup failed**
  → Check the `HostHop preparation failed during` line in `logs\latest.log`.
- **The Drive link expires after a week**
  → Your Google consent screen is in "Testing". Switch to production to avoid this. Link times are recorded in `config\hosthop\drive-token-history.log`.

# Notes and limitations

- Only one player can host at a time.
- Each player grants access to the shared files only once. It is needed again only when the base data is rebuilt (the changes grew larger than the whole world), after recovering from a backup or a stopped host, or when a shared file had to be replaced.
- If you set up the server with an earlier version (0.2.x), apply `poc/d1/delta_slots_migration.sql` to the database **before** deploying the new Worker.
- Windows only.
- This is experimental software made as a personal hobby project. World data may be corrupted or rolled back.
- Privacy Policy: https://hosthop.iroprobox.com/privacy.html
- Terms of Use: https://hosthop.iroprobox.com/terms.html
- Bug reports: https://github.com/Ta0917/hosthop/issues

---
---

# HostHop（日本語）

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
- 自分とホストの MOD・設定ファイルの違いを一覧で表示し、そのまま参加することもできます
- ワールドに必要な MOD が足りないときはホストを拒否します（ワールドは変更されません）
- ホスト交代は変更分だけを転送し、進捗を表示するので速くなります

## 必要なもの

- Windows 10/11
- Minecraft Java Edition 26.2 と NeoForge 26.2.0.6-beta
- 全員が同じ HostHop の JAR（MOD 構成も揃っているのが望ましいです。クライアント専用 MOD は違っても構いません）
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
https://github.com/Ta0917/hosthop/releases/download/server-v0.3.0/hosthop-server.zip
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
- ホストを交代するときは、前のホストが保存を待って退出し、次の人が「共有ワールドに参加」を押します。
- 初回など再び許可が必要なときは、ブラウザーが開きます。表示されたファイルを**すべて**選んで許可します。あらかじめ「**最新データへのアクセスを許可**」で済ませることもできます。以降の通常の交代ではブラウザーは開きません。

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
- 共有ファイルへのアクセス許可は、各自1回だけです。基準データが作り直されたとき（変更分がワールド全体より大きくなったとき）、バックアップや停止したホストから復旧したとき、共有ファイルを置き換えたときは、もう一度必要です。
- 0.2.x で構築済みのサーバーは、新しい Worker を配備する**前**に `poc/d1/delta_slots_migration.sql` を適用してください。
- Windows のみ対応です。
- 個人が趣味で作っている実験的なソフトウェアです。データの破損や巻き戻りが起こる可能性があります。
- プライバシーポリシー: https://hosthop.iroprobox.com/privacy.html
- 利用規約: https://hosthop.iroprobox.com/terms.html
- 不具合の報告: https://github.com/Ta0917/hosthop/issues

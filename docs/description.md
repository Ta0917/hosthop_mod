# HostHop

**HostHop is a Minecraft mod that lets a small group of invited friends share one world and take turns hosting it, storing world snapshots in their own Google Drive folder.**

> ⚠️ **Experimental.** HostHop is a hobby project under active testing. Back up your world before using it, and do not rely on it for a world you cannot afford to lose.

## What it does

Normally, a world lives on the PC of whoever hosts it, and nobody can play when that person is offline. HostHop removes that limit without a dedicated server:

1. One player opens the shared world and hosts it (via the e4mc relay).
2. When the host leaves, HostHop saves the world to a Google Drive folder you control.
3. The next player downloads the latest save and becomes the new host.

Only one player hosts at a time, so there is a single, consistent copy of the world.

## Features

- **Shared world, rotating host** – anyone invited can host; the world follows the latest save.
- **Differential saves** – after the first full upload, only the changes are uploaded and restored.
- **Background prefetch** – while you are a guest, HostHop quietly caches the current world so that taking over is faster. Downloads are throttled to leave bandwidth for play.
- **Automatic backups every 5 minutes** while hosting, with history and restore.
- **Safe quit** – leaving the world shows a saving screen; closing the window waits for the save to finish, then quits.
- **Crash recovery** – an interrupted session can be recovered and published from the host's local copy.
- **Lease-based host control** – the server grants one host at a time and releases it when the host stops.
- **Mod-compatibility check** – players are warned when mods or configs differ from the host's.
- **Maintenance mode** – block new hosts while you repair or migrate a world.

## Requirements

| | |
|---|---|
| Minecraft | Java Edition **26.2** |
| Loader | **NeoForge 26.2.0.6-beta** |
| Hosting relay | **e4mc 6.2.3** (needed by the player who hosts) |
| Platform | **Windows** (credentials are protected with Windows encryption) |
| Accounts | A Google account for each player |
| Invitation | A personal **invitation code** from the person who runs the world |

HostHop is not self-service: the person running the world sets up a small management service (Cloudflare Workers + D1) and a Google Drive folder, then sends each player a personal invitation code.

## Getting started (players)

1. Put the same HostHop jar in your `mods` folder as the other players (a different jar triggers a compatibility warning).
2. In the multiplayer screen, open **HostHop** (top right) → **Shared world**.
3. Copy your invitation code and press **Register invitation from clipboard**.
4. Press **Link Google Drive** and choose the shared Drive folder.
5. Press **Join shared world**.
   - If someone is hosting, you join them.
   - If nobody is, you become the host. The **first** host enters the folder name of the starting world in the "initial world" box (first time only).

### Taking over as host

1. The current host leaves and waits for **"Shared world saved"**.
2. The next player presses **Allow access to latest data** and picks the latest backup in the browser (Drive permissions are per file).
3. Press **Join shared world**.

## Performance notes

- The first upload and download transfer the whole world and can take several minutes on a typical home connection (a 600 MB world takes about 3–4 minutes at 20 Mbps).
- Later hand-overs transfer only differences. Their size depends on how much changed during the session, so expect up to a minute or more at 20 Mbps.

## Privacy and Google permissions

- HostHop requests only the Google Drive **`drive.file`** scope: it can access only files it created or that you select.
- Google tokens are stored encrypted on your PC. They are not sent to the developer.
- The management service stores world/lease records, Drive file IDs and hashes, and mod/config hashes — never the world itself and never Google tokens.
- Google user data is used only to provide the mod's features. HostHop's use of information received from Google APIs adheres to the Google API Services User Data Policy, including the Limited Use requirements.

Full details: [Privacy Policy](https://hosthop.iroprobox.com/privacy.html) · [Terms of Use](https://hosthop.iroprobox.com/terms.html) · [Home](https://hosthop.iroprobox.com/)

## Known limitations

- Experimental: long-term stability has not been verified.
- Only one host at a time; hand-overs need the previous host's save to finish.
- Windows only for now.
- Requires the world owner to run the management service and issue invitations. Invitation codes cannot yet be revoked or reissued.
- If Google OAuth is in testing mode, the Drive link expires after 7 days and must be redone with **Link Google Drive**.
- Not intended for large public servers.

## Support

Bug reports and questions: [GitHub Issues](https://github.com/Ta0917/hosthop/issues). When reporting a problem, please include the on-screen message and the lines containing "HostHop" from `logs/latest.log`. Never post your invitation code.

---

# HostHop（日本語）

**HostHop は、招待された少人数の友達どうしで1つのワールドを共有し、ホストを交代しながら遊ぶための Minecraft MOD です。ワールドは、あなた自身の Google Drive のフォルダーに保存されます。**

> ⚠️ **実験的なソフトウェアです。** 使う前に、必ずワールドのバックアップを取ってください。失えないワールドには、使わないでください。

## できること
- 専用サーバーなしで、誰かがホストを終えたあとも、次の人が最新のワールドを引き継いで、遊べます。
- ホストが退出すると、ワールドが Google Drive に保存され、次の人が、それをダウンロードして、新しいホストになります。同時にホストできるのは、1人だけです。

## 主な機能
- 差分保存（初回以外は、変更分だけをアップロード・復元）
- ゲスト中の先読み（次にホストになるときの待ち時間を短縮）
- ホスト中の5分ごとの自動バックアップと、履歴・復元
- 退出時の保存待ち画面（保存が終わるまで、閉じても、終了しない）
- 異常終了後の復旧
- リースによるホスト管理（同時に1人だけ）
- MOD 構成の不一致の検出
- メンテナンスモード

## 必要なもの
Minecraft Java 26.2／NeoForge 26.2.0.6-beta／e4mc 6.2.3（ホストする人）／Windows／各自の Google アカウント／ワールドの管理者から受け取る**招待コード**

HostHop は、誰でも自由に使える形ではありません。ワールドの管理者が、管理用サービス（Cloudflare Workers と D1）と Drive のフォルダーを用意して、参加者ごとに招待コードを渡します。

## 使い方
1. 全員が、同じ JAR を `mods` に入れる。
2. マルチプレイ画面右上の「HostHop」→「共有ワールド」を開く。
3. 招待コードをコピーして、「クリップボードの招待コードを登録」を押す。
4. 「Google Driveを連携」で、共有フォルダーを選ぶ。
5. 「共有ワールドに参加」を押す。ホストがいれば参加し、いなければ、自分がホストになります。最初のホストは、初回だけ、「初期ワールド」欄に、元のワールドのフォルダー名を入れます。

**ホストの交代:** 現在のホストが退出して「保存しました」と出るのを待つ → 次の人が「最新データへのアクセスを許可」で最新のバックアップを選ぶ → 「共有ワールドに参加」を押す。

## 注意
- 初回のアップロード・ダウンロードは、全体を転送するので、数分かかります（600MBのワールドで、20Mbps なら約3〜4分）。
- Google の権限は、`drive.file` だけです。トークンは、PC 内に暗号化して保存され、開発者には送られません。
- OAuth がテスト中の場合、Drive の連携は7日で切れ、「Google Driveを連携」で、やり直す必要があります。
- プライバシーポリシー・利用規約は、上のリンクを、ご覧ください。不具合は、GitHub Issues へ。

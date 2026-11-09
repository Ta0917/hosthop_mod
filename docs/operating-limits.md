# 運用条件の確認状況

2026年11月9日時点の公式公開情報と、現在の実装に基づく目安です。各アカウントの利用量や契約を確認した結果ではなく、無料で運用できる保証ではありません。

- Cloudflare Workers Freeは1日10万リクエストです。ホスト中は15秒ごとにリースを更新するため、24時間連続なら更新だけで約5,760リクエストになります。参加者の状態確認、保存、他のWorkerからの利用も別に加算されます。[Workers limits](https://developers.cloudflare.com/workers/platform/limits/)
- D1 Freeには読み取り行数・書き込み行数・ストレージの制限があります。行数はリクエスト数と同じではなく、ほかのデータベースの使用分も影響します。上限に達した場合の動作は公式の最新情報を確認してください。[D1 pricing](https://developers.cloudflare.com/d1/platform/pricing/)
- Google OAuth同意画面が外部向けのテスト状態の場合、利用するDrive権限では更新トークンが7日で失効する場合があります。失効時はHostHop画面でGoogle Driveを再連携します。[Google OAuth token expiration](https://developers.google.com/identity/protocols/oauth2#expiration)
- 数GBの実データ、3〜5人の長時間プレイ、無料枠内の実際の消費量は未検証です。合成データでの確認やリクエスト数の計算から実運用の安定性は保証できません。
- 隔離された実Cloudflareのワールドで、同じテスト用招待情報を使う5並列の状態照会を5回実行し、すべて応答を受け取りました。これは5人の同時プレイや、5つの異なる参加者アカウントを検証したものではありません。

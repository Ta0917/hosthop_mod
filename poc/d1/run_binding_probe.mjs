// Local Miniflare D1 binding tests only. This is not a Worker to deploy.
import { createRequire } from 'node:module';
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createLeaseService } from './lease_service.mjs';
import { verifyDriveObject } from './drive_verifier.mjs';

if (process.argv.length !== 3) throw new Error('Pass the path to the miniflare package');
const require = createRequire(import.meta.url);
const { Miniflare, convertV4MiniflareOptions } = require(resolve(process.argv[2]));
const here = dirname(fileURLToPath(import.meta.url));
const load = name => readFileSync(join(here, name), 'utf8');
const schema = load('schema.sql').replace(/^--.*$/gm, '').split(/(?=CREATE (?:TABLE|TRIGGER) )/).map(s => s.trim()).filter(Boolean);
const acquire = load('acquire.sql');
const acquireStatus = load('acquire_status.sql');
const renew = load('renew.sql');
const commit = load('commit.sql');
const commitStatus = load('commit_status.sql');
const release = load('release.sql');
const endpointUpdate = load('endpoint_update.sql');
const endpointStatus = load('endpoint_status.sql');
const endpointRevision = load('endpoint_revision.sql');
const snapshotStatus = load('snapshot_status.sql');
const releaseStatus = load('release_status.sql');
const mf = new Miniflare(convertV4MiniflareOptions({
  modules: true,
  script: 'export default { fetch() { return new Response("local test"); } }',
  compatibilityDate: '2026-11-01',
  d1Databases: { DB: '00000000-0000-0000-0000-000000000000' },
}));
function check(condition, message) { if (!condition) throw new Error(message); }

try {
  const db = await mf.getD1Database('DB');
  await db.batch(schema.map(statement => db.prepare(statement)));
  await db.prepare('INSERT INTO worlds (id) VALUES (?1)').bind('world').run();
  for (const [owner, lease, duration] of [['', 'lease', 30], ['owner', '', 30], ['owner', 'lease', 1.5], ['owner', 'lease', 301]]) {
    check((await db.prepare(acquire).bind('world', owner, lease, duration).all()).results.length === 0, 'invalid acquisition succeeded');
  }

  const candidates = Array.from({ length: 16 }, (_, i) => ({ owner: `owner-${i}`, lease: `lease-${i}` }));
  const attempts = await Promise.all(candidates.map(candidate =>
    db.prepare(acquire).bind('world', candidate.owner, candidate.lease, 120).all()));
  const winners = attempts.map((result, i) => result.results.length ? i : -1).filter(i => i >= 0);
  check(winners.length === 1, `expected one lease winner, got ${winners.length}`);
  const winner = candidates[winners[0]];
  const epoch = attempts[winners[0]].results[0].epoch;
  check(attempts[winners[0]].results[0].expires - attempts[winners[0]].results[0].server_now === 120, 'grant clock interval incorrect');
  check((await db.prepare(acquire).bind('world', winner.owner, winner.lease, 120).all()).results.length === 0, 'retry created another lease');
  const originalGrant = await db.prepare(acquireStatus).bind('world', winner.lease).first();
  check(originalGrant.owner === winner.owner && originalGrant.duration_seconds === 120 && originalGrant.epoch === epoch && originalGrant.active === 1 && originalGrant.expires > originalGrant.server_now, 'acquisition retry record invalid');
  check((await db.prepare(renew).bind('world', winner.owner, 'wrong', epoch, 120).all()).results.length === 0, 'wrong lease renewed');
  check((await db.prepare(renew).bind('world', winner.owner, winner.lease, epoch, 120).all()).results.length === 1, 'valid lease failed renewal');
  const expiry = (await db.prepare('SELECT expires FROM worlds WHERE id=?1').bind('world').first()).expires;
  const shortRenew = await db.prepare(renew).bind('world', winner.owner, winner.lease, epoch, 1).first();
  check(shortRenew.expires === expiry, 'renewal shortened the lease');

  const retries = await Promise.all(Array.from({ length: 12 }, () =>
    db.prepare(commit).bind('world', 'same-operation', winner.owner, winner.lease, epoch, 0, 'digest-a', 'object-a', 42).all()));
  check(retries.reduce((count, result) => count + result.results.length, 0) === 1, 'same request changed state more than once');
  const recorded = await db.prepare(commitStatus).bind('world', 'same-operation').first();
  check(recorded.world_id === 'world' && recorded.operation_id === 'same-operation' && recorded.owner === winner.owner && recorded.lease_id === winner.lease && recorded.epoch === epoch && recorded.previous_generation === 0 && recorded.digest === 'digest-a' && recorded.object_id === 'object-a' && recorded.object_size === 42 && recorded.result_generation === 1, 'retry record differs from original request');
  check(!(await db.prepare(commitStatus).bind('world', 'missing').first()), 'missing operation had a receipt');
  check((await db.prepare(commit).bind('world', 'same-operation', winner.owner, winner.lease, epoch, 1, 'different', 'object-b', 42).all()).results.length === 0, 'operation ID reuse changed state');
  const conflicting = await Promise.all(['digest-b', 'digest-c'].map((digest, i) =>
    db.prepare(commit).bind('world', `competing-${i}`, winner.owner, winner.lease, epoch, 1, digest, 'object-b', 42).all()));
  check(conflicting.reduce((count, result) => count + result.results.length, 0) === 1, 'competing commits both succeeded or both failed');
  const state = await db.prepare('SELECT generation FROM worlds WHERE id=?1').bind('world').first();
  const count = await db.prepare('SELECT count(*) AS count FROM committed_operations WHERE world_id=?1').bind('world').first();
  check(state.generation === 2 && count.count === 2, 'unexpected stored state');

  await db.prepare("CREATE TRIGGER fail_test BEFORE UPDATE ON worlds BEGIN SELECT RAISE(ABORT, 'injected failure'); END").run();
  let failed = false;
  try { await db.prepare(commit).bind('world', 'fail-op', winner.owner, winner.lease, epoch, 2, 'digest-d', 'object-d', 42).all(); }
  catch (error) { if (!String(error).includes('injected failure')) throw error; failed = true; }
  check(failed, 'failure injection did not fail');
  const after = await db.prepare('SELECT generation FROM worlds WHERE id=?1').bind('world').first();
  const afterCount = await db.prepare('SELECT count(*) AS count FROM committed_operations WHERE world_id=?1').bind('world').first();
  check(after.generation === 2 && afterCount.count === 2, 'failure was not rolled back');
  await db.prepare('DROP TRIGGER fail_test').run();
  await db.prepare("UPDATE worlds SET expires=unixepoch('now') WHERE id=?1").bind('world').run();
  check((await db.prepare(acquire).bind('world', winner.owner, winner.lease, 120).all()).results.length === 0, 'expired grant was reused');
  check((await db.prepare(acquireStatus).bind('world', winner.lease).first()).active === 0, 'expired grant remained active');
  check(!(await db.prepare(acquire).bind('world', 'next-owner', 'fresh-lease', 120).first()), 'expiry alone permitted takeover');
  const finalReceipt = await db.prepare('SELECT operation_id FROM committed_operations WHERE world_id=?1 AND result_generation=?2').bind('world', 2).first();
  check((await db.prepare(release).bind('world', winner.owner, winner.lease, epoch, finalReceipt.operation_id, 2).all()).results.length === 1, 'confirmed release failed');
  const next = await db.prepare(acquire).bind('world', 'next-owner', 'fresh-lease', 120).first();
  check(next.epoch === epoch + 1, 'fresh acquisition did not fence prior epoch');
  check((await db.prepare(release).bind('world', winner.owner, winner.lease, epoch, 'same-operation', 1).all()).results.length === 0, 'old owner released newer lease');
  check((await db.prepare(release).bind('world', 'next-owner', 'fresh-lease', next.epoch, 'same-operation', 1).all()).results.length === 0, 'another lease commit allowed release');
  check((await db.prepare(commit).bind('world', 'final-operation', 'next-owner', 'fresh-lease', next.epoch, 2, 'digest-final', 'object-final', 42).all()).results.length === 1, 'final commit failed');
  check((await db.prepare(release).bind('world', 'next-owner', 'fresh-lease', next.epoch, 'final-operation', 2).all()).results.length === 0, 'incorrect final generation released lease');
  await db.prepare("CREATE TRIGGER fail_release BEFORE UPDATE ON worlds BEGIN SELECT RAISE(ABORT, 'release failure'); END").run();
  let releaseFailed = false;
  try { await db.prepare(release).bind('world', 'next-owner', 'fresh-lease', next.epoch, 'final-operation', 3).all(); }
  catch (error) { if (!String(error).includes('release failure')) throw error; releaseFailed = true; }
  check(releaseFailed && !(await db.prepare('SELECT 1 FROM lease_releases WHERE world_id=?1 AND lease_id=?2').bind('world', 'fresh-lease').first()), 'release failure left a receipt');
  await db.prepare('DROP TRIGGER fail_release').run();
  const releases = await Promise.all(Array.from({length: 8}, () => db.prepare(release).bind('world', 'next-owner', 'fresh-lease', next.epoch, 'final-operation', 3).all()));
  check(releases.reduce((n, result) => n + result.results.length, 0) === 1, 'release was not applied exactly once');
  const receipt = await db.prepare('SELECT owner,epoch,operation_id,result_generation FROM lease_releases WHERE world_id=?1 AND lease_id=?2').bind('world', 'fresh-lease').first();
  check(receipt.owner === 'next-owner' && receipt.epoch === next.epoch && receipt.operation_id === 'final-operation' && receipt.result_generation === 3, 'release receipt differs');
  const latest = await db.prepare(acquire).bind('world', 'third-owner', 'third-lease', 120).first();
  check(latest.epoch === next.epoch + 1 && latest.generation === 3, 'successor did not acquire released generation');
  check((await db.prepare(commitStatus).bind('world', 'final-operation').first()).digest === 'digest-final', 'later acquisition erased commit receipt');
  check((await db.prepare(release).bind('world', 'next-owner', 'fresh-lease', next.epoch, 'final-operation', 3).all()).results.length === 0, 'release retry affected successor');
  await db.prepare('INSERT INTO worlds (id) VALUES (?1)').bind('api-world').run();
  const token = 'a'.repeat(43);
  const tokenBytes = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(token));
  const tokenHash = Array.from(new Uint8Array(tokenBytes), b => b.toString(16).padStart(2, '0')).join('');
  let objectVerified = false;
  const service = createLeaseService({ acquire, renew, acquireStatus, endpointUpdate, endpointStatus, endpointRevision,
    commit, commitStatus, snapshotStatus, release, releaseStatus }, async () => objectVerified);
  const env = { DB: db, MEMBERS: [{ world: 'api-world', owner: 'api-owner', tokenHash }], WORLDS: [{ id: 'api-world', folder: 'folder' }] };
  const request = (route, body, credential = token) => service.fetch(new Request(`https://example.invalid/${route}`, {
    method: 'POST', headers: { Authorization: `Bearer ${credential}` }, body: JSON.stringify(body),
  }), env);
  const grantBody = { world: 'api-world', lease: 'api-lease', duration: 120, owner: 'forged-owner' };
  check((await request('acquire', grantBody, 'b'.repeat(43))).status === 401, 'invalid token accepted');
  check((await request('acquire', { ...grantBody, world: 'world' })).status === 401, 'token used for different world');
  const grantResponse = await request('acquire', grantBody);
  check(grantResponse.status === 200, 'authorized grant failed');
  const grant = await grantResponse.json();
  check((await request('acquire', grantBody)).status === 409, 'grant retry created another grant');
  const recovered = await request('acquire-status', grantBody);
  check(recovered.status === 200 && (await recovered.json()).epoch === grant.epoch, 'lost grant response could not be reconciled');
  check((await request('acquire-status', { ...grantBody, duration: 119 })).status === 404, 'different acquisition request matched');
  check((await db.prepare('SELECT owner FROM worlds WHERE id=?1').bind('api-world').first()).owner === 'api-owner', 'client chose owner');
  check((await request('renew', { ...grantBody, epoch: grant.epoch })).status === 200, 'authorized renewal failed');
  check((await request('renew', { ...grantBody, epoch: grant.epoch + 1 })).status === 409, 'wrong epoch renewed');
  check((await request('acquire', { ...grantBody, duration: 301 })).status === 400, 'invalid duration accepted');
  check((await request('acquire', { ...grantBody, extra: 'x'.repeat(5000) })).status === 400, 'oversized body accepted');
  const revisionResponse = await request('endpoint-revision', { ...grantBody, epoch: grant.epoch });
  check(revisionResponse.status === 200, 'owner could not retrieve endpoint revision');
  const revision = (await revisionResponse.json()).endpoint_revision;
  check((await request('endpoint-update', { ...grantBody, epoch: grant.epoch, revision, endpoint: 'unsafe.example' })).status === 400, 'untrusted endpoint accepted');
  const publishResponse = await request('endpoint-update', { ...grantBody, epoch: grant.epoch, revision, endpoint: 'relay-one.e4mc.link' });
  check(publishResponse.status === 200, 'authenticated endpoint publication failed');
  const publish = await publishResponse.json();
  check(publish && publish.endpoint_revision === revision + 1, 'endpoint publication failed');
  const statusResponse = await request('endpoint-status', { world: 'api-world' });
  check(statusResponse.status === 200 && (await statusResponse.json()).endpoint === 'relay-one.e4mc.link', 'endpoint lookup failed');
  check((await request('endpoint-status', { world: 'api-world' }, 'b'.repeat(43))).status === 401, 'unauthenticated endpoint lookup succeeded');
  const otherToken = 'c'.repeat(43);
  const otherHash = Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(otherToken))), b => b.toString(16).padStart(2, '0')).join('');
  env.MEMBERS.push({ world: 'api-world', owner: 'other-owner', tokenHash: otherHash });
  check((await request('endpoint-status', { world: 'api-world' }, otherToken)).status === 200, 'member could not see current endpoint');
  check((await request('endpoint-update', { ...grantBody, epoch: grant.epoch, revision: publish.endpoint_revision, endpoint: null }, otherToken)).status === 409, 'other member changed current endpoint');
  check((await request('endpoint-revision', { ...grantBody, epoch: grant.epoch }, otherToken)).status === 409, 'other member read private revision');
  const revoked = await db.prepare(endpointUpdate).bind('api-world', 'api-owner', 'api-lease', grant.epoch, publish.endpoint_revision, null).first();
  check(revoked && !(await db.prepare(endpointStatus).bind('api-world').first()), 'revoked endpoint remained visible');
  const replacement = await db.prepare(endpointUpdate).bind('api-world', 'api-owner', 'api-lease', grant.epoch, revoked.endpoint_revision, 'relay-two.example').first();
  check(replacement, 'replacement endpoint publication failed');
  check(!(await db.prepare(endpointUpdate).bind('api-world', 'api-owner', 'api-lease', grant.epoch, publish.endpoint_revision, null).first()), 'stale callback revoked replacement');
  check(!(await db.prepare(endpointUpdate).bind('api-world', 'api-owner', 'api-lease', grant.epoch, revision, 'stale.example').first()), 'stale callback replaced endpoint');
  check((await db.prepare(commit).bind('api-world', 'api-final', 'api-owner', 'api-lease', grant.epoch, 0, 'digest-final', 'object-final', 42).all()).results.length === 1, 'test final commit failed');
  await db.prepare("UPDATE worlds SET expires=unixepoch('now') WHERE id=?1").bind('api-world').run();
  check(!(await db.prepare(endpointStatus).bind('api-world').first()), 'expired endpoint remained visible');
  check(!(await db.prepare(endpointUpdate).bind('api-world', 'api-owner', 'api-lease', grant.epoch, replacement.endpoint_revision, 'stale.example').first()), 'expired owner published endpoint');
  check(!(await db.prepare(acquire).bind('api-world', 'new-owner', 'new-lease', 120).first()), 'expired endpoint owner was taken over without release');
  check((await db.prepare(release).bind('api-world', 'api-owner', 'api-lease', grant.epoch, 'api-final', 1).all()).results.length === 1, 'test release failed');
  await db.prepare(acquire).bind('api-world', 'new-owner', 'new-lease', 120).first();
  check(!(await db.prepare(endpointStatus).bind('api-world').first()), 'old endpoint leaked into new lease');
  await db.prepare('INSERT INTO worlds (id) VALUES (?1)').bind('commit-world').run();
  env.MEMBERS.push({ world: 'commit-world', owner: 'api-owner', tokenHash });
  env.WORLDS.push({ id: 'commit-world', folder: 'folder' });
  const commitGrant = await (await request('acquire', { world: 'commit-world', lease: 'commit-lease', duration: 120 })).json();
  const op = { world: 'commit-world', lease: 'commit-lease', epoch: commitGrant.epoch, operation: 'op',
    previousGeneration: 0, digest: 'a'.repeat(64), fileId: 'drive-file', size: 42, driveToken: 'temporary-token' };
  check((await request('commit', op)).status === 422, 'unverified object committed');
  objectVerified = true;
  const committed = await request('commit', op);
  check(committed.status === 200 && (await committed.json()).object_id === 'drive-file', 'verified object failed to commit');
  check((await request('commit', { ...op, driveToken: undefined })).status === 200, 'commit retry did not recover receipt');
  check((await request('commit-status', { ...op, fileId: 'other' })).status === 409, 'conflicting commit ID accepted');
  const snapshot = await request('snapshot-status', { world: 'commit-world' });
  check(snapshot.status === 200 && (await snapshot.json()).object_id === 'drive-file', 'committed object was not advertised');
  const releaseBody = { world: 'commit-world', lease: 'commit-lease', epoch: commitGrant.epoch, operation: 'op', resultGeneration: 1 };
  check((await request('release', { ...releaseBody, operation: 'wrong' })).status === 409, 'wrong final commit released lease');
  check((await request('release', releaseBody)).status === 200, 'verified final release failed');
  check((await request('release-status', releaseBody)).status === 200, 'release receipt unavailable');
  const originalFetch = globalThis.fetch;
  try {
    const expected = { folder: 'folder', fileId: 'drive-file', digest: 'a'.repeat(64), size: 42, accessToken: 'temporary-token' };
    globalThis.fetch = async (url, options) => {
      check(url.startsWith('https://www.googleapis.com/drive/v3/files/drive-file?'), 'Drive verification used wrong host');
      check(options.redirect === 'manual' && options.headers.Authorization === 'Bearer temporary-token', 'Drive request was not scoped');
      return Response.json({ id: 'drive-file', name: `${expected.digest}.zip`, size: '42', sha256Checksum: expected.digest, parents: ['folder'], trashed: false });
    };
    check(await verifyDriveObject(expected), 'valid Drive metadata rejected');
    check(!await verifyDriveObject({ ...expected, digest: 'b'.repeat(64) }), 'incorrect Drive digest accepted');
    check(!await verifyDriveObject({ ...expected, folder: 'other' }), 'incorrect Drive folder accepted');
  } finally { globalThis.fetch = originalFetch; }
  console.log('PASS: Miniflare D1 bindings: leases, retries, renewals, commits, releases, concurrency, rollback');
} finally {
  await mf.dispose();
}

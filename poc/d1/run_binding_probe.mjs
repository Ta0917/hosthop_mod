// Local Miniflare D1 binding tests only. This is not a Worker to deploy.
import { createRequire } from 'node:module';
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

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
  check((await db.prepare(acquire).bind('world', winner.owner, winner.lease, 120).all()).results.length === 0, 'retry created another lease');
  const originalGrant = await db.prepare(acquireStatus).bind('world', winner.lease).first();
  check(originalGrant.owner === winner.owner && originalGrant.duration_seconds === 120 && originalGrant.epoch === epoch && originalGrant.active === 1, 'acquisition retry record invalid');
  check((await db.prepare(renew).bind('world', winner.owner, 'wrong', epoch, 120).all()).results.length === 0, 'wrong lease renewed');
  check((await db.prepare(renew).bind('world', winner.owner, winner.lease, epoch, 120).all()).results.length === 1, 'valid lease failed renewal');
  const expiry = (await db.prepare('SELECT expires FROM worlds WHERE id=?1').bind('world').first()).expires;
  const shortRenew = await db.prepare(renew).bind('world', winner.owner, winner.lease, epoch, 1).first();
  check(shortRenew.expires === expiry, 'renewal shortened the lease');

  const retries = await Promise.all(Array.from({ length: 12 }, () =>
    db.prepare(commit).bind('world', 'same-operation', winner.owner, winner.lease, epoch, 0, 'digest-a').all()));
  check(retries.reduce((count, result) => count + result.results.length, 0) === 1, 'same request changed state more than once');
  const recorded = await db.prepare('SELECT owner,lease_id,epoch,previous_generation,digest,result_generation FROM committed_operations WHERE world_id=?1 AND operation_id=?2').bind('world', 'same-operation').first();
  check(recorded.owner === winner.owner && recorded.lease_id === winner.lease && recorded.epoch === epoch && recorded.previous_generation === 0 && recorded.digest === 'digest-a' && recorded.result_generation === 1, 'retry record differs from original request');
  check((await db.prepare(commit).bind('world', 'same-operation', winner.owner, winner.lease, epoch, 1, 'different').all()).results.length === 0, 'operation ID reuse changed state');
  const conflicting = await Promise.all(['digest-b', 'digest-c'].map((digest, i) =>
    db.prepare(commit).bind('world', `competing-${i}`, winner.owner, winner.lease, epoch, 1, digest).all()));
  check(conflicting.reduce((count, result) => count + result.results.length, 0) === 1, 'competing commits both succeeded or both failed');
  const state = await db.prepare('SELECT generation FROM worlds WHERE id=?1').bind('world').first();
  const count = await db.prepare('SELECT count(*) AS count FROM committed_operations WHERE world_id=?1').bind('world').first();
  check(state.generation === 2 && count.count === 2, 'unexpected stored state');

  await db.prepare("CREATE TRIGGER fail_test BEFORE UPDATE ON worlds BEGIN SELECT RAISE(ABORT, 'injected failure'); END").run();
  let failed = false;
  try { await db.prepare(commit).bind('world', 'fail-op', winner.owner, winner.lease, epoch, 2, 'digest-d').all(); }
  catch (error) { if (!String(error).includes('injected failure')) throw error; failed = true; }
  check(failed, 'failure injection did not fail');
  const after = await db.prepare('SELECT generation FROM worlds WHERE id=?1').bind('world').first();
  const afterCount = await db.prepare('SELECT count(*) AS count FROM committed_operations WHERE world_id=?1').bind('world').first();
  check(after.generation === 2 && afterCount.count === 2, 'failure was not rolled back');
  await db.prepare('DROP TRIGGER fail_test').run();
  await db.prepare("UPDATE worlds SET expires=unixepoch('now') WHERE id=?1").bind('world').run();
  check((await db.prepare(acquire).bind('world', winner.owner, winner.lease, 120).all()).results.length === 0, 'expired grant was reused');
  check((await db.prepare(acquireStatus).bind('world', winner.lease).first()).active === 0, 'expired grant remained active');
  const next = await db.prepare(acquire).bind('world', 'next-owner', 'fresh-lease', 120).first();
  check(next.epoch === epoch + 1, 'fresh acquisition did not fence prior epoch');
  console.log('PASS: Miniflare D1 bindings: invalid requests, concurrent leases, acquisition retries, renewal fence, commits, rollback');
} finally {
  await mf.dispose();
}

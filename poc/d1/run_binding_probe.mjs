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
const recoveryMigration = load('recover_stopped_migration.sql').replace(/^\s*--.*$/gm, '').split(/(?=CREATE (?:TABLE|TRIGGER) )/).map(s => s.trim()).filter(Boolean);
const recoverStopped = load('recover_stopped.sql');
const checkpoint = load('checkpoint.sql');
const checkpointStatus = load('checkpoint_status.sql');
const checkpointHistory = load('checkpoint_history.sql');
const checkpointFull = load('checkpoint_full.sql');
const checkpointMigration = load('checkpoint_migration.sql').replace(/^\s*--.*$/gm, '').split(/(?=CREATE (?:TABLE|TRIGGER|UNIQUE INDEX) )/).map(s => s.trim()).filter(Boolean);
const checkpointDelete = load('checkpoint_delete.sql');
const checkpointDeleteStatus = load('checkpoint_delete_status.sql');
const checkpointRetention = load('checkpoint_retention.sql');
const checkpointRetentionSet = load('checkpoint_retention_set.sql');
const checkpointPrune = load('checkpoint_prune.sql');
const checkpointPruneDelete = load('checkpoint_prune_delete.sql');
const checkpointTrashPending = load('checkpoint_trash_pending.sql');
const checkpointTrashDone = load('checkpoint_trash_done.sql');
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
const abort = load('abort.sql');
const abortStatus = load('abort_status.sql');
const hostStatus = load('host_status.sql');
const history = load('history.sql');
const cancelAcquisition = load('cancel_acquisition.sql');
const cancelAcquisitionStatus = load('cancel_acquisition_status.sql');
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
  await db.batch(recoveryMigration.map(statement => db.prepare(statement)));
  await db.batch(checkpointMigration.map(statement => db.prepare(statement)));
  await db.batch(load('checkpoint_dependencies_migration.sql').trim().split(/\r?\n(?=ALTER TABLE |CREATE |DROP TRIGGER )/).map(statement => db.prepare(statement)));
  await db.batch(load('maintenance_migration.sql').trim().split(/\r?\n(?=CREATE )/).map(statement => db.prepare(statement)));
  await db.prepare('INSERT INTO worlds (id) VALUES (?1)').bind('recovery-world').run();
  const recoveryGrant = await db.prepare(acquire).bind('recovery-world', 'owner', 'lease', 120).first();
  const recover = (owner, operation, generation = 0) => db.prepare(recoverStopped)
    .bind('recovery-world', operation, owner, 'lease', recoveryGrant.epoch, generation, 'digest', 'object', 42).all();
  check((await recover('owner', 'active')).results.length === 0, 'active lease was recovered');
  await db.prepare("UPDATE worlds SET expires=unixepoch('now') WHERE id=?1").bind('recovery-world').run();
  check((await recover('other', 'wrong-owner')).results.length === 0, 'other owner recovered stopped lease');
  check((await recover('owner', 'wrong-generation', 1)).results.length === 0, 'stale generation recovered');
  const recoveryAttempts = await Promise.all(Array.from({ length: 5 }, (_, i) => recover('owner', `recover-${i}`)));
  check(recoveryAttempts.reduce((sum, result) => sum + result.results.length, 0) === 1, 'recovery did not commit exactly once');
  const recoveredWorld = await db.prepare('SELECT owner,lease_id,generation FROM worlds WHERE id=?1').bind('recovery-world').first();
  check(recoveredWorld.owner === null && recoveredWorld.lease_id === null && recoveredWorld.generation === 1, 'recovery did not atomically release');
  check((await db.prepare(renew).bind('recovery-world', 'owner', 'lease', recoveryGrant.epoch, 120).all()).results.length === 0, 'old host renewed after recovery');
  const successor = await db.prepare(acquire).bind('recovery-world', 'next', 'next-lease', 120).first();
  check(successor.epoch === recoveryGrant.epoch + 1 && successor.generation === 1, 'successor did not receive recovered generation');
  check((await recover('owner', 'late')).results.length === 0, 'old recovery affected successor');
  await db.prepare(checkpoint).bind('recovery-world', 'protected', 'next', 'next-lease', successor.epoch, 1, 'a'.repeat(64), 'object', 42, 'delta', 0, '').run();
  await db.prepare(checkpointDelete).bind('recovery-world', 'protected', 'object', 'a'.repeat(64), 42).run();
  check((await db.prepare(checkpointStatus).bind('recovery-world', 'protected').first()) != null, 'checkpoint referenced by shared head was deleted');
  await db.prepare('INSERT INTO worlds (id) VALUES (?1)').bind('recovery-rollback').run();
  const rollbackGrant = await db.prepare(acquire).bind('recovery-rollback', 'owner', 'lease', 120).first();
  await db.prepare("UPDATE worlds SET expires=unixepoch('now') WHERE id=?1").bind('recovery-rollback').run();
  await db.prepare("CREATE TRIGGER reject_test_recovery BEFORE INSERT ON lease_releases WHEN NEW.world_id='recovery-rollback' BEGIN SELECT RAISE(ABORT, 'injected recovery failure'); END").run();
  let recoveryFailed = false;
  try { await db.prepare(recoverStopped).bind('recovery-rollback', 'rollback-op', 'owner', 'lease', rollbackGrant.epoch, 0, 'digest', 'object', 42).all(); }
  catch (error) { if (!String(error).includes('injected recovery failure')) throw error; recoveryFailed = true; }
  check(recoveryFailed, 'recovery failure injection did not fail');
  const rolledBack = await db.prepare("SELECT owner,lease_id,generation,expires <= unixepoch('now') AS expired FROM worlds WHERE id=?1").bind('recovery-rollback').first();
  check(rolledBack.owner === 'owner' && rolledBack.lease_id === 'lease' && rolledBack.generation === 0 && rolledBack.expired === 1,
    'failed recovery partially changed the world');
  check(!(await db.prepare(commitStatus).bind('recovery-rollback', 'rollback-op').first()), 'failed recovery left a commit receipt');
  await db.prepare('DROP TRIGGER reject_test_recovery').run();
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
    commit, commitStatus, snapshotStatus, release, releaseStatus, abort, abortStatus, hostStatus, cancelAcquisition, cancelAcquisitionStatus, history, recoverStopped,
    checkpoint, checkpointStatus, checkpointHistory, checkpointFull, checkpointDelete, checkpointDeleteStatus,
    checkpointRetention, checkpointRetentionSet, checkpointPrune, checkpointPruneDelete, checkpointTrashPending, checkpointTrashDone }, async () => objectVerified);
  const env = { DB: db, MEMBERS: [{ world: 'api-world', owner: 'api-owner', tokenHash }], WORLDS: [{ id: 'api-world', folder: 'folder' }] };
  const request = (route, body, credential = token) => service.fetch(new Request(`https://example.invalid/${route}`, {
    method: 'POST', headers: { Authorization: `Bearer ${credential}` }, body: JSON.stringify(body),
  }), env);
  await db.prepare('INSERT INTO worlds (id) VALUES (?1)').bind('api-recovery-world').run();
  env.MEMBERS.push({ world: 'api-recovery-world', owner: 'api-owner', tokenHash });
  env.WORLDS.push({ id: 'api-recovery-world', folder: 'folder' });
  const stoppedGrant = await db.prepare(acquire).bind('api-recovery-world', 'api-owner', 'stopped-lease', 120).first();
  const stoppedRequest = { world: 'api-recovery-world', lease: 'stopped-lease', epoch: stoppedGrant.epoch,
    operation: 'stopped-operation', previousGeneration: 0, digest: 'a'.repeat(64), fileId: 'object-id', size: 42, driveToken: 'token' };
  objectVerified = true;
  check((await request('recover-stopped', stoppedRequest)).status === 409, 'active lease accepted for recovery');
  await db.prepare("UPDATE worlds SET expires=unixepoch('now') WHERE id=?1").bind('api-recovery-world').run();
  check((await request('recover-stopped', stoppedRequest, 'b'.repeat(43))).status === 401, 'unauthorized recovery accepted');
  objectVerified = false;
  check((await request('recover-stopped', stoppedRequest)).status === 422, 'unverified object accepted');
  objectVerified = true;
  check((await request('recover-stopped', stoppedRequest)).status === 200, 'stopped recovery failed');
  check((await request('recover-stopped', stoppedRequest)).status === 200, 'lost recovery response could not be retried');
  check((await request('recover-stopped', { ...stoppedRequest, digest: 'b'.repeat(64) })).status === 409, 'recovery operation reused with different data');
  check((await request('release-status', { ...stoppedRequest, resultGeneration: 1 })).status === 200, 'recovery release receipt missing');
  objectVerified = false;
  await db.prepare('INSERT INTO worlds (id) VALUES (?1)').bind('cancel-world').run();
  env.MEMBERS.push({ world: 'cancel-world', owner: 'api-owner', tokenHash });
  const cancelled = { world: 'cancel-world', lease: 'cancel-first', duration: 120 };
  check((await request('cancel-acquisition', cancelled)).status === 200, 'ungranted acquisition could not be cancelled');
  check((await request('acquire', cancelled)).status === 409, 'delayed cancelled acquisition was granted');
  check((await request('cancel-acquisition', cancelled)).status === 200, 'cancellation retry failed');
  const granted = { ...cancelled, lease: 'grant-first' };
  check((await request('acquire', granted)).status === 200, 'test acquisition failed');
  check((await request('cancel-acquisition', granted)).status === 200, 'unstarted grant could not be cancelled');
  check((await request('cancel-acquisition', granted)).status === 200, 'grant cancellation retry failed');
  const raced = { ...cancelled, lease: 'race' };
  const [raceCancel, raceGrant] = await Promise.all([request('cancel-acquisition', raced), request('acquire', raced)]);
  check([200, 409].includes(raceGrant.status) && raceCancel.status === 200, 'concurrent cancellation failed');
  check((await request('cancel-acquisition', raced)).status === 200, 'concurrent cancellation retry failed');
  check((await request('acquire', raced)).status === 409, 'cancelled race could acquire later');
  check((await request('acquire', { ...cancelled, lease: 'next-grant' })).status === 200, 'cancelled grant blocked successor');
  const grantBody = { world: 'api-world', lease: 'api-lease', duration: 120, owner: 'forged-owner' };
  check((await request('acquire', grantBody, 'b'.repeat(43))).status === 401, 'invalid token accepted');
  check((await request('acquire', { ...grantBody, world: 'world' })).status === 401, 'token used for different world');
  const grantResponse = await request('acquire', grantBody);
  check(grantResponse.status === 200, 'authorized grant failed');
  const grant = await grantResponse.json();
  const checkpointBody = { world: 'api-world', lease: 'api-lease', epoch: grant.epoch, generation: 0,
    checkpoint: 'checkpoint-one', digest: 'a'.repeat(64), fileId: 'checkpoint-file', size: 42, kind: 'delta', driveToken: 'token' };
  objectVerified = false;
  check((await request('checkpoint', checkpointBody)).status === 422, 'unverified checkpoint accepted');
  objectVerified = true;
  const checkpoints = await Promise.all(Array.from({length: 5}, () => request('checkpoint', checkpointBody)));
  check(checkpoints.every(result => result.status === 200), 'checkpoint retry failed');
  check((await request('checkpoint', { ...checkpointBody, digest: 'b'.repeat(64) })).status === 409, 'checkpoint identity reused');
  check((await request('checkpoint', { ...checkpointBody, checkpoint: 'alias', kind: 'full' })).status === 409, 'checkpoint object used with conflicting kind');
  check((await request('checkpoint', { ...checkpointBody, checkpoint: 'stale', epoch: grant.epoch + 1 })).status === 409, 'stale checkpoint accepted');
  check((await (await request('checkpoint-history', { world: 'api-world' })).json()).checkpoints.length === 1, 'checkpoint history mismatch');
  for (let i = 0; i < 40; i++) {
    await db.prepare(checkpoint).bind('api-world', `list-${i}`, 'api-owner', 'api-lease', grant.epoch, 0,
      'a'.repeat(64), `file-${i}`, 42, i < 4 ? 'full' : 'delta', 0, '').run();
  }
  const checkpointRows = (await db.prepare(checkpointHistory).bind('api-world').all()).results;
  check(checkpointRows.length === 32 && checkpointRows.filter(row => row.kind === 'full').length === 4,
    'recent full backups were hidden by frequent deltas');
  check((await (await request('checkpoint-retention', { world: 'api-world' })).json()).keep_delta === 4, 'default retention mismatch');
  check((await request('checkpoint-retention-set', { world: 'api-world', keep: 0 })).status === 400, 'invalid retention accepted');
  check((await (await request('checkpoint-retention-set', { world: 'api-world', keep: 6 })).json()).keep_delta === 6, 'retention not updated');
  const pruneCandidates = (await (await request('checkpoint-prune', { world: 'api-world' })).json()).checkpoints;
  check(pruneCandidates.length === 2 && pruneCandidates.every(row => row.kind === 'delta'), 'prune candidates included protected full backups');
  const pruneBody = { world: 'api-world', checkpoint: pruneCandidates[0].checkpoint_id, fileId: pruneCandidates[0].object_id,
    digest: pruneCandidates[0].digest, size: pruneCandidates[0].object_size, keep: 6 };
  check((await request('checkpoint-retention-set', { world: 'api-world', keep: 28 })).status === 200, 'retention increase failed');
  check((await request('checkpoint-prune-delete', pruneBody)).status === 409, 'stale retention deleted a checkpoint');
  check((await request('checkpoint-retention-set', { world: 'api-world', keep: 6 })).status === 200, 'retention reset failed');
  check((await request('checkpoint-prune-delete', pruneBody)).status === 200, 'eligible old checkpoint was not deleted');
  const deletion = { world: 'api-world', checkpoint: 'checkpoint-one', fileId: 'checkpoint-file', digest: 'a'.repeat(64), size: 42 };
  check((await request('checkpoint-delete', { ...deletion, digest: 'b'.repeat(64) })).status === 409, 'wrong deletion digest accepted');
  check((await request('checkpoint-delete', deletion)).status === 200, 'delta deletion failed');
  check((await request('checkpoint-delete', deletion)).status === 200, 'lost deletion response could not be retried');
  const pendingTrash = (await (await request('checkpoint-trash-pending', { world: 'api-world' })).json()).checkpoints;
  check(pendingTrash.some(item => item.checkpoint_id === deletion.checkpoint), 'deleted file not queued for cleanup');
  check((await request('checkpoint-trash-done', deletion)).status === 200, 'cleanup receipt failed');
  check((await request('checkpoint-trash-done', deletion)).status === 200, 'cleanup receipt retry failed');
  const afterTrash = (await (await request('checkpoint-trash-pending', { world: 'api-world' })).json()).checkpoints;
  check(!afterTrash.some(item => item.checkpoint_id === deletion.checkpoint), 'completed cleanup still pending');
  check((await request('checkpoint-delete', { ...deletion, fileId: 'different' })).status === 409, 'deletion receipt reused for another file');
  check((await db.prepare(commit).bind('api-world', 'deleted-object', 'api-owner', 'api-lease', grant.epoch, 0,
    'a'.repeat(64), 'checkpoint-file', 42).all()).results.length === 0, 'deleted object became shared head');
  check((await request('checkpoint', { ...checkpointBody, checkpoint: 'deleted-alias' })).status === 409, 'deleted object registered again');
  check((await request('checkpoint-delete', { ...deletion, checkpoint: 'list-0', fileId: 'file-0' })).status === 409, 'full checkpoint deletion allowed');
  check((await db.prepare('SELECT generation FROM worlds WHERE id=?1').bind('api-world').first()).generation === 0, 'checkpoint changed shared head');
  objectVerified = false;
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
  const publishResponse = await request('endpoint-update', { ...grantBody, epoch: grant.epoch, revision, endpoint: 'relay-one.us.e4mc.link' });
  check(publishResponse.status === 200, 'authenticated endpoint publication failed');
  const publish = await publishResponse.json();
  check(publish && publish.endpoint_revision === revision + 1, 'endpoint publication failed');
  const statusResponse = await request('endpoint-status', { world: 'api-world' });
  check(statusResponse.status === 200 && (await statusResponse.json()).endpoint === 'relay-one.us.e4mc.link', 'endpoint lookup failed');
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
  const listed = await request('history', { world: 'commit-world' });
  const saved = await listed.json();
  check(listed.status === 200 && saved.snapshots.length === 1 && saved.snapshots[0].generation === 1
      && saved.snapshots[0].object_id === 'drive-file', 'committed snapshot missing from history');
  check((await request('history', { world: 'commit-world' }, 'b'.repeat(43))).status === 401, 'history accepted unauthorized user');
  await db.prepare('INSERT INTO worlds (id) VALUES (?1)').bind('history-world').run();
  env.MEMBERS.push({ world: 'history-world', owner: 'api-owner', tokenHash });
  env.WORLDS.push({ id: 'history-world', folder: 'folder' });
  for (let generation = 0; generation < 5; generation++) {
    const lease = `history-${generation}`;
    const g = await (await request('acquire', { world: 'history-world', lease, duration: 120 })).json();
    const operation = `history-op-${generation}`;
    const item = { world: 'history-world', lease, epoch: g.epoch, operation, previousGeneration: generation,
      digest: 'a'.repeat(64), fileId: `file-${generation}`, size: 42, driveToken: 'temporary-token' };
    check((await request('commit', item)).status === 200, 'history test commit failed');
    check((await request('release', { ...item, resultGeneration: generation + 1 })).status === 200, 'history test release failed');
  }
  const recent = await (await request('history', { world: 'history-world' })).json();
  check(recent.snapshots.map(x => x.generation).join(',') === '5,4,3,2', 'history did not return latest four generations');
  await db.prepare('INSERT INTO worlds (id) VALUES (?1)').bind('abort-world').run();
  env.MEMBERS.push({ world: 'abort-world', owner: 'api-owner', tokenHash });
  env.WORLDS.push({ id: 'abort-world', folder: 'folder' });
  check((await (await request('host-status', { world: 'abort-world' })).json()).held === 0, 'free world not reported');
  const abortGrant = await (await request('acquire', { world: 'abort-world', lease: 'abort-lease', duration: 120 })).json();
  const abortBody = { world: 'abort-world', lease: 'abort-lease', epoch: abortGrant.epoch, baseGeneration: 0 };
  check((await (await request('host-status', { world: 'abort-world' })).json()).held === 1, 'active lease not reported');
  check((await request('abort', { ...abortBody, baseGeneration: 1 })).status === 409, 'wrong generation aborted lease');
  check((await request('abort', abortBody)).status === 200, 'unstarted host could not release lease');
  const replacementGrant = await request('acquire', { world: 'abort-world', lease: 'replacement', duration: 120 });
  check(replacementGrant.status === 200, 'replacement could not acquire aborted lease');
  check((await request('abort', abortBody)).status === 200, 'abort retry could not recover receipt');
  check((await db.prepare('SELECT lease_id FROM worlds WHERE id=?1').bind('abort-world').first()).lease_id === 'replacement', 'abort retry released replacement');
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

// Local-only D1 integration probe. Never supplies --remote or deploys a Worker.
import { mkdtempSync, mkdirSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const here = dirname(fileURLToPath(import.meta.url));
if (process.argv.length !== 3) throw new Error('Pass the path to wrangler/bin/wrangler.js');
const wrangler = resolve(process.argv[2]);
const root = resolve(here, '../../.tools/d1-runs');
mkdirSync(root, { recursive: true });
const state = mkdtempSync(join(root, 'run-'));
const env = { ...process.env, WRANGLER_SEND_METRICS: 'false', CI: 'true' };
const sql = readFileSync(join(here, 'commit.sql'), 'utf8').replace(/^--.*$/gm, '').trim();
const acquire = readFileSync(join(here, 'acquire.sql'), 'utf8').replace(/^--.*$/gm, '').trim();
const renew = readFileSync(join(here, 'renew.sql'), 'utf8').replace(/^--.*$/gm, '').trim();

function run(args, expectedFailure = false) {
  const result = spawnSync(process.execPath, [wrangler, 'd1', 'execute', 'hosthop-local-prototype', '--local', '--json', '--persist-to', state, ...args],
    { cwd: here, env, encoding: 'utf8', timeout: 60000, maxBuffer: 4 * 1024 * 1024 });
  if (result.error) throw result.error;
  if (expectedFailure) {
    if (result.status === 0 || !(result.stderr + result.stdout).includes('injected failure')) throw new Error('Expected injected D1 failure');
    return;
  }
  if (result.status !== 0) throw new Error('Local D1 command failed: ' + result.stderr.slice(-1500));
  return JSON.parse(result.stdout);
}

function commit(op, generation, digest) {
  return bindConstants(sql, ["'test-world'", `'${op}'`, "'test-owner'", "'lease-1'", '1', String(generation), `'${digest}'`, "'file-id'", '42']);
}
function check(condition, message) { if (!condition) throw new Error(message); }
function bindConstants(statement, values) {
  return statement.replace(/\?([1-9][0-9]*)/g, (_, index) => values[Number(index) - 1]);
}
function leaseStatement(statement, leaseId) {
  const params = ["'lease-world'", "'test-owner'", `'${leaseId}'`];
  return bindConstants(statement, statement === acquire ? [...params, '60'] : [...params, '1', '60']);
}

run(['--file', join(here, 'schema.sql')]);
run(['--file', join(here, 'checkpoint_migration.sql')]);
run(['--command', "INSERT INTO worlds (id, owner, lease_id, epoch, expires) VALUES ('test-world', 'test-owner', 'lease-1', 1, unixepoch('now') + 300); INSERT INTO worlds (id) VALUES ('lease-world');"]);
const leases = run(['--command', `${leaseStatement(acquire, 'lease-1')}\n${leaseStatement(acquire, 'lease-2')}\n${leaseStatement(renew, 'wrong')}\n${leaseStatement(renew, 'lease-1')}`]);
check(leases[0].results[0].epoch === 1 && leases[1].results.length === 0, 'concurrent lease was not excluded');
check(leases[2].results.length === 0 && leases[3].results[0].epoch === 1, 'lease renewal fence failed');
run(['--command', "UPDATE worlds SET expires=unixepoch('now') WHERE id='lease-world'"]);
check(run(['--command', leaseStatement(acquire, 'lease-2')])[0].results.length === 0, 'expiry alone allowed takeover');
const repeated = run(['--command', `${commit('op-1', 0, 'first')}\n${commit('op-1', 0, 'first')}\nSELECT generation,digest,(SELECT count(*) FROM committed_operations) AS operations FROM worlds WHERE id='test-world';`]);
check(repeated[0].results[0].result_generation === 1 && repeated[1].results.length === 0, 'repeat changed generation');
check(repeated[2].results[0].generation === 1 && repeated[2].results[0].operations === 1, 'wrong committed state');

run(['--command', "UPDATE worlds SET expires=unixepoch('now') WHERE id='test-world'"]);
const stale = run(['--command', commit('stale-op', 1, 'stale')]);
check(stale[0].results.length === 0, 'expired lease committed');
run(['--command', "UPDATE worlds SET expires=unixepoch('now')+300 WHERE id='test-world'; CREATE TRIGGER fail_test BEFORE UPDATE ON worlds BEGIN SELECT RAISE(ABORT, 'injected failure'); END;"]);
run(['--command', commit('fail-op', 1, 'second')], true);
const after = run(['--command', "SELECT generation,digest,(SELECT count(*) FROM committed_operations) AS operations FROM worlds WHERE id='test-world'"])[0].results[0];
check(after.generation === 1 && after.digest === 'first' && after.operations === 1, 'failed update changed committed state');
console.log('PASS: local D1 lease, retry, expiry, and transaction rollback checks');
console.log('Local test state:', state);

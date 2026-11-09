// Executes the local-only lease service in workerd; never deploys or opens an external resource.
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
const migration = load('recover_stopped_migration.sql').replace(/^\s*--.*$/gm, '').split(/(?=CREATE (?:TABLE|TRIGGER) )/).map(s => s.trim()).filter(Boolean);
const token = 'q'.repeat(43);
const hash = Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(token))),
  value => value.toString(16).padStart(2, '0')).join('');
const built = name => readFileSync(join(here, '..', '..', 'service', 'build', name), 'utf8');
const mf = new Miniflare(convertV4MiniflareOptions({
  modules: ['worker.mjs', 'sql.mjs', 'lease_service.mjs', 'drive_verifier.mjs']
    .map(path => ({ type: 'ESModule', path, contents: built(path) })),
  compatibilityDate: '2026-11-01',
  d1Databases: { DB: '00000000-0000-0000-0000-000000000000' },
  bindings: { MEMBERS_JSON: JSON.stringify([{ world: 'test-world', owner: 'test-owner', tokenHash: hash }]),
    WORLDS_JSON: JSON.stringify([{ id: 'test-world', folder: 'test-folder' }]) },
}));
const check = (value, message) => { if (!value) throw new Error(message); };
try {
  const db = await mf.getD1Database('DB');
  await db.batch(schema.map(statement => db.prepare(statement)));
  await db.batch(migration.map(statement => db.prepare(statement)));
  await db.prepare('INSERT INTO worlds (id) VALUES (?1)').bind('test-world').run();
  const call = (route, body, credential = token) => mf.dispatchFetch(`http://localhost/${route}`, {
    method: 'POST', headers: { Authorization: `Bearer ${credential}` }, body: JSON.stringify(body),
  });
  const body = { world: 'test-world', lease: 'test-lease', duration: 30 };
  check((await call('acquire', body, 'z'.repeat(43))).status === 401, 'unauthorized request succeeded');
  const acquired = await call('acquire', body);
  check(acquired.status === 200, 'acquisition failed in workerd');
  const grant = await acquired.json();
  check((await call('acquire-status', body)).status === 200, 'grant lookup failed');
  const current = await (await call('endpoint-revision', { ...body, epoch: grant.epoch })).json();
  const published = await call('endpoint-update', { ...body, epoch: grant.epoch,
    revision: current.endpoint_revision, endpoint: 'example.e4mc.link' });
  check(published.status === 200, 'endpoint publication failed in workerd');
  const status = await call('endpoint-status', { world: 'test-world' });
  check(status.status === 200 && (await status.json()).endpoint === 'example.e4mc.link', 'endpoint lookup failed in workerd');
  check((await call('endpoint-update', { ...body, epoch: grant.epoch,
    revision: current.endpoint_revision, endpoint: null })).status === 409, 'stale revision accepted');
  console.log('PASS: local workerd lease and endpoint API');
} finally {
  await mf.dispose();
}

// Assemble a Cloudflare Worker from the checked-in service and SQL; does not deploy anything.
import { mkdir, readFile, writeFile, copyFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const source = join(here, '..', 'poc', 'd1');
const out = join(here, 'build');
const mappings = { acquire: 'acquire.sql', renew: 'renew.sql', acquireStatus: 'acquire_status.sql',
  endpointUpdate: 'endpoint_update.sql', endpointStatus: 'endpoint_status.sql', endpointRevision: 'endpoint_revision.sql',
  commit: 'commit.sql', commitStatus: 'commit_status.sql', snapshotStatus: 'snapshot_status.sql', release: 'release.sql', releaseStatus: 'release_status.sql' };
const sql = Object.fromEntries(await Promise.all(Object.entries(mappings).map(async ([key, file]) => [key, await readFile(join(source, file), 'utf8')])));
await mkdir(out, { recursive: true });
await writeFile(join(out, 'sql.mjs'), `export default ${JSON.stringify(sql)};\n`);
await Promise.all(['lease_service.mjs', 'drive_verifier.mjs'].map(file => copyFile(join(source, file), join(out, file))));
await copyFile(join(here, 'worker.mjs'), join(out, 'worker.mjs'));
console.log('Worker assembled in service/build; no deployment performed');

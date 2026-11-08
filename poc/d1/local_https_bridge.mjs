// Opt-in test bridge: local HTTPS -> isolated workerd + local D1. No deployment.
import { createRequire } from 'node:module';
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import https from 'node:https';

if (process.argv.length !== 4) throw new Error('Pass miniflare path and test PKCS12 path');
const require = createRequire(import.meta.url);
const { Miniflare, convertV4MiniflareOptions } = require(resolve(process.argv[2]));
const here = dirname(fileURLToPath(import.meta.url));
const load = name => readFileSync(join(here, name), 'utf8');
const schema = load('schema.sql').replace(/^--.*$/gm, '').split(/(?=CREATE (?:TABLE|TRIGGER) )/).map(s => s.trim()).filter(Boolean);
const token = 'a'.repeat(43);
const hash = Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(token))),
  value => value.toString(16).padStart(2, '0')).join('');
let uploadedObject = null;
const built = name => readFileSync(join(here, '..', '..', 'service', 'build', name), 'utf8');
const mf = new Miniflare(convertV4MiniflareOptions({
  modules: ['worker.mjs', 'sql.mjs', 'lease_service.mjs', 'drive_verifier.mjs']
    .map(path => ({ type: 'ESModule', path, contents: built(path) })),
  compatibilityDate: '2026-11-01', d1Databases: { DB: '00000000-0000-0000-0000-000000000000' },
  bindings: { MEMBERS_JSON: JSON.stringify([{ world: 'world', owner: 'owner', tokenHash: hash }]),
    WORLDS_JSON: JSON.stringify([{ id: 'world', folder: 'folder' }]) },
  outboundService: async request => {
    if (uploadedObject && request.url.startsWith(`https://www.googleapis.com/drive/v3/files/${uploadedObject.id}?`)
        && request.headers.get('Authorization') === 'Bearer drive-test-token')
      return Response.json(uploadedObject);
    return new Response(null, { status: 403 });
  },
}));
const db = await mf.getD1Database('DB');
await db.batch(schema.map(statement => db.prepare(statement)));
await db.prepare('INSERT INTO worlds (id) VALUES (?1)').bind('world').run();
let dropFirstAcquisitionReply = !process.env.HOSTHOP_TEST_NO_DROPS;
let dropFirstCommitReply = !process.env.HOSTHOP_TEST_NO_DROPS;
let dropFirstReleaseReply = !process.env.HOSTHOP_TEST_NO_DROPS;
const server = https.createServer({ pfx: readFileSync(process.argv[3]), passphrase: 'test-password' }, async (req, res) => {
  try {
    const chunks = [];
    for await (const chunk of req) chunks.push(chunk);
    if (req.url === '/test-object') {
      const object = JSON.parse(Buffer.concat(chunks).toString('utf8'));
      uploadedObject = { id: object.fileId, name: `${object.digest}.zip`, size: String(object.size),
        sha256Checksum: object.digest, parents: ['folder'], trashed: false };
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end('{}');
      return;
    }
    if (req.url === '/test-expire') {
      await db.prepare("UPDATE worlds SET expires=unixepoch('now') WHERE id=?1").bind('world').run();
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end('{}');
      return;
    }
    const response = await mf.dispatchFetch(`http://localhost${req.url}`, {
      method: req.method, headers: req.headers, body: Buffer.concat(chunks),
    });
    if (dropFirstAcquisitionReply && req.url === '/acquire' && response.status === 200) {
      dropFirstAcquisitionReply = false;
      req.socket.destroy(); // Simulate a committed grant whose response never arrives.
      return;
    }
    if (dropFirstCommitReply && req.url === '/commit' && response.status === 200) {
      dropFirstCommitReply = false;
      req.socket.destroy();
      return;
    }
    if (dropFirstReleaseReply && req.url === '/release' && response.status === 200) {
      dropFirstReleaseReply = false;
      req.socket.destroy();
      return;
    }
    res.writeHead(response.status, Object.fromEntries(response.headers));
    res.end(Buffer.from(await response.arrayBuffer()));
  } catch {
    if (!res.headersSent) res.writeHead(500);
    res.end();
  }
});
await new Promise((resolveListen, reject) => server.once('error', reject).listen(0, '127.0.0.1', resolveListen));
console.log(`PORT=${server.address().port}`);
process.stdin.resume();
process.stdin.once('end', async () => {
  server.closeAllConnections();
  await new Promise(resolveClose => server.close(resolveClose));
  await mf.dispose();
});

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtemp, readFile, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';

test('generates distinct invitations matching Worker credentials and refuses overwrites', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'hosthop-provision-'));
  try {
    const input = join(dir, 'input.json'), output = join(dir, 'output');
    await writeFile(input, JSON.stringify({ authority: 'https://host.example', world: 'world', drive_folder: 'folder',
      oauth_client_id: 'client', oauth_client_secret: 'secret', members: ['one', 'two'] }));
    const run = () => spawnSync(process.execPath, [resolve('service/provision.mjs'), input, output], { encoding: 'utf8' });
    const result = run();
    assert.equal(result.status, 0);
    const members = JSON.parse(await readFile(join(output, 'members-secrets.json'), 'utf8'));
    const worlds = JSON.parse(await readFile(join(output, 'worlds-secrets.json'), 'utf8'));
    assert.deepEqual(worlds, [{ id: 'world', folder: 'folder' }]);
    assert.equal(await readFile(join(output, 'initialize-world.sql'), 'utf8'), "INSERT INTO worlds (id) VALUES ('world');\n");
    const codes = await Promise.all(['one', 'two'].map(name => readFile(join(output, `invite-${name}.txt`), 'utf8')));
    const invites = codes.map(code => JSON.parse(Buffer.from(code.trim().slice('hosthop1:'.length), 'base64url')));
    assert.notEqual(invites[0].member_token, invites[1].member_token);
    for (let i = 0; i < 2; i++) {
      assert.ok(codes[i].startsWith('hosthop1:'));
      assert.equal(invites[i].principal, members[i].owner);
      assert.equal(createHash('sha256').update(invites[i].member_token).digest('hex'), members[i].tokenHash);
      assert.ok(!result.stdout.includes(invites[i].member_token));
    }
    assert.notEqual(run().status, 0);
    assert.equal(await readFile(join(output, 'invite-one.txt'), 'utf8'), codes[0]);
  } finally { await rm(dir, { recursive: true, force: true }); }
});

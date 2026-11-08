// Generate one world's Worker configuration and per-member invitations locally.
import { randomBytes, createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { join } from 'node:path';

const [input, output, ...extra] = process.argv.slice(2);
if (!input || !output || extra.length) {
  console.error('Usage: node service/provision.mjs <config.json> <new-output-directory>');
  process.exit(2);
}
try {
  const config = JSON.parse(await readFile(input, 'utf8'));
  const ident = value => typeof value === 'string' && /^[A-Za-z0-9_-]{1,80}$/.test(value);
  const str = (value, pattern) => typeof value === 'string' && pattern.test(value);
  const url = new URL(config.authority);
  if (url.protocol !== 'https:' || !url.hostname || url.username || url.password || url.search || url.hash || url.pathname !== '/') throw Error('Invalid HTTPS authority');
  if (!ident(config.world) || !str(config.drive_folder, /^[A-Za-z0-9_-]{1,200}$/)
      || !str(config.oauth_client_id, /^[A-Za-z0-9._-]{1,512}$/)
      || !str(config.oauth_client_secret, /^[A-Za-z0-9_-]{1,512}$/)
      || !Array.isArray(config.members) || config.members.length < 1 || config.members.length > 128
      || config.members.some(member => !ident(member)) || new Set(config.members.map(member => member.toLowerCase())).size !== config.members.length)
    throw Error('Invalid configuration fields');
  // Exclusive directory creation prevents replacing earlier credentials.
  await mkdir(output, { mode: 0o700 });
  const members = [];
  for (const principal of config.members) {
    const member_token = randomBytes(32).toString('base64url');
    members.push({ world: config.world, owner: principal, tokenHash: createHash('sha256').update(member_token).digest('hex') });
    const invitation = { authority: url.origin, world: config.world, principal, member_token,
      oauth_client_id: config.oauth_client_id, oauth_client_secret: config.oauth_client_secret, drive_folder: config.drive_folder };
    const code = 'hosthop1:' + Buffer.from(JSON.stringify(invitation)).toString('base64url');
    if (code.length > 8192) throw Error('Invitation too large');
    await writeFile(join(output, `invite-${principal}.txt`), code + '\n', { flag: 'wx', mode: 0o600 });
  }
  await writeFile(join(output, 'members-secrets.json'), JSON.stringify(members) + '\n', { flag: 'wx', mode: 0o600 });
  await writeFile(join(output, 'worlds-secrets.json'), JSON.stringify([{ id: config.world, folder: config.drive_folder }]) + '\n', { flag: 'wx', mode: 0o600 });
  // World identifiers are restricted to the ASCII identifier grammar above.
  await writeFile(join(output, 'initialize-world.sql'), `INSERT INTO worlds (id) VALUES ('${config.world}');\n`, { flag: 'wx', mode: 0o600 });
  console.log('Configuration and invitations generated. Treat the output directory as secret.');
} catch (error) {
  console.error('Provisioning failed. Check the input and output path; any partially created output must not be used.');
  process.exitCode = 1;
}

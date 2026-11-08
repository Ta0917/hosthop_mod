// Prepare a new invitation and replacement Worker member secret without changing the deployed service.
import { randomBytes, createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { join } from 'node:path';

const [configFile, membersFile, principal, output, ...extra] = process.argv.slice(2);
if (!configFile || !membersFile || !principal || !output || extra.length) {
  console.error('Usage: node service/add-member.mjs <config.json> <members-secrets.json> <member-id> <new-output-directory>');
  process.exit(2);
}
try {
  const config = JSON.parse(await readFile(configFile, 'utf8'));
  const members = JSON.parse(await readFile(membersFile, 'utf8'));
  const ident = value => typeof value === 'string' && /^[A-Za-z0-9_-]{1,80}$/.test(value);
  const match = (value, regex) => typeof value === 'string' && regex.test(value);
  const url = new URL(config.authority);
  if (url.protocol !== 'https:' || !url.hostname || url.username || url.password || url.search || url.hash || url.pathname !== '/'
      || !ident(config.world) || !ident(principal) || !match(config.drive_folder, /^[A-Za-z0-9_-]{1,200}$/)
      || !match(config.oauth_client_id, /^[A-Za-z0-9._-]{1,512}$/) || !match(config.oauth_client_secret, /^[A-Za-z0-9_-]{1,512}$/)
      || !Array.isArray(members) || members.length < 1 || members.length >= 128
      || members.some(m => !m || m.world !== config.world || !ident(m.owner) || !match(m.tokenHash, /^[0-9a-f]{64}$/))
      || new Set(members.map(m => m.owner.toLowerCase())).size !== members.length
      || new Set(members.map(m => m.tokenHash)).size !== members.length
      || members.some(m => m.owner.toLowerCase() === principal.toLowerCase())) throw Error('Invalid configuration');
  const token = randomBytes(32).toString('base64url');
  const invitation = { authority: url.origin, world: config.world, principal, member_token: token,
    oauth_client_id: config.oauth_client_id, oauth_client_secret: config.oauth_client_secret, drive_folder: config.drive_folder };
  const code = 'hosthop1:' + Buffer.from(JSON.stringify(invitation)).toString('base64url');
  if (code.length > 8192) throw Error('Invitation too large');
  const updated = [...members, { world: config.world, owner: principal, tokenHash: createHash('sha256').update(token).digest('hex') }];
  await mkdir(output, { mode: 0o700 });
  await writeFile(join(output, `invite-${principal}.txt`), code + '\n', { flag: 'wx', mode: 0o600 });
  await writeFile(join(output, 'members-secrets.json'), JSON.stringify(updated) + '\n', { flag: 'wx', mode: 0o600 });
  console.log('Additional invitation and member configuration prepared locally; service was not changed.');
} catch {
  console.error('Member preparation failed. Check the input and output path; partial output must not be used.');
  process.exitCode = 1;
}

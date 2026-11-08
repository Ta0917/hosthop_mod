import sql from './sql.mjs';
import { createLeaseService } from './lease_service.mjs';

const service = createLeaseService(sql);
const id = value => typeof value === 'string' && /^[A-Za-z0-9_-]{1,80}$/.test(value);

function configuration(env) {
  const members = JSON.parse(env.MEMBERS_JSON);
  const worlds = JSON.parse(env.WORLDS_JSON);
  if (!Array.isArray(members) || members.length < 1 || members.length > 128 || !Array.isArray(worlds) || worlds.length < 1 || worlds.length > 32)
    throw new Error('Invalid configuration');
  const worldIds = new Set();
  for (const world of worlds) {
    if (!id(world.id) || worldIds.has(world.id) || typeof world.folder !== 'string' || !/^[A-Za-z0-9_-]{1,200}$/.test(world.folder))
      throw new Error('Invalid world configuration');
    worldIds.add(world.id);
  }
  const identities = new Set();
  for (const member of members) {
    const key = `${member.world}:${member.tokenHash}`;
    if (!worldIds.has(member.world) || !id(member.owner) || typeof member.tokenHash !== 'string'
        || !/^[0-9a-f]{64}$/.test(member.tokenHash) || identities.has(key)) throw new Error('Invalid member configuration');
    identities.add(key);
  }
  return { DB: env.DB, MEMBERS: members, WORLDS: worlds };
}

export default {
  async fetch(request, env) {
    let configured;
    try { configured = configuration(env); }
    catch { return Response.json({ error: 'service unavailable' }, { status: 503, headers: { 'Cache-Control': 'no-store' } }); }
    return service.fetch(request, configured);
  },
};

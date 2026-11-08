// Local-only service experiment; not a deployable service or a production auth design.
// SQL is injected so this experiment executes the same statements as the D1 probes.
const identifier = value => typeof value === 'string' && /^[A-Za-z0-9_-]{1,80}$/.test(value);
const tokenPattern = /^[A-Za-z0-9_-]{43}$/;
const json = (body, status = 200) => Response.json(body, { status, headers: { 'Cache-Control': 'no-store' } });

async function digestToken(token) {
  const bytes = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(token));
  return Array.from(new Uint8Array(bytes), value => value.toString(16).padStart(2, '0')).join('');
}

async function readJson(request) {
  const reader = request.body?.getReader();
  if (!reader) throw new Error('missing body');
  let size = 0;
  const parts = [];
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    size += value.length;
    if (size > 4096) { await reader.cancel(); throw new Error('body too large'); }
    parts.push(value);
  }
  const bytes = new Uint8Array(size);
  let offset = 0;
  for (const part of parts) { bytes.set(part, offset); offset += part.length; }
  return JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes));
}

export function createLeaseService(sql) {
  if (!sql?.acquire || !sql?.renew || !sql?.acquireStatus) throw new Error('missing SQL');
  return {
    async fetch(request, env) {
      if (request.method !== 'POST') return json({ error: 'not found' }, 404);
      const route = new URL(request.url).pathname;
      if (route !== '/acquire' && route !== '/renew' && route !== '/acquire-status') return json({ error: 'not found' }, 404);
      const auth = request.headers.get('Authorization') || '';
      const token = auth.startsWith('Bearer ') ? auth.slice(7) : '';
      if (!tokenPattern.test(token)) return json({ error: 'unauthorized' }, 401);
      let body;
      try { body = await readJson(request); } catch { return json({ error: 'invalid request' }, 400); }
      if (!body || Array.isArray(body) || typeof body !== 'object' || !identifier(body.world) || !identifier(body.lease)
          || !Number.isSafeInteger(body.duration) || body.duration < 1 || body.duration > 300
          || (route === '/renew' && (!Number.isSafeInteger(body.epoch) || body.epoch < 1)))
        return json({ error: 'invalid request' }, 400);
      // A high-entropy bearer token identifies a single owner in a single world.
      // The experiment does not implement provisioning, rotation, or revocation.
      const tokenHash = await digestToken(token);
      const member = env.MEMBERS.find(item => item.world === body.world && item.tokenHash === tokenHash);
      if (!member || !identifier(member.owner)) return json({ error: 'unauthorized' }, 401);
      if (route === '/acquire-status') {
        const record = await env.DB.prepare(sql.acquireStatus).bind(body.world, body.lease).first();
        if (!record || record.owner !== member.owner || record.duration_seconds !== body.duration)
          return json({ error: 'unknown grant' }, 404);
        return json(record);
      }
      const statement = route === '/acquire'
        ? env.DB.prepare(sql.acquire).bind(body.world, member.owner, body.lease, body.duration)
        : env.DB.prepare(sql.renew).bind(body.world, member.owner, body.lease, body.epoch, body.duration);
      const result = await statement.first();
      return result ? json(result) : json({ error: 'lease conflict' }, 409);
    },
  };
}

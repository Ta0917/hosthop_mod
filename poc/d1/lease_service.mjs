// Service under development; deployment, key provisioning and security review are still required.
// SQL is injected so this experiment executes the same statements as the D1 probes.
import { verifyDriveObject } from './drive_verifier.mjs';
const identifier = value => typeof value === 'string' && /^[A-Za-z0-9_-]{1,80}$/.test(value);
const tokenPattern = /^[A-Za-z0-9_-]{43}$/;
const json = (body, status = 200) => Response.json(body, { status, headers: { 'Cache-Control': 'no-store' } });
const sameHash = (a, b) => {
  if (typeof a !== 'string' || typeof b !== 'string' || !/^[0-9a-f]{64}$/.test(a) || !/^[0-9a-f]{64}$/.test(b)) return false;
  let difference = 0;
  for (let i = 0; i < 64; i++) difference |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return difference === 0;
};

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

export function createLeaseService(sql, verifyObject = verifyDriveObject) {
  if (!sql?.acquire || !sql?.renew || !sql?.acquireStatus || !sql?.endpointUpdate || !sql?.endpointStatus || !sql?.endpointRevision
      || !sql?.commit || !sql?.commitStatus || !sql?.snapshotStatus || !sql?.release || !sql?.releaseStatus || !sql?.abort || !sql?.abortStatus || !sql?.hostStatus
      || !sql?.cancelAcquisition || !sql?.cancelAcquisitionStatus || !sql?.history || !sql?.recoverStopped)
    throw new Error('missing SQL');
  return {
    async fetch(request, env) {
      if (request.method !== 'POST') return json({ error: 'not found' }, 404);
      const route = new URL(request.url).pathname;
      if (!['/acquire', '/renew', '/acquire-status', '/endpoint-update', '/endpoint-status', '/endpoint-revision',
        '/commit', '/commit-status', '/recover-stopped', '/snapshot-status', '/release', '/release-status', '/abort', '/host-status', '/cancel-acquisition', '/history'].includes(route))
        return json({ error: 'not found' }, 404);
      const auth = request.headers.get('Authorization') || '';
      const token = auth.startsWith('Bearer ') ? auth.slice(7) : '';
      if (!tokenPattern.test(token)) return json({ error: 'unauthorized' }, 401);
      let body;
      try { body = await readJson(request); } catch { return json({ error: 'invalid request' }, 400); }
      if (!body || Array.isArray(body) || typeof body !== 'object' || !identifier(body.world))
        return json({ error: 'invalid request' }, 400);
      if (!['/endpoint-status', '/snapshot-status', '/host-status', '/history'].includes(route) && !identifier(body.lease)) return json({ error: 'invalid request' }, 400);
      if (['/acquire', '/renew', '/acquire-status'].includes(route)
          && (!Number.isSafeInteger(body.duration) || body.duration < 1 || body.duration > 300))
        return json({ error: 'invalid request' }, 400);
      if (['/renew', '/endpoint-update', '/endpoint-revision', '/commit', '/commit-status', '/recover-stopped', '/release', '/release-status', '/abort'].includes(route)
          && (!Number.isSafeInteger(body.epoch) || body.epoch < 1))
        return json({ error: 'invalid request' }, 400);
      if (route === '/endpoint-update' && (!Number.isSafeInteger(body.revision) || body.revision < 0
          || (body.endpoint !== null && (typeof body.endpoint !== 'string' || body.endpoint.length > 253
              || !/^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*\.e4mc\.link$/.test(body.endpoint)))))
        return json({ error: 'invalid request' }, 400);
      if (['/commit', '/commit-status', '/recover-stopped'].includes(route) && (!identifier(body.operation) || !Number.isSafeInteger(body.previousGeneration)
          || body.previousGeneration < 0 || typeof body.digest !== 'string' || !/^[0-9a-f]{64}$/.test(body.digest)
          || typeof body.fileId !== 'string' || !/^[A-Za-z0-9_-]{1,200}$/.test(body.fileId)
          || !Number.isSafeInteger(body.size) || body.size < 1 || body.size > 68_719_476_736))
        return json({ error: 'invalid request' }, 400);
      if (['/release', '/release-status'].includes(route) && (!identifier(body.operation)
          || !Number.isSafeInteger(body.resultGeneration) || body.resultGeneration < 1))
        return json({ error: 'invalid request' }, 400);
      if (route === '/abort' && (!Number.isSafeInteger(body.baseGeneration) || body.baseGeneration < 0))
        return json({ error: 'invalid request' }, 400);
      // A high-entropy bearer token identifies a single owner in a single world.
      // The experiment does not implement provisioning, rotation, or revocation.
      const tokenHash = await digestToken(token);
      const member = env.MEMBERS.find(item => item.world === body.world && sameHash(item.tokenHash, tokenHash));
      if (!member || !identifier(member.owner)) return json({ error: 'unauthorized' }, 401);
      if (route === '/history') {
        const rows = await env.DB.prepare(sql.history).bind(body.world).all();
        return json({ snapshots: rows.results });
      }
      if (route === '/cancel-acquisition') {
        // The caller must have stopped its local process and never started the server.
        // Serial D1 writes fence both already-granted and delayed acquire requests.
        await env.DB.prepare(sql.cancelAcquisition).bind(body.world, member.owner, body.lease).run();
        const cancelled = await env.DB.prepare(sql.cancelAcquisitionStatus).bind(body.world, body.lease).first();
        if (cancelled) return cancelled.owner === member.owner ? json(cancelled) : json({ error: 'lease conflict' }, 409);
        const grant = await env.DB.prepare(sql.acquireStatus).bind(body.world, body.lease).first();
        if (!grant || grant.owner !== member.owner) return json({ error: 'lease conflict' }, 409);
        const findAbort = () => env.DB.prepare(sql.abortStatus).bind(body.world, body.lease).first();
        let aborted = await findAbort();
        if (!aborted) {
          await env.DB.prepare(sql.abort).bind(body.world, member.owner, body.lease, grant.epoch, grant.base_generation).run();
          aborted = await findAbort();
        }
        return aborted && aborted.owner === member.owner && aborted.epoch === grant.epoch && aborted.base_generation === grant.base_generation
          ? json({ world_id: body.world, owner: member.owner, lease_id: body.lease }) : json({ error: 'lease conflict' }, 409);
      }
      if (route === '/host-status') {
        const status = await env.DB.prepare(sql.hostStatus).bind(body.world).first();
        return status ? json(status) : json({ error: 'unknown world' }, 404);
      }
      if (route === '/abort') {
        const find = () => env.DB.prepare(sql.abortStatus).bind(body.world, body.lease).first();
        const matches = row => row && row.owner === member.owner && row.epoch === body.epoch && row.base_generation === body.baseGeneration;
        let receipt = await find();
        if (receipt) return matches(receipt) ? json(receipt) : json({ error: 'abort conflict' }, 409);
        await env.DB.prepare(sql.abort).bind(body.world, member.owner, body.lease, body.epoch, body.baseGeneration).all();
        receipt = await find();
        return matches(receipt) ? json(receipt) : json({ error: 'lease conflict' }, 409);
      }
      if (route === '/snapshot-status') {
        const snapshot = await env.DB.prepare(sql.snapshotStatus).bind(body.world).first();
        return snapshot ? json(snapshot) : json({ error: 'unknown world' }, 404);
      }
      if (route === '/commit' || route === '/commit-status' || route === '/recover-stopped') {
        const find = () => env.DB.prepare(sql.commitStatus).bind(body.world, body.operation).first();
        const matches = row => row && row.owner === member.owner && row.lease_id === body.lease && row.epoch === body.epoch
          && row.previous_generation === body.previousGeneration && row.digest === body.digest && row.object_id === body.fileId
          && row.object_size === body.size && row.result_generation === body.previousGeneration + 1;
        let receipt = await find();
        if (receipt) return matches(receipt) ? json(receipt) : json({ error: 'operation conflict' }, 409);
        if (route === '/commit-status') return json({ error: 'unknown operation' }, 404);
        const world = env.WORLDS?.find(item => item.id === body.world);
        if (!world || typeof world.folder !== 'string' || !/^[A-Za-z0-9_-]{1,200}$/.test(world.folder)
            || typeof body.driveToken !== 'string' || body.driveToken.length < 1 || body.driveToken.length > 2048
            || /[\x00-\x20\x7f]/.test(body.driveToken)) return json({ error: 'invalid request' }, 400);
        if (!await verifyObject({ folder: world.folder, fileId: body.fileId, digest: body.digest, size: body.size, accessToken: body.driveToken }))
          return json({ error: 'snapshot verification failed' }, 422);
        // Recovery uses a single atomic transaction and is only safe when the
        // owning client has independently verified its server and relay stopped.
        await env.DB.prepare(route === '/recover-stopped' ? sql.recoverStopped : sql.commit).bind(body.world, body.operation, member.owner, body.lease, body.epoch,
          body.previousGeneration, body.digest, body.fileId, body.size).all();
        receipt = await find();
        return matches(receipt) ? json(receipt) : json({ error: 'lease conflict' }, 409);
      }
      if (route === '/release' || route === '/release-status') {
        const find = () => env.DB.prepare(sql.releaseStatus).bind(body.world, body.lease).first();
        const matches = row => row && row.owner === member.owner && row.epoch === body.epoch
          && row.operation_id === body.operation && row.result_generation === body.resultGeneration;
        let receipt = await find();
        if (receipt) return matches(receipt) ? json(receipt) : json({ error: 'release conflict' }, 409);
        if (route === '/release-status') return json({ error: 'unknown release' }, 404);
        // The caller must have fully stopped its server and verified the final snapshot.
        await env.DB.prepare(sql.release).bind(body.world, member.owner, body.lease, body.epoch, body.operation, body.resultGeneration).all();
        receipt = await find();
        return matches(receipt) ? json(receipt) : json({ error: 'lease conflict' }, 409);
      }
      if (route === '/endpoint-status') {
        const endpoint = await env.DB.prepare(sql.endpointStatus).bind(body.world).first();
        return endpoint ? json(endpoint) : json({ error: 'no endpoint' }, 404);
      }
      if (route === '/endpoint-revision') {
        const current = await env.DB.prepare(sql.endpointRevision).bind(body.world, member.owner, body.lease, body.epoch).first();
        return current ? json(current) : json({ error: 'lease conflict' }, 409);
      }
      if (route === '/endpoint-update') {
        const endpoint = await env.DB.prepare(sql.endpointUpdate)
          .bind(body.world, member.owner, body.lease, body.epoch, body.revision, body.endpoint).first();
        return endpoint ? json(endpoint) : json({ error: 'lease conflict' }, 409);
      }
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

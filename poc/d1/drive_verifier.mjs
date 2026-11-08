// Verify that the snapshot with the expected digest is present in the configured Drive folder.
// The file remains mutable in Drive; downloaders must always verify its digest again.
export async function verifyDriveObject({ folder, fileId, digest, size, accessToken }) {
  const url = `https://www.googleapis.com/drive/v3/files/${encodeURIComponent(fileId)}?fields=id,name,size,sha256Checksum,parents,trashed`;
  let response;
  try {
    response = await fetch(url, { headers: { Authorization: `Bearer ${accessToken}` }, redirect: 'manual', signal: AbortSignal.timeout(10_000) });
    if (!response.ok) return false;
    const reader = response.body?.getReader();
    if (!reader) return false;
    const parts = [];
    let total = 0;
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      total += value.length;
      if (total > 16_384) { await reader.cancel(); return false; }
      parts.push(value);
    }
    const bytes = new Uint8Array(total);
    let offset = 0;
    for (const part of parts) { bytes.set(part, offset); offset += part.length; }
    const data = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes));
    return data.id === fileId && data.name === `${digest}.zip` && data.sha256Checksum === digest
      && data.size === String(size) && data.trashed === false && Array.isArray(data.parents) && data.parents.includes(folder);
  } catch { return false; }
}

// Offline audio handler, imported into the generated Workbox service worker.
//
// The <audio> element always points at the same-origin stream URL (/Audio/{id}/stream),
// never a blob: URL — so it satisfies a strict `media-src 'self'` CSP. When the track is
// downloaded, this worker answers the request from IndexedDB (with HTTP Range support so
// seeking works); otherwise it falls through to the network. This is what makes offline
// playback work without a CSP exception for blob:.

const OFFLINE_DB_NAME = 'yaytsa-offline';
const OFFLINE_STORE_BLOBS = 'blobs';
const OFFLINE_STORE_TRACKS = 'tracks';

function openOfflineDb() {
  return new Promise((resolve, reject) => {
    // No version: open the DB as the app left it; never trigger an upgrade from the worker.
    const req = indexedDB.open(OFFLINE_DB_NAME);
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error || new Error('indexedDB open failed'));
  });
}

function idbGet(db, storeName, key) {
  return new Promise(resolve => {
    if (!db.objectStoreNames.contains(storeName)) {
      resolve(undefined);
      return;
    }
    let tx;
    try {
      tx = db.transaction(storeName, 'readonly');
    } catch {
      resolve(undefined);
      return;
    }
    const req = tx.objectStore(storeName).get(key);
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => resolve(undefined);
  });
}

async function getCachedAudio(trackId) {
  const db = await openOfflineDb();
  try {
    const blobRec = await idbGet(db, OFFLINE_STORE_BLOBS, trackId);
    if (!blobRec?.blob || blobRec.blob.size === 0) return null;
    const trackRec = await idbGet(db, OFFLINE_STORE_TRACKS, trackId);
    const contentType = trackRec?.contentType || blobRec.blob.type || 'audio/mpeg';
    return { blob: blobRec.blob, contentType };
  } finally {
    db.close();
  }
}

function buildResponse(blob, contentType, rangeHeader) {
  const size = blob.size;
  const baseHeaders = {
    'Content-Type': contentType,
    'Accept-Ranges': 'bytes',
    'Cache-Control': 'no-store',
  };

  if (!rangeHeader) {
    return new Response(blob, {
      status: 200,
      headers: { ...baseHeaders, 'Content-Length': String(size) },
    });
  }

  const match = /bytes=(\d*)-(\d*)/.exec(rangeHeader);
  let start = match?.[1] ? Number.parseInt(match[1], 10) : 0;
  let end = match?.[2] ? Number.parseInt(match[2], 10) : size - 1;
  if (!Number.isFinite(start) || start < 0) start = 0;
  if (!Number.isFinite(end) || end >= size) end = size - 1;

  if (start > end || start >= size) {
    return new Response(null, {
      status: 416,
      headers: { ...baseHeaders, 'Content-Range': `bytes */${size}` },
    });
  }

  return new Response(blob.slice(start, end + 1, contentType), {
    status: 206,
    headers: {
      ...baseHeaders,
      'Content-Length': String(end - start + 1),
      'Content-Range': `bytes ${start}-${end}/${size}`,
    },
  });
}

globalThis.addEventListener('fetch', event => {
  if (event.request.method !== 'GET') return;

  let pathname;
  try {
    pathname = new URL(event.request.url).pathname;
  } catch {
    return;
  }

  const match = /\/Audio\/([^/]+)\/stream/.exec(pathname);
  if (!match) return; // Not an audio stream — leave it to Workbox / the network.

  const trackId = decodeURIComponent(match[1]);
  event.respondWith(
    (async () => {
      try {
        const cached = await getCachedAudio(trackId);
        if (cached) {
          return buildResponse(cached.blob, cached.contentType, event.request.headers.get('range'));
        }
      } catch {
        // Fall through to the network on any IndexedDB error.
      }
      return fetch(event.request);
    })()
  );
});

import { assetOps, unwrap } from './api';
import type { RpcChannel } from './api';

const MAX_CACHE_ENTRIES = 64;
const MAX_CONCURRENT_READS = 4;

interface CacheEntry {
  key: string;
  pack: string;
  path: string;
  settled: boolean;
  invalidated: boolean;
  value?: string;
  promise: Promise<string>;
}

interface ReadTask {
  run: () => Promise<string>;
  resolve: (value: string) => void;
  reject: (reason: unknown) => void;
}

const cache = new Map<string, CacheEntry>();
const inFlight = new Set<CacheEntry>();
const channelIds = new WeakMap<object, number>();
const readQueue: ReadTask[] = [];
const capacityListeners = new Set<() => void>();
let nextChannelId = 1;
let activeReads = 0;

export class ThumbnailCapacityError extends Error {}

/** Visible previews retry on capacity, not a timer or an unbounded request queue. */
export function onThumbnailCapacity(listener: () => void): () => void {
  capacityListeners.add(listener);
  if (inFlight.size < MAX_CACHE_ENTRIES) queueMicrotask(listener);
  return () => { capacityListeners.delete(listener); };
}

function notifyCapacity(): void {
  for (const listener of [...capacityListeners]) listener();
}

const MIME_TYPES: Record<string, string> = {
  png: 'image/png',
  jpg: 'image/jpeg',
  jpeg: 'image/jpeg',
  webp: 'image/webp',
  gif: 'image/gif',
};

/** Read a thumbnail asset through the sealed channel; no document RPC is involved. */
export function loadThumbnail(
  channel: RpcChannel,
  pack: string,
  path: string,
  version = 0,
): Promise<string> {
  const channelId = channelIdentity(channel);
  const key = JSON.stringify([channelId, pack, path, version]);
  const existing = cache.get(key);
  if (existing) {
    touch(existing);
    return existing.settled
      ? Promise.resolve(existing.value!)
      : existing.promise;
  }
  if (inFlight.size >= MAX_CACHE_ENTRIES) {
    return Promise.reject(new ThumbnailCapacityError('Waiting for other thumbnail previews to finish.'));
  }

  let resolveRequest!: (dataUrl: string) => void;
  let rejectRequest!: (error: unknown) => void;
  const promise = new Promise<string>((resolve, reject) => {
    resolveRequest = resolve;
    rejectRequest = reject;
  });
  const entry: CacheEntry = {
    key,
    pack,
    path,
    settled: false,
    invalidated: false,
    promise,
  };
  cache.set(key, entry);
  inFlight.add(entry);
  trimCache();

  enqueue(async () => {
    if (entry.invalidated) {
      throw new Error('Thumbnail request was invalidated before it started.');
    }
    if (cache.get(key) !== entry) {
      throw new Error('Thumbnail request was evicted before it started.');
    }
    return fetchThumbnail(channel, pack, path);
  }).then(
    (dataUrl) => {
      inFlight.delete(entry);
      if (entry.invalidated) {
        rejectRequest(new Error('Thumbnail request was invalidated before it completed.'));
        notifyCapacity();
        return;
      }
      if (cache.get(key) === entry) {
        entry.value = dataUrl;
        entry.settled = true;
        touch(entry);
      }
      resolveRequest(dataUrl);
      notifyCapacity();
    },
    (error: unknown) => {
      inFlight.delete(entry);
      if (cache.get(key) === entry) cache.delete(key);
      rejectRequest(error);
      notifyCapacity();
    },
  );

  return promise;
}

/** Invalidate all thumbnails, one pack, or one exact asset path. */
export function invalidateThumbnails(pack?: string, path?: string): void {
  for (const [key, entry] of cache) {
    const matches = (pack === undefined || entry.pack === pack)
      && (path === undefined || entry.path === path);
    if (matches) cache.delete(key);
    if (matches && !entry.settled) entry.invalidated = true;
  }
  for (const entry of inFlight) {
    if ((pack === undefined || entry.pack === pack)
        && (path === undefined || entry.path === path)) {
      entry.invalidated = true;
    }
  }
}

/** Clear the cache and ensure no older in-flight read can repopulate it. */
export function resetThumbnailCache(): void {
  cache.clear();
  for (const entry of inFlight) entry.invalidated = true;
}

async function fetchThumbnail(channel: RpcChannel, pack: string, path: string): Promise<string> {
  const extension = path.slice(path.lastIndexOf('.') + 1).toLowerCase();
  const mime = MIME_TYPES[extension];
  if (!mime) {
    throw new Error(`Unsupported thumbnail format${extension ? `: .${extension}` : ''}.`);
  }

  const payload = unwrap(await assetOps.get(channel, pack, path));
  const data = typeof payload.data === 'string' ? payload.data.trim() : '';
  if (!data || !/^[A-Za-z0-9+/]+={0,2}$/.test(data)
      || data.length % 4 === 1 || (data.includes('=') && data.length % 4 !== 0)) {
    throw new Error('The thumbnail asset is missing or is not valid base64 image data.');
  }
  return `data:${mime};base64,${data}`;
}

function channelIdentity(channel: RpcChannel): number {
  const existing = channelIds.get(channel);
  if (existing !== undefined) return existing;
  const id = nextChannelId++;
  channelIds.set(channel, id);
  return id;
}

function touch(entry: CacheEntry): void {
  if (cache.get(entry.key) !== entry) return;
  cache.delete(entry.key);
  cache.set(entry.key, entry);
}

function trimCache(): void {
  while (cache.size > MAX_CACHE_ENTRIES) {
    const oldest = [...cache.values()].find((entry) => entry.settled);
    // Admission bounds pending entries; evict cached bytes, never queued work.
    if (!oldest) return;
    cache.delete(oldest.key);
  }
}

function enqueue(run: () => Promise<string>): Promise<string> {
  return new Promise<string>((resolve, reject) => {
    readQueue.push({
      run: () => run(),
      resolve,
      reject,
    });
    pumpQueue();
  });
}

function pumpQueue(): void {
  while (activeReads < MAX_CONCURRENT_READS && readQueue.length) {
    const task = readQueue.shift()!;
    activeReads += 1;
    void task.run().then(task.resolve, task.reject).finally(() => {
      activeReads -= 1;
      pumpQueue();
    });
  }
}

import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { RpcChannel } from '../src/lib/api';
import type { Response } from '../src/lib/protocol/wire';
import {
  invalidateThumbnails,
  loadThumbnail,
  onThumbnailCapacity,
  resetThumbnailCache,
  ThumbnailCapacityError,
} from '../src/lib/thumbnails';

const pngData = 'aGVsbG8=';

function channelFor(call: RpcChannel['call']): RpcChannel {
  return { call };
}

function response(data = pngData): Response {
  return { id: 'thumbnail', op: 'assets.get', ok: true, data: { data } };
}

describe('thumbnail cache', () => {
  beforeEach(() => resetThumbnailCache());

  it('deduplicates reads for the same asset and returns a data URL', async () => {
    let finish!: (value: Response) => void;
    const call = vi.fn(() => new Promise<Response>((resolve) => { finish = resolve; }));
    const channel = channelFor(call);

    const first = loadThumbnail(channel, 'demo', 'assets/items/sword.png');
    const second = loadThumbnail(channel, 'demo', 'assets/items/sword.png');
    expect(call).toHaveBeenCalledTimes(1);

    finish(response());
    await expect(Promise.all([first, second])).resolves.toEqual([
      `data:image/png;base64,${pngData}`,
      `data:image/png;base64,${pngData}`,
    ]);
    expect(call).toHaveBeenCalledWith('assets.get', {
      pack: 'demo',
      path: 'assets/items/sword.png',
    });
  });

  it('limits parallel asset reads to four', async () => {
    const finishers: ((value: Response) => void)[] = [];
    let active = 0;
    let peak = 0;
    const call = vi.fn(() => new Promise<Response>((resolve) => {
      active += 1;
      peak = Math.max(peak, active);
      finishers.push((value) => {
        active -= 1;
        resolve(value);
      });
    }));
    const channel = channelFor(call);
    const requests = Array.from({ length: 8 }, (_, index) =>
      loadThumbnail(channel, 'demo', `assets/${index}.png`));

    expect(call).toHaveBeenCalledTimes(4);
    for (let start = 0; start < requests.length; start += 4) {
      const batch = finishers.splice(0);
      expect(batch).toHaveLength(Math.min(4, requests.length - start));
      for (const finish of batch) finish(response());
      await Promise.all(requests.slice(start, start + batch.length));
    }
    expect(call).toHaveBeenCalledTimes(8);
    expect(peak).toBe(4);
  });

  it('bounds the cache and evicts the least recently used asset', async () => {
    const call = vi.fn(async () => response());
    const channel = channelFor(call);

    for (let index = 0; index < 65; index += 1) {
      await loadThumbnail(channel, 'demo', `assets/${index}.png`);
    }
    expect(call).toHaveBeenCalledTimes(65);
    await loadThumbnail(channel, 'demo', 'assets/0.png');
    expect(call).toHaveBeenCalledTimes(66);
  });

  it('does not let an invalidated in-flight read overwrite the fresh entry', async () => {
    const finishers: ((value: Response) => void)[] = [];
    const call = vi.fn(() => new Promise<Response>((resolve) => { finishers.push(resolve); }));
    const channel = channelFor(call);

    const stale = loadThumbnail(channel, 'demo', 'assets/sword.png');
    invalidateThumbnails('demo', 'assets/sword.png');
    const fresh = loadThumbnail(channel, 'demo', 'assets/sword.png');
    expect(call).toHaveBeenCalledTimes(2);

    finishers[0]!(response('c3RhbGU='));
    await expect(stale).rejects.toThrow('invalidated');
    finishers[1]!(response('ZnJlc2g='));
    await expect(fresh).resolves.toBe('data:image/png;base64,ZnJlc2g=');
    await expect(loadThumbnail(channel, 'demo', 'assets/sword.png'))
      .resolves.toBe('data:image/png;base64,ZnJlc2g=');
    expect(call).toHaveBeenCalledTimes(2);
  });

  it('leaves unrelated in-flight assets valid when one path is invalidated', async () => {
    const finishers: ((value: Response) => void)[] = [];
    const call = vi.fn(() => new Promise<Response>((resolve) => { finishers.push(resolve); }));
    const channel = channelFor(call);
    const changed = loadThumbnail(channel, 'demo', 'assets/changed.png');
    const unchanged = loadThumbnail(channel, 'demo', 'assets/unchanged.png');

    invalidateThumbnails('demo', 'assets/changed.png');
    finishers[0]!(response('c3RhbGU='));
    finishers[1]!(response('c3RpbGwtdmFsaWQ='));

    await expect(changed).rejects.toThrow('invalidated');
    await expect(unchanged).resolves.toBe('data:image/png;base64,c3RpbGwtdmFsaWQ=');
  });

  it('rejects missing or unsupported image data and retries failed reads', async () => {
    const call = vi.fn(async (): Promise<Response> => ({
      id: 'thumbnail', op: 'assets.get',
      ok: false,
      error: { code: 'asset.missing', message: 'not found' },
    }));
    const channel = channelFor(call);

    await expect(loadThumbnail(channel, 'demo', 'assets/missing.png'))
      .rejects.toThrow('asset.missing: not found');
    await expect(loadThumbnail(channel, 'demo', 'assets/missing.png'))
      .rejects.toThrow('asset.missing: not found');
    await expect(loadThumbnail(channel, 'demo', 'models/sword.json'))
      .rejects.toThrow('Unsupported thumbnail format');
    expect(call).toHaveBeenCalledTimes(2);
  });

  it('rejects malformed image data instead of caching a healthy-looking thumbnail', async () => {
    const call = vi.fn(async () => response('not base64'));
    await expect(loadThumbnail(channelFor(call), 'demo', 'assets/broken.png'))
      .rejects.toThrow('not valid base64 image data');
  });

  it('keeps cached bytes scoped to the channel', async () => {
    const firstChannel = channelFor(vi.fn(async () => response('Zmlyc3Q=')));
    const secondChannel = channelFor(vi.fn(async () => response('c2Vjb25k')));

    await expect(loadThumbnail(firstChannel, 'demo', 'assets/sword.png'))
      .resolves.toBe('data:image/png;base64,Zmlyc3Q=');
    await expect(loadThumbnail(secondChannel, 'demo', 'assets/sword.png'))
      .resolves.toBe('data:image/png;base64,c2Vjb25k');
  });

  it('signals capacity so visible previews beyond the bounded queue can retry', async () => {
    const finishers: ((value: Response) => void)[] = [];
    const channel = channelFor(() => new Promise<Response>((resolve) => { finishers.push(resolve); }));
    const requests = Array.from({ length: 64 }, (_, index) => loadThumbnail(channel, 'demo', `assets/${index}.png`));
    await expect(loadThumbnail(channel, 'demo', 'assets/overflow.png')).rejects.toBeInstanceOf(ThumbnailCapacityError);
    const available = vi.fn();
    const unsubscribe = onThumbnailCapacity(available);
    finishers.shift()!(response());
    await requests[0];
    expect(available).toHaveBeenCalled();
    unsubscribe();
    const retry = loadThumbnail(channel, 'demo', 'assets/overflow.png');
    const remaining = [...requests.slice(1), retry];
    for (let completed = 0; completed < remaining.length;) {
      const batch = finishers.splice(0);
      expect(batch.length).toBeGreaterThan(0);
      for (const finish of batch) finish(response());
      await Promise.all(remaining.slice(completed, completed + batch.length));
      completed += batch.length;
    }
    await Promise.all([...requests, retry]);
  });

  it('evicts settled bytes instead of cancelling queued previews under cache pressure', async () => {
    const finishers: ((value: Response) => void)[] = [];
    const channel = channelFor(() => new Promise<Response>((resolve) => { finishers.push(resolve); }));
    const initial = Array.from({ length: 12 }, (_, index) => loadThumbnail(channel, 'demo', `assets/initial-${index}.png`));
    for (const finish of finishers.splice(0)) finish(response());
    await Promise.all(initial.slice(0, 4));
    const extra = Array.from({ length: 56 }, (_, index) => loadThumbnail(channel, 'demo', `assets/extra-${index}.png`));
    const remaining = [...initial.slice(4), ...extra];
    const all = Promise.all(remaining);
    for (let completed = 0; completed < remaining.length;) {
      const batch = finishers.splice(0);
      expect(batch.length).toBeGreaterThan(0);
      for (const finish of batch) finish(response());
      await Promise.all(remaining.slice(completed, completed + batch.length));
      completed += batch.length;
    }
    await expect(all).resolves.toHaveLength(64);
  });
});

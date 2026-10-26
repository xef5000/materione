import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  manifestVersionFor,
  resetVanillaIcons,
  vanillaIconUrl,
  vanillaItemId,
  VANILLA_ICONS_PACKAGE,
} from '../src/lib/vanilla-icons';

const BASE = `https://cdn.jsdelivr.net/npm/${VANILLA_ICONS_PACKAGE}/dist/textures`;

function manifestFetch(items: Record<string, unknown>, ok = true) {
  return vi.fn(async () => ({
    ok,
    status: ok ? 200 : 503,
    json: async () => ({ version: 'x', items }),
  }) as unknown as Response);
}

describe('vanilla icons', () => {
  beforeEach(() => resetVanillaIcons());

  it('maps Bukkit materials and namespaced ids to item ids', () => {
    expect(vanillaItemId('NETHERITE_SWORD')).toBe('minecraft:netherite_sword');
    expect(vanillaItemId(' minecraft:Book ')).toBe('minecraft:book');
    expect(vanillaItemId('custom:thing')).toBeUndefined();
    expect(vanillaItemId('')).toBeUndefined();
  });

  it('picks the newest manifest not newer than the server', () => {
    expect(manifestVersionFor('26.3')).toBe('26.3');
    expect(manifestVersionFor('26.2.1')).toBe('26.2');
    expect(manifestVersionFor('1.21.10')).toBe('1.21.9');
    expect(manifestVersionFor('1.21.4')).toBe('1.21.4');
    expect(manifestVersionFor('27.1')).toBe('26.3');
    expect(manifestVersionFor('')).toBe('26.3');
  });

  it('resolves icons from one cached manifest fetch', async () => {
    const fetcher = manifestFetch({ 'minecraft:book': { texture: '18a40392a5e04e49.png' } });
    await expect(vanillaIconUrl('BOOK', '1.21.5', fetcher)).resolves.toBe(`${BASE}/assets/18a40392a5e04e49.png`);
    await expect(vanillaIconUrl('STONE', '1.21.5', fetcher)).resolves.toBeUndefined();
    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(fetcher).toHaveBeenCalledWith(`${BASE}/manifest/1.21.5.id.json`);
  });

  it('ignores manifest entries that are not plain hash file names', async () => {
    const fetcher = manifestFetch({ 'minecraft:book': { texture: 'https://elsewhere.example/x.png' } });
    await expect(vanillaIconUrl('BOOK', '26.3', fetcher)).resolves.toBeUndefined();
  });

  it('retries the manifest after a failed load', async () => {
    const failing = manifestFetch({}, false);
    await expect(vanillaIconUrl('BOOK', '26.3', failing)).rejects.toThrow(/HTTP 503/);
    const working = manifestFetch({ 'minecraft:book': { texture: 'ab.png' } });
    await expect(vanillaIconUrl('BOOK', '26.3', working)).resolves.toBe(`${BASE}/assets/ab.png`);
  });
});

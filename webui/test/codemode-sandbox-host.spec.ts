import { describe, expect, it } from 'vitest';
import { createOpHost } from '../src/lib/codemode/sandbox/ophost';

describe('createOpHost seal pagination', () => {
  it('never requests limit > 200 on authoring.summaries and resolves single- and multi-page resources cleanly', async () => {
    const requestedLimits: number[] = [];
    const totalItems = 245;
    const allItems = Array.from({ length: totalItems }, (_, i) => ({
      type: 'items',
      id: `item_${i}`,
      path: `items/item_${i}.yml`,
      name: `Item ${i}`,
    }));

    const host = createOpHost(async (op, payload) => {
      if (op === 'browse.packs') {
        return { ok: true, data: { packs: ['bench'], builtin: [] } };
      }
      if (op === 'authoring.summaries') {
        const offset = typeof payload.offset === 'number' ? payload.offset : 0;
        const limit = typeof payload.limit === 'number' ? payload.limit : 100;
        requestedLimits.push(limit);
        if (offset < 0 || limit < 1 || limit > 200) {
          return {
            ok: false,
            error: { code: 'MTR-0007', message: 'summary page requires offset >= 0 and limit between 1 and 200' },
          };
        }
        const type = String(payload.type ?? '');
        const source = type === 'items' ? allItems : type === 'guis' ? [{ type: 'guis', id: 'bloodfang_menu', path: 'guis/bloodfang_menu.yml', name: 'Bloodfang Menu' }] : [];
        const end = Math.min(offset + limit, source.length);
        return {
          ok: true,
          data: {
            resources: source.slice(offset, end),
            total: source.length,
            ...(end < source.length ? { nextOffset: end } : {}),
          },
        };
      }
      return { ok: false, error: { code: 'MTR-0008', message: `unexpected op ${op}` } };
    });

    const reply = await host.resolve({
      packs: ['bench'],
      refs: [
        { pack: 'bench', kind: 'item', directory: 'items', id: 'item_244', needsDocument: false },
        { pack: 'bench', kind: 'gui', directory: 'guis', id: 'bloodfang_menu', needsDocument: false },
        { pack: 'bench', kind: 'item', directory: 'items', id: 'missing_item', needsDocument: false },
      ],
      instantiate: [],
      assets: [],
    });

    expect(requestedLimits.length).toBeGreaterThanOrEqual(3);
    expect(requestedLimits.every((limit) => limit >= 1 && limit <= 200)).toBe(true);
    expect(reply.packs).toEqual([{ state: 'ok' }]);
    expect(reply.refs[0]).toEqual({ found: true });
    expect(reply.refs[1]).toEqual({ found: true });
    expect(reply.refs[2]?.found).toBe(false);
  });
});

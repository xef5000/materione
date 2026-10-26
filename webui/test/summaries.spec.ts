import { describe, expect, it } from 'vitest';
import { ops, unwrap, type RpcChannel } from '../src/lib/api';
import type { Response } from '../src/lib/protocol/wire';

function page(resources: unknown[], total: number, nextOffset?: number): Response {
  return { id: 'page', op: 'authoring.summaries', ok: true, data: {
    resources, total, ...(nextOffset === undefined ? {} : { nextOffset }),
  } };
}

describe('bulk summary pages', () => {
  it('aggregates pages without per-resource document reads or a leftover cursor', async () => {
    const calls: unknown[] = [];
    const channel: RpcChannel = { call: async (op, data) => {
      calls.push({ op, data });
      return data?.offset === 1 ? page([{ id: 'second' }], 2) : page([{ id: 'first' }], 2, 1);
    } };
    expect(unwrap(await ops.summaries(channel, 'pack'))).toEqual({
      resources: [{ id: 'first' }, { id: 'second' }], total: 2,
    });
    expect(calls).toEqual([
      { op: 'authoring.summaries', data: { pack: 'pack' } },
      { op: 'authoring.summaries', data: { pack: 'pack', offset: 1 } },
    ]);
  });

  it('surfaces a later page failure rather than returning partial success', async () => {
    const channel: RpcChannel = { call: async (_op, data) => data?.offset === 1
      ? { id: 'page', op: 'authoring.summaries', ok: false, error: { code: 'summary.failed', message: 'Read failed' } }
      : page([{ id: 'first' }], 2, 1),
    };
    async function unwrapResult() { return unwrap(await ops.summaries(channel, 'pack')); }
    await expect(unwrapResult()).rejects.toThrow('Read failed');
  });

  it.each([
    page([], 5, 1),
    page([{ id: 'first' }], 5, 4),
    page([{ id: 'first' }], 5),
    page([], Number.POSITIVE_INFINITY),
  ])('rejects skipped, empty, incomplete or invalid page metadata', async (result) => {
    const channel: RpcChannel = { call: async () => result };
    await expect(ops.summaries(channel, 'pack')).rejects.toThrow();
  });

  it('rejects a changing total rather than silently dropping newly added entries', async () => {
    const channel: RpcChannel = { call: async (_op, data) => data?.offset === 1
      ? page([{ id: 'second' }], 3) : page([{ id: 'first' }], 2, 1),
    };
    await expect(ops.summaries(channel, 'pack')).rejects.toThrow('catalog changed');
  });
});

/**
 * The host's answers to the sandbox (codemode.md section 4.4, 4.6): `SandboxHost` is what a run is given, and `createOpHost`
 * is the one adapter that builds it from a function that makes read-only editor ops (the sealed channel's `call`, in the editor).
 *
 * The allowlist is enforced HERE, on the host, from the core's operation table: a read is an op the table lists for the
 * `m.read.*` family and the lookups behind it (`READS` without `via: 'resolve'`), and the seal's batch is answered by the table's
 * `via: 'resolve'` rows. An op outside the table is never made, whatever the guest asks, so the frame cannot make the editor
 * send anything the SDK does not map. No write op is reachable from here by construction: the table's reads hold none, and a
 * test pins that (`codemode-sandbox-host.spec.ts`).
 */
import { READS } from '../core/ops';
import { nearest } from '../core/values';
import type { JsonValue, ReadReply, ResolveReply, ResolveRequest } from '../core/types';

/** What a run needs of the editor: answers, never effects. Both are injected; the chat tool and the proposal flow wire them. */
export interface SandboxHost {
  /** One read-only op for `m.read.*`. Called only with an allowlisted op. */
  read(op: string, payload: Record<string, unknown>): Promise<ReadReply>;
  /** The seal's one batched resolve (codemode.md section 3.9). */
  resolve(request: ResolveRequest): Promise<ResolveReply>;
}

/** Ops the guest may ask `read` for: the table's reads the core makes itself. */
export const READ_ALLOWLIST: ReadonlySet<string> = new Set(READS.filter((entry) => entry.via === undefined).map((entry) => entry.op));

/** Ops the resolver of an op host may make: the table's `via: 'resolve'` rows. */
export const RESOLVE_OPS: ReadonlySet<string> = new Set(READS.filter((entry) => entry.via === 'resolve').map((entry) => entry.op));

/** A function that makes one read-only editor op: the sealed channel's `call`, reduced to its answer. */
export type OpCall = (op: string, payload: Record<string, unknown>) => Promise<ReadReply>;

type Dict = Record<string, unknown>;
const isDict = (value: unknown): value is Dict => typeof value === 'object' && value !== null && !Array.isArray(value);

/**
 * Builds a host from `call`. `read` refuses an op outside the allowlist (the sandbox host checks too: two layers, the second is
 * this one's own contract). `resolve` answers the seal's batch with the table's `via: 'resolve'` ops, a request at a time per
 * lookup so each is a plain read-only op.
 */
export function createOpHost(call: OpCall): SandboxHost {
  const guarded: OpCall = (op, payload) => {
    if (!READ_ALLOWLIST.has(op) && !RESOLVE_OPS.has(op)) return Promise.resolve({ ok: false, error: { code: 'SBX-0010', message: `op ${op} is not an SDK read` } });
    return call(op, payload);
  };
  return {
    read(op, payload) {
      if (!READ_ALLOWLIST.has(op)) return Promise.resolve({ ok: false, error: { code: 'SBX-0010', message: `op ${op} is not an SDK read` } });
      return call(op, payload);
    },
    async resolve(request) {
      const packsReply = await guarded('browse.packs', {});
      const known = packsReply.ok && isDict(packsReply.data) && Array.isArray(packsReply.data.packs) ? (packsReply.data.packs as unknown[]).filter((p): p is string => typeof p === 'string') : [];
      const builtin = packsReply.ok && isDict(packsReply.data) && Array.isArray(packsReply.data.builtin) ? (packsReply.data.builtin as unknown[]).filter((p): p is string => typeof p === 'string') : [];
      const reply: ResolveReply = { packs: [], refs: [], instantiate: [], assets: [] };
      for (const pack of request.packs) {
        if (builtin.includes(pack)) reply.packs.push({ state: 'builtin' });
        else if (known.includes(pack)) reply.packs.push({ state: 'ok' });
        else reply.packs.push({ state: 'missing', nearest: nearest(pack, known) });
      }
      // one summaries read per (pack, directory), however many ids ask
      const rowsCache = new Map<string, Promise<Dict[]>>();
      const rowsOf = (pack: string, directory: string): Promise<Dict[]> => {
        const key = `${pack}\u0000${directory}`;
        let rows = rowsCache.get(key);
        if (rows === undefined) {
          rows = (async () => {
            const found: Dict[] = [];
            for (let offset = 0; offset < 100_000; ) {
              const page = await guarded('authoring.summaries', { pack, type: directory, offset, limit: 500 });
              if (!page.ok || !isDict(page.data) || !Array.isArray(page.data.resources)) break;
              const batch = (page.data.resources as unknown[]).filter(isDict);
              found.push(...batch);
              const next = page.data.nextOffset;
              if (typeof next !== 'number' || batch.length === 0) break;
              offset = next;
            }
            return found;
          })();
          rowsCache.set(key, rows);
        }
        return rows;
      };
      for (const ref of request.refs) {
        const rows = (await rowsOf(ref.pack, ref.directory)).filter((row) => typeof row.type !== 'string' || row.type.toLowerCase() === ref.directory.toLowerCase());
        const row = rows.find((candidate) => candidate.id === ref.id);
        if (row === undefined) {
          reply.refs.push({ found: false, nearest: nearest(`${ref.pack}:${ref.id}`, rows.map((candidate) => `${ref.pack}:${String(candidate.id)}`)) });
          continue;
        }
        const path = typeof row.path === 'string' ? row.path : undefined;
        if (!ref.needsDocument || path === undefined) {
          reply.refs.push(path === undefined ? { found: true } : { found: true, ...(ref.needsDocument ? { path } : {}) });
          continue;
        }
        const document = await guarded('browse.get', { pack: ref.pack, path });
        if (!document.ok || !isDict(document.data)) {
          reply.refs.push({ found: true, path });
          continue;
        }
        const parsed = isDict(document.data.parsed) ? document.data.parsed : {};
        const tree = isDict(parsed[ref.id]) ? (parsed[ref.id] as Dict) : undefined;
        const held = ref.rulesKey === undefined || tree === undefined ? undefined : tree[ref.rulesKey];
        reply.refs.push({ found: true, path, rev: typeof document.data.rev === 'number' ? document.data.rev : 0, rules: Array.isArray(held) ? held.length : 0 });
      }
      for (const entry of request.instantiate) {
        const expanded = await guarded('logic.instantiate', { recipe: entry.recipe, params: entry.params as JsonValue });
        if (expanded.ok && isDict(expanded.data) && Array.isArray(expanded.data.rules)) {
          reply.instantiate.push({ rules: expanded.data.rules as JsonValue[], warnings: Array.isArray(expanded.data.warnings) ? expanded.data.warnings : [] });
        } else {
          reply.instantiate.push({ error: expanded.ok ? { message: 'the server returned no rules' } : expanded.error });
        }
      }
      const assetsByPack = new Map<string, Promise<string[]>>();
      for (const asset of request.assets) {
        let listed = assetsByPack.get(asset.pack);
        if (listed === undefined) {
          listed = guarded('assets.list', { pack: asset.pack }).then((answer) =>
            answer.ok && isDict(answer.data) && Array.isArray(answer.data.assets)
              ? (answer.data.assets as unknown[]).filter(isDict).map((entry) => String(entry.path))
              : [],
          );
          assetsByPack.set(asset.pack, listed);
        }
        const paths = await listed;
        if (paths.includes(asset.path) || paths.includes(`assets/${asset.path}`)) reply.assets.push({ found: true });
        else reply.assets.push({ found: false, nearest: nearest(asset.path, paths) });
      }
      return reply;
    },
  };
}

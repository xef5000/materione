// The recorded world a fake editor answers from (codemode.md section 3.9, 11): the SDK core's tests give it to the core's port, and
// the sandbox's tests give it, through the op host, to a real run. Pure and DOM-free, so the browser tier imports it too.
import type { ReadReply, ResolveReply, ResolveRequest } from '../../src/lib/codemode/core';
import { nearest } from '../../src/lib/codemode/core/values';

export interface WorldResource {
  pack?: string;
  type: string;
  path: string;
  id: string;
  name?: string;
  health?: string;
}

export interface World {
  packs: string[];
  builtin?: string[];
  /** Catalog rows by pack. */
  resources?: Record<string, WorldResource[]>;
  /** Documents by `pack/path`: their revision and parsed tree. */
  documents?: Record<string, { rev: number; parsed: unknown; computed?: unknown }>;
  /** Asset paths by pack. */
  assets?: Record<string, string[]>;
  /** Recipe expansions by recipe id. */
  recipes?: Record<string, { rules: unknown[]; warnings?: unknown[] } | { error: string }>;
  references?: Record<string, unknown[]>;
  history?: Record<string, unknown[]>;
  diagnostics?: unknown[];
}

export interface Call {
  op: string;
  payload: unknown;
}

const ok = (data: unknown): ReadReply => ({ ok: true, data });
const refuse = (message: string, code = 'MTR-0008'): ReadReply => ({ ok: false, error: { code, message } });

/**
 * Answers `m.read.*` ops and the seal's one batched resolve from a world, and records every call (a resolve is
 * recorded as the op `resolve`). The reply to a read is synchronous here; the run wraps it in a promise, as a host would.
 */
export function fakePort(world: World, calls: Call[] = []): {
  read(op: string, payload: Record<string, unknown>): ReadReply;
  resolve(request: ResolveRequest): ResolveReply;
  calls: Call[];
} {
  return {
    calls,
    resolve(request) {
      calls.push({ op: 'resolve', payload: request });
      return answerResolve(world, request);
    },
    read(op, payload) {
      calls.push({ op, payload });
      switch (op) {
        case 'browse.packs':
          return ok({ packs: world.packs, ...(world.builtin && world.builtin.length > 0 ? { builtin: world.builtin } : {}) });
        case 'authoring.summaries': {
          const pack = String(payload.pack);
          const rows = (world.resources?.[pack] ?? [])
            .filter((row) => payload.type === undefined || row.type.toLowerCase() === String(payload.type).toLowerCase())
            .filter((row) => payload.query === undefined || row.id.toLowerCase().includes(String(payload.query).toLowerCase()));
          const offset = typeof payload.offset === 'number' ? payload.offset : 0;
          const limit = typeof payload.limit === 'number' ? payload.limit : 100;
          const page = rows.slice(offset, offset + limit).map((row) => ({ pack, name: row.id, health: 'ok', ...row }));
          const next = offset + limit < rows.length ? { nextOffset: offset + limit } : {};
          return ok({ resources: page, total: rows.length, ...next });
        }
        case 'browse.get': {
          const found = world.documents?.[`${String(payload.pack)}/${String(payload.path)}`];
          if (found === undefined) return refuse(`no document ${String(payload.path)}`);
          return ok({ raw: '', sha256: 'x', ...found });
        }
        case 'authoring.references':
          return ok({ hits: world.references?.[`${String(payload.pack)}:${String(payload.target)}`] ?? [] });
        case 'browse.history':
          return ok({ revisions: world.history?.[`${String(payload.pack)}/${String(payload.path)}`] ?? [] });
        case 'admin.diagnostics.recent':
          return ok({ diagnostics: world.diagnostics ?? [] });
        case 'assets.list':
          return ok({ assets: (world.assets?.[String(payload.pack)] ?? []).map((path) => ({ path, bytes: 1, kind: 'texture' })) });
        case 'logic.instantiate': {
          const expansion = world.recipes?.[String(payload.recipe)];
          if (expansion === undefined) return refuse(`unknown recipe '${String(payload.recipe)}': no recipe with that id is registered`);
          if ('error' in expansion) return refuse(expansion.error);
          return ok({ rules: expansion.rules, warnings: expansion.warnings ?? [] });
        }
        default:
          return refuse(`the fake port does not answer ${op}`, 'MTR-9999');
      }
    },
  };
}

/** The seal's batch answered from a world: what a host does with the read-only editor ops. */
function answerResolve(world: World, request: ResolveRequest): ResolveReply {
  const builtin = world.builtin ?? [];
  return {
    packs: request.packs.map((pack) => {
      if (builtin.includes(pack)) return { state: 'builtin' as const };
      if (world.packs.includes(pack)) return { state: 'ok' as const };
      return { state: 'missing' as const, nearest: nearest(pack, world.packs) };
    }),
    refs: request.refs.map((ref) => {
      const rows = (world.resources?.[ref.pack] ?? []).filter((row) => row.type.toLowerCase() === ref.directory.toLowerCase());
      const row = rows.find((candidate) => candidate.id === ref.id);
      if (row === undefined) return { found: false as const, nearest: nearest(`${ref.pack}:${ref.id}`, rows.map((candidate) => `${ref.pack}:${candidate.id}`)) };
      if (!ref.needsDocument) return { found: true as const };
      const document = world.documents?.[`${ref.pack}/${row.path}`];
      if (document === undefined) return { found: true as const, path: row.path };
      const tree = (document.parsed as Record<string, Record<string, unknown>> | undefined)?.[ref.id];
      const held = ref.rulesKey === undefined ? undefined : tree?.[ref.rulesKey];
      return { found: true as const, path: row.path, rev: document.rev, rules: Array.isArray(held) ? held.length : 0 };
    }),
    instantiate: request.instantiate.map((entry) => {
      const expansion = world.recipes?.[entry.recipe];
      if (expansion === undefined) return { error: { code: 'MTR-0008', message: `unknown recipe '${entry.recipe}': no recipe with that id is registered` } };
      if ('error' in expansion) return { error: { code: 'MTR-0008', message: expansion.error } };
      return { rules: expansion.rules as never, warnings: expansion.warnings ?? [] };
    }),
    assets: request.assets.map((asset) => {
      const listed = world.assets?.[asset.pack] ?? [];
      if (listed.includes(asset.path) || listed.includes(`assets/${asset.path}`)) return { found: true };
      return { found: false, nearest: nearest(asset.path, listed) };
    }),
  };
}


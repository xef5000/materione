import { readFileSync } from 'node:fs';
import { beforeEach, describe, expect, it } from 'vitest';
import type { PushFrame } from '../src/lib/events';
import { DemoServer } from '../src/lib/demo/server';
import { loadSnapshot, type DemoSnapshot } from '../src/lib/demo/snapshot';
import { loadWorkspace, resetWorkspace, session, workspace } from '../src/lib/state.svelte';

let snapshot: DemoSnapshot;
let server: DemoServer;
let pushes: PushFrame[];

beforeEach(async () => {
  resetWorkspace();
  snapshot = await loadSnapshot();
  server = new DemoServer(snapshot);
  pushes = [];
  server.setPushHandler((frame) => pushes.push(frame));
});

async function ok(op: string, payload: Record<string, unknown> = {}): Promise<Record<string, unknown>> {
  const response = await server.call(op, payload);
  if (!response.ok) throw new Error(`${op}: ${response.error.code} ${response.error.message}`);
  return response.data as Record<string, unknown>;
}

describe('the demo server replays the recorded real server', () => {
  it('answers every op the editor can send (api.ts), so no feature is demo-only broken', async () => {
    const api = readFileSync('src/lib/api.ts', 'utf8');
    const ops = [...api.matchAll(/c\.call\('([a-z]+\.[A-Za-z.]+)'/g)].map((match) => match[1]!);
    expect(ops.length).toBeGreaterThan(30);
    for (const op of new Set(ops)) {
      const response = await server.call(op, {});
      if (!response.ok) expect(response.error.code, op).not.toBe('MTR-0033');
    }
  });

  it('serves every registered content type and the real behaviour vocabulary', async () => {
    const types = (await ok('browse.types')).types as { name: string; parameters: unknown[] }[];
    expect(types.map((type) => type.name).sort())
      .toEqual(['block', 'effect', 'furniture', 'glyph', 'gui', 'item', 'painting', 'recipe', 'sound']);
    expect(types.find((type) => type.name === 'block')!.parameters.length).toBeGreaterThan(5);
    const describe = await ok('authoring.describe');
    expect((describe.behavior as { events: unknown[] }).events.length).toBeGreaterThan(5);
    expect((await ok('browse.packs')).packs).toEqual(['sample', 'world']);
  });

  it('loads the real workspace through the same state code a session uses', async () => {
    session.channel = server;
    await loadWorkspace();
    expect(workspace.activePack).toBe('sample');
    expect(new Set(workspace.summaries.map((summary) => summary.type)))
      .toEqual(new Set(['items', 'blocks', 'furnitures', 'glyphs', 'sounds', 'paintings', 'effects', 'recipes', 'templates', 'guis']));
    const lamp = workspace.summaries.find((summary) => summary.id === 'amethyst_lamp')!;
    expect(lamp.render?.model).toBeTruthy();
    const texture = await ok('assets.get', { pack: 'sample', path: lamp.visual!.path });
    expect(String(texture.data)).toMatch(/^iVBORw0KGgo/); // a real PNG
  });
});

describe('writes stay in the tab and behave like the server', () => {
  it('edits fields in place, keeping comments, and bumps the revision', async () => {
    const before = await ok('browse.get', { pack: 'sample', path: 'blocks/amethyst_lamp.yml' });
    const saved = await ok('authoring.setFields', {
      pack: 'sample', path: 'blocks/amethyst_lamp.yml', rev: before.rev,
      edits: [{ field: ['amethyst_lamp', 'display_name'], value: 'Glowing Lamp' }],
    });
    expect(saved.rev).toBe(Number(before.rev) + 1);
    expect(saved.raw).toContain('# the placement link');
    expect(saved.raw).toContain('display_name: Glowing Lamp');
    const summaries = (await ok('authoring.summaries', { pack: 'sample', type: 'blocks' })).resources as { id: string; name: string }[];
    expect(summaries.find((summary) => summary.id === 'amethyst_lamp')!.name).toBe('Glowing Lamp');
    await Promise.resolve();
    expect(pushes.map((push) => push.op)).toEqual(['push.reload']);
    expect((await ok('delivery.status')).candidateSha256).not.toBe('');
  });

  it('commits a setFields binary with its edit, and refuses a path outside assets/ before writing', async () => {
    const before = await ok('browse.get', { pack: 'sample', path: 'blocks/amethyst_lamp.yml' });
    const png = btoa('\u0089PNG\r\n\u001a\n');
    const saved = await ok('authoring.setFields', {
      pack: 'sample', path: 'blocks/amethyst_lamp.yml', rev: before.rev,
      edits: [{ field: ['amethyst_lamp', 'display_name'], value: 'Painted Lamp' }],
      assets: [{ path: 'assets/textures/gui/painted_lamp.png', data: png }],
    });
    expect(saved.rev).toBe(Number(before.rev) + 1);
    expect((await ok('assets.get', { pack: 'sample', path: 'assets/textures/gui/painted_lamp.png' })).data).toBe(png);

    const refused = await server.call('authoring.setFields', {
      pack: 'sample', path: 'blocks/amethyst_lamp.yml', rev: saved.rev,
      edits: [{ field: ['amethyst_lamp', 'display_name'], value: 'Refused' }],
      assets: [{ path: '.materione/thumbnails/x.png', data: png }],
    });
    expect(refused.ok).toBe(false);
    if (!refused.ok) expect(refused.error.code).toBe('MTR-0008');
    expect((await ok('browse.get', { pack: 'sample', path: 'blocks/amethyst_lamp.yml' })).rev).toBe(saved.rev);
  });

  it('takes a revision for a paint-only save, and refuses a save with neither edits nor binaries', async () => {
    const before = await ok('browse.get', { pack: 'sample', path: 'blocks/amethyst_lamp.yml' });
    const saved = await ok('authoring.setFields', {
      pack: 'sample', path: 'blocks/amethyst_lamp.yml', rev: before.rev, edits: [],
      assets: [{ path: 'assets/textures/gui/paint_only.png', data: btoa('\u0089PNG') }],
    });
    expect(saved.rev).toBe(Number(before.rev) + 1);
    expect(saved.raw).toBe(before.raw);

    const refused = await server.call('authoring.setFields', {
      pack: 'sample', path: 'blocks/amethyst_lamp.yml', rev: saved.rev, edits: [],
    });
    expect(refused.ok).toBe(false);
    if (!refused.ok) expect(refused.error.code).toBe('MTR-0008');
    expect((await ok('browse.get', { pack: 'sample', path: 'blocks/amethyst_lamp.yml' })).rev).toBe(saved.rev);
  });

  it('rejects a stale revision with the server conflict code', async () => {
    const response = await server.call('edit.put', {
      pack: 'sample', path: 'items/ruby.yml', rev: 999, content: 'ruby:\n  material: STICK\n',
    });
    expect(response.ok ? '' : response.error.code).toBe('MTR-0034');
  });

  it('creates resources from the server scaffolds', async () => {
    const created = await ok('authoring.createResource', {
      pack: 'sample', id: 'night_lamp', name: 'Night Lamp', type: 'blocks',
    });
    expect(created.path).toBe('blocks/night_lamp.yml');
    expect(created.raw).toContain('# night_lamp — created by the Materione editor');
    expect(created.raw).toContain('display_name: "Night Lamp"');
    const resources = (await ok('browse.resources', { pack: 'sample', type: 'blocks' })).resources as { id: string }[];
    expect(resources.map((resource) => resource.id)).toContain('night_lamp');
  });

  it('finds the users of an asset and says what it covered', async () => {
    for (const id of ['amulet_red', 'amulet_blue']) {
      const created = await ok('authoring.createResource', { pack: 'sample', id, name: id });
      await ok('authoring.setFields', {
        pack: 'sample', path: created.path, rev: created.rev,
        edits: [{ field: [id, 'texture'], value: 'amulets/shared' }],
      });
    }
    const found = await ok('authoring.references', {
      pack: 'sample', kind: 'asset', target: 'assets/textures/amulets/shared.png',
    });
    expect((found.hits as { id: string }[]).map((hit) => hit.id).sort()).toEqual(['amulet_blue', 'amulet_red']);
    expect(found.coverage).toMatchObject({ complete: true, truncated: false });
  });

  it('applies a bulk change to every document together, or none of them', async () => {
    const red = await ok('authoring.createResource', { pack: 'sample', id: 'amulet_red', name: 'Red' });
    const blue = await ok('authoring.createResource', { pack: 'sample', id: 'amulet_blue', name: 'Blue' });
    const edit = (id: string) => [{ field: [id, 'material'], value: 'EMERALD' }];
    const operations = (blueRev: number) => [
      { kind: 'set', path: red.path, rev: red.rev, edits: edit('amulet_red') },
      { kind: 'set', path: blue.path, rev: blueRev, edits: edit('amulet_blue') },
    ];

    const stale = await server.call('authoring.bulk', { pack: 'sample', operations: operations(99) });
    expect(stale.ok ? '' : stale.error.code).toBe('MTR-0034');
    expect(((await ok('browse.get', { pack: 'sample', path: red.path })).parsed as Record<string, { material: string }>)
      .amulet_red!.material).not.toBe('EMERALD');

    const dry = await ok('authoring.bulk', { pack: 'sample', operations: operations(Number(blue.rev)), dryRun: true });
    expect(dry.applied).toBe(false);
    const applied = await ok('authoring.bulk', { pack: 'sample', operations: operations(Number(blue.rev)) });
    expect(applied.applied).toBe(true);
    expect((applied.documents as { changes: unknown[] }[]).every((doc) => doc.changes.length === 1)).toBe(true);
    expect(((await ok('browse.get', { pack: 'sample', path: blue.path })).parsed as Record<string, { material: string }>)
      .amulet_blue!.material).toBe('EMERALD');
  });

  it('duplicates variants and creates a recipe in one all-or-none change', async () => {
    const created = await ok('authoring.createResource', { pack: 'sample', id: 'amulet_red', name: 'Red' });
    const result = await ok('authoring.bulk', { pack: 'sample', operations: [
      { kind: 'duplicate', fromPath: created.path, id: 'amulet_teal', edits: [{ field: ['amulet_teal', 'display_name'], value: 'Teal' }] },
      { kind: 'create', type: 'recipes', id: 'amulet_teal', edits: [{ field: ['amulet_teal', 'result'], value: 'amulet_teal' }] },
    ] });
    expect((result.documents as { path: string }[]).map((doc) => doc.path))
      .toEqual(['items/amulet_teal.yml', 'recipes/amulet_teal.yml']);

    const collision = await server.call('authoring.bulk', { pack: 'sample', operations: [
      { kind: 'duplicate', fromPath: created.path, id: 'amulet_gold' },
      { kind: 'duplicate', fromPath: created.path, id: 'amulet_teal' },
    ] });
    expect(collision.ok).toBe(false);
    const ids = ((await ok('browse.resources', { pack: 'sample', type: 'items' })).resources as { id: string }[]).map((r) => r.id);
    expect(ids).not.toContain('amulet_gold');
  });

  it('never touches the recorded snapshot of another demo', async () => {
    await ok('authoring.setFields', {
      pack: 'sample', path: 'items/ruby.yml', rev: 0,
      edits: [{ field: ['ruby', 'material'], value: 'EMERALD' }],
    });
    const fresh = new DemoServer(await loadSnapshot());
    const document = await fresh.call('browse.get', { pack: 'sample', path: 'items/ruby.yml' });
    expect(document.ok && (document.data as { rev: number }).rev).toBe(0);
  });
});

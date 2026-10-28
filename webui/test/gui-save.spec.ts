/**
 * Saving a GUI (gui.md sections 6 and 7): changed fields only, dirty pixel
 * assets in the same transaction (the shape issue 183 adds to
 * `authoring.setFields`), and a lossless round trip through the demo server,
 * which replays the server's revision checks and comment-keeping YAML edits.
 */
import { beforeEach, describe, expect, it } from 'vitest';
import { parse } from 'yaml';
import { DemoServer } from '../src/lib/demo/server';
import { loadSnapshot } from '../src/lib/demo/snapshot';
import { patchLayers, addLayer, setDocumentFields } from '../src/lib/gui/commands';
import { GuiHistory } from '../src/lib/gui/history';
import { parseGui } from '../src/lib/gui/model';
import { isEmptyPlan, paintReference, planSave, saveRequest, sendSave, textureAssetPath } from '../src/lib/gui/save';

let server: DemoServer;
const PATH = 'guis/shop/main.yml';

beforeEach(async () => {
  server = new DemoServer(await loadSnapshot());
});

async function read() {
  const response = await server.call('browse.get', { pack: 'sample', path: PATH });
  if (!response.ok) throw new Error(response.error.message);
  return response.data as { rev: number; raw: string; parsed: Record<string, unknown> };
}

describe('the save plan', () => {
  it('is empty for an untouched document: a lossless round trip writes nothing', async () => {
    const { parsed } = await read();
    const gui = parseGui(parsed, 'main')!;
    expect(isEmptyPlan(planSave(gui.location.prefix, gui.doc, gui.doc, new Map()))).toBe(true);
  });

  it('sends only the fields that changed, under the resource root', async () => {
    const gui = parseGui((await read()).parsed, 'main')!;
    const history = new GuiHistory(gui.doc);
    history.execute(patchLayers(history.doc, { buy: { x: 100 } }, 'Move'));
    const plan = planSave(gui.location.prefix, history.baseline, history.doc, new Map());
    expect(plan.edits.map((edit) => ('field' in edit ? edit.field : []))).toEqual([['main', 'layers']]);
    expect(plan.assets).toEqual([]);

    history.execute(setDocumentFields(history.doc, { title: 'Bazaar' }, 'Title'));
    const both = planSave(gui.location.prefix, history.baseline, history.doc, new Map());
    expect(both.edits.map((edit) => edit.field)).toEqual([['main', 'title'], ['main', 'layers']]);
  });

  it('a flat file (no resource root) is written without a prefix', () => {
    const gui = parseGui({ rows: 1, layers: [] }, 'whatever')!;
    const next = { ...gui.doc, rows: 2 };
    expect(planSave(gui.location.prefix, gui.doc, next, new Map()).edits).toEqual([{ field: ['rows'], value: 2 }]);
  });

  it('names a paint layer\'s PNG after the document and the layer', () => {
    expect(paintReference('guis/shop/main.yml', 'art')).toBe('gui/shop/main/art');
    expect(paintReference('guis/hud.yml', 'bg')).toBe('gui/hud/bg');
    expect(textureAssetPath('gui/shop/main/art')).toBe('assets/textures/gui/shop/main/art.png');
    expect(textureAssetPath('shop:gui/x')).toBe('assets/textures/gui/x.png');
  });

  it('ships a dirty asset only while a layer still refers to it', async () => {
    const gui = parseGui((await read()).parsed, 'main')!;
    const dirty = new Map([['gui/shop/main/scratch', 'QUJD']]);
    expect(planSave([], gui.doc, gui.doc, dirty).assets).toEqual([]);
    const withLayer = addLayer(gui.doc, { id: 'scratch', type: 'paint', image: 'gui/shop/main/scratch' })!.apply(gui.doc);
    const plan = planSave(['main'], gui.doc, withLayer, dirty);
    expect(plan.assets).toEqual([{ path: 'assets/textures/gui/shop/main/scratch.png', bytes: 'QUJD' }]);
    expect(saveRequest('sample', PATH, 3, plan)).toEqual({
      pack: 'sample', path: PATH, rev: 3, edits: plan.edits, assets: plan.assets,
    });
    // a pixels-only change still sends one edit, because the op wants at least one
    const pixelsOnly = planSave(['main'], withLayer, withLayer, dirty);
    expect(pixelsOnly.edits).toHaveLength(1);
    expect(pixelsOnly.assets).toHaveLength(1);
  });
});

describe('against the server', () => {
  it('commits the fields and the new texture together, keeping comments and untouched layers byte-identical', async () => {
    const before = await read();
    const gui = parseGui(before.parsed, 'main')!;
    const history = new GuiHistory(gui.doc);
    history.execute(patchLayers(history.doc, { buy: { x: 100, y: 52 } }, 'Move'));
    history.execute(addLayer(history.doc, { id: 'scratch', type: 'paint', image: 'gui/shop/main/scratch' }));
    const plan = planSave(gui.location.prefix, history.baseline, history.doc, new Map([['gui/shop/main/scratch', 'iVBORw0KGgo=']]));
    const response = await sendSave(server, 'sample', PATH, before.rev, plan);
    expect(response.ok).toBe(true);

    const after = await read();
    expect(after.rev).toBe(before.rev + 1);
    const saved = parseGui(after.parsed, 'main')!;
    expect(saved.doc.layers.find((layer) => layer.id === 'buy')).toMatchObject({ x: 100, y: 52 });
    expect(saved.doc.layers.at(-1)).toMatchObject({ id: 'scratch', type: 'paint' });
    expect(after.raw.startsWith('# main — a shop screen')).toBe(true); // the comment survived
    const assets = await server.call('assets.get', { pack: 'sample', path: 'assets/textures/gui/shop/main/scratch.png' });
    expect(assets.ok).toBe(true);
  });

  it('a stale revision is refused and writes neither fields nor assets', async () => {
    const before = await read();
    const gui = parseGui(before.parsed, 'main')!;
    const history = new GuiHistory(gui.doc);
    history.execute(addLayer(history.doc, { id: 'scratch', type: 'paint', image: 'gui/shop/main/scratch' }));
    const plan = planSave(gui.location.prefix, history.baseline, history.doc, new Map([['gui/shop/main/scratch', 'iVBORw0KGgo=']]));
    const response = await sendSave(server, 'sample', PATH, before.rev + 5, plan);
    expect(response.ok ? '' : response.error.code).toBe('MTR-0034');
    expect((await read()).rev).toBe(before.rev);
    const missing = await server.call('assets.get', { pack: 'sample', path: 'assets/textures/gui/shop/main/scratch.png' });
    expect(missing.ok).toBe(false);
  });

  it('a new GUI from the server starter parses as the vanilla chest', async () => {
    const created = await server.call('authoring.createResource', {
      pack: 'sample', id: 'chest_two', name: 'Chest Two', type: 'guis',
    });
    expect(created.ok).toBe(true);
    const data = (created as { data: { parsed: unknown; raw: string } }).data;
    const gui = parseGui(data.parsed, 'chest_two')!;
    expect(gui.doc.layers.map((layer) => layer.type)).toEqual(['panel', 'slots']);
    expect(parse(data.raw)).toBeTruthy();
  });

  it('describes the gui-components registry through authoring.describe', async () => {
    const response = await server.call('authoring.describe');
    expect(response.ok).toBe(true);
    const data = (response as { data: { guiComponents: { id: string }[] } }).data;
    expect(data.guiComponents.map((component) => component.id)).toEqual(
      ['panel', 'slots', 'image', 'paint', 'button', 'text', 'item']);
  });
});

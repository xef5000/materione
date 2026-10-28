/**
 * The GUI document model (gui.md section 2): parsing the structure the API
 * returns, losslessness, and the registry self-description the studio is
 * derived from.
 */
import { describe, expect, it } from 'vitest';
import { parse } from 'yaml';
import { GUI_COMPONENTS, GUI_TYPE, sampleGuiRaw, scaffoldGui } from '../src/lib/demo/gui';
import {
  isHidden,
  isLocked,
  layerLabel,
  locateBody,
  parseGui,
  sameDocument,
  serializeGui,
  starterDocument,
  uniqueId,
} from '../src/lib/gui/model';
import { newLayer, readRegistry, transformability, defaultValue, componentOf } from '../src/lib/gui/registry';

const sample = parse(sampleGuiRaw()) as Record<string, unknown>;
const registry = readRegistry({ guiComponents: GUI_COMPONENTS })!;

describe('parseGui', () => {
  it('reads a resource-rooted file (the id -> fields mapping every other type uses)', () => {
    const parsed = parseGui(sample, 'main')!;
    expect(parsed.location.prefix).toEqual(['main']);
    expect(parsed.doc.title).toBe('Shop');
    expect(parsed.doc.rows).toBe(3);
    expect(parsed.doc.layers.map((layer) => layer.id)).toEqual(
      ['frame', 'wells', 'art', 'heading', 'tag', 'buy', 'coin', 'sword', 'apple']);
    expect(parsed.blocking).toEqual([]);
  });

  it('reads a flat file (the gui.md section 2 example) with an empty field prefix', () => {
    const flat = (sample.main as Record<string, unknown>);
    const parsed = parseGui(flat, 'main')!;
    expect(parsed.location.prefix).toEqual([]);
    expect(parsed.doc.layers).toHaveLength(9);
  });

  it('locates the body or refuses what is not a GUI', () => {
    expect(locateBody({ main: { rows: 2 } }, 'main')?.prefix).toEqual(['main']);
    expect(locateBody({ other: { layers: [] } }, 'main')?.prefix).toEqual(['other']);
    expect(locateBody({ a: 1, b: 2 }, 'main')).toBeNull();
    expect(parseGui('nope', 'main')).toBeNull();
  });

  it('clamps rows for display and says so, but never writes the clamp back', () => {
    const parsed = parseGui({ main: { rows: 9, layers: [] } }, 'main')!;
    expect(parsed.doc.rows).toBe(6);
    expect(parsed.issues.join()).toContain('clamped');
  });

  it('refuses to edit a file it cannot hold faithfully (blocking), instead of rewriting it', () => {
    const parsed = parseGui({ main: { layers: [{ id: 'a', type: 'panel' }, { id: 'a', type: 'panel' }, 'junk', { type: 'slots' }] } }, 'main')!;
    expect(parsed.blocking.length).toBe(3);
  });
});

describe('losslessness (master 5.6)', () => {
  it('serializes an untouched document to a value equal to what was loaded, key order included', () => {
    const body = (sample.main as Record<string, unknown>);
    const parsed = parseGui(sample, 'main')!;
    const out = serializeGui(parsed.doc);
    expect(JSON.stringify(out)).toBe(JSON.stringify(body));
  });

  it('keeps unknown layer fields, unknown layer types and unknown top-level keys', () => {
    const file = {
      main: {
        title: 'T',
        sound: 'ui.click',
        rows: 2,
        layers: [
          { id: 'a', type: 'addon:progress', x: 1, y: 2, value: { nested: [1, 2, 3] }, future: true },
          { id: 'p', type: 'panel', x: 0, y: 0, width: 4, height: 4, flair: 'sparkle' },
        ],
        trailing: [1],
      },
    };
    const parsed = parseGui(file, 'main')!;
    expect(parsed.doc.layers[0]).toEqual(file.main.layers[0]);
    expect(JSON.stringify(serializeGui(parsed.doc))).toBe(JSON.stringify(file.main));
  });

  it('does not invent a title the file never had', () => {
    const parsed = parseGui({ main: { rows: 1, layers: [] } }, 'main')!;
    expect(Object.keys(serializeGui(parsed.doc))).toEqual(['rows', 'layers']);
  });

  it('parsing copies: editing the parsed document never reaches the source', () => {
    const file = { main: { rows: 1, layers: [{ id: 'a', type: 'panel', x: 0 }] } };
    const parsed = parseGui(file, 'main')!;
    (parsed.doc.layers[0] as Record<string, unknown>).x = 99;
    expect(file.main.layers[0]!.x).toBe(0);
  });

  it('same documents compare equal and different ones do not', () => {
    const a = starterDocument(3);
    expect(sameDocument(a, { ...a })).toBe(true);
    expect(sameDocument(a, { ...a, rows: 4 })).toBe(false);
    expect(sameDocument(a, { ...a, layers: [...a.layers].reverse() })).toBe(false);
  });
});

describe('helpers', () => {
  it('makes unique ids in the [a-z0-9_]+ grammar', () => {
    expect(uniqueId('panel', [])).toBe('panel');
    expect(uniqueId('panel', ['panel'])).toBe('panel_2');
    expect(uniqueId('panel', ['panel', 'panel_2'])).toBe('panel_3');
    expect(uniqueId('Price Tag!', [])).toBe('price_tag');
    expect(uniqueId('', [])).toBe('layer');
  });

  it('labels layers by name, else id, and reads the flags', () => {
    expect(layerLabel({ id: 'a', type: 'panel', name: ' Frame ' })).toBe(' Frame ');
    expect(layerLabel({ id: 'a', type: 'panel', name: '  ' })).toBe('a');
    expect(isHidden({ id: 'a', type: 'panel', visible: false })).toBe(true);
    expect(isLocked({ id: 'a', type: 'panel', locked: true })).toBe(true);
    expect(isLocked({ id: 'a', type: 'panel' })).toBe(false);
  });

  it('a new GUI starts as a vanilla chest: panel + all-region slots, panel as tall as the canvas', () => {
    const starter = starterDocument(6);
    expect(starter.layers.map((layer) => layer.type)).toEqual(['panel', 'slots']);
    expect(starter.layers[0]).toMatchObject({ x: 0, y: 0, width: 176, height: 222 });
    expect(starter.layers[1]).toMatchObject({ region: 'all' });
    // the server-side scaffold the demo uses describes the very same GUI
    const scaffold = parseGui(parse(scaffoldGui('shop_two', 'Shop Two')), 'shop_two')!;
    expect(scaffold.doc.rows).toBe(6);
    expect(scaffold.doc.title).toBe('Shop Two');
    expect(scaffold.doc.layers.map((layer) => [layer.type, layer.region ?? layer.height])).toEqual([['panel', 222], ['slots', 'all']]);
  });
});

describe('the gui-components registry description', () => {
  it('is read from the describe answer wherever the server nests it, and refused when absent', () => {
    expect(readRegistry({ guiComponents: GUI_COMPONENTS })!.components).toHaveLength(7);
    expect(readRegistry({ gui: { components: GUI_COMPONENTS } })!.components).toHaveLength(7);
    expect(readRegistry({ 'gui-components': GUI_COMPONENTS })!.components).toHaveLength(7);
    expect(readRegistry({ types: [] })).toBeNull();
    expect(readRegistry(null)).toBeNull();
  });

  it('describes the seven v1 components with their render targets', () => {
    expect(registry.components.map((component) => [component.id, component.target])).toEqual([
      ['panel', 'RASTER'], ['slots', 'RASTER'], ['image', 'RASTER'], ['paint', 'RASTER'],
      ['button', 'RASTER'], ['text', 'TEXT'], ['item', 'SLOT'],
    ]);
    expect(GUI_TYPE.directory).toBe('guis');
  });

  it('derives what may be transformed from the fields, not from names', () => {
    const can = (type: string) => transformability(componentOf(registry, type));
    expect(can('panel')).toEqual({ movable: true, resizable: true, slotBound: false });
    expect(can('button').resizable).toBe(true);
    expect(can('image').resizable).toBe(true);
    expect(can('text')).toEqual({ movable: true, resizable: false, slotBound: false });
    expect(can('item')).toEqual({ movable: true, resizable: false, slotBound: true });
    expect(can('slots')).toEqual({ movable: false, resizable: false, slotBound: false });
    expect(can('paint').movable).toBe(false);
    // an addon component the studio has never heard of, described with x/y/width/height, is just as movable
    const addon = { id: 'addon:bar', displayName: 'Bar', description: '', target: 'RASTER' as const, parameters: GUI_COMPONENTS[0]!.parameters };
    expect(transformability(addon).resizable).toBe(true);
  });

  it('builds a new layer from required defaults only, with a unique id', () => {
    const button = newLayer(componentOf(registry, 'button')!, ['button']);
    expect(button).toEqual({ id: 'button_2', type: 'button', name: 'Button', x: 0, y: 0, width: 52, height: 20 });
    expect(Object.keys(newLayer(componentOf(registry, 'panel')!, []))).not.toContain('style');
    expect(defaultValue({ id: 'n', type: 'integer', required: true, default: '7' })).toBe(7);
    expect(defaultValue({ id: 'b', type: 'boolean', required: true, default: 'true' })).toBe(true);
    expect(defaultValue({ id: 's', type: 'string', required: true })).toBeUndefined();
  });
});

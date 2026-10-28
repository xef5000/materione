/**
 * Every studio edit is an invertible command (gui.md section 6, "History"):
 * apply then revert returns the exact document, drags and nudges coalesce, and
 * undoing back to the saved state makes the document clean again.
 */
import { describe, expect, it } from 'vitest';
import { parse } from 'yaml';
import { GUI_COMPONENTS, sampleGuiRaw } from '../src/lib/demo/gui';
import {
  addLayer,
  compose,
  insertLayers,
  moveLayers,
  patchLayers,
  removeLayers,
  setDocumentFields,
} from '../src/lib/gui/commands';
import { COALESCE_WINDOW_MS, GuiHistory } from '../src/lib/gui/history';
import { parseGui, starterDocument, type GuiDocument, type GuiLayer } from '../src/lib/gui/model';
import * as ops from '../src/lib/gui/ops';
import { readRegistry, componentOf } from '../src/lib/gui/registry';
import { sceneOf } from '../src/lib/gui/scene';

const registry = readRegistry({ guiComponents: GUI_COMPONENTS })!;
const sample = (): GuiDocument => parseGui(parse(sampleGuiRaw()), 'main')!.doc;
const sceneFor = (doc: GuiDocument) => sceneOf(doc, registry, () => null);
const ids = (doc: GuiDocument): string[] => doc.layers.map((layer) => layer.id);
const json = (doc: GuiDocument): string => JSON.stringify(doc);

describe('commands are invertible', () => {
  it('patching fields, including removing one, reverts exactly (key order included)', () => {
    const doc = sample();
    const command = patchLayers(doc, { buy: { x: 10, y: 11, opacity: 50, style: undefined } }, 'Edit')!;
    const after = command.apply(doc);
    const buy = after.layers.find((layer) => layer.id === 'buy')!;
    expect(buy).toMatchObject({ x: 10, y: 11, opacity: 50 });
    expect('style' in buy).toBe(false);
    expect(json(command.revert(after))).toBe(json(doc));
  });

  it('a patch that changes nothing is not a command', () => {
    const doc = sample();
    expect(patchLayers(doc, { buy: { x: 118 } }, 'Edit')).toBeNull();
    expect(patchLayers(doc, { buy: { opacity: undefined } }, 'Edit')).toBeNull();
    expect(patchLayers(doc, { ghost: { x: 1 } }, 'Edit')).toBeNull();
  });

  it('share unchanged layers between versions (a command is references, not a copy)', () => {
    const doc = sample();
    const after = patchLayers(doc, { buy: { x: 1 } }, 'Edit')!.apply(doc);
    expect(after.layers.filter((layer, index) => layer === doc.layers[index]).length).toBe(doc.layers.length - 1);
  });

  it('insert and remove are each other\'s inverse, restoring list positions', () => {
    const doc = sample();
    const removed = removeLayers(doc, ['wells', 'tag', 'apple'])!;
    const gone = removed.apply(doc);
    expect(ids(gone)).toEqual(['frame', 'art', 'heading', 'buy', 'coin', 'sword']);
    expect(json(removed.revert(gone))).toBe(json(doc));

    const extra: GuiLayer = { id: 'extra', type: 'panel', x: 0, y: 0, width: 4, height: 4 };
    const inserted = addLayer(doc, extra, 2)!;
    expect(ids(inserted.apply(doc))[2]).toBe('extra');
    expect(json(inserted.revert(inserted.apply(doc)))).toBe(json(doc));
    const several = insertLayers('x', [{ layer: { ...extra, id: 'e1' }, index: 1 }, { layer: { ...extra, id: 'e2' }, index: 4 }]);
    expect(json(several.revert(several.apply(doc)))).toBe(json(doc));
  });

  it('reordering moves a group, keeps its internal order, and reverts', () => {
    const doc = sample();
    const command = moveLayers(doc, ['frame', 'art'], 5)!;
    const after = command.apply(doc);
    expect(ids(after)).toEqual(['wells', 'heading', 'tag', 'buy', 'coin', 'frame', 'art', 'sword', 'apple']);
    expect(json(command.revert(after))).toBe(json(doc));
    expect(moveLayers(doc, ['frame'], 0)).toBeNull(); // already there
    expect(moveLayers(doc, ['ghost'], 0)).toBeNull();
  });

  it('document fields (title, rows) revert', () => {
    const doc = sample();
    const command = setDocumentFields(doc, { title: 'Bazaar', rows: 5 }, 'Edit')!;
    const after = command.apply(doc);
    expect([after.title, after.rows]).toEqual(['Bazaar', 5]);
    expect(json(command.revert(after))).toBe(json(doc));
    expect(setDocumentFields(doc, { title: 'Shop' }, 'Edit')).toBeNull();
  });

  it('composites run in order and revert in reverse', () => {
    const doc = starterDocument(6);
    const first = patchLayers(doc, { frame: { height: 100 } }, 'a')!;
    const second = setDocumentFields(doc, { rows: 2 }, 'b')!;
    const both = compose('both', [first, null, second])!;
    const after = both.apply(doc);
    expect(after.rows).toBe(2);
    expect(json(both.revert(after))).toBe(json(doc));
    expect(compose('none', [null])).toBeNull();
  });
});

describe('the history', () => {
  it('undoes and redoes, and clears redo on a new edit', () => {
    const history = new GuiHistory(sample());
    history.execute(patchLayers(history.doc, { buy: { x: 1 } }, 'Move buy'), 1);
    history.execute(patchLayers(history.doc, { tag: { x: 2 } }, 'Move tag'), 100000);
    expect(history.undoLabel).toBe('Move tag');
    history.undo();
    expect(history.redoLabel).toBe('Move tag');
    expect(history.canRedo).toBe(true);
    history.undo();
    expect(history.canUndo).toBe(false);
    history.redo();
    history.redo();
    expect(history.doc.layers.find((layer) => layer.id === 'tag')!.x).toBe(2);
    history.undo();
    history.execute(patchLayers(history.doc, { coin: { x: 3 } }, 'Move coin'), 200000);
    expect(history.canRedo).toBe(false);
  });

  it('is dirty only while it differs from the saved baseline, so undo can make it clean again', () => {
    const history = new GuiHistory(sample());
    expect(history.dirty).toBe(false);
    history.execute(patchLayers(history.doc, { buy: { x: 1 } }, 'a'), 1);
    expect(history.dirty).toBe(true);
    history.undo();
    expect(history.dirty).toBe(false);
    history.redo();
    history.markSaved();
    expect(history.dirty).toBe(false);
    history.undo(); // back across the save: the saved file is now the thing that differs
    expect(history.dirty).toBe(true);
  });

  it('folds repeated same-key edits inside the window into one undo step, keeping the oldest value', () => {
    const history = new GuiHistory(sample());
    const nudge = (x: number, now: number) =>
      history.execute(patchLayers(history.doc, { buy: { x } }, 'Nudge', 'nudge:buy'), now);
    nudge(119, 1000);
    nudge(120, 1200);
    nudge(121, 1400);
    expect(history.doc.layers.find((layer) => layer.id === 'buy')!.x).toBe(121);
    history.undo();
    expect(history.doc.layers.find((layer) => layer.id === 'buy')!.x).toBe(118);
    expect(history.canUndo).toBe(false);
  });

  it('does not fold across the window, across keys, or across a save', () => {
    const history = new GuiHistory(sample());
    history.execute(patchLayers(history.doc, { buy: { x: 1 } }, 'a', 'k'), 1000);
    history.execute(patchLayers(history.doc, { buy: { x: 2 } }, 'a', 'k'), 1000 + COALESCE_WINDOW_MS + 1);
    history.execute(patchLayers(history.doc, { buy: { x: 3 } }, 'a', 'other'), 1000 + COALESCE_WINDOW_MS + 2);
    history.undo();
    history.undo();
    expect(history.doc.layers.find((layer) => layer.id === 'buy')!.x).toBe(1);
    history.redo();
    history.redo();
    history.markSaved();
    history.execute(patchLayers(history.doc, { buy: { x: 9 } }, 'a', 'other'), 1000 + COALESCE_WINDOW_MS + 3);
    history.undo();
    expect(history.doc.layers.find((layer) => layer.id === 'buy')!.x).toBe(3);
  });

  it('ignores null commands and resets wholesale', () => {
    const history = new GuiHistory(sample());
    history.execute(null);
    expect(history.canUndo).toBe(false);
    history.execute(patchLayers(history.doc, { buy: { x: 1 } }, 'a'));
    history.reset(starterDocument(2));
    expect([history.canUndo, history.dirty, history.doc.rows]).toEqual([false, false, 2]);
  });
});

describe('layer operations', () => {
  it('moves free layers by pixels and hops items a whole slot', () => {
    const doc = sample();
    const scene = sceneFor(doc);
    const moved = ops.nudge(doc, scene, ['buy', 'sword'], 1, 0)!.apply(doc);
    expect(moved.layers.find((layer) => layer.id === 'buy')).toMatchObject({ x: 119, y: 50 });
    expect(moved.layers.find((layer) => layer.id === 'sword')!.slot).toBe(14); // one slot right
    const down = ops.nudge(doc, scene, ['sword'], 0, 1)!.apply(doc);
    expect(down.layers.find((layer) => layer.id === 'sword')!.slot).toBe(22); // one row down
    expect(ops.nudge(doc, scene, ['frame'], 0, 0)).toBeNull();
  });

  it('never moves or deletes a locked layer', () => {
    const doc = patchLayers(sample(), { buy: { locked: true } }, 'Lock')!.apply(sample());
    const scene = sceneFor(doc);
    expect(ops.nudge(doc, scene, ['buy'], 5, 5)).toBeNull();
    expect(ops.deleteLayers(doc, ['buy'])).toBeNull();
    expect(ops.deleteLayers(doc, ['buy', 'tag'])!.apply(doc).layers.some((layer) => layer.id === 'buy')).toBe(true);
  });

  it('moves and resizes through rectangles; items follow the nearest slot; locked paint is untouched', () => {
    const doc = sample();
    const scene = sceneFor(doc);
    const rect = ops.setRects(doc, scene, { buy: { x: 10, y: 20, w: 30, h: 12 }, apple: { x: 8 + 18 * 2, y: 18 + 18, w: 16, h: 16 } }, 'Resize')!.apply(doc);
    expect(rect.layers.find((layer) => layer.id === 'buy')).toMatchObject({ x: 10, y: 20, width: 30, height: 12 });
    expect(rect.layers.find((layer) => layer.id === 'apple')!.slot).toBe(11);
    // a text layer has x/y but no size fields: a resize only moves it
    const text = ops.setRects(doc, scene, { heading: { x: 30, y: 40, w: 99, h: 99 } }, 'Resize')!.apply(doc);
    expect(text.layers.find((layer) => layer.id === 'heading')).toMatchObject({ x: 30, y: 40 });
    expect('width' in text.layers.find((layer) => layer.id === 'heading')!).toBe(false);
  });

  it('duplicates just above the original with a unique id, name and a visible offset', () => {
    const doc = sample();
    const { command, created } = ops.duplicateLayers(doc, sceneFor(doc), ['buy', 'tag']);
    const after = command!.apply(doc);
    expect(created).toEqual(['tag_copy', 'buy_copy']);
    expect(ids(after).indexOf('tag_copy')).toBe(ids(after).indexOf('tag') + 1);
    expect(ids(after).indexOf('buy_copy')).toBe(ids(after).indexOf('buy') + 1);
    expect(after.layers.find((layer) => layer.id === 'buy_copy')).toMatchObject({ x: 122, y: 54, name: 'Buy button copy' });
    expect(json(command!.revert(after))).toBe(json(doc));
  });

  it('restacks by one or to the ends', () => {
    const doc = sample();
    expect(ids(ops.restack(doc, ['frame'], 'forward')!.apply(doc)).slice(0, 3)).toEqual(['wells', 'frame', 'art']);
    expect(ids(ops.restack(doc, ['apple'], 'backward')!.apply(doc)).slice(-3)).toEqual(['coin', 'apple', 'sword']);
    expect(ids(ops.restack(doc, ['frame'], 'front')!.apply(doc)).at(-1)).toBe('frame');
    expect(ids(ops.restack(doc, ['apple'], 'back')!.apply(doc))[0]).toBe('apple');
    expect(ops.restack(doc, ['apple'], 'front')).toBeNull();
  });

  it('visibility, lock and rename are single-step edits; an empty name falls back to the id', () => {
    const doc = sample();
    expect(ops.setVisible(doc, 'buy', false)!.apply(doc).layers.find((layer) => layer.id === 'buy')!.visible).toBe(false);
    const hidden = ops.setVisible(doc, 'buy', false)!.apply(doc);
    expect('visible' in ops.setVisible(hidden, 'buy', true)!.apply(hidden).layers.find((layer) => layer.id === 'buy')!).toBe(false);
    expect(ops.setLocked(doc, 'buy', true)!.apply(doc).layers.find((layer) => layer.id === 'buy')!.locked).toBe(true);
    expect(ops.rename(doc, 'buy', '  Pay  ')!.apply(doc).layers.find((layer) => layer.id === 'buy')!.name).toBe('Pay');
    expect('name' in ops.rename(doc, 'buy', '')!.apply(doc).layers.find((layer) => layer.id === 'buy')!).toBe(false);
  });

  it('adds a palette component centered on the canvas, with registry defaults, on top', () => {
    const doc = starterDocument(6);
    const { command, layer } = ops.addComponent(doc, sceneFor(doc), componentOf(registry, 'button')!);
    expect(layer).toMatchObject({ type: 'button', id: 'button', width: 52, height: 20, x: 62, y: 101 });
    expect(ids(command.apply(doc)).at(-1)).toBe('button');
    // a slot-bound item has no free position to center
    const item = ops.addComponent(doc, sceneFor(doc), componentOf(registry, 'item')!).layer;
    expect(item).toMatchObject({ type: 'item', slot: 13 });
    expect('x' in item).toBe(false);
  });

  it('changing rows resizes a frame panel that spanned the canvas', () => {
    // exercised through the same commands the studio composes
    const doc = starterDocument(6);
    const command = compose('Change rows', [
      setDocumentFields(doc, { rows: 3 }, 'rows'),
      patchLayers(doc, { frame: { height: 168 } }, 'frame'),
    ])!;
    const after = command.apply(doc);
    expect([after.rows, after.layers[0]!.height]).toEqual([3, 168]);
  });
});

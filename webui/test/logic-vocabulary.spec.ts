/**
 * The palette is the server's vocabulary and nothing else (logic.md §7.3, D5): grouped as Triggers, the
 * action categories, Conditions and Flow; an add-on's category appears and gets the add-on colour; an
 * unavailable provider is listed with its reason; a new node is born with the shape the entry implies.
 * The recorded snapshot is the real vocabulary the plugin's golden test writes.
 */
import { describe, expect, it } from 'vitest';
import snapshot from '../../fixtures/golden/editor-demo-snapshot.json';
import {
  categoryColor, defaultActionStep, defaultControlStep, defaultRule, paletteGroups, readVocabulary, searchPalette, stepFor,
} from '../src/lib/logic/vocabulary';
import { frameView, stepZoom, wheelZoom, zoomAt, revealRect, MAX_ZOOM, MIN_ZOOM } from '../src/lib/logic/viewport';

const vocabulary = readVocabulary(snapshot.describe.behavior)!;

describe('reading the vocabulary', () => {
  it('reads the version-2 vocabulary the plugin records', () => {
    expect(vocabulary.version).toBe(2);
    expect(vocabulary.actions.size).toBeGreaterThan(20);
    expect(vocabulary.triggers.get('gui.open')!.firedBy).toEqual(['gui']);
    expect(vocabulary.steps.get('choose')!.supported).toBe(true);
    expect(readVocabulary({})).toBeNull();
  });

  it('an unavailable action keeps the server\'s reason', () => {
    const money = vocabulary.actions.get('take_money')!;
    expect(money.available).toBe(false);
    expect(money.reason).toContain('vault');
    expect(vocabulary.actions.get('sound')!.available).toBe(true);
  });
});

describe('the palette', () => {
  const groups = paletteGroups(vocabulary, { host: 'gui' });

  it('is grouped Triggers first, the action categories in the server\'s order, Flow last', () => {
    expect(groups[0]!.id).toBe('triggers');
    const ids = groups.map((group) => group.id);
    expect(ids.slice(0, 6)).toEqual(['triggers', 'player', 'gui', 'world', 'server', 'economy']);
    expect(ids[ids.length - 1]).toBe('flow');
    if (ids.includes('conditions')) expect(ids.indexOf('conditions')).toBe(ids.length - 2); // Conditions, then Flow (§7.3)
    expect(groups[groups.length - 1]!.entries[0]!.kind).toBe('control'); // the control steps lead the Flow group
  });

  it('lists only the triggers the host fires', () => {
    expect(groups[0]!.entries.map((entry) => entry.id)).toEqual(['gui.opening', 'gui.open', 'gui.close', 'gui.click', 'gui.click_outside', 'gui.tick']);
    expect(paletteGroups(vocabulary)[0]!.entries.length).toBeGreaterThan(20);
  });

  it('greys an unavailable provider with its reason', () => {
    const money = groups.find((group) => group.id === 'economy')!.entries.find((entry) => entry.id === 'take_money')!;
    expect(money.available).toBe(false);
    expect(money.reason).toContain('vault');
  });

  it('shows an add-on\'s action under its own category, with the add-on colour', () => {
    const raw = structuredClone(snapshot.describe.behavior) as { actions: Record<string, unknown>[]; categories: Record<string, unknown>[] };
    raw.categories.push({ id: 'quests', displayName: 'Quests', builtIn: false });
    raw.actions.push({ id: 'example:accept_quest', displayName: 'Accept quest', description: 'x', category: 'quests', available: true, parameters: [], registeredBy: 'example' });
    const extended = readVocabulary(raw)!;
    const group = paletteGroups(extended).find((entry) => entry.id === 'quests')!;
    expect(group.title).toBe('Quests');
    expect(group.entries[0]!.id).toBe('example:accept_quest');
    expect(categoryColor('quests')).toBe('var(--logic-addon)');
    expect(categoryColor('economy')).toBe('var(--logic-economy)');
    expect(categoryColor('trigger')).toBe('var(--logic-trigger)');
  });

  it('searches titles, ids and descriptions, every word', () => {
    expect(searchPalette(groups, 'money').flatMap((group) => group.entries.map((entry) => entry.id))).toEqual(expect.arrayContaining(['take_money', 'give_money']));
    const found = searchPalette(groups, 'play sound').flatMap((group) => group.entries.map((entry) => entry.id));
    expect(found).toContain('sound');
    expect(searchPalette(groups, 'zzzz')).toEqual([]);
    expect(searchPalette(groups, '').length).toBe(groups.length);
  });
});

describe('what a new node is born as', () => {
  it('an action with a shorthand starts with that parameter, one without parameters is a bare name', () => {
    expect(defaultActionStep(vocabulary.actions.get('sound')!)).toEqual({ sound: '' });
    expect(defaultActionStep(vocabulary.actions.get('gui.close')!)).toBe('gui.close');
    expect(defaultActionStep(vocabulary.actions.get('give_exp')!)).toEqual({ give_exp: 1 });
  });

  it('control steps and rules', () => {
    expect(defaultControlStep('if')).toEqual({ if: '${true}', then: [] });
    expect(defaultControlStep('wait')).toEqual({ wait: 20 });
    expect(defaultControlStep('call', 'purchase')).toEqual({ call: 'purchase' });
    expect(defaultControlStep('stop')).toBe('stop');
    expect(defaultRule(vocabulary.triggers.get('gui.open')!)).toEqual({ on: 'gui.open', do: [] });
    const entry = groups().find((group) => group.id === 'flow')!.entries.find((e) => e.id === 'require')!;
    expect(stepFor(vocabulary, entry)).toEqual({ require: [] });
  });
});

function groups() {
  return paletteGroups(vocabulary, { host: 'gui' });
}

describe('the camera', () => {
  it('zooms about the cursor and stays within its range', () => {
    const view = { zoom: 1, panX: 100, panY: 50 };
    const zoomed = zoomAt(view, 2, { x: 300, y: 250 });
    expect((300 - zoomed.panX) / zoomed.zoom).toBeCloseTo((300 - view.panX) / view.zoom);
    expect(zoomAt(view, 99, { x: 0, y: 0 }).zoom).toBe(MAX_ZOOM);
    expect(zoomAt(view, 0.01, { x: 0, y: 0 }).zoom).toBe(MIN_ZOOM);
    expect(stepZoom(view, 1, { x: 0, y: 0 }).zoom).toBe(1.4);
    expect(stepZoom(view, -1, { x: 0, y: 0 }).zoom).toBe(0.7);
    expect(wheelZoom(view, -100, { x: 0, y: 0 }).zoom).toBeGreaterThan(1);
  });

  it('frames a rectangle whole and centered, never closer than actual size', () => {
    const framed = frameView({ w: 800, h: 600 }, { x: 0, y: 0, w: 2000, h: 1000 });
    expect(framed.zoom).toBeCloseTo(704 / 2000);
    expect(framed.panX + 1000 * framed.zoom).toBeCloseTo(400, 0);
    expect(frameView({ w: 800, h: 600 }, { x: 10, y: 10, w: 100, h: 100 }).zoom).toBe(1);
  });

  it('reveals a node with the least panning', () => {
    const view = { zoom: 1, panX: 0, panY: 0 };
    expect(revealRect(view, { w: 800, h: 600 }, { x: 100, y: 100, w: 100, h: 50 })).toBe(view);
    expect(revealRect(view, { w: 800, h: 600 }, { x: 900, y: 100, w: 100, h: 50 }).panX).toBeLessThan(0);
  });
});

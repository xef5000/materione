/**
 * The SDK generator against recorded registries (codemode.md section 3.1, 3.6, 11): the `.d.ts` for each
 * snapshot is a golden compared byte for byte, the output is a pure function of the surface document, and
 * an addon's registration becomes SDK surface with no generator change (D5).
 *
 * Regenerate the goldens (and the derived addon snapshot) with MATERIONE_GOLDEN_REGEN=true.
 */
import { describe, expect, it } from 'vitest';
import { join } from 'node:path';
import { DemoServer } from '../src/lib/demo/server';
import { loadSnapshot } from '../src/lib/demo/snapshot';
import { generateSdk } from '../src/lib/codemode/generate/generate';
import { fetchSurface } from '../src/lib/codemode/surface-source';
import { assembleSurface, type SurfaceDocument } from '../src/lib/codemode/generate/surface';
import {
  GOLDEN_DIR,
  REGISTRY_DIR,
  goldenText,
  readSnapshot,
  snapshotJson,
  surfaceOf,
  withFakeAddon,
} from './helpers/codemode';

const current = readSnapshot('plugin-current');
const addonSnapshot = withFakeAddon(current);

describe('the recorded registries', () => {
  it('record the real plugin: every registry the generator reads is present', () => {
    const surface = surfaceOf(current);
    expect(surface.contentTypes.length).toBeGreaterThanOrEqual(11);
    expect(surface.behavior.actions.length).toBeGreaterThan(20);
    expect(surface.behavior.conditions.length).toBeGreaterThan(5);
    expect(surface.behavior.triggers.length).toBeGreaterThan(20);
    expect(surface.behavior.mechanics.length).toBeGreaterThan(3);
    expect(surface.behavior.functions.length).toBeGreaterThan(10);
    expect(surface.behavior.recipes.length).toBeGreaterThan(5);
    expect(surface.gui.components.length).toBeGreaterThan(5);
    expect(surface.capabilities).toContain('logic');
  });

  it('keeps the fake-addon snapshot equal to its derivation from the plugin snapshot', () => {
    const committed = goldenText(join(REGISTRY_DIR, 'with-addon.json'), snapshotJson(addonSnapshot));
    expect(committed).toBe(snapshotJson(addonSnapshot));
  });
});

describe('the surface document', () => {
  it('is assembled from authoring.describe over the editor channel, with no other call', async () => {
    const calls: string[] = [];
    const server = new DemoServer(await loadSnapshot());
    const spy = { call: (op: string, payload?: Record<string, unknown>) => (calls.push(op), server.call(op, payload)) };
    const live = await fetchSurface(spy);
    expect(calls).toEqual(['authoring.describe']);
    // the demo replays the recorded server, so what the channel serves is what the snapshot recorded
    const recorded = surfaceOf(current);
    expect(live.behavior).toEqual(recorded.behavior);
    expect(live.gui).toEqual(recorded.gui);
    expect(live.contentTypes.map((type) => type.name).sort()).toEqual(recorded.contentTypes.map((type) => type.name).sort());
    expect(live.capabilities).toBeNull(); // a demo has no session blob, so the full surface is assumed
  });
});

describe('the generated surface (golden)', () => {
  it('matches the golden .d.ts of today\'s plugin, byte for byte', () => {
    const text = generateSdk(surfaceOf(current));
    expect(text).toBe(goldenText(join(GOLDEN_DIR, 'plugin-current.d.ts'), text));
  });

  it('matches the golden .d.ts of the plugin with a fake addon, byte for byte', () => {
    const text = generateSdk(surfaceOf(addonSnapshot));
    expect(text).toBe(goldenText(join(GOLDEN_DIR, 'with-addon.d.ts'), text));
  });
});

describe('the generator', () => {
  it('is deterministic: registry order and repeated runs do not change a byte', () => {
    const surface = surfaceOf(addonSnapshot);
    const text = generateSdk(surface);
    expect(generateSdk(structuredClone(surface))).toBe(text);

    const reversed: SurfaceDocument = structuredClone(surface);
    reversed.contentTypes.reverse();
    for (const list of Object.values(reversed.behavior)) {
      // the registries, not the parameter lists: a parameter's position is the schema's
      if (Array.isArray(list) && list.length > 0 && typeof list[0] === 'object' && 'id' in list[0] && !('type' in list[0])) list.reverse();
    }
    reversed.gui.components.reverse();
    reversed.gui.containers.reverse();
    expect(generateSdk(reversed)).toBe(text);
  });

  it('turns an addon registration into SDK surface and changes nothing else', () => {
    const before = generateSdk(surfaceOf(current));
    const after = generateSdk(surfaceOf(addonSnapshot));
    // the addon's action, trigger and content type are members now ...
    expect(after).toContain('greet(params: GreetActionParams | string, options?: ActionOptions): ActionStep<"example:greet">');
    expect(after).toContain('ping(params?: PingTriggerArgs): TriggerBinding<"example:ping", PingContext>');
    expect(after).toContain('readonly quests: Collection<"quest", QuestFields, QuestHandle>');
    expect(after).toContain('@registeredBy example');
    expect(before).not.toContain('example:greet');
    // ... and every line the plugin snapshot produced is still there, except the unions the ids joined
    const kept = new Set(after.split('\n'));
    const lost = before.split('\n').filter((line) => !kept.has(line));
    expect(lost.every((line) => line.startsWith('type ') || line.startsWith('// Surface:'))).toBe(true);
  });

  it('says in the types which content type takes a flat file, from the self-description and nowhere else (#475)', () => {
    const surface = surfaceOf(current);
    // the registry says it; the generator keeps no list of directories
    expect(surface.contentTypes.filter((type) => type.flatFile).map((type) => type.directory)).toEqual(['currencies']);
    const text = generateSdk(surface);
    const sentence = 'A file may also hold one currency as its top-level fields, with the file name as its id';
    expect(text).toContain(sentence);
    // exactly one type says it, and a registry that does not send the flag says nothing
    expect(text.split(sentence).length - 1).toBe(1);
    const silent = structuredClone(surface);
    for (const type of silent.contentTypes) delete type.flatFile;
    expect(generateSdk(silent)).not.toContain('top-level fields');
    // the flag is read as data: only a literal true counts
    const hostile = assembleSurface({ types: [{ name: 'a', flatFile: 'yes' }, { name: 'b', flatFile: true }] }, null);
    expect(hostile.contentTypes.map((type) => type.flatFile)).toEqual([undefined, true]);
  });

  it('reports the view facet on a view kind, and types a view reference as the union of the view kinds (views.md section 9)', () => {
    const surface = surfaceOf(current);
    const text = generateSdk(surface);
    // gui declares the facet, and nothing else does; the sentence comes from the facet, not from a list in the generator
    expect(surface.contentTypes.filter((type) => type.view !== undefined).map((type) => type.name)).toEqual(['gui']);
    expect(surface.contentTypes.find((type) => type.name === 'gui')?.view).toEqual({
      viewerRule: 'opened', rerenderDefault: 'on_change', supportsTarget: false, supportsKey: false,
    });
    expect(text).toContain('A view kind: a gui is opened by `view.open` and closed by `view.close`');
    expect(text.split('A view kind:').length - 1).toBe(1);
    // the view verbs exist, and the gui verbs they replace do not
    expect(text).toContain('viewOpen(params: ViewOpenActionParams | Ref<"gui">');
    expect(text).toContain('viewClose(');
    expect(text).toContain('viewBack(');
    expect(text).not.toMatch(/ActionStep<"gui.(open|close|back)">/); // the triggers keep their names
    // `view` is the union of the view kinds, not a kind a Ref can name by itself
    expect(text).not.toContain('Ref<"view">');
    expect(text).toMatch(/type RefKind = [^;]*"gui"/);
    expect(text).not.toMatch(/type RefKind = [^;]*"view"/);
  });

  it('a second view kind joins the view reference with no generator change', () => {
    const surface = structuredClone(surfaceOf(current));
    surface.contentTypes.push({
      name: 'dialog', directory: 'dialogs', displayName: 'Dialog', description: 'A native modal screen',
      view: { viewerRule: 'opened', rerenderDefault: 'off', supportsTarget: false, supportsKey: false },
      parameters: [],
    });
    const text = generateSdk(surface);
    expect(text).toContain('viewOpen(params: ViewOpenActionParams | Ref<"dialog"> | Ref<"gui">');
    expect(text).toContain('A view kind: a dialog is opened by');
    expect(text).toContain('re-renders only when a flow asks');
    // a predicate kind (a HUD) says that no verb opens it
    const hud = structuredClone(surface);
    hud.contentTypes.push({
      name: 'hud', directory: 'huds', displayName: 'HUD', description: 'A heads-up display',
      view: { viewerRule: 'predicate', rerenderDefault: 'on_change', supportsTarget: false, supportsKey: false },
      parameters: [],
    });
    expect(generateSdk(hud)).toContain('a hud is shown to every player for whom its condition holds, and no verb opens it');
  });

  it('reads the view facet as data: only a well-formed facet counts', () => {
    const hostile = assembleSurface({
      types: [
        { name: 'a', view: 'opened' },
        { name: 'b', view: { rerenderDefault: 'off' } },
        { name: 'c', view: { viewerRule: 'opened', supportsTarget: 'yes', supportsKey: true } },
      ],
    }, null);
    expect(hostile.contentTypes.map((type) => type.view)).toEqual([
      undefined, undefined, { viewerRule: 'opened', rerenderDefault: 'off', supportsTarget: false, supportsKey: true },
    ]);
  });

  it('drops what a server does not send: an empty describe yields an empty, valid surface', () => {
    const text = generateSdk(assembleSurface({}, null));
    expect(text).toContain('type Capability = string;');
    expect(text).toContain('type ActionId = never;');
    expect(text).not.toContain('interface ActionCatalog');
  });

  it('reads a hostile describe as data: malformed entries are dropped', () => {
    const surface = assembleSurface(
      { types: [{ name: 7 }, { name: 'ok', parameters: [{ id: 'x' }, { id: 'y', type: 'string' }] }], behavior: { actions: 'no', triggers: [null, 3] } },
      ['logic'],
    );
    expect(surface.contentTypes.map((type) => type.name)).toEqual(['ok']);
    expect(surface.contentTypes[0]?.parameters.map((param) => param.id)).toEqual(['y']);
    expect(surface.behavior.actions).toEqual([]);
    expect(surface.behavior.triggers).toEqual([]);
  });
});

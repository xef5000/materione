// The recorded registries the code-mode SDK generator is tested against (codemode.md section 3.1, 3.6, 11).
//
//   plugin-current.json  recorded from the real authoring.describe op by the JVM test
//                        EditorDemoSnapshotTest, so it cannot drift from the registries.
//   with-addon.json      plugin-current plus one fake addon's action, trigger and content type, derived by
//                        `withFakeAddon` below and committed; the golden spec fails if it is not the derivation.
//
// Goldens are regenerated with MATERIONE_GOLDEN_REGEN=true (the vitest counterpart of the JVM's
// -Dmaterione.golden.regen=true): review the diff, then commit; CI never regenerates.
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { assembleSurface, type SurfaceDocument } from '../../src/lib/codemode/generate/surface';

export const FIXTURES = fileURLToPath(new URL('../../../fixtures', import.meta.url));
export const REGISTRY_DIR = join(FIXTURES, 'codemode', 'registry');
export const GOLDEN_DIR = join(FIXTURES, 'golden', 'codemode');
export const CORPUS_DIR = join(FIXTURES, 'codemode', 'corpus');
export const CORE_DTS = fileURLToPath(new URL('../../src/lib/codemode/generate/core.d.ts', import.meta.url));

export const REGEN = process.env.MATERIONE_GOLDEN_REGEN === 'true';

export interface RegistrySnapshot {
  format: number;
  source?: string;
  capabilities: string[];
  describe: Record<string, unknown> & { types: unknown[]; behavior: { actions: unknown[]; triggers: unknown[] } & Record<string, unknown[]> };
}

export function readSnapshot(name: string): RegistrySnapshot {
  return JSON.parse(readFileSync(join(REGISTRY_DIR, `${name}.json`), 'utf8')) as RegistrySnapshot;
}

export function surfaceOf(snapshot: RegistrySnapshot): SurfaceDocument {
  return assembleSurface(snapshot.describe, snapshot.capabilities);
}

export function surfaceNamed(name: string): SurfaceDocument {
  return surfaceOf(readSnapshot(name));
}

/**
 * What an addon's registration adds to the wire: an action, a trigger with its filter and context, and a
 * content type with a rule list. The generator is not told about any of it.
 */
export function withFakeAddon(base: RegistrySnapshot): RegistrySnapshot {
  const next = structuredClone(base);
  const behavior = next.describe.behavior;
  behavior.actions = [
    ...behavior.actions,
    {
      id: 'example:greet',
      displayName: 'Greet',
      description: 'Greets a player by name in chat',
      category: 'player',
      shorthand: 'who',
      deferred: false,
      available: true,
      threading: 'player_region',
      registeredBy: 'example',
      sinceVersion: '1.2.0',
      parameters: [
        { id: 'who', type: 'string', required: true, description: 'Whom to greet; ${player.name} for the viewer', label: 'Who', section: 'general', order: 0, advanced: false, uiHint: 'text' },
        { id: 'loud', type: 'boolean', required: false, default: 'false', description: 'Shout the greeting', label: 'Loud', section: 'general', order: 0, advanced: false, uiHint: 'toggle' },
      ],
    },
  ];
  behavior.triggers = [
    ...behavior.triggers,
    {
      id: 'example:ping',
      displayName: 'Ping',
      description: 'A ping reaches the item from the example addon',
      firedBy: ['item'],
      details: [],
      cancellable: false,
      registeredBy: 'example',
      sinceVersion: '1.2.0',
      parameters: [],
      filters: [{ id: 'strength', type: 'integer', required: false, min: 1, max: 10, description: 'Only pings of at least this strength', label: 'Strength', section: 'general', order: 0, advanced: false, uiHint: 'number' }],
      context: [
        { path: 'player.name', type: 'string', description: "The player's name", hosts: [] },
        { path: 'ping.strength', type: 'integer', description: 'How strong the ping was', hosts: ['item'] },
      ],
    },
  ];
  next.describe.types = [
    ...next.describe.types,
    {
      name: 'quest',
      directory: 'quests',
      displayName: 'Quest',
      description: 'A quest an addon adds to the content model',
      parameters: [
        { id: 'title', type: 'string', required: true, description: 'The quest name', label: 'Title', section: 'general', order: 0, advanced: false, uiHint: 'text' },
        { id: 'reward', type: 'reference', required: false, referenceKind: 'item', description: 'The item given on completion', label: 'Reward', section: 'general', order: 0, advanced: false, uiHint: 'reference' },
        { id: 'behavior', type: 'list', required: false, description: 'The quest rules', label: 'Rules', section: 'behavior', order: 0, advanced: false, uiHint: 'behavior-rules' },
      ],
    },
  ];
  next.source = 'Derived from plugin-current.json by withFakeAddon (webui/test/helpers/codemode.ts); regenerate with MATERIONE_GOLDEN_REGEN=true.';
  return next;
}

/** Serialises like the JVM's pretty printer for the keys it sorts: two-space indent, trailing newline. */
export function snapshotJson(value: unknown): string {
  return `${JSON.stringify(sortKeys(value), null, 2)}\n`;
}

function sortKeys(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(sortKeys);
  if (typeof value !== 'object' || value === null) return value;
  const out: Record<string, unknown> = {};
  for (const key of Object.keys(value).sort()) out[key] = sortKeys((value as Record<string, unknown>)[key]);
  return out;
}

/** Writes `text` when regenerating; otherwise returns what is committed (a missing golden is a failure). */
export function goldenText(path: string, text: string): string {
  if (REGEN) {
    mkdirSync(dirname(path), { recursive: true });
    writeFileSync(path, text, 'utf8');
  }
  if (!existsSync(path)) {
    throw new Error(`Missing golden ${path}: run with MATERIONE_GOLDEN_REGEN=true, review the file, commit it.`);
  }
  return readFileSync(path, 'utf8');
}

/** Every id a registry of the recorded snapshots carries. */
export function registryIds(): Set<string> {
  const ids = new Set<string>();
  for (const snapshot of [readSnapshot('plugin-current'), withFakeAddon(readSnapshot('plugin-current'))]) {
    const surface = surfaceOf(snapshot);
    const b = surface.behavior;
    for (const type of surface.contentTypes) {
      ids.add(type.name);
      if (type.directory !== undefined) ids.add(type.directory);
    }
    for (const group of [b.actions, b.conditions, b.triggers, b.mechanics, b.functions, b.recipes, b.itemSources, b.listSources, b.inputMethods, b.steps, b.categories, surface.gui.components, surface.gui.containers]) {
      for (const entry of group) ids.add(entry.id);
    }
    for (const capability of surface.capabilities ?? []) ids.add(capability);
  }
  return ids;
}

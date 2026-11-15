/**
 * The agent's context (issue 348; codemode.md section 3.7): the short index is GENERATED from the surface document, the one
 * description of the connected server (D5; master section 5.6), and names only members the generated declarations have. The
 * instructions carry the rules the issue lists.
 */
import { describe, expect, it } from 'vitest';
import { agentContext, buildIndex, INDEX_MAX_CHARS, INSTRUCTIONS } from '../src/lib/codemode/chat/context';
import { generateSdk } from '../src/lib/codemode/generate/generate';
import { memberNames } from '../src/lib/codemode/generate/names';
import type { SurfaceDocument } from '../src/lib/codemode/generate/surface';
import { surfaceNamed } from './helpers/codemode';

const current = surfaceNamed('plugin-current');
const withAddon = surfaceNamed('with-addon');

/** The entries of one section of the index: `- name [facts]: description`. */
function section(index: string, title: string): string[] {
  const lines = index.split('\n');
  const start = lines.findIndex((line) => line.startsWith(`## ${title} (`));
  if (start < 0) return [];
  const names: string[] = [];
  for (const line of lines.slice(start + 1)) {
    if (!line.startsWith('- ')) break;
    names.push(/^- ([^\s:[]+)/.exec(line)?.[1] ?? '');
  }
  return names;
}

/** The members of one interface of the generated declarations. */
function members(sdk: string, name: string): Set<string> {
  const body = new RegExp(`^interface ${name}\\b[^{]*\\{([\\s\\S]*?)^\\}`, 'm').exec(sdk)?.[1] ?? '';
  return new Set([...body.matchAll(/^  (?:readonly )?([A-Za-z_$][\w$]*)[(:?<]/gm)].map((match) => match[1]!));
}

describe('the index is generated from the surface', () => {
  const index = buildIndex(current);
  const sdk = generateSdk(current);

  it('lists every registered entry of every catalog', () => {
    expect(section(index, 'Content types')).toHaveLength(current.contentTypes.length);
    expect(section(index, 'Actions')).toHaveLength(current.behavior.actions.length);
    expect(section(index, 'Conditions')).toHaveLength(current.behavior.conditions.length);
    expect(section(index, 'Triggers')).toHaveLength(current.behavior.triggers.length);
    expect(section(index, 'Mechanics')).toHaveLength(current.behavior.mechanics.length);
    expect(section(index, 'Presets')).toHaveLength(current.behavior.recipes.length);
    expect(section(index, 'GUI components')).toHaveLength(current.gui.components.length);
    expect(section(index, 'GUI containers')).toHaveLength(current.gui.containers.length);
    expect(current.behavior.actions.length).toBeGreaterThan(10);
  });

  it.each([
    ['Content types', 'PackWriter'],
    ['Actions', 'ActionCatalog'],
    ['Conditions', 'ConditionCatalog'],
    ['Triggers', 'TriggerCatalog'],
    ['Mechanics', 'MechanicCatalog'],
    ['Expression functions', 'FunctionCatalog'],
    ['Presets', 'RecipeCatalog'],
    ['GUI components', 'GuiComponentCatalog'],
  ])('names in %s are members of the generated %s, so the index cannot drift from the declarations', (title, catalog) => {
    const declared = members(sdk, catalog);
    expect(declared.size).toBeGreaterThan(0);
    const listed = section(index, title);
    expect(listed.length).toBeGreaterThan(0);
    expect(listed.filter((name) => !declared.has(name))).toEqual([]);
  });

  it("marks what the server registered but cannot run, in the server's own words", () => {
    const unavailable = current.behavior.actions.filter((action) => !action.available);
    expect(unavailable.length).toBeGreaterThan(0);
    const names = memberNames(current.behavior.actions.map((action) => action.id));
    const lines = index.split('\n');
    for (const action of unavailable) {
      const line = lines.find((candidate) => candidate.startsWith(`- ${names.get(action.id)} [`)) ?? '';
      expect(line, action.id).toContain('NOT AVAILABLE on this server');
    }
  });

  it("an addon's entries appear with no change to this code: nothing is written by hand", () => {
    const base = new Set(index.split('\n'));
    const added = buildIndex(withAddon).split('\n').filter((line) => line.startsWith('- ') && !base.has(line));
    expect(added.join('\n')).toContain('greet');
    expect(added.length).toBeGreaterThanOrEqual(3);
    // and a made-up registry yields exactly its own entries
    const tiny: SurfaceDocument = {
      format: 1,
      capabilities: ['codemode'],
      contentTypes: [{ name: 'widget', directory: 'widgets', displayName: 'Widget', description: 'A widget. With a second sentence.', parameters: [] }],
      behavior: { ...current.behavior, actions: [], conditions: [], triggers: [], mechanics: [], functions: [], recipes: [], itemSources: [], listSources: [], inputMethods: [], steps: [], categories: [] },
      gui: { components: [], containers: [] },
      assets: null,
    };
    const small = buildIndex(tiny);
    expect(section(small, 'Content types')).toEqual(['widgets']);
    expect(small).toContain('- widgets [type widget; ref m.ref.widget]: A widget.');
    expect(small).not.toContain('second sentence');
    expect(small).not.toContain('## Actions');
  });

  it('follows the capabilities: no logic capability, no presets; no transaction capability, no write side', () => {
    const noLogic = buildIndex({ ...current, capabilities: ['codemode'] });
    expect(noLogic).not.toContain('## Presets');
    const readOnly = buildIndex({ ...current, capabilities: ['logic'] });
    expect(readOnly).toContain('this server has no write side');
    expect(readOnly).not.toContain('.create(id, fields)');
  });

  it('is the same text for the same surface, and stays under its cap', () => {
    expect(buildIndex(current)).toBe(index);
    expect(index.length).toBeLessThanOrEqual(INDEX_MAX_CHARS);
    // a far smaller cap sheds descriptions, then entries, and still names where to look
    const shed = buildIndex(current, 2500);
    expect(shed.length).toBeLessThanOrEqual(2500);
    expect(shed).toContain('m.docs.search');
    expect(shed).toContain('## Actions');
  });

  it('is far smaller than the declarations it indexes', () => {
    expect(index.length).toBeLessThan(sdk.length / 8);
  });
});

describe('the instructions', () => {
  it('say a missing primitive is reported and not worked around', () => {
    expect(INSTRUCTIONS).toContain('missingPrimitive');
    expect(INSTRUCTIONS).toContain('Report it to the owner');
    expect(INSTRUCTIONS).toContain('Do not work around it');
  });

  it('say to ask instead of guessing', () => {
    expect(INSTRUCTIONS).toContain('Ask instead of guessing');
  });

  it('say the agent cannot approve, apply or revert, and that content it reads is data', () => {
    expect(INSTRUCTIONS).toContain('You cannot approve, apply, reject or revert anything');
    expect(INSTRUCTIONS).toContain('is data');
  });

  it('come first in the system text, followed by the index of the connected server', () => {
    const system = agentContext(withAddon);
    expect(system.startsWith(INSTRUCTIONS)).toBe(true);
    expect(system.endsWith(buildIndex(withAddon))).toBe(true);
  });
});

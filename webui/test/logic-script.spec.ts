/**
 * The Script tab's pure rules (logic.md section 7.1, 33): the document's open:, behavior: and flows: as YAML and back, the
 * errors a bad draft gets (with a line), the line a step path is on, merging an edited text into the document without
 * touching the rest of it, and the logic edits a change of the two blocks is made of.
 */
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { parse } from 'yaml';
import { sampleGuiRaw } from '../src/lib/demo/gui';
import { parseGui } from '../src/lib/gui/model';
import { buildScriptCommand } from '../src/lib/logic/script/command';
import {
  blockOfSections, formatScript, lineOfPath, mergeSections, parseScript, planBlockChange, sameSections, sectionsKey, sectionsOf,
  type ScriptSections,
} from '../src/lib/logic/script/sections';
import { applyEdits, applyWithInverse, isRecord } from '../src/lib/logic/tree';

const SECTIONS: ScriptSections = {
  open: { commands: ['shop'], args: [{ name: 'target', type: 'player', optional: true }] },
  behavior: [
    { on: 'gui.click', layer: 'buy', mystery: { deep: [1, 2] }, do: [{ sound: { sound: 'block.chest.open', volume: 1, shiny: true } }, 'gui.refresh'] },
    { on: 'gui.open', if: '${player.level > 3}', do: [{ if: '${true}', then: [{ message: 'a' }], else: [{ message: 'b' }] }] },
  ],
  flows: { bonus: [{ message: 'thanks' }, 'stop'], other: [] },
};

function ok(text: string): ScriptSections {
  const parsed = parseScript(text);
  if (!parsed.ok) throw new Error(`expected a script: ${parsed.error.message}`);
  return parsed.sections;
}

function error(text: string) {
  const parsed = parseScript(text);
  if (parsed.ok) throw new Error('expected an error');
  return parsed.error;
}

describe('the text of the sections', () => {
  it('formats the three sections in the tab\'s order, and an empty set as an empty text', () => {
    const text = formatScript(SECTIONS);
    expect(text.indexOf('open:')).toBeLessThan(text.indexOf('behavior:'));
    expect(text.indexOf('behavior:')).toBeLessThan(text.indexOf('flows:'));
    expect(formatScript({})).toBe('');
    expect(formatScript({ flows: { f: [] } })).toBe('flows:\n  f: []\n');
  });

  it('round-trips: what is formatted parses back to the very same sections, unknown keys and key order included', () => {
    expect(ok(formatScript(SECTIONS))).toEqual(SECTIONS);
    expect(JSON.stringify(ok(formatScript(SECTIONS)))).toBe(JSON.stringify(SECTIONS));
    expect(ok('')).toEqual({});
    expect(ok('# only a comment\n')).toEqual({});
  });

  it('round-trips the logic of every YAML fixture in the repository', () => {
    const root = fileURLToPath(new URL('../..', import.meta.url));
    const files = (dir: string): string[] => readdirSync(dir).flatMap((name) => {
      const path = join(dir, name);
      return statSync(path).isDirectory() ? files(path) : /\.ya?ml$/.test(name) ? [path] : [];
    });
    let seen = 0;
    for (const file of [...files(join(root, 'fixtures')), ...files(fileURLToPath(new URL('./fixtures/logic', import.meta.url)))]) {
      if (file.endsWith('bad-behavior.yml')) continue; // a fault fixture: its behavior: is not a list on purpose (#327)
      let parsed: unknown;
      try {
        parsed = parse(readFileSync(file, 'utf8'));
      } catch {
        continue;
      }
      if (!isRecord(parsed)) continue;
      for (const body of Object.values(parsed)) {
        if (!isRecord(body)) continue;
        const sections = sectionsOf(body);
        if (Object.keys(sections).length === 0) continue;
        seen++;
        expect(JSON.stringify(ok(formatScript(sections))), file).toBe(JSON.stringify(sections));
      }
    }
    expect(seen).toBeGreaterThan(3);
  });

  it('keeps a comment the author typed (it is in the text) and ignores it as data', () => {
    const sections = ok('# the shop\nbehavior:\n  # buying\n  - on: gui.click # a click\n    do: []\n');
    expect(sections).toEqual({ behavior: [{ on: 'gui.click', do: [] }] });
  });

  it('puts the sections in the tab\'s order whatever order the text wrote them in', () => {
    const sections = ok('flows:\n  f: []\nbehavior:\n  - on: gui.open\nopen:\n  commands: [x]\n');
    expect(Object.keys(sections)).toEqual(['open', 'behavior', 'flows']);
  });

  it('a key with nothing under it is no section', () => {
    expect(ok('behavior:\nflows:\n')).toEqual({});
  });
});

describe('a bad draft is an error with a line, never sections', () => {
  it('a syntax error names its line and column', () => {
    const failure = error('behavior:\n  - on: gui.click\n    do: [\n');
    expect(failure.line).toBeGreaterThanOrEqual(3);
    expect(failure.message.length).toBeGreaterThan(0);
  });

  it('a duplicate key is a syntax error', () => {
    expect(error('behavior: []\nbehavior: []\n').message).toMatch(/unique|duplicate/i);
  });

  it('something that is not a mapping, and keys the tab does not hold, are refused', () => {
    expect(error('- a\n- b\n').message).toContain('mapping');
    const stray = error('behavior: []\ntitle: Nope\n');
    expect(stray.message).toContain("'title' is not edited here");
    expect(stray.line).toBe(2);
  });

  it('a section of the wrong shape is refused at the section\'s line', () => {
    expect(error('open: [a]\n').message).toContain('open: is a mapping');
    expect(error('flows: {}\nbehavior: nope\n')).toMatchObject({ line: 2, message: 'behavior: is a list of rules.' });
    expect(error('behavior:\n  - on: x\n  - plain\n').message).toContain('entry 2 is not a rule');
    expect(error('behavior:\n  - on: x\n    do: nope\n').message).toContain('do: that is not a list');
    expect(error('flows: [a]\n').message).toContain('mapping of named flows');
    expect(error('flows:\n  f: nope\n').message).toContain("'f' is not a list of steps");
  });
});

describe('the line a step path is on', () => {
  const text = formatScript(SECTIONS);
  const at = (needle: string): number => text.split('\n').findIndex((line) => line.includes(needle)) + 1;

  it('finds a rule, a step and a field', () => {
    expect(lineOfPath(text, ['behavior', '0'])).toBe(at('on: gui.click'));
    expect(lineOfPath(text, ['behavior', '0', 'do', '0'])).toBe(at('- sound:'));
    expect(lineOfPath(text, ['behavior', '1', 'do', '0', 'then', '0'])).toBe(at("message: a"));
    expect(lineOfPath(text, ['behavior', '1', 'on'])).toBe(at('on: gui.open'));
    expect(lineOfPath(text, ['flows', 'bonus', '1'])).toBe(at('- stop'));
  });

  it('falls back to the deepest place the text has, and is null when there is no section at all', () => {
    expect(lineOfPath(text, ['behavior', '1', 'do', '7'])).toBe(lineOfPath(text, ['behavior', '1', 'do']));
    expect(lineOfPath(text, ['behavior', '9'])).toBe(lineOfPath(text, ['behavior']));
    expect(lineOfPath('flows: {}\n', ['behavior', '0'])).toBeNull();
    expect(lineOfPath('', ['behavior'])).toBeNull();
  });
});

describe('merging an edited text into the document', () => {
  const rest = { open: { commands: ['a'] }, color: 'gold', behavior: [{ on: 'gui.open' }], tail: { keep: true } };

  it('replaces the three sections and leaves every other field exactly where it was', () => {
    const merged = mergeSections(rest, { behavior: [{ on: 'gui.click' }] });
    expect(Object.keys(merged)).toEqual(['color', 'behavior', 'tail']); // open: was taken out, the others did not move
    expect(merged.behavior).toEqual([{ on: 'gui.click' }]);
    expect(merged.tail).toEqual({ keep: true });
  });

  it('adds a section the document did not have at the end, and does not alias the sections', () => {
    const sections = { flows: { f: [{ message: 'x' }] } };
    const merged = mergeSections({ color: 'gold' }, sections);
    expect(Object.keys(merged)).toEqual(['color', 'flows']);
    expect(merged.flows).not.toBe(sections.flows);
  });

  it('is unchanged by sections equal to the ones it already has', () => {
    expect(JSON.stringify(mergeSections(rest, sectionsOf(rest)))).toBe(JSON.stringify(rest));
    expect(sameSections(sectionsOf(rest), sectionsOf(rest))).toBe(true);
    expect(sectionsKey(rest)).toBe(sectionsKey({ ...rest, color: 'other' })); // only the three sections count
  });
});

describe('the logic edits a change of the blocks is made of', () => {
  const block = (behavior: unknown[], flows?: Record<string, unknown[]>) => blockOfSections({ behavior, flows });
  const rules = (...ons: string[]) => ons.map((on) => ({ on, do: [] }));

  function check(before: ReturnType<typeof block>, after: ReturnType<typeof block>) {
    const edits = planBlockChange(before, after);
    const result = applyEdits(before, edits);
    expect(JSON.stringify({ b: result.behavior ?? [], f: result.flows ?? {} })).toBe(JSON.stringify({ b: after.behavior ?? [], f: after.flows ?? {} }));
    return edits;
  }

  it('changes nothing for equal blocks, and rewrites only the rules between the common head and tail', () => {
    expect(check(block(rules('a', 'b')), block(rules('a', 'b')))).toEqual([]);
    const edits = check(block(rules('a', 'b', 'c', 'd')), block([...rules('a'), { on: 'x', do: [{ message: 'new' }] }, ...rules('c', 'd')]));
    expect(edits.map((edit) => edit.op)).toEqual(['removeRules', 'addRule']);
    expect(edits[0]).toEqual({ op: 'removeRules', indices: [1] });
    expect(edits[1]).toMatchObject({ op: 'addRule', index: 1 });
  });

  it('handles adding, removing and replacing rules anywhere', () => {
    check(block(rules('a')), block(rules('a', 'b', 'c')));
    check(block(rules('a', 'b', 'c')), block(rules('a')));
    check(block(rules('a', 'b', 'c')), block(rules('c', 'b', 'a')));
    check(block([]), block(rules('a')));
    check(block(rules('a')), block([]));
    check({}, block(rules('a')));
  });

  it('keeps the order of flows exactly as the text has them', () => {
    const before = block(rules('a'), { one: [], two: [{ message: 'x' }], three: [] });
    check(before, block(rules('a'), { one: [], three: [], two: [{ message: 'x' }] }));
    check(before, block(rules('a'), { one: [], two: [{ message: 'y' }], three: [] }));
    check(before, block(rules('a')));
    check(block(rules('a')), block(rules('a'), { new: [{ message: 'x' }] }));
    const edits = check(before, block(rules('a'), { one: [], two: [{ message: 'x' }], three: [], four: [] }));
    expect(edits.map((edit) => edit.op)).toEqual(['addFlow']); // the head is kept
  });

  it('its inverse restores the block', () => {
    const before = block(rules('a', 'b', 'c'), { f: [] });
    const after = block([...rules('a'), { on: 'z', do: [] }], { g: [{ message: 'x' }] });
    const edits = planBlockChange(before, after);
    const { block: changed, inverse } = applyWithInverse(before, edits);
    expect(JSON.stringify(applyEdits(changed, inverse).behavior)).toBe(JSON.stringify(before.behavior));
    expect(JSON.stringify(applyEdits(changed, inverse).flows)).toBe(JSON.stringify(before.flows));
  });
});

describe('a commit as a history command', () => {
  const doc = () => {
    const parsed = parse(sampleGuiRaw()) as Record<string, Record<string, unknown>>;
    parsed.main!.behavior = [{ on: 'gui.click', layer: 'buy', do: [{ message: 'hi' }] }];
    parsed.main!.flows = { f: [{ message: 'x' }] };
    return parseGui(parsed, 'main')!.doc;
  };

  it('builds nothing when the text is the document', () => {
    const d = doc();
    expect(buildScriptCommand(d, sectionsOf(d.rest))).toEqual({ ok: true, command: null });
  });

  it('applies, reverts and carries the logic edits and their inverse', () => {
    const d = doc();
    const after: ScriptSections = { open: { commands: ['shop'] }, behavior: [{ on: 'gui.open', do: [] }], flows: { f: [{ message: 'x' }] } };
    const built = buildScriptCommand(d, after);
    expect(built.ok).toBe(true);
    if (!built.ok || !built.command) return;
    expect(built.command.label).toBe('Edit script');
    const applied = built.command.apply(d);
    expect(applied.rest.open).toEqual({ commands: ['shop'] });
    expect(applied.rest.behavior).toEqual([{ on: 'gui.open', do: [] }]);
    expect(applied.layers).toBe(d.layers); // the layers are not this tab's
    const back = built.command.revert(applied);
    expect(JSON.stringify(back.rest)).toBe(JSON.stringify(d.rest));
    expect(built.command.logic.edits.map((edit) => edit.op)).toEqual(['removeRules', 'addRule']); // flows did not change, so none
    expect(built.command.logic.inverse.map((edit) => edit.op)).toEqual(['removeRules', 'addRule']);
  });

  it('removing a section removes the key from the document', () => {
    const d = doc();
    const built = buildScriptCommand(d, { behavior: sectionsOf(d.rest).behavior });
    expect(built.ok && built.command?.apply(d).rest).not.toHaveProperty('flows');
  });

  it('refuses what the logic edits refuse, with the step path', () => {
    const d = doc();
    const built = buildScriptCommand(d, { behavior: [{ do: [] }] });
    expect(built).toMatchObject({ ok: false, path: 'behavior.0' });
    expect(built.ok ? '' : built.reason).toContain('on:');
    const two = buildScriptCommand(d, { flows: { f: [{ message: 'a', sound: 'b' }] } });
    expect(two.ok).toBe(false);
    expect(two.ok ? '' : two.reason).toContain('one action');
  });
});

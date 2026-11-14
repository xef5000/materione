/**
 * The SDK core (codemode.md section 3.1 to 3.4, issue 339): programs run against the BUNDLED core in a bare
 * context (no globals but the language's), with a fake port, and the staged operation list is what is
 * checked. The corpus programs that type-check are executed and their lists recorded as goldens
 * (fixtures/golden/codemode/ops/); a list is also validated against the wire shape the plugin's own parser
 * reads, so the core cannot stage what the server does not accept.
 */
import { describe, expect, it } from 'vitest';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { SDK_CODES, type ProgramResult, type SdkDiagnostic } from '../src/lib/codemode/core';
import { CORPUS_DIR, GOLDEN_DIR, goldenText, readSnapshot, surfaceNamed, surfaceOf, withFakeAddon } from './helpers/codemode';
import { bareContext, inBare, run, type World } from './helpers/sdk-runner';
import { wireProblems, wireShapes } from './helpers/wire';

const surface = surfaceNamed('plugin-current');
/** The diagnostics that end a run (a warning, such as an entry unavailable on the recorded server, does not). */
const errorsOf = (result: ProgramResult): SdkDiagnostic[] => result.diagnostics.filter((d) => d.severity === 'error');
const addonSurface = surfaceOf(withFakeAddon(readSnapshot('plugin-current')));

interface Expectation {
  task: string;
  title: string;
  registry: string;
  result: 'pass' | 'fail';
}

const corpus = readdirSync(CORPUS_DIR)
  .filter((name) => name.endsWith('.ts'))
  .sort()
  .map((name) => {
    const stem = name.replace(/\.ts$/, '');
    const worldFile = join(CORPUS_DIR, `${stem}.world.json`);
    return {
      stem,
      program: readFileSync(join(CORPUS_DIR, name), 'utf8'),
      expect: JSON.parse(readFileSync(join(CORPUS_DIR, `${stem}.expect.json`), 'utf8')) as Expectation,
      world: (existsSync(worldFile) ? JSON.parse(readFileSync(worldFile, 'utf8')) : { packs: ['bench'] }) as World,
    };
  });

const passing = corpus.filter((entry) => entry.expect.result === 'pass');
const surfaceOfRegistry = (name: string) => (name === 'with-addon' ? addonSurface : surface);

describe('the corpus, executed (the operation lists are goldens: fixtures/golden/codemode/ops)', () => {
  it('runs every program that type-checks, and only those', () => {
    expect(passing.map((entry) => entry.expect.task)).toEqual(expect.arrayContaining(['T04', 'T08', 'T11', 'T25']));
    expect(corpus.filter((entry) => entry.expect.result === 'fail').length).toBeGreaterThan(0);
  });

  it.each(passing.map((entry) => [`${entry.expect.task} ${entry.expect.title}`, entry] as const))('%s stages a byte-stable list', async (_label, entry) => {
    const first = await run({ surface: surfaceOfRegistry(entry.expect.registry), program: entry.program, world: entry.world });
    expect(first.result.diagnostics.filter((d) => d.severity === 'error')).toEqual([]);
    expect(first.result.ok).toBe(true);
    // every operation names the program line that staged it, and the line is the call's
    const programLines = entry.program.split('\n');
    first.result.sources.forEach((source, index) => {
      expect(source, `operation ${index}`).not.toBeNull();
      const line = programLines[(source?.line ?? 0) - 1] ?? '';
      expect(line, `operation ${index} at line ${source?.line}`).toMatch(/\.(create|add|instantiate|set|duplicate|delete)\(/);
    });
    // every operation is in the wire's shape
    const shapes = wireShapes();
    for (const operation of first.result.operations) expect(wireProblems(operation, shapes), JSON.stringify(operation)).toEqual([]);
    // the golden
    const record = {
      task: entry.expect.task,
      registry: entry.expect.registry,
      ok: first.result.ok,
      pack: first.result.pack,
      summary: first.result.summary,
      operations: first.result.operations,
      sources: first.result.sources,
      diagnostics: first.result.diagnostics,
      stats: first.result.stats,
    };
    const text = `${JSON.stringify(record, null, 2)}\n`;
    expect(text).toBe(goldenText(join(GOLDEN_DIR, 'ops', `${entry.stem}.json`), text));
    // determinism: the same program against the same reads is the same bytes
    const second = await run({ surface: surfaceOfRegistry(entry.expect.registry), program: entry.program, world: entry.world });
    expect(JSON.stringify(second.result)).toBe(JSON.stringify(first.result));
  });

  it('records a golden for each passing program and no other', () => {
    const goldens = readdirSync(join(GOLDEN_DIR, 'ops')).filter((name) => name.endsWith('.json')).sort();
    expect(goldens).toEqual(passing.map((entry) => `${entry.stem}.json`).sort());
  });
});

describe('the wire shape the core stages is the plugin parser\'s', () => {
  it('reads the operation kinds from TransactionOps and finds the nine of the transaction', () => {
    expect([...wireShapes().keys()].sort()).toEqual(['create', 'delete', 'detachTemplate', 'duplicate', 'guiEdit', 'logicEdit', 'move', 'saveAsTemplate', 'setFields']);
  });

  it('refuses a field the parser does not read, and a missing required one', () => {
    expect(wireProblems({ kind: 'setFields', path: 'items/a.yml', edits: [], nonsense: 1 })).toEqual(['setFields: the wire reads no "nonsense"']);
    expect(wireProblems({ kind: 'delete' })).toEqual(['delete: the wire requires "path"']);
  });
});

describe('every kind of operation, and what it stages', () => {
  const world: World = {
    packs: ['bench'],
    builtin: ['materione'],
    resources: {
      bench: [
        { type: 'items', path: 'items/sword.yml', id: 'sword' },
        { type: 'items', path: 'items/shield.yml', id: 'shield' },
        { type: 'guis', path: 'guis/menu.yml', id: 'menu' },
      ],
      materione: [{ type: 'guis', path: 'guis/backpack.yml', id: 'backpack', pack: 'materione' }],
    },
    documents: {
      'bench/items/sword.yml': { rev: 3, parsed: { sword: { material: 'IRON_SWORD', behavior: [{ on: 'click', do: ['cancel'] }, { on: 'break', do: ['cancel'] }] } } },
      'bench/items/shield.yml': { rev: 1, parsed: { shield: { material: 'SHIELD' } } },
      'bench/guis/menu.yml': { rev: 7, parsed: { menu: { container: 'chest_3' } } },
    },
    recipes: {
      cooldown_gate: { rules: [{ on: 'gui.click', layer: 'x', do: ['cancel'] }, { on: 'gui.click', layer: 'y', do: ['cancel'] }], warnings: [{ message: 'check the variable' }] },
    },
  };
  const go = (program: string, w: World = world) => run({ surface, program, world: w });

  it('create: one operation with the directory, the id, and one edit per field in the program\'s order', async () => {
    const { result } = await go(`const p = m.pack("bench");
      p.items.create("ruby", { material: "STICK", display_name: m.mm("<red>Ruby") }, { folder: "gems" });
      p.items.create("plain", {});`);
    expect(result.ok).toBe(true);
    expect(result.operations).toEqual([
      { kind: 'create', type: 'items', id: 'ruby', folder: 'gems', edits: [{ field: ['ruby', 'material'], value: 'STICK' }, { field: ['ruby', 'display_name'], value: '<red>Ruby' }] },
      { kind: 'create', type: 'items', id: 'plain' },
    ]);
    expect(result.sources.map((s) => s?.line)).toEqual([2, 3]);
  });

  it('set, unset and rules on a created resource address its path, with no revision', async () => {
    const { result } = await go(`const p = m.pack("bench");
      const a = p.items.create("axe", { material: "IRON_AXE" }, { folder: "tools" });
      a.set({ material: "DIAMOND_AXE" });
      a.unset("display_name");
      a.rules.add(m.triggers.click(), () => ({ do: [m.actions.cancel()] }));
      a.rules.add(m.triggers.click({ detail: "right" }), () => ({ cooldown: 100, do: [m.actions.message("hi")] }));`);
    expect(errorsOf(result)).toEqual([]);
    expect(result.operations.slice(1)).toEqual([
      { kind: 'setFields', path: 'items/tools/axe.yml', edits: [{ field: ['axe', 'material'], value: 'DIAMOND_AXE' }] },
      { kind: 'setFields', path: 'items/tools/axe.yml', edits: [{ field: ['axe', 'display_name'], remove: true }] },
      { kind: 'logicEdit', path: 'items/tools/axe.yml', edits: [{ op: 'addRule', index: 0, rule: { on: 'click', do: ['cancel'] } }] },
      { kind: 'logicEdit', path: 'items/tools/axe.yml', edits: [{ op: 'addRule', index: 1, rule: { on: 'click:right', cooldown: 100, do: [{ message: 'hi' }] } }] },
    ]);
  });

  it('an existing resource is edited through m.edit: the stored revision on its first operation, the rule index after the rules it has', async () => {
    const { result, calls } = await go(`const sword = m.edit(m.ref.item("bench:sword"));
      sword.set({ display_name: m.mm("Sword") });
      sword.rules.add(m.triggers.click(), () => ({ do: [m.actions.cancel()] }));`);
    expect(errorsOf(result)).toEqual([]);
    expect(result.operations).toEqual([
      { kind: 'setFields', path: 'items/sword.yml', rev: 3, edits: [{ field: ['sword', 'display_name'], value: 'Sword' }] },
      { kind: 'logicEdit', path: 'items/sword.yml', rev: 3, edits: [{ op: 'addRule', index: 2, rule: { on: 'click', do: ['cancel'] } }] },
    ]);
    expect(result.pack).toBe('bench');
    // one catalog read to resolve the id, one read of the document: reads are cached inside the run
    expect(calls.filter((c) => c.op === 'browse.get')).toHaveLength(1);
  });

  it('duplicate, delete, detachTemplate and saveAsTemplate map onto their operation kinds', async () => {
    const { result } = await go(`const shield = m.edit(m.ref.item("bench:shield"));
      const copy = shield.duplicate("shield_two", { fields: { material: "STICK" } });
      copy.set({ material: "BONE" });
      m.edit(m.ref.item("bench:sword")).delete();
      m.edit(m.ref.gui("bench:menu")).detachTemplate();
      shield.saveAsTemplate("shields", ["material"]);`);
    expect(errorsOf(result)).toEqual([]);
    expect(result.operations).toEqual([
      { kind: 'duplicate', fromPath: 'items/shield.yml', rev: 1, id: 'shield_two', edits: [{ field: ['shield_two', 'material'], value: 'STICK' }] },
      { kind: 'setFields', path: 'items/shield_two.yml', edits: [{ field: ['shield_two', 'material'], value: 'BONE' }] },
      { kind: 'delete', path: 'items/sword.yml', rev: 3 },
      { kind: 'detachTemplate', path: 'guis/menu.yml', rev: 7, resource: 'menu' },
      { kind: 'saveAsTemplate', fromPath: 'items/shield.yml', rev: 1, resource: 'shield', templateId: 'shields', fields: ['material'] },
    ]);
    const shapes = wireShapes();
    for (const operation of result.operations) expect(wireProblems(operation, shapes)).toEqual([]);
  });

  it('a recipe is expanded by the server (a read) and staged as one addRule per rule', async () => {
    const { result, calls } = await go(`const menu = m.edit(m.ref.gui("bench:menu"));
      menu.rules.instantiate(m.recipes.cooldownGate({ variable: "bench:daily", seconds: 60, message: "wait" }));`);
    expect(result.diagnostics.map((d) => d.code)).toEqual(['SDK-0017']);
    expect(result.operations).toEqual([
      { kind: 'logicEdit', path: 'guis/menu.yml', rev: 7, edits: [
        { op: 'addRule', index: 0, rule: { on: 'gui.click', layer: 'x', do: ['cancel'] } },
        { op: 'addRule', index: 1, rule: { on: 'gui.click', layer: 'y', do: ['cancel'] } },
      ] },
    ]);
    expect(calls.find((c) => c.op === 'logic.instantiate')?.payload).toEqual({ recipe: 'cooldown_gate', params: { variable: 'bench:daily', seconds: 60, message: 'wait' } });
  });

  it('an unknown recipe is the server\'s refusal, structured', async () => {
    const { result } = await go(`m.edit(m.ref.gui("bench:menu")).rules.instantiate(m.recipes.cooldownGate({ variable: "a:b", seconds: 1, message: "x" }));`, { ...world, recipes: {} });
    expect(result.ok).toBe(false);
    expect(result.diagnostics[0]).toMatchObject({ code: 'SDK-0010', serverCode: 'MTR-0008' });
    expect(result.operations).toEqual([]);
  });

  it('serializes steps, conditions, expressions and references the way a document writes them', async () => {
    const { result } = await go(`const p = m.pack("bench");
      const v = p.variables.create("coins", { scope: "player", type: "number" });
      const g = p.guis.create("shop", { container: "chest_3", title: "Shop", layers: [m.guiComponents.button({ id: "buy", x: 1, y: 2, width: 3, height: 4 })] });
      g.rules.add(m.triggers.guiClick({ layer: "buy", click: "left" }), (ctx) => ({
        id: "buy_rule",
        if: m.expr\`\${ctx.player.sneaking} && \${m.fn.max(1, 2)} > 0 && \${v} == 'x'\`,
        do: [
          m.actions.takeMoney(5),
          m.actions.giveItem({ item: "minecraft:stick", amount: 2, delay: 10 }),
          m.actions.cancel(),
          m.actions.cancel({}, { chance: 0.5 }),
          m.steps.if(m.conditions.hasItemSpace(1), { then: [m.steps.wait(20)], else: [m.steps.stop({ cancel: true })] }),
          m.steps.require([m.actions.takeMoney(1)], { else: [m.steps.call("again", { n: 1 })] }),
          m.steps.choose([{ if: m.expr\`1 > 0\`, then: [m.actions.cancel()] }], [m.steps.stop()]),
        ],
      }));`);
    expect(errorsOf(result)).toEqual([]);
    const rule = (result.operations[2] as unknown as { edits: { rule: unknown }[] }).edits[0]?.rule;
    expect(rule).toEqual({
      on: 'gui.click',
      layer: 'buy',
      click: 'left',
      id: 'buy_rule',
      if: "${player.sneaking && max(1, 2) > 0 && bench:coins == 'x'}",
      do: [
        { take_money: 5 },
        { give_item: { item: 'minecraft:stick', amount: 2 }, delay: 10 },
        'cancel',
        { cancel: {}, chance: 0.5 },
        { if: { has_item_space: 1 }, then: [{ wait: 20 }], else: [{ stop: { cancel: true } }] },
        { require: [{ take_money: 1 }], else: [{ call: 'again', with: { n: 1 } }] },
        { choose: [{ if: '${1 > 0}', then: ['cancel'] }], else: ['stop'] },
      ],
    });
  });

  it('a context value interpolates as its path, and a wildcard family answers any name', async () => {
    const { result } = await go(`const g = m.pack("bench").guis.create("ctx", { container: "chest_1", title: "t" });
      g.rules.add(m.triggers.guiClick(), (ctx) => ({ if: m.expr\`\${ctx.player.name} == \${ctx.gui.args.category}\`, do: [m.actions.cancel()] }));`);
    expect(errorsOf(result)).toEqual([]);
    const rule = (result.operations[1] as unknown as { edits: { rule: { if: string } }[] }).edits[0]?.rule;
    expect(rule?.if).toBe('${player.name == gui.args.category}');
  });

  it('m.docs answers from the registry\'s own text, in the program', async () => {
    const { result } = await go(`return {
      type: m.docs.type("item").directory,
      has: m.docs.type("item").fields.some((f) => f.id === "material"),
      found: m.docs.search("take money from the player").map((e) => e.kind + " " + e.member),
    };`);
    expect(errorsOf(result)).toEqual([]);
    expect(result.returned).toMatchObject({ type: 'items', has: true });
    expect((result.returned as { found: string[] }).found).toContain('m.actions takeMoney');
  });

  it('m.read reads the stored state: packs, a catalog page, one document, references and history', async () => {
    const { result } = await go(`
      const packs = m.read.packs().map((p) => p.id + (p.builtin ? "*" : ""));
      const rows = m.read.resources({ pack: "bench", type: "item" }).map((r) => r.id);
      const view = m.read.get(m.ref.item("bench:sword"));
      return { packs, rows, rev: view.revision, material: view.fields.material, refs: m.read.references(m.ref.item("bench:sword")).hits.length, complete: m.read.references(m.ref.item("bench:sword")).coverage.complete };`, {
      ...world,
      references: { 'bench:sword': [{ pack: 'bench', type: 'guis', path: 'guis/menu.yml', id: 'menu', field: ['layers'], value: 'bench:sword' }] },
    });
    expect(errorsOf(result)).toEqual([]);
    expect(result.returned).toEqual({ packs: ['bench', 'materione*'], rows: ['sword', 'shield'], rev: 3, material: 'IRON_SWORD', refs: 1, complete: false });
  });
});

describe('errors a program can cause are structured, never thrown strings', () => {
  const world: World = { packs: ['bench', 'other'], builtin: ['materione'], resources: { bench: [{ type: 'items', path: 'items/sword.yml', id: 'sword' }, { type: 'items', path: 'items/swordfish.yml', id: 'swordfish' }] }, documents: { 'bench/items/sword.yml': { rev: 1, parsed: { sword: {} } } } };
  const go = (program: string, limits = {}) => run({ surface, program, world, limits });

  it('an unknown id from a read names the nearest ids and stages nothing', async () => {
    const { result } = await go(`const p = m.pack("bench"); p.items.create("fresh", {}); m.ref.item("bench:sord");`);
    expect(result.ok).toBe(false);
    expect(result.operations).toEqual([]);
    expect(result.diagnostics).toHaveLength(1);
    expect(errorsOf(result)[0]).toMatchObject({ code: 'SDK-0007', severity: 'error', source: { line: 1 } });
    expect(errorsOf(result)[0]?.hint).toContain('bench:sword');
  });

  it('a second pack is a diagnostic: one program is one transaction in one pack', async () => {
    const { result } = await go(`m.pack("bench").items.create("one", {});\nm.pack("other").items.create("two", {});`);
    expect(errorsOf(result)[0]).toMatchObject({ code: 'SDK-0004', source: { line: 2 } });
    expect(errorsOf(result)[0]?.hint).toContain('split');
  });

  it('a built-in pack and a pack that does not exist are refused when staged into', async () => {
    const built = await go(`m.pack("materione").items.create("hack", {});`);
    expect(errorsOf(built.result)[0]).toMatchObject({ code: 'SDK-0008' });
    expect(errorsOf(built.result)[0]?.message).toContain('built in');
    const none = await go(`m.pack("bnech").items.create("x1", {});`);
    expect(errorsOf(none.result)[0]?.hint).toContain('bench');
  });

  it('a continuation that outlives finish() is refused with SDK-0003 and does not change the result', async () => {
    const vm = await import('node:vm');
    const { bundleCore, fakePort, transpile } = await import('./helpers/sdk-runner');
    const context = vm.createContext({});
    vm.runInContext(await bundleCore(), context, { filename: 'core.js' });
    const port = fakePort(world);
    (context as Record<string, unknown>).__port = { read: (op: string, payload: Record<string, unknown>) => port.read(op, JSON.parse(JSON.stringify(payload))) };
    (context as Record<string, unknown>).__surface = JSON.stringify(surface);
    vm.runInContext(`var s = MaterioneCore.createSession({ surface: JSON.parse(__surface), port: __port, programFile: 'program.js' }); var m = s.m;`, context);
    vm.runInContext(transpile('const p = m.pack("bench"); var h = p.items.create("early", {});'), context, { filename: 'program.js' });
    const before = vm.runInContext('JSON.stringify(s.finish({}))', context) as string;
    expect(JSON.parse(before).operations).toHaveLength(1);
    const thrown = vm.runInContext('(function () { try { h.set({ material: "STICK" }); return null; } catch (e) { return { name: e.name, code: e.diagnostic.code }; } })()', context);
    expect(thrown).toEqual({ name: 'SdkError', code: 'SDK-0003' });
    expect(JSON.parse(vm.runInContext('JSON.stringify(s.late())', context) as string)[0]).toMatchObject({ code: 'SDK-0003', severity: 'error' });
    expect(vm.runInContext('JSON.stringify(s.finish({}))', context)).toBe(before);
  });

  it('exceeding a cap ends the run: operations, edits in one operation, documents, reads', async () => {
    const ops = await go(`const p = m.pack("bench"); for (let i = 0; i < 5; i++) p.items.create("item_" + i, {});`, { operations: 3 });
    expect(errorsOf(ops.result)[0]).toMatchObject({ code: 'SDK-0005', source: { line: 1 } });
    expect(errorsOf(ops.result)[0]?.message).toContain('3 operations');
    expect(ops.result.operations).toEqual([]);
    const edits = await go(`const p = m.pack("bench"); const h = p.items.create("wide", {}); h.set({ a: 1, b: 2, c: 3 });`, { editsPerOperation: 2 });
    expect(errorsOf(edits.result)[0]).toMatchObject({ code: 'SDK-0005' });
    const docs = await go(`const p = m.pack("bench"); for (let i = 0; i < 5; i++) p.items.create("item_" + i, {});`, { documents: 2 });
    expect(errorsOf(docs.result)[0]).toMatchObject({ code: 'SDK-0005' });
    expect(errorsOf(docs.result)[0]?.message).toContain('2 documents');
    const reads = await go(`for (let i = 0; i < 5; i++) m.read.resources({ pack: "bench", query: "q" + i });`, { reads: 2 });
    expect(errorsOf(reads.result)[0]).toMatchObject({ code: 'SDK-0005' });
    expect(errorsOf(reads.result)[0]?.message).toContain('2 reads');
  });

  it('a program that catches the error does not hide it', async () => {
    const { result } = await go(`try { m.ref.item("bench:nope"); } catch (e) { /* swallowed */ }\nm.pack("bench").items.create("fine", {});`);
    expect(result.ok).toBe(false);
    expect(errorsOf(result)[0]).toMatchObject({ code: 'SDK-0007' });
    expect(result.operations).toEqual([]);
  });

  it('anything the program throws is one structured diagnostic with its program line', async () => {
    const text = await go(`m.pack("bench").items.create("a1", {});\nthrow "plain string";`);
    expect(text.result.diagnostics).toEqual([expect.objectContaining({ code: 'SDK-0001', message: 'plain string' })]);
    const error = await go(`m.pack("bench").items.create("a1", {});\n\nnull.boom;`);
    expect(errorsOf(error.result)[0]).toMatchObject({ code: 'SDK-0001', source: { line: 3 } });
    expect(errorsOf(error.result)[0]?.message).toContain('TypeError');
    expect(error.result.operations).toEqual([]);
  });

  it('m.fail ends the run with the program\'s own message and no operations', async () => {
    const { result } = await go(`m.pack("bench").items.create("a1", {}); m.fail("the owner has not uploaded the texture");`);
    expect(result.diagnostics).toEqual([expect.objectContaining({ code: 'SDK-0002', message: 'the owner has not uploaded the texture' })]);
    expect(result.operations).toEqual([]);
  });

  it('values a document cannot hold, and invalid arguments, are SDK-0009 and SDK-0012', async () => {
    const fn = await go(`m.pack("bench").items.create("a1", { material: () => 1 });`);
    expect(errorsOf(fn.result)[0]).toMatchObject({ code: 'SDK-0009' });
    expect(errorsOf(fn.result)[0]?.message).toContain('material');
    const nan = await go(`m.pack("bench").items.create("a1", { durability: NaN });`);
    expect(errorsOf(nan.result)[0]).toMatchObject({ code: 'SDK-0009' });
    const id = await go(`m.pack("bench").items.create("Bad Id", {});`);
    expect(errorsOf(id.result)[0]).toMatchObject({ code: 'SDK-0012' });
    const key = await go(`m.actions.takeMoney({ amount: 1, nonsense: true });`);
    expect(errorsOf(key.result)[0]).toMatchObject({ code: 'SDK-0012' });
    expect(errorsOf(key.result)[0]?.message).toContain('nonsense');
    const twice = await go(`const p = m.pack("bench"); p.items.create("dup1", {}); p.items.create("dup1", {});`);
    expect(errorsOf(twice.result)[0]).toMatchObject({ code: 'SDK-0013' });
    const gone = await go(`const p = m.pack("bench"); p.items.create("made", {}).delete();`);
    expect(errorsOf(gone.result)[0]).toMatchObject({ code: 'SDK-0012' });
    const arity = await go(`m.fn.now(1);`);
    expect(errorsOf(arity.result)[0]).toMatchObject({ code: 'SDK-0012' });
    const wrongHost = await go(`m.pack("bench").items.create("host1", {}).rules.add(m.triggers.guiClick(), () => ({ do: [] }));`);
    expect(errorsOf(wrongHost.result)[0]).toMatchObject({ code: 'SDK-0012' });
    expect(errorsOf(wrongHost.result)[0]?.message).toContain('not fired by item');
  });

  it('a missing asset is SDK-0011 with the nearest assets, found when the run ends', async () => {
    const { result } = await run({
      surface,
      world: { packs: ['bench'], assets: { bench: ['assets/textures/item/golden_berry.png'] } },
      program: `const p = m.pack("bench");\np.items.create("berry", { material: "SWEET_BERRIES", texture: m.ref.asset("textures/item/golden_bery.png") });`,
    });
    expect(errorsOf(result)[0]).toMatchObject({ code: 'SDK-0011', source: { line: 2 } });
    expect(errorsOf(result)[0]?.hint).toContain('golden_berry');
    expect(result.operations).toEqual([]);
  });

  it('warnings do not end the run: unavailable members and malformed formatted text', async () => {
    const { result } = await go(`const p = m.pack("bench"); p.items.create("warn", { display_name: m.mm("<red never closed") });`);
    expect(result.ok).toBe(true);
    expect(result.diagnostics).toEqual([expect.objectContaining({ code: 'SDK-0015', severity: 'warning' })]);
    expect(result.operations).toHaveLength(1);
  });

  it('logs, the summary and the return value are bounded', async () => {
    const logged = await go(`for (let i = 0; i < 400; i++) m.log("x".repeat(100)); return m.read.packs().length;`);
    expect(logged.result.ok).toBe(true);
    expect(logged.result.logs.length).toBeLessThan(400);
    expect(logged.result.logs[logged.result.logs.length - 1]).toContain('truncated');
    expect(logged.result.returned).toBe(3);
    const big = await go(`return "x".repeat(40000);`);
    expect(errorsOf(big.result)[0]).toMatchObject({ code: 'SDK-0014' });
    const longSummary = await go(`m.summary("x".repeat(600));`);
    expect(errorsOf(longSummary.result)[0]).toMatchObject({ code: 'SDK-0005' });
  });

  it('every code the core can emit is in the table, and the table is documented in plugin.md', () => {
    const doc = readFileSync(join(__dirname, '..', '..', 'docs', 'architecture', 'plugin.md'), 'utf8');
    for (const code of Object.keys(SDK_CODES)) expect(doc, code).toContain(`\`${code}\``);
  });
});

describe('a bare context: no globals, no side channel', () => {
  it('the bundle runs where nothing but the language exists, and nothing leaks into it', async () => {
    const context = await bareContext();
    const vm = await import('node:vm');
    const probe = vm.runInContext(
      `[typeof setTimeout, typeof setInterval, typeof fetch, typeof XMLHttpRequest, typeof process, typeof require, typeof document, typeof window, typeof localStorage, typeof postMessage, typeof importScripts, typeof Buffer, typeof TextEncoder, typeof queueMicrotask].join(",")`,
      context,
    );
    expect(probe).toBe(Array(14).fill('undefined').join(','));
  });

  it('is plain ES2020 with no module syntax left in it', async () => {
    const { bundleCore } = await import('./helpers/sdk-runner');
    const code = await bundleCore();
    expect(code).not.toMatch(/^\s*(import|export)\s/m);
    // (the step builder named `require` is a property, not a module load)
    expect(code).not.toMatch(/[^.\w]require\(\s*["'`]/);
    expect(code).not.toMatch(/\?\?=|\|\|=|&&=/);
  });
});

describe('an addon registration is usable with no change to the core (D5)', () => {
  const program = `
    const p = m.pack("addon");
    const reward = p.items.create("trophy", { material: "GOLD_INGOT" });
    p.quests.create("first", { title: "First steps", reward });
    reward.rules.add(m.triggers.ping({ strength: 3 }), (ctx) => ({
      if: m.expr\`\${ctx.ping.strength} > 5\`,
      do: [m.actions.greet({ who: "\${player.name}", loud: true }), m.actions.greet("again")],
    }));`;

  it('stages the addon\'s content type, trigger and action', async () => {
    const { result } = await run({ surface: addonSurface, program, world: { packs: ['addon'] } });
    expect(errorsOf(result)).toEqual([]);
    expect(result.operations).toEqual([
      { kind: 'create', type: 'items', id: 'trophy', edits: [{ field: ['trophy', 'material'], value: 'GOLD_INGOT' }] },
      { kind: 'create', type: 'quests', id: 'first', edits: [{ field: ['first', 'title'], value: 'First steps' }, { field: ['first', 'reward'], value: 'addon:trophy' }] },
      { kind: 'logicEdit', path: 'items/trophy.yml', edits: [{ op: 'addRule', index: 0, rule: {
        on: 'example:ping',
        strength: 3,
        if: '${ping.strength > 5}',
        do: [{ 'example:greet': { who: '${player.name}', loud: true } }, { 'example:greet': 'again' }],
      } }] },
    ]);
  });

  it('is absent without the registration, as a runtime diagnostic and not a crash', async () => {
    const { result } = await run({ surface, program, world: { packs: ['addon'] } });
    expect(result.ok).toBe(false);
    expect(result.diagnostics[0]).toMatchObject({ code: 'SDK-0001' });
  });
});

describe('a jar without a capability has a smaller surface, at run time as in the types', () => {
  const lacking = (...names: string[]) => ({ ...surface, capabilities: (surface.capabilities ?? []).filter((name) => !names.includes(name)) });

  it('without logic a handle has no rules and a recipe cannot be instantiated', async () => {
    const { result } = await run({ surface: lacking('logic'), program: `const h = m.pack("bench").items.create("nr", {}); return { rules: typeof h.rules };`, world: { packs: ['bench'] } });
    expect(result.returned).toEqual({ rules: 'undefined' });
  });

  it('without the transaction capability the pack has no collections and m.edit says why', async () => {
    const { result } = await run({ surface: lacking('codemode'), program: `return { items: typeof m.pack("bench").items, hasCap: m.hasCapability("codemode") };`, world: { packs: ['bench'] } });
    expect(result.returned).toEqual({ items: 'undefined', hasCap: false });
    const edit = await run({ surface: lacking('codemode'), program: `m.edit(m.ref.item("bench:a1"));`, world: { packs: ['bench'], resources: { bench: [{ type: 'items', path: 'items/a1.yml', id: 'a1' }] } } });
    expect(edit.result.diagnostics[0]).toMatchObject({ code: 'SDK-0006' });
  });

  it('a jar that predates the capability list is assumed to have everything', async () => {
    const { result } = await run({ surface: { ...surface, capabilities: null }, program: `return m.hasCapability("anything");`, world: { packs: ['bench'] } });
    expect(result.returned).toBe(true);
  });
});

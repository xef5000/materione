/**
 * Programs against the generated surface, compiled by the real TypeScript checker (codemode.md section
 * 5.1, 9.2, 11). The corpus under fixtures/codemode/corpus/ holds the benchmark programs (agent-benchmark.md
 * section 3) with their expected result: a task that passes type-checks cleanly, and a task that needs a
 * primitive the server lacks fails with the diagnostic that names the gap.
 *
 * The surface checked against is the committed golden `.d.ts`, so this test reads exactly what the
 * generator's golden test pins.
 */
import { describe, expect, it } from 'vitest';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { generateSdk } from '../src/lib/codemode/generate/generate';
import { CORPUS_DIR, FIXTURES, GOLDEN_DIR, REGISTRY_DIR, surfaceOf, type RegistrySnapshot } from './helpers/codemode';
import { checkDeclarations, checkProgram, type Problem } from './helpers/typecheck';

const golden = (name: string): string => readFileSync(join(GOLDEN_DIR, `${name}.d.ts`), 'utf8');
const current = golden('plugin-current');
const withAddon = golden('with-addon');

const RELEASED_PROTOCOL_DIR = join(FIXTURES, 'protocol', 'released');
const RELEASED_REGISTRY_DIR = join(REGISTRY_DIR, 'released');

interface ReleasedManifest {
  version: string;
  capabilities?: string[];
}

function discoverRegistrySnapshots(): { name: string; sdk: string; capabilities: string[] }[] {
  const out: { name: string; sdk: string; capabilities: string[] }[] = [];
  for (const file of readdirSync(REGISTRY_DIR).filter((n) => n.endsWith('.json')).sort()) {
    const stem = file.replace(/\.json$/, '');
    const snap = JSON.parse(readFileSync(join(REGISTRY_DIR, file), 'utf8')) as RegistrySnapshot;
    const sdk = stem === 'plugin-current' || stem === 'with-addon' ? golden(stem) : generateSdk(surfaceOf(snap));
    out.push({ name: stem, sdk, capabilities: snap.capabilities ?? [] });
  }
  if (existsSync(RELEASED_REGISTRY_DIR)) {
    for (const file of readdirSync(RELEASED_REGISTRY_DIR).filter((n) => n.endsWith('.json')).sort()) {
      const snap = JSON.parse(readFileSync(join(RELEASED_REGISTRY_DIR, file), 'utf8')) as RegistrySnapshot;
      out.push({ name: `released/${file.replace(/\.json$/, '')}`, sdk: generateSdk(surfaceOf(snap)), capabilities: snap.capabilities ?? [] });
    }
  }
  return out;
}

const allSnapshots = discoverRegistrySnapshots();

interface Expectation {
  task: string;
  kind: 'task' | 'followup' | 'corpus';
  title: string;
  registry: string;
  result: 'pass' | 'fail';
  gaps?: string[];
  note?: string;
  diagnostics?: { code: number; line?: number; gap?: string; messageContains?: string[] }[];
}

const corpus = readdirSync(CORPUS_DIR)
  .filter((name) => name.endsWith('.ts'))
  .sort()
  .map((name) => ({
    name,
    program: readFileSync(join(CORPUS_DIR, name), 'utf8'),
    expect: JSON.parse(readFileSync(join(CORPUS_DIR, name.replace(/\.ts$/, '.expect.json')), 'utf8')) as Expectation,
  }));

function show(problems: readonly Problem[]): string {
  return problems.map((problem) => `  line ${problem.line}: TS${problem.code} ${problem.message}`).join('\n');
}

/** The codes of the diagnostics a program draws. */
function codes(program: string, sdk = current): number[] {
  return checkProgram(program, sdk).map((problem) => problem.code);
}

describe('the generated declarations', () => {
  it.each(allSnapshots.map((s) => [s.name, s.sdk] as const))('%s compiles with the core, nothing skipped', (_name, sdk) => {
    const problems = checkDeclarations(sdk);
    expect(show(problems)).toBe('');
  });

  it('released plugin manifests advertising codemode have a recorded registry snapshot (codemode.md section 7)', () => {
    const manifests = readdirSync(RELEASED_PROTOCOL_DIR)
      .filter((name) => name.endsWith('.json'))
      .sort()
      .map((name) => JSON.parse(readFileSync(join(RELEASED_PROTOCOL_DIR, name), 'utf8')) as ReleasedManifest);
    expect(manifests.length).toBeGreaterThan(0);
    for (const manifest of manifests) {
      const hasCodemode = (manifest.capabilities ?? []).includes('codemode');
      if (manifest.version === '0.1.0') expect(hasCodemode).toBe(false);
      if (hasCodemode) {
        expect(existsSync(join(RELEASED_REGISTRY_DIR, `${manifest.version}.json`)), `missing released registry snapshot for ${manifest.version}`).toBe(true);
      }
    }
  });
});

describe('the program corpus', () => {
  it('holds all 29 benchmark tasks, 5 follow-ups, and 3 corpus programs', () => {
    const tasks = corpus.filter((e) => e.expect.kind === 'task').map((e) => e.expect.task);
    const followups = corpus.filter((e) => e.expect.kind === 'followup').map((e) => e.expect.task);
    const extra = corpus.filter((e) => e.expect.kind === 'corpus').map((e) => e.expect.task);
    expect(tasks).toEqual(Array.from({ length: 29 }, (_, i) => `T${String(i + 1).padStart(2, '0')}`));
    expect(followups).toEqual(['F1', 'F2', 'F3', 'F4', 'F5']);
    expect(extra).toEqual(['C01', 'C02', 'C03']);
  });

  it.each(corpus.map((entry) => [entry.expect.task + ' ' + entry.expect.title, entry] as const))('%s', (_label, entry) => {
    const targets = allSnapshots.filter((s) => {
      if (entry.expect.registry === 'with-addon') return s.name === 'with-addon';
      if (s.name === 'plugin-current' || s.name === 'with-addon') return true;
      return s.capabilities.includes('codemode');
    });
    expect(targets.length).toBeGreaterThan(0);
    for (const target of targets) {
      const problems = checkProgram(entry.program, target.sdk);
      if (entry.expect.result === 'pass') {
        expect(show(problems), target.name).toBe('');
        continue;
      }
      const wantedList = entry.expect.diagnostics ?? [];
      expect(problems.length, `${target.name}: unexpected diagnostic count:\n${show(problems)}`).toBe(wantedList.length);
      for (const wanted of wantedList) {
        const hit = problems.find(
          (problem) =>
            problem.code === wanted.code &&
            (wanted.line === undefined || problem.line === wanted.line) &&
            (wanted.messageContains ?? []).every((part) => problem.message.includes(part)),
        );
        expect(hit, `${target.name}: expected TS${wanted.code} at line ${wanted.line} with ${JSON.stringify(wanted.messageContains)}, got:\n${show(problems)}`).toBeDefined();
      }
    }
  });
});

describe('rules are typed from their trigger (benchmark S1, S7)', () => {
  const pack = 'const p = m.pack("t");\n';
  const sword = 'const sword = p.items.create("s", { material: "DIAMOND_SWORD" });\n';
  const chair = 'const chair = p.furnitures.create("c", {});\n';

  it('gives the callback the context the trigger declares for that host', () => {
    expect(codes(`${pack}${sword}
      sword.rules.add(m.triggers.click({ detail: "right" }), (ctx) => ({
        if: m.expr\`\${ctx.player.sneaking} && \${ctx.player.health} < 6\`,
        do: [m.actions.message({ text: "careful", target: "player" })],
      }));`)).toEqual([]);
  });

  it('rejects a context variable the host does not fill', () => {
    // player.health is filled for an item, not for furniture (hosts of the context schema)
    const found = checkProgram(`${pack}${chair}
      chair.rules.add(m.triggers.click(), (ctx) => ({ if: m.expr\`\${ctx.player.health} < 3\`, do: [] }));`, current);
    expect(found.some((problem) => problem.code === 2339 && problem.message.includes('health'))).toBe(true);
  });

  it('types timer context from its audience filter', () => {
    const clock = `${pack}const clock = p.rules.create("clock", {});\n`;
    expect(codes(`${clock}
      clock.rules.add(m.triggers.every({ interval: "1s" }), (ctx) => ({
        if: m.expr\`\${ctx.player.name} != ""\`, do: [],
      }));`)).toContain(2339);
    expect(codes(`${clock}
      clock.rules.add(m.triggers.every({ interval: "1s", for: "server" }), (ctx) => ({
        if: m.expr\`\${ctx.player.name} != ""\`, do: [],
      }));`)).toContain(2339);
    expect(codes(`${clock}
      clock.rules.add(m.triggers.every({ interval: "1s", for: "each_player" }), (ctx) => ({
        if: m.expr\`\${ctx.player.name} != ""\`, do: [],
      }));`)).toEqual([]);
  });

  it('rejects a trigger no rule of that host can use', () => {
    // gui.click is fired by a GUI, not by an item
    expect(codes(`${pack}${sword}
      sword.rules.add(m.triggers.guiClick({ layer: "x" }), () => ({ do: [] }));`).length).toBeGreaterThan(0);
  });

  it('rejects a filter value the schema does not list', () => {
    expect(codes(`${pack}${sword}
      sword.rules.add(m.triggers.click({ detail: "middle" }), () => ({ do: [] }));`)).toContain(2322);
  });

  it('types expression functions, and a function the server lacks is a type error', () => {
    expect(codes(`const e = m.expr\`\${m.fn.max(1, 2, 3)} + \${m.fn.round(2.5)}\`;`)).toEqual([]);
    expect(codes('const e = m.fn.sqrt(4);')).toContain(2339);
    // the clock functions take no argument: now() is typed as a call with none
    expect(codes('const e = m.expr`${m.fn.now()} - 5`;')).toEqual([]);
    expect(codes('const e = m.fn.now(1);')).toContain(2554);
  });

  it('rejects a rule option the registry does not list, accepts one it does', () => {
    expect(codes(`${pack}${sword}
      sword.rules.add(m.triggers.click(), () => ({ cooldown: 1000, do: [] }));`)).toEqual([]);
    expect(codes(`${pack}${sword}
      sword.rules.add(m.triggers.click(), () => ({ onCooldown: [], do: [] }));`).length).toBeGreaterThan(0);
  });
});

describe('the structural steps follow the engine grammar (logic.md section 2.2)', () => {
  const pack = 'const p = m.pack("t");\nconst sword = p.items.create("s", { material: "DIAMOND_SWORD" });\n';
  const rule = (body: string): string => `${pack}sword.rules.add(m.triggers.click(), () => ({ do: [${body}] }));`;

  it('takes steps in a require, and a condition there is an error', () => {
    expect(codes(rule('m.steps.require([m.actions.takeMoney(5), m.actions.giveItem("DIAMOND")], { else: [m.actions.message("no")] })'))).toEqual([]);
    expect(codes(rule('m.steps.require([m.conditions.hasMoney(5)], { else: [] })')).length).toBeGreaterThan(0);
    expect(codes(rule('m.steps.require([m.expr`1 > 0`])')).length).toBeGreaterThan(0);
    expect(codes(rule('m.steps.require(m.conditions.hasMoney(5))')).length).toBeGreaterThan(0);
  });

  it('guards with a condition in an if, which needs a branch', () => {
    expect(codes(rule('m.steps.if(m.conditions.hasItemSpace(1), { else: [m.steps.stop()] })'))).toEqual([]);
    expect(codes(rule('m.steps.if(m.conditions.hasItemSpace(1), { then: [m.actions.cancel()] })'))).toEqual([]);
    expect(codes(rule('m.steps.if(m.conditions.hasItemSpace(1), {})')).length).toBeGreaterThan(0);
    expect(codes(rule('m.steps.if(m.actions.cancel(), { then: [] })')).length).toBeGreaterThan(0);
  });

  it('types choose, wait, stop and call as the engine reads them', () => {
    expect(codes(rule('m.steps.choose([{ if: m.expr`1 > 0`, then: [m.actions.cancel()] }], { else: [m.steps.stop({ cancel: true })] })'))).toEqual([]);
    // a list of branches needs no tuple cast (benchmark S19); that it is not empty is checked when the program runs
    expect(codes(rule('m.steps.choose([1, 2].map((n) => ({ if: m.expr`vars.roll <= ${n}`, then: [m.actions.cancel()] })))'))).toEqual([]);
    expect(codes(rule('m.steps.choose([{ if: m.expr`1 > 0` }])')).length).toBeGreaterThan(0);
    expect(codes(rule('m.steps.wait(20), m.steps.call("purchase", { with: { price: 250 } })'))).toEqual([]);
    expect(codes(rule('m.steps.stop({ cancel: "yes" })')).length).toBeGreaterThan(0);
  });
});

describe('what self-description says beyond a value type is typed (issue 355)', () => {
  const pack = 'const p = m.pack("bench"); ';
  const rule = (steps: string): string => `${pack}const s = p.items.create("s", { material: "STICK" });
    s.rules.add(m.triggers.click({ detail: "right" }), () => ({ do: [${steps}] }));`;

  it('a parameter that accepts an expression takes m.expr and an m.fn result, and one that does not refuses them', () => {
    expect(codes(rule('m.actions.setVar({ var: "roll", value: m.expr`${m.fn.random(1, 100)}` })'))).toEqual([]);
    expect(codes(rule('m.actions.giveItem({ item: "minecraft:stone", amount: m.fn.random(1, 3) })'))).toEqual([]);
    expect(codes(rule('m.actions.setVar({ var: "roll", op: m.expr`1` })')).length).toBeGreaterThan(0);
  });

  it('a trigger filter takes one value or a list of values', () => {
    const gui = `${pack}const g = p.guis.create("g", { container: "chest_3", title: "t" }); `;
    expect(codes(`${gui}g.rules.add(m.triggers.guiClick({ layer: "buy", click: ["left", "shift_left"] }), () => ({ do: [] }));`)).toEqual([]);
    expect(codes(`${gui}g.rules.add(m.triggers.guiClick({ click: ["left", "sideways"] }), () => ({ do: [] }));`).length).toBeGreaterThan(0);
  });

  it('the composite conditions, the cooldown steps and the else of a guarding action are typed', () => {
    expect(codes(rule('m.steps.if(m.conditions.all([m.conditions.hasItemSpace(1), m.conditions.not(m.expr`player.sneaking`)]), { then: [] })'))).toEqual([]);
    expect(codes(rule('m.steps.if(m.conditions.atLeast({ count: 1, of: [m.expr`1 > 0`] }), { then: [] })'))).toEqual([]);
    expect(codes(rule('m.steps.if(m.conditions.all([m.actions.cancel()]), { then: [] })')).length).toBeGreaterThan(0);
    expect(codes(`${pack}const s = p.items.create("s", { material: "STICK" });
      s.rules.add(m.triggers.click(), () => ({ cooldown: 1000, do: [], on_cooldown: [m.actions.actionbar("wait")] }));`)).toEqual([]);
    expect(codes(rule('m.actions.custodyConsume({ slot: 1, else: [m.actions.cancel()] })'))).toEqual([]);
    expect(codes(rule('m.actions.message({ text: "hi", else: [m.actions.cancel()] })')).length).toBeGreaterThan(0);
  });
});

describe('references and fields are checked', () => {
  const pack = 'const p = m.pack("t");\n';

  it('rejects a reference of the wrong kind', () => {
    const wrong = `${pack}
      const menu = p.guis.create("menu", { container: "chest_1", title: "t" });
      p.items.create("i", { material: "STICK", jukebox_song: menu });`;
    expect(codes(wrong)).toContain(2322);
  });

  it('rejects an enum value that is not registered, and a missing required field', () => {
    // TypeScript words a near-miss string literal as TS2820 ("Did you mean ...?"), a far one as TS2322
    expect(codes(`${pack}p.guis.create("menu", { container: "chest_9", title: "t" });`).some((code) => code === 2322 || code === 2820)).toBe(true);
    expect(codes(`${pack}p.items.create("i", {});`)).toContain(2345);
  });

  it('wants formatted text through m.mm', () => {
    expect(codes(`${pack}p.items.create("i", { material: "STICK", display_name: "<red>plain" });`)).toContain(2322);
  });

  it('does not offer a field the schema does not describe', () => {
    expect(codes(`${pack}p.items.create("i", { material: "STICK", secret: 1 });`)).toContain(2353);
  });
});

describe('an addon registration is usable with no SDK code (D5, codemode.md section 3.6)', () => {
  const program = `
    const p = m.pack("addon");
    const reward = p.items.create("trophy", { material: "GOLD_INGOT" });
    p.quests.create("first", { title: "First steps", reward });
    reward.rules.add(m.triggers.ping({ strength: 3 }), (ctx) => ({
      if: m.expr\`\${ctx.ping.strength} > 5\`,
      do: [m.actions.greet({ who: "\${player.name}", loud: true }), m.actions.greet("again")],
    }));`;

  it('type-checks against the snapshot with the addon', () => {
    expect(show(checkProgram(program, withAddon))).toBe('');
  });

  it('is a type error against the snapshot without it', () => {
    const found = checkProgram(program, current);
    expect(found.some((problem) => problem.code === 2339 && problem.message.includes('quests'))).toBe(true);
    expect(found.some((problem) => problem.code === 2339 && problem.message.includes('greet'))).toBe(true);
    expect(found.some((problem) => problem.code === 2339 && problem.message.includes('ping'))).toBe(true);
  });

  it('types the addon trigger\'s context: a variable it does not declare is an error', () => {
    const found = checkProgram(`
      const p = m.pack("addon");
      const i = p.items.create("x", { material: "STICK" });
      i.rules.add(m.triggers.ping(), (ctx) => ({ if: m.expr\`\${ctx.ping.power}\`, do: [] }));`, withAddon);
    expect(found.some((problem) => problem.code === 2339 && problem.message.includes('power'))).toBe(true);
  });
});

describe('handles, editing and the read side are typed (issue 339)', () => {
  const pack = 'const p = m.pack("t");\n';

  it('types set, unset, duplicate, delete and the template calls by the content type\'s fields', () => {
    expect(codes(`${pack}
      const sword = p.items.create("s", { material: "DIAMOND_SWORD" });
      sword.set({ material: "IRON_SWORD", display_name: m.mm("<red>Sword") });
      sword.unset("display_name");
      const copy = sword.duplicate("s2", { folder: "x", fields: { material: "STICK" } });
      copy.rules.add(m.triggers.click(), () => ({ do: [] }));
      sword.saveAsTemplate("swords", ["material"]);`)).toEqual([]);
    expect(codes(`${pack}p.items.create("s", { material: "STICK" }).set({ secret: 1 });`)).toContain(2353);
    expect(codes(`${pack}p.items.create("s", { material: "STICK" }).set({ display_name: "plain" });`)).toContain(2322);
    expect(codes(`${pack}p.items.create("s", { material: "STICK" }).unset("nonsense");`)).toContain(2345);
    expect(codes(`${pack}p.items.create("s", { material: "STICK" }).saveAsTemplate("t");`)).toContain(2554);
  });

  it('edits an existing resource through its reference, typed by the reference\'s kind', () => {
    expect(codes('const sword = m.edit(m.ref.item("rpg:sword")); sword.set({ material: "STICK" }); sword.delete();')).toEqual([]);
    expect(codes('m.edit(m.ref.item("rpg:sword")).set({ container: "chest_1" });')).toContain(2353);
    expect(codes('m.edit(m.ref.asset("textures/x.png"));').length).toBeGreaterThan(0);
  });

  it('types the read side: every read is a promise, a stored resource a typed record, a catalog row carries its reference', () => {
    // checkProgram wraps the program as the type-check Worker does (typecheck/wrap.ts): the body of an async function
    expect(codes(`
      const view = await m.read.get(m.ref.item("rpg:sword"));
      const material: string | undefined = view.fields.material;
      const rows = (await m.read.resources({ pack: "rpg", type: "item", limit: 5 })).map((row) => row.ref);
      const found = m.docs.search("bleed").map((entry) => entry.member);
      const doc = m.docs.type("item").fields.length;
      const hits = (await m.read.references(m.ref.item("rpg:sword"))).hits.length;
      const complete: boolean = (await m.read.references(m.ref.item("rpg:sword"))).coverage.complete;`)).toEqual([]);
    expect(codes('await m.read.resources({ pack: "rpg", type: "nonsense" });').length).toBeGreaterThan(0);
    expect(codes('(await m.read.get(m.ref.item("rpg:sword"))).fields.nonsense;')).toContain(2339);
    // forgetting the await is a type error, and staging needs none
    expect(codes('m.read.get(m.ref.item("rpg:sword")).fields;')).toContain(2339);
    expect(codes('const p = m.pack("t"); p.items.create("s", { material: "STICK" }).set({ material: "BONE" });')).toEqual([]);
  });
});

describe('item sources, list sources, and block/furniture item refs (root cause 2)', () => {
  it('accepts vanilla strings, item handles, ItemSourceSpec, and Expr on m.guiComponents.item.item', () => {
    const program = `
      const p = m.pack("bench");
      const swordHandle = p.items.create("sword", { material: "DIAMOND_SWORD" });
      p.guis.create("menu", {
        container: "chest_3",
        title: m.mm("<gold>Menu"),
        layers: [
          m.guiComponents.item({ id: "icon", slot: 13, item: "minecraft:diamond" }),
          m.guiComponents.item({ id: "custom", slot: 14, item: swordHandle }),
          m.guiComponents.item({ id: "head", slot: 15, item: m.itemSources.head({ player: "Notch" }) }),
          m.guiComponents.item({ id: "expr", slot: 16, item: m.expr\`\${"minecraft:stone"}\` }),
        ],
      });
    `;
    expect(codes(program)).toEqual([]);
  });

  it('accepts ListSourceSpec on m.guiComponents.list.source', () => {
    const program = `
      const p = m.pack("bench");
      p.guis.create("players_gui", {
        container: "chest_3",
        title: m.mm("<gold>Players"),
        layers: [
          m.guiComponents.list({
            id: "players",
            slots: [10, 11],
            source: m.listSources.onlinePlayers(),
            entry: { item: "minecraft:player_head" },
          }),
        ],
      });
    `;
    expect(codes(program)).toEqual([]);
  });

  it('accepts custom item handles on blocks.create.item while rejecting bare vanilla strings', () => {
    expect(codes('const p = m.pack("bench"); const customItemHandle = p.items.create("ruby", { material: "EMERALD" }); p.blocks.create("ore", { item: customItemHandle });')).toEqual([]);
    expect(codes('const p = m.pack("bench"); p.blocks.create("ore", { item: "minecraft:stone" });')).toContain(2322);
  });
});

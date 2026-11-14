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
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { CORPUS_DIR, GOLDEN_DIR } from './helpers/codemode';
import { checkDeclarations, checkProgram, type Problem } from './helpers/typecheck';

const golden = (name: string): string => readFileSync(join(GOLDEN_DIR, `${name}.d.ts`), 'utf8');
const current = golden('plugin-current');
const withAddon = golden('with-addon');

interface Expectation {
  task: string;
  title: string;
  registry: string;
  result: 'pass' | 'fail';
  note?: string;
  diagnostics?: { code: number; messageContains?: string[] }[];
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
  it.each([['plugin-current', current], ['with-addon', withAddon]])('%s compiles with the core, nothing skipped', (_name, sdk) => {
    const problems = checkDeclarations(sdk);
    expect(show(problems)).toBe('');
  });
});

describe('the program corpus', () => {
  it('holds the first benchmark programs', () => {
    expect(corpus.map((entry) => entry.expect.task)).toEqual(expect.arrayContaining(['T08', 'T11', 'T25', 'T27']));
  });

  it.each(corpus.map((entry) => [entry.expect.task + ' ' + entry.expect.title, entry] as const))('%s', (_label, entry) => {
    const registry = entry.expect.registry === 'with-addon' ? withAddon : current;
    const problems = checkProgram(entry.program, registry);
    if (entry.expect.result === 'pass') {
      expect(show(problems)).toBe('');
      return;
    }
    expect(problems.length).toBeGreaterThan(0);
    for (const wanted of entry.expect.diagnostics ?? []) {
      const hit = problems.find((problem) => problem.code === wanted.code && (wanted.messageContains ?? []).every((part) => problem.message.includes(part)));
      expect(hit, `expected TS${wanted.code} with ${JSON.stringify(wanted.messageContains)}, got:\n${show(problems)}`).toBeDefined();
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
    expect(codes(rule('m.steps.choose([{ if: m.expr`1 > 0`, then: [m.actions.cancel()] }], [m.steps.stop({ cancel: true })])'))).toEqual([]);
    expect(codes(rule('m.steps.choose([])')).length).toBeGreaterThan(0);
    expect(codes(rule('m.steps.wait(20), m.steps.call("purchase", { price: 250 })'))).toEqual([]);
    expect(codes(rule('m.steps.stop({ cancel: "yes" })')).length).toBeGreaterThan(0);
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
    // the program entry is async (the host wraps it), so the checker sees the body of an async function
    const inAsync = (body: string): string => `(async () => {
${body}
})();`;
    expect(codes(inAsync(`
      const view = await m.read.get(m.ref.item("rpg:sword"));
      const material: string | undefined = view.fields.material;
      const rows = (await m.read.resources({ pack: "rpg", type: "item", limit: 5 })).map((row) => row.ref);
      const found = m.docs.search("bleed").map((entry) => entry.member);
      const doc = m.docs.type("item").fields.length;
      const hits = (await m.read.references(m.ref.item("rpg:sword"))).hits.length;
      const complete: boolean = (await m.read.references(m.ref.item("rpg:sword"))).coverage.complete;`))).toEqual([]);
    expect(codes(inAsync('await m.read.resources({ pack: "rpg", type: "nonsense" });')).length).toBeGreaterThan(0);
    expect(codes(inAsync('(await m.read.get(m.ref.item("rpg:sword"))).fields.nonsense;'))).toContain(2339);
    // forgetting the await is a type error, and staging needs none
    expect(codes('m.read.get(m.ref.item("rpg:sword")).fields;')).toContain(2339);
    expect(codes('const p = m.pack("t"); p.items.create("s", { material: "STICK" }).set({ material: "BONE" });')).toEqual([]);
  });
});

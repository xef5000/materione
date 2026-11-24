/**
 * The recorded Bloodfang conversation (issue 348's exit criterion; the task is T01 of agent-benchmark.md and the worked
 * example of codemode.md section 9.1). It is a recording for the scripted provider: the assistant's words and programs are
 * fixed, and everything else is real. Each program is type-checked against the connected server's generated SDK, run in the
 * sandbox, dry-run by the server and applied or left waiting by the proposal store.
 *
 * It expects a pack that has an item `ruby_sword` (the sample pack and the browser fixture both do).
 */
import type { Script } from '../scripted';

export const BLOODFANG_PROMPTS = [
  'Make a Bloodfang sword. Hits apply a bleed that scales with the sword\'s level. It gains XP from kills, up to level 5. Sneak and right-click opens a menu with the level, the XP and an upgrade button that costs 500 coins. Only players with rpg.weapons may use it.',
  'Also change the old Ruby Sword\'s lore to say a smith can reforge it into Bloodfang.',
] as const;

const look = (pack: string): string => `// What swords does the pack already have?
const swords = await m.read.resources({ pack: ${JSON.stringify(pack)}, type: "item", query: "sword" });
return swords.map((row) => row.id);`;

const build = (pack: string, sneakRule: string): string => `const p = m.pack(${JSON.stringify(pack)});

const level = p.variables.create("bloodfang_level", { scope: "item", type: "number", default: 1 });
const xp = p.variables.create("bloodfang_xp", { scope: "item", type: "number", default: 0 });

const menu = p.guis.create("bloodfang_menu", {
  container: "chest_3",
  title: "Bloodfang",
  layers: [
    m.guiComponents.text({ id: "status", x: 8, y: 8, text: "Level \${vars.item.${pack}.bloodfang_level} / 5   XP \${vars.item.${pack}.bloodfang_xp}" }),
    m.guiComponents.button({ id: "upgrade", x: 62, y: 30, width: 52, height: 20 }),
  ],
});
menu.rules.add(m.triggers.guiClick({ layer: "upgrade", click: "left" }), () => ({
  if: m.conditions.hasPermission("rpg.weapons"),
  do: [
    m.steps.if(m.expr\`\${level} >= 5\`, {
      then: [m.actions.message("<red>Bloodfang is already level 5."), m.steps.stop()],
    }),
    m.steps.require([m.actions.takeMoney(500)], { else: [m.actions.message("<red>The upgrade costs 500 coins.")] }),
    m.actions.setVar({ var: level, op: "add", value: m.expr\`1\` }),
    m.actions.guiRefresh(),
  ],
}));

const sword = p.items.create("bloodfang", {
  material: "NETHERITE_SWORD",
  display_name: m.mm("<dark_red><bold>Bloodfang"),
  lore: [m.mm("<gray>Sneak and right-click to open its menu.")],
});
sword.rules.add(m.triggers.entityHit(), () => ({
  if: m.conditions.hasPermission("rpg.weapons"),
  do: [m.actions.potion({ type: "wither", duration: 100, amplifier: m.expr\`\${level} - 1\`, target: "event.target" })],
}));
sword.rules.add(m.triggers.entityKill(), () => ({
  if: m.conditions.hasPermission("rpg.weapons"),
  do: [
    m.actions.setVar({ var: xp, op: "add", value: m.expr\`10\` }),
    m.steps.if(m.expr\`\${xp} >= 100 && \${level} < 5\`, {
      then: [
        m.actions.setVar({ var: level, op: "add", value: m.expr\`1\` }),
        m.actions.setVar({ var: xp, op: "set", value: m.expr\`0\` }),
      ],
    }),
  ],
}));
${sneakRule}

m.summary("Adds Bloodfang with level, XP, bleed strength and upgrades held by the sword itself, for players with rpg.weapons.");`;

// The first attempt reaches for a helper the SDK does not have: an ordinary mistake, fixed in the next turn.
const SNEAK_RULE_WRONG = `sword.rules.add(m.triggers.click({ detail: "right" }), (ctx) => ({
  if: m.all(m.conditions.hasPermission("rpg.weapons"), m.expr<boolean>\`\${ctx.player.sneaking}\`),
  do: [m.actions.viewOpen(menu)],
}));`;

const SNEAK_RULE = `sword.rules.add(m.triggers.click({ detail: "right" }), (ctx) => ({
  if: m.expr<boolean>\`\${ctx.player.sneaking}\`,
  do: [
    m.steps.if(m.conditions.hasPermission("rpg.weapons"), {
      else: [m.actions.message("<red>You may not use Bloodfang."), m.steps.stop()],
    }),
    m.actions.viewOpen(menu),
  ],
}));`;

const followUp = (pack: string): string => `// The Ruby Sword already exists: this is a change to existing content.
const ruby = m.ref.item(${JSON.stringify(`${pack}:ruby_sword`)});
m.edit(ruby).set({
  lore: [m.mm("<gray>An old blade."), m.mm("<dark_red>A smith can reforge it into Bloodfang.")],
});

m.summary("Replaces the Ruby Sword's lore with two lines that point to Bloodfang.");`;

/**
 * The recorded conversation, with its programs written for `pack`. The usage figures are part of the recording (they show how
 * a provider's report is displayed); they are not measurements of any model.
 */
export function bloodfangScript(pack: string): Script {
  return {
    id: 'bloodfang',
    title: 'the Bloodfang sword',
    prompts: BLOODFANG_PROMPTS,
    turns: [
      { text: 'I will look at what the pack has first.', program: look(pack), usage: { inputTokens: 5200, outputTokens: 70 } },
      {
        text: 'The pack has a ruby_sword and no Bloodfang yet. I will build the item-scoped version you described.',
        program: build(pack, SNEAK_RULE_WRONG),
        usage: { inputTokens: 5400, outputTokens: 210, cachedInputTokens: 5100 },
      },
      {
        text: 'My mistake: the SDK has no m.all. I can use the shipped condition combinator instead.',
        program: build(pack, SNEAK_RULE),
        usage: { inputTokens: 6900, outputTokens: 540, cachedInputTokens: 5100 },
      },
      {
        text:
          'Bloodfang is in. The program created four documents (two item-scoped variables, the menu and the sword). The level and XP belong to each sword, and the bleed and menu read that stack. They only add new content, so they are applied already; the card above shows each file, and you can revert the change from the change log.\n\n' +
          'If the result above carries a warning that take_money is not available, the server has no economy plugin: the upgrade button will refuse until one is installed.',
        usage: { inputTokens: 7600, outputTokens: 150, cachedInputTokens: 5100 },
      },
      {
        text: 'The Ruby Sword already exists, so this change will wait for your approval.',
        program: followUp(pack),
        usage: { inputTokens: 7900, outputTokens: 120, cachedInputTokens: 5100 },
      },
      {
        text: 'The change to the Ruby Sword is waiting for you: the card above shows the diff of its file. It replaces the lore with two lines. Approve it there if it is right. I cannot approve it.',
        usage: { inputTokens: 8200, outputTokens: 60, cachedInputTokens: 5100 },
      },
    ],
  };
}

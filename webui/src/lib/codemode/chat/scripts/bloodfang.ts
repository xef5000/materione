/**
 * The recorded Bloodfang conversation (issue 348's exit criterion; the task is T01 of agent-benchmark.md and the worked
 * example of codemode.md section 9.1). It is a recording for the scripted provider: the assistant's words and programs are
 * fixed, and everything else is real. Each program is type-checked against the connected server's generated SDK, run in the
 * sandbox, dry-run by the server and applied or left waiting by the proposal store.
 *
 * The recording is honest about today's surface (agent-benchmark.md, T01 "Fail: STATE"): the sword as first asked
 * needs a level held by the item, and the server has none (an effect aimed at the struck entity is `target: "event.target"`
 * since target addressing). The assistant reports the gap, does not work around it, asks, and builds what exists once the
 * owner has chosen. A later request changes an item that already exists, so it waits for the owner's approval.
 *
 * It expects a pack that has an item `ruby_sword` (the sample pack and the browser fixture both do).
 */
import type { Script } from '../scripted';

export const BLOODFANG_PROMPTS = [
  'Make a Bloodfang sword. Hits apply a bleed that scales with the sword\'s level. It gains XP from kills, up to level 5. Sneak and right-click opens a menu with the level, the XP and an upgrade button that costs 500 coins. Only players with rpg.weapons may use it.',
  'Per player is fine.',
  'Also change the old Ruby Sword\'s lore to say a smith can reforge it into Bloodfang.',
] as const;

const look = (pack: string): string => `// What swords does the pack already have?
const swords = await m.read.resources({ pack: ${JSON.stringify(pack)}, type: "item", query: "sword" });
return swords.map((row) => row.id);`;

const asAsked = (pack: string): string => `const p = m.pack(${JSON.stringify(pack)});

// The level lives with the sword, not the player.
const level = p.variables.create("bloodfang_level", { type: "number", holder: "item", default: 1 });

const sword = p.items.create("bloodfang", { material: "NETHERITE_SWORD", display_name: m.mm("<red>Bloodfang") });

// The bleed: a wither effect on the entity that was hit.
sword.rules.add(m.triggers.entityHit(), () => ({
  do: [m.actions.potion({ type: "wither", duration: 100, amplifier: 1, target: "event.target" })],
}));`;

const build = (pack: string, sneakRule: string): string => `const p = m.pack(${JSON.stringify(pack)});

// Level and XP, kept per player (the owner chose this: the server cannot keep them on the sword).
const level = p.variables.create("bloodfang_level", { scope: "player", type: "number", default: 1 });
const xp = p.variables.create("bloodfang_xp", { scope: "player", type: "number", default: 0 });

// The menu: the level and XP, and an upgrade button that costs 500 coins.
const menu = p.guis.create("bloodfang_menu", {
  container: "chest_3",
  title: "Bloodfang",
  layers: [
    m.guiComponents.text({ id: "status", x: 8, y: 8, text: "Level \${vars.bloodfang_level} / 5   XP \${vars.bloodfang_xp}" }),
    m.guiComponents.button({ id: "upgrade", x: 62, y: 30, width: 52, height: 20 }),
  ],
});
menu.rules.add(m.triggers.guiClick({ layer: "upgrade", click: "left" }), () => ({
  if: m.conditions.hasPermission("rpg.weapons"),
  do: [
    m.steps.if(m.conditions.var({ var: level, op: ">=", value: "5" }), {
      then: [m.actions.message("<red>Bloodfang is already level 5."), m.steps.stop()],
    }),
    m.steps.require([m.actions.takeMoney(500)], { else: [m.actions.message("<red>The upgrade costs 500 coins.")] }),
    m.actions.setVar({ var: level, op: "add", value: "1" }),
    m.actions.guiRefresh(),
  ],
}));

// The sword: a bleed on the entity it hits, XP from kills, and sneak + right-click opens the menu.
const sword = p.items.create("bloodfang", {
  material: "NETHERITE_SWORD",
  display_name: m.mm("<dark_red><bold>Bloodfang"),
  lore: [m.mm("<gray>Sneak and right-click to open its menu.")],
});
sword.rules.add(m.triggers.entityHit(), () => ({
  if: m.conditions.hasPermission("rpg.weapons"),
  do: [m.actions.potion({ type: "wither", duration: 100, amplifier: 0, target: "event.target" })],
}));
sword.rules.add(m.triggers.entityKill(), () => ({
  if: m.conditions.hasPermission("rpg.weapons"),
  do: [m.actions.setVar({ var: xp, op: "add", value: "10" })],
}));
${sneakRule}

m.summary("Adds the Bloodfang sword with a bleed on the entity it hits, a level and XP kept per player, XP from kills, and a menu with a 500-coin upgrade, for players with rpg.weapons.");`;

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
        text: 'The pack has a ruby_sword and no Bloodfang yet. I will write the sword as you described it.',
        program: asAsked(pack),
        usage: { inputTokens: 5400, outputTokens: 210, cachedInputTokens: 5100 },
      },
      {
        text:
          'This server cannot do one part of what you asked, so I stopped before building anything:\n\n' +
          'A level kept on the sword itself. Variables here have no item holder (`holder` does not exist on VariableFields); a variable is per player or per server.\n\n' +
          'I will not fake it. What I can build today is the sword, a bleed on the entity it hits, XP from kills, and the menu with the 500-coin upgrade, all limited to rpg.weapons, with the level and XP kept per player. Per player means two Bloodfangs owned by one player share one level, and the bleed has a fixed strength because it cannot read that level.\n\n' +
          'Do you want that version, or should I wait until the server can do the rest?',
        usage: { inputTokens: 5900, outputTokens: 190, cachedInputTokens: 5100 },
      },
      {
        text: 'Building that version: level and XP per player, a fixed-strength bleed.',
        program: build(pack, SNEAK_RULE_WRONG),
        usage: { inputTokens: 6200, outputTokens: 520, cachedInputTokens: 5100 },
      },
      {
        text: 'My mistake: the SDK has no m.all. The permission check moves into the rule\'s steps.',
        program: build(pack, SNEAK_RULE),
        usage: { inputTokens: 6900, outputTokens: 540, cachedInputTokens: 5100 },
      },
      {
        text:
          'Bloodfang is in. The program created four documents (two variables, the menu and the sword). They only add new content, so they are applied already; the card above shows each file, and you can revert the change from the change log.\n\n' +
          'Two things to know. The level is per player and the bleed has a fixed strength, as agreed. And if the result above carries a warning that take_money is not available, the server has no economy plugin: the upgrade button will refuse until one is installed.',
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

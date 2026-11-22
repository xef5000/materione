// T01 Bloodfang sword (agent-benchmark.md section 3): custom model, bleed on hit scaled by level,
// XP from kills to level 5, sneak-right-click GUI with a 500-coin upgrade, rpg.weapons only.
const p = m.pack("bench");
const level = p.variables.create("bloodfang_level", {
  scope: "player",
  type: "number",
  default: 1,
  holder: "item",
});
const xp = p.variables.create("bloodfang_xp", { scope: "player", type: "number", default: 0 });
const sword = p.items.create("bloodfang", {
  material: "DIAMOND_SWORD",
  display_name: m.mm("<dark_red>Bloodfang"),
  model: m.ref.asset("models/bloodfang.json"),
});

sword.rules.add(m.triggers.entityHit(), () => ({
  if: m.conditions.hasPermission("rpg.weapons"),
  do: [m.actions.potion({ type: "wither", duration: 100, target: "event.target" })],
}));

sword.rules.add(m.triggers.entityKill(), () => ({
  if: m.conditions.hasPermission("rpg.weapons"),
  do: [
    m.actions.setVar({ var: xp, op: "add", value: "10" }),
    m.steps.if(m.expr`${xp} >= 100 && ${level} < 5`, {
      then: [
        m.actions.setVar({ var: level, op: "add", value: "1" }),
        m.actions.setVar({ var: xp, op: "set", value: "0" }),
      ],
    }),
  ],
}));

const menu = p.guis.create("bloodfang_upgrade", {
  container: "chest_3",
  title: "Bloodfang Upgrade",
  layers: [
    m.guiComponents.text({ id: "stats", x: 8, y: 6, text: "Level ${vars.player.bench.bloodfang_level}" }),
    m.guiComponents.button({ id: "upgrade", x: 70, y: 30, width: 36, height: 20 }),
  ],
});

menu.rules.add(m.triggers.guiClick({ layer: "upgrade" }), () => ({
  if: m.conditions.hasPermission("rpg.weapons"),
  do: [
    m.steps.if(m.expr`${level} < 5`, {
      else: [m.actions.message("<gold>Already max level."), m.steps.stop()],
    }),
    m.steps.require([m.actions.takeMoney(500)], {
      else: [m.actions.message("<red>You need 500 coins.")],
    }),
    m.actions.setVar({ var: level, op: "add", value: "1" }),
    m.actions.guiRefresh(),
  ],
}));

sword.rules.add(m.triggers.click({ detail: "right" }), (ctx) => ({
  if: m.conditions.all([m.conditions.hasPermission("rpg.weapons"), m.expr`${ctx.player.sneaking}`]),
  do: [m.actions.viewOpen(menu)],
}));

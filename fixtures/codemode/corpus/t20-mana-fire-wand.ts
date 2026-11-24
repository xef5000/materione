// T20 Mana and a fire wand (agent-benchmark.md section 3): mana regenerates to 100, shows as a HUD bar,
// the wand spends 20 to shoot a fireball.
const p = m.pack("bench");
const mana = p.variables.create("mana", { scope: "player", type: "number", default: 100 });
const regen = p.rules.create("mana_regen", { description: "Regenerate player mana" });
regen.rules.add(m.triggers.every({ interval: "1s", for: "each_player" }), () => ({
  do: [m.actions.setVar({ var: mana, op: "add", value: "2" })],
}));

p.huds.create("mana_bar", {
  layers: [{ id: "bar", value: m.expr`${mana} / 100` }],
});

const wand = p.items.create("fire_wand", {
  material: "BLAZE_ROD",
  display_name: m.mm("<red>Fire Wand"),
});
wand.rules.add(m.triggers.click({ detail: "right" }), () => ({
  do: [
    m.steps.if(m.expr`${mana} >= 20`, {
      else: [m.actions.actionbar("<blue>Not enough mana"), m.steps.stop()],
    }),
    m.actions.setVar({ var: mana, op: "subtract", value: "20" }),
    m.actions.launch({ projectile: "small_fireball", speed: 1.5 }),
  ],
}));

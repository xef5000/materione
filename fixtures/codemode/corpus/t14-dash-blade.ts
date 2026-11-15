// T14 Dash blade (agent-benchmark.md section 3): right-click launches the player forward with a particle trail, 5 s cooldown.
const p = m.pack("bench");
const blade = p.items.create("dash_blade", {
  material: "IRON_SWORD",
  display_name: m.mm("<aqua>Dash Blade"),
});
blade.rules.add(m.triggers.click({ detail: "right" }), () => ({
  cooldown: 5000,
  do: [
    m.actions.velocity({ forward: 1.6, up: 0.3 }),
    m.actions.particle({ particle: "cloud", count: 20 }),
    m.actions.sound("entity.ender_dragon.flap"),
  ],
}));

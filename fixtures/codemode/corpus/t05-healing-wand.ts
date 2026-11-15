// T05 Healing wand (agent-benchmark.md section 3): right click heals, 30 s cooldown, and it says how long is left.
// The rule options `cooldown` and `on_cooldown` exist (the clock work), and `on_cooldown` describes itself as a list of
// steps that can read cooldown.remaining (#355), so the surface types it and the paper gap [TIME] is closed end to end.
const p = m.pack("bench");
const wand = p.items.create("healing_wand", {
  material: "BLAZE_ROD",
  display_name: m.mm("<green>Healing Wand"),
});

wand.rules.add(m.triggers.click({ detail: "right" }), () => ({
  cooldown: 30000,
  do: [m.actions.potion({ type: "instant_health", amplifier: 1 }), m.actions.sound("entity.player.levelup")],
  // cooldown.remaining is whole seconds left; it is text the server expands, so it is written inside the string
  on_cooldown: [m.actions.actionbar("<yellow>Ready in ${cooldown.remaining}s")],
}));

m.summary("Adds a healing wand: right click heals, with a 30 second cooldown that says how long is left.");

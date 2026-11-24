// F5 Level 5 particles (agent-benchmark.md section 2): "Level 5 swords emit red particles when held."
// Repetition exists, but the generic rule-document timer has no held-item context (item timers stay out of scope).
const p = m.pack("bench");
const aura = p.rules.create("bloodfang_particles", { description: "Emit red particles for level 5 Bloodfang" });
aura.rules.add(m.triggers.every({ interval: "1s", for: "each_player" }), (ctx) => ({
  if: m.expr`${ctx.item.id} == ${"bench:bloodfang"}`,
  do: [m.actions.particle({ particle: "dust", color: "#ff0000", count: 5 })],
}));

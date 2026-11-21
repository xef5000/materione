// F5 Level 5 particles (agent-benchmark.md section 2): "Level 5 swords emit red particles when held."
// Requires a repeating timer trigger (TIMER). The particle is the particle action (#329), which now exists.
const p = m.pack("bench");
const aura = p.rules.create("bloodfang_particles", { description: "Emit red particles for level 5 Bloodfang" });
aura.rules.add(m.triggers.every({ seconds: 1, for: "each_player" }), () => ({
  if: m.conditions.hasItem({ item: "bench:bloodfang" }),
  do: [m.actions.particle({ particle: "dust", color: "#ff0000", count: 5 })],
}));

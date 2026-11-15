// T12 Lifesteal dagger (agent-benchmark.md section 3): heal the wielder for 20% of the damage dealt.
const p = m.pack("bench");
const dagger = p.items.create("lifesteal_dagger", {
  material: "IRON_SWORD",
  display_name: m.mm("<dark_red>Leech"),
});
dagger.rules.add(m.triggers.entityHit(), (ctx) => ({
  do: [m.actions.heal({ amount: m.expr`${ctx.damage.final} * 0.2` })],
}));

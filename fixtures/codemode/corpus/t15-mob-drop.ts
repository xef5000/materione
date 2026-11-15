// T15 Mob drop (agent-benchmark.md section 3): zombies have a 5% chance to drop a Rotten Heart.
const p = m.pack("bench");
const heart = p.items.create("rotten_heart", {
  material: "ROTTEN_FLESH",
  display_name: m.mm("<dark_green>Rotten Heart"),
});
const rule = p.rules.create("zombie_heart", { description: "5% chance for zombie kills to grant a Rotten Heart" });
rule.rules.add(m.triggers.entityKill({ type: "zombie" }), () => ({
  do: [m.actions.giveItem({ item: heart, amount: 1 }, { chance: 0.05 })],
}));

m.summary("Adds the Rotten Heart item and a 5% drop rule on zombie kills.");

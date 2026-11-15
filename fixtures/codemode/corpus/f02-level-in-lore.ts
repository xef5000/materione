// F2 Level in lore (agent-benchmark.md section 2): "Show the level in the sword's lore."
// Requires a runtime stack-mutation action (VERB).
const sword = m.edit(m.ref.item("bench:bloodfang"));
sword.rules.add(m.triggers.entityKill(), () => ({
  do: [m.actions.setItemLore({ lore: ["Level: ${vars.player.bench.bloodfang_level}"] })],
}));

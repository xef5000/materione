// F2 Level in lore (agent-benchmark.md section 2): "Show the level in the sword's lore."
// One step that edits the stack the rule is about (stack.edit, #329): the kill rule hangs on the sword, so the stack is the sword.
const sword = m.edit(m.ref.item("bench:bloodfang"));
sword.rules.add(m.triggers.entityKill(), () => ({
  do: [m.actions.stackEdit({ lore: ["Level: ${vars.item.bench.bloodfang_level}"] })],
}));

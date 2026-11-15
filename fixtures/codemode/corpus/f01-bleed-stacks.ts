// F1 Bleed stacks (agent-benchmark.md section 2): "Make bleed stack up to 3 times."
// Requires a variable held by the struck entity (STATE).
const p = m.pack("bench");
const stacks = p.variables.create("bleed_stacks", {
  scope: "player",
  type: "number",
  default: 0,
  holder: "entity",
});
const sword = m.edit(m.ref.item("bench:bloodfang"));
sword.rules.add(m.triggers.entityHit(), () => ({
  do: [
    m.steps.if(m.expr`${stacks} < 3`, {
      then: [m.actions.setVar({ var: stacks, op: "add", value: "1" })],
    }),
  ],
}));

// T28 Rules on first join (agent-benchmark.md section 3): a dialog with the rules and an Accept button; no accept, no play.
const p = m.pack("bench");
const accepted = p.variables.create("rules_accepted", { scope: "player", type: "boolean", default: false });
const rules = p.dialogs.create("rules", {
  title: "Server rules",
  body: ["Be kind.", "No cheating."],
  buttons: [
    { label: "Accept", do: [m.actions.setVar({ var: accepted, op: "set", value: "true" })] },
    { label: "Leave", do: [m.actions.runCommand("kick %player% Rules declined")] },
  ],
});
const gate = p.rules.create("rules_gate", { description: "Show rules dialog on join until accepted" });
gate.rules.add(m.triggers.playerJoin(), () => ({
  if: m.conditions.var({ var: accepted, op: "==", value: "false" }),
  do: [m.actions.dialogOpen(rules)],
}));

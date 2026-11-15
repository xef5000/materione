// T21 /pay <player> <amount> (agent-benchmark.md section 3).
const p = m.pack("bench");
p.commands.create("pay", {
  args: { to: { type: "player" }, amount: { type: "number", min: 1 } },
  do: [
    m.steps.require([m.actions.takeMoney(100)], {
      else: [m.actions.message("<red>Not enough coins.")],
    }),
    m.actions.giveMoney({ amount: 100, target: "arg:to" }),
  ],
});

// T21 /pay <player> <amount> (agent-benchmark.md section 3).
// The command is a document (#330, logic.md section 40): a typed player argument and a typed number argument reach the flow as
// args.to and args.amount, and #331's `target` addresses the other player. The amount is an expression: the money actions take
// one since #464 (logic.md section 42), so an amount that comes from an argument is a number in the SDK.
const p = m.pack("bench");
p.commands.create("pay", {
  names: ["pay"],
  args: [
    { name: "to", type: "player" },
    { name: "amount", type: "number" },
  ],
  requirement: m.expr`args.amount > 0`,
  deny: [m.actions.message("<red>Pay a positive amount.")],
  do: [
    m.steps.require([m.actions.takeMoney({ amount: m.expr`args.amount` })], {
      else: [m.actions.message("<red>Not enough coins.")],
    }),
    m.actions.giveMoney({ amount: m.expr`args.amount`, target: { arg: "to" } }),
    m.actions.message({ text: m.expr`player.name + " paid you " + args.amount`, target: { arg: "to" } }),
  ],
});

// T02 /trade <player> (agent-benchmark.md section 3): request, accept, shared two-player window, atomic swap, expiry.
const p = m.pack("bench");
const req = p.variables.create("trade_request", {
  scope: "player",
  type: "string",
  ttl: 30,
});
const tradeGui = p.guis.create("trade", {
  container: "chest_6",
  title: "Trade",
  viewers: 2,
  layers: [m.guiComponents.slots({ id: "offer", region: "container", input: true, durable: "required" })],
});

p.commands.create("trade", {
  args: { target: { type: "player" } },
  do: [
    m.actions.setVar({ var: req, op: "set", value: "pending" }),
    m.actions.message({ text: "<gold>Trade request sent.", target: { arg: "target" } }),
    m.actions.custodyTransfer({ from: "offer", to: "other" }),
    m.actions.guiOpen(tradeGui),
  ],
});
